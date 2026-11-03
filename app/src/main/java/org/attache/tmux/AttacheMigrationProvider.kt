package org.attache.tmux

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Signature-protected local migration provider that streams `databases/`,
 * `shared_prefs/`, and `files/tailscale/` (plus `files/fonts/`) from the legacy
 * `org.connectbot.debug` package to the new `me.xueshi.attache` package on the same device.
 *
 * Security:
 * - Strictly verifies `PackageManager.checkSignatures(myUid, callingUid) == SIGNATURE_MATCH`
 *   so only an APK signed with the exact same developer certificate can read the stream.
 * - Streams directly over an in-memory kernel pipe (`ParcelFileDescriptor.createPipe()`),
 *   leaving zero temporary files on shared/external storage.
 */
class AttacheMigrationProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        verifyCallerSignature(ctx)

        if (uri.lastPathSegment != "archive") {
            return null
        }

        // Gracefully hand off embedded Tailscale node so legacy and new packages don't race on the same node key
        runCatching {
            if (org.attache.tailscale.TsnetNative.isAvailable) {
                org.attache.tailscale.TsnetNative.nativeStop()
            }
        }

        checkpointSqliteDatabases(ctx)

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { rawOut ->
                    ZipOutputStream(BufferedOutputStream(rawOut)).use { zos ->
                        val dataDir = File(ctx.applicationInfo.dataDir)

                        // 1. databases/ (connectbot.db, -wal, -shm, etc.)
                        val dbDir = ctx.getDatabasePath("connectbot.db").parentFile
                        if (dbDir != null && dbDir.exists()) {
                            zipDirectoryRecursive(
                                rootBaseDir = dataDir,
                                currentDir = dbDir,
                                zos = zos,
                            )
                        }

                        // 2. shared_prefs/
                        val prefsDir = File(dataDir, "shared_prefs")
                        if (prefsDir.exists()) {
                            zipDirectoryRecursive(
                                rootBaseDir = dataDir,
                                currentDir = prefsDir,
                                zos = zos,
                            ) { file ->
                                // Skip AndroidKeyStore-bound password file as Keystore keys are UID-scoped
                                file.name != "secure_host_passwords.xml"
                            }
                        }

                        // 3. files/ (tailscale state, custom fonts, etc., excluding large inbox downloads)
                        val filesDir = ctx.filesDir
                        if (filesDir != null && filesDir.exists()) {
                            zipDirectoryRecursive(
                                rootBaseDir = dataDir,
                                currentDir = filesDir,
                                zos = zos,
                            ) { file ->
                                val rel = file.relativeTo(filesDir).path.replace('\\', '/')
                                !rel.startsWith("attache-inbox") &&
                                    file.name != MARKER_FILE_NAME
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "AttacheMigrationProvider failed while streaming archive")
            }
        }.apply {
            name = "AttacheMigrationZipWriter"
            isDaemon = true
            start()
        }

        return readSide
    }

    private fun verifyCallerSignature(ctx: Context) {
        val callingUid = Binder.getCallingUid()
        val myUid = Process.myUid()
        if (callingUid == myUid) return

        val pm = ctx.packageManager
        val sigMatch = pm.checkSignatures(myUid, callingUid) == PackageManager.SIGNATURE_MATCH
        if (!sigMatch) {
            throw SecurityException("Caller UID $callingUid is not signed with the same certificate")
        }
    }

    private fun checkpointSqliteDatabases(ctx: Context) {
        val dbDir = ctx.getDatabasePath("connectbot.db").parentFile ?: return
        val dbFiles = dbDir.listFiles { f -> f.isFile && f.name.endsWith(".db") }.orEmpty()
        for (dbFile in dbFiles) {
            runCatching {
                SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { db ->
                    db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
                        cursor.moveToFirst()
                    }
                }
            }
        }
    }

    private fun zipDirectoryRecursive(
        rootBaseDir: File,
        currentDir: File,
        zos: ZipOutputStream,
        filter: (File) -> Boolean = { true },
    ) {
        val children = currentDir.listFiles() ?: return
        val buffer = ByteArray(16 * 1024)
        for (child in children) {
            if (!filter(child)) continue
            if (child.isDirectory) {
                zipDirectoryRecursive(rootBaseDir, child, zos, filter)
            } else if (child.isFile && child.canRead()) {
                val relPath = child.relativeTo(rootBaseDir).path.replace('\\', '/')
                runCatching {
                    val entry = ZipEntry(relPath).apply {
                        time = child.lastModified()
                    }
                    zos.putNextEntry(entry)
                    FileInputStream(child).use { fis ->
                        var read: Int
                        while (fis.read(buffer).also { read = it } != -1) {
                            zos.write(buffer, 0, read)
                        }
                    }
                    zos.closeEntry()
                }
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = "application/zip"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val LEGACY_PACKAGE_NAME = "org.connectbot.debug"
        private const val LEGACY_MIGRATION_URI = "content://org.connectbot.debug.migration/archive"
        private const val MARKER_FILE_NAME = ".migrated_from_connectbot"

        @Volatile
        private var migrationAttemptedInProcess = false

        @Synchronized
        fun migrateFromLegacyPackageIfNeeded(context: Context) {
            if (migrationAttemptedInProcess) return
            migrationAttemptedInProcess = true

            val appContext = context.applicationContext ?: context
            if (appContext.packageName == LEGACY_PACKAGE_NAME) {
                return
            }

            val filesDir = appContext.filesDir ?: return
            val markerFile = File(filesDir, MARKER_FILE_NAME)
            if (markerFile.exists()) {
                return
            }

            val dbFile = appContext.getDatabasePath("connectbot.db")
            if (dbFile.exists() && hasExistingUserData(dbFile)) {
                runCatching { markerFile.writeText("existing_data") }
                return
            }

            runCatching {
                val uri = Uri.parse(LEGACY_MIGRATION_URI)
                val inputStream = appContext.contentResolver.openInputStream(uri) ?: return
                val dataDir = File(appContext.applicationInfo.dataDir)
                val canonicalDataDir = dataDir.canonicalPath + File.separator
                var copiedDb = false
                val buffer = ByteArray(16 * 1024)

                inputStream.use { rawIn ->
                    ZipInputStream(BufferedInputStream(rawIn)).use { zis ->
                        var entry: ZipEntry? = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val outFile = File(dataDir, entry.name)
                                if (outFile.canonicalPath.startsWith(canonicalDataDir)) {
                                    outFile.parentFile?.mkdirs()
                                    FileOutputStream(outFile).use { fos ->
                                        var read: Int
                                        while (zis.read(buffer).also { read = it } != -1) {
                                            fos.write(buffer, 0, read)
                                        }
                                    }
                                    if (entry.name == "databases/connectbot.db") {
                                        copiedDb = true
                                    }
                                    // Map default SharedPreferences file name from legacy package to new package
                                    if (entry.name == "shared_prefs/${LEGACY_PACKAGE_NAME}_preferences.xml") {
                                        val newDefaultPrefs = File(
                                            dataDir,
                                            "shared_prefs/${appContext.packageName}_preferences.xml",
                                        )
                                        runCatching {
                                            outFile.copyTo(newDefaultPrefs, overwrite = true)
                                        }
                                    }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }

                if (copiedDb) {
                    markerFile.writeText("migrated_${System.currentTimeMillis()}")
                    Timber.i("Successfully migrated data from %s to %s", LEGACY_PACKAGE_NAME, appContext.packageName)
                }
            }.onFailure { e ->
                Timber.w(e, "Legacy package migration not available or skipped")
            }
        }

        private fun hasExistingUserData(dbFile: File): Boolean = runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                val hostCount = db.rawQuery("SELECT COUNT(*) FROM hosts", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                val pubkeyCount = db.rawQuery("SELECT COUNT(*) FROM pubkeys", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
                hostCount > 0 || pubkeyCount > 0
            }
        }.getOrDefault(false)
    }
}
