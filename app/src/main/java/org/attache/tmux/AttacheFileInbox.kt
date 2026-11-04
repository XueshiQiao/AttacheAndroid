package org.attache.tmux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.BuildConfig
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AttacheFileKind {
    IMAGE,
    VIDEO,
    APK,
    OTHER,
}

data class LocalInboxFile(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val kind: AttacheFileKind,
    val apkVersionLabel: String? = null,
)

data class FileDownloadProgress(
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

object AttacheFileStorage {
    private const val PREFS_NAME = "attache_file_inbox_prefs"
    private const val KEY_LOCAL_SUBDIR = "local_subdir"
    private const val KEY_REMOTE_BIN_DIR = "remote_bin_dir"
    private const val KEY_AUTO_SYNC_RULES = "auto_sync_agent_rules"
    private const val KEY_LAST_HANDLED_PUSH_TOKEN = "last_handled_push_token"
    private const val KEY_HANDLED_PUSH_TOKENS = "handled_push_tokens"
    private const val MAX_HANDLED_PUSH_TOKENS = 64
    const val DEFAULT_LOCAL_SUBDIR = "Attache"
    const val DEFAULT_REMOTE_BIN_DIR = "~/.local/bin"

    private val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    private val VIDEO_EXTS = setOf("mp4", "mov", "mkv", "webm", "avi", "m4v")
    private val ATTACHE_APK_BUILD_REGEX = Regex("""^Attache-v[^/]+-b(\d+)-.*\.apk$""", RegexOption.IGNORE_CASE)
    private val apkVersionCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var cachedLocalSubdir: String? = null
    @Volatile private var cachedRemoteBinDir: String? = null
    @Volatile private var cachedAutoSyncRules: Boolean? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getLastHandledPushToken(context: Context): String =
        prefs(context).getString(KEY_LAST_HANDLED_PUSH_TOKEN, null)?.trim().orEmpty()

    @Synchronized
    fun isPushTokenHandled(context: Context, token: String): Boolean {
        val clean = token.trim()
        if (clean.isEmpty()) return true
        val p = prefs(context)
        if (p.getString(KEY_LAST_HANDLED_PUSH_TOKEN, null)?.trim() == clean) return true
        val history = p.getString(KEY_HANDLED_PUSH_TOKENS, null).orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return clean in history
    }

    @Synchronized
    fun markPushTokenHandled(context: Context, token: String) {
        val clean = token.trim()
        if (clean.isEmpty()) return
        val p = prefs(context)
        val existing = p.getString(KEY_HANDLED_PUSH_TOKENS, null).orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != clean }
            .toList()
        val updated = (listOf(clean) + existing).take(MAX_HANDLED_PUSH_TOKENS).joinToString("\n")
        p.edit()
            .putString(KEY_LAST_HANDLED_PUSH_TOKEN, clean)
            .putString(KEY_HANDLED_PUSH_TOKENS, updated)
            .apply()
    }

    /**
     * Returns true if [remotePath] is an `Attache-v*-b<N>-*.apk` whose build number `<N>`
     * is less than or equal to the currently installed build (`BuildConfig.VERSION_CODE - 12_000_000`).
     */
    fun isAlreadyInstalledOrOlderAttacheApk(remotePath: String): Boolean {
        val fileName = remotePath.trim().substringAfterLast('/')
        val match = ATTACHE_APK_BUILD_REGEX.matchEntire(fileName) ?: return false
        val pushedBuild = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return false
        val installedBuild = BuildConfig.VERSION_CODE - 12_000_000
        return installedBuild > 0 && pushedBuild <= installedBuild
    }

    fun getLocalSubdir(context: Context): String {
        cachedLocalSubdir?.let { return it }
        val loaded = prefs(context).getString(KEY_LOCAL_SUBDIR, DEFAULT_LOCAL_SUBDIR)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.contains("..") }
            ?: DEFAULT_LOCAL_SUBDIR
        cachedLocalSubdir = loaded
        return loaded
    }

    fun setLocalSubdir(context: Context, subdir: String) {
        val clean = subdir.trim().trim('/').replace(Regex("""[\\:*?"<>|]"""), "_").ifBlank { DEFAULT_LOCAL_SUBDIR }
        cachedLocalSubdir = clean
        prefs(context).edit().putString(KEY_LOCAL_SUBDIR, clean).apply()
    }

    fun getRemoteBinDir(context: Context): String {
        cachedRemoteBinDir?.let { return it }
        val loaded = prefs(context).getString(KEY_REMOTE_BIN_DIR, DEFAULT_REMOTE_BIN_DIR)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_REMOTE_BIN_DIR
        cachedRemoteBinDir = loaded
        return loaded
    }

    fun setRemoteBinDir(context: Context, binDir: String) {
        val clean = binDir.trim().trimEnd('/').ifBlank { DEFAULT_REMOTE_BIN_DIR }
        cachedRemoteBinDir = clean
        prefs(context).edit().putString(KEY_REMOTE_BIN_DIR, clean).apply()
    }

    fun isAutoSyncAgentRulesEnabled(context: Context): Boolean {
        cachedAutoSyncRules?.let { return it }
        val loaded = prefs(context).getBoolean(KEY_AUTO_SYNC_RULES, true)
        cachedAutoSyncRules = loaded
        return loaded
    }

    fun setAutoSyncAgentRulesEnabled(context: Context, enabled: Boolean) {
        cachedAutoSyncRules = enabled
        prefs(context).edit().putBoolean(KEY_AUTO_SYNC_RULES, enabled).apply()
    }

    fun classifyFileName(name: String): AttacheFileKind {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            ext == "apk" -> AttacheFileKind.APK
            ext in IMAGE_EXTS -> AttacheFileKind.IMAGE
            ext in VIDEO_EXTS -> AttacheFileKind.VIDEO
            else -> AttacheFileKind.OTHER
        }
    }

    fun mimeTypeFor(file: File): String {
        val ext = file.extension.lowercase(Locale.US)
        if (ext == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (classifyFileName(file.name)) {
            AttacheFileKind.IMAGE -> "image/*"
            AttacheFileKind.VIDEO -> "video/*"
            AttacheFileKind.APK -> "application/vnd.android.package-archive"
            AttacheFileKind.OTHER -> "*/*"
        }
    }

    /**
     * Returns the public `Download/<localSubdir>` directory on the device so files
     * are visible both inside Attaché and in system File Managers / Galleries.
     */
    fun getInboxDirectory(context: Context): File {
        val subdir = getLocalSubdir(context)
        val publicDownloads = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (_: Exception) {
            null
        }
        val primary = publicDownloads?.let { File(it, subdir) }
        if (primary != null && (primary.exists() || primary.mkdirs())) {
            return primary
        }
        val appExternal = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, subdir) }
        if (appExternal != null && (appExternal.exists() || appExternal.mkdirs())) {
            return appExternal
        }
        return File(context.filesDir, subdir).apply { mkdirs() }
    }

    private fun extractApkVersionLabel(context: Context, file: File): String? {
        if (classifyFileName(file.name) != AttacheFileKind.APK) return null
        val cacheKey = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        apkVersionCache[cacheKey]?.let { cached ->
            return cached.takeIf { it.isNotEmpty() }
        }
        val parsed = try {
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            val vName = info?.versionName?.trim()?.takeIf { it.isNotEmpty() }
            if (vName == null) {
                ""
            } else if (vName.startsWith("v", ignoreCase = true) || vName.startsWith("git-")) {
                vName
            } else {
                "v$vName"
            }
        } catch (_: Exception) {
            ""
        }
        apkVersionCache[cacheKey] = parsed
        return parsed.takeIf { it.isNotEmpty() }
    }

    fun listInboxFiles(context: Context): List<LocalInboxFile> {
        val dir = getInboxDirectory(context)
        val files = dir.listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sortedByDescending { it.lastModified() }
        return files.map { f ->
            LocalInboxFile(
                file = f,
                name = f.name,
                sizeBytes = f.length(),
                lastModifiedMs = f.lastModified(),
                kind = classifyFileName(f.name),
                apkVersionLabel = extractApkVersionLabel(context, f),
            )
        }
    }

    /**
     * Downloads [remotePath] from [control] into `Download/Attache/` and indexes
     * it into Android's MediaStore so Gallery and File Managers see it immediately.
     */
    suspend fun receiveRemoteFile(
        context: Context,
        control: TmuxControl,
        remotePath: String,
        onProgress: (FileDownloadProgress) -> Unit = {},
    ): LocalInboxFile? = withContext(Dispatchers.IO) {
        val rawName = remotePath.trim().substringAfterLast('/').ifBlank { "download.bin" }
        val safeName = rawName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val dir = getInboxDirectory(context)
        val destFile = allocateTargetFile(dir, safeName)
        val tempFile = File(dir, "${destFile.name}.part")
        try {
            val ok = FileOutputStream(tempFile).use { out ->
                control.downloadRemoteFile(remotePath, out) { downloaded, total ->
                    onProgress(FileDownloadProgress(destFile.name, downloaded, total))
                }
            }
            if (!ok || !tempFile.exists()) {
                tempFile.delete()
                return@withContext null
            }
            if (destFile.exists()) destFile.delete()
            var finalFile = destFile
            var moved = tempFile.renameTo(finalFile)
            if (!moved) {
                val dot = safeName.lastIndexOf('.')
                val base = if (dot > 0) safeName.substring(0, dot) else safeName
                val ext = if (dot > 0) safeName.substring(dot) else ""
                for (i in 1..999) {
                    val alt = File(dir, "$base ($i)$ext")
                    if (alt.exists()) alt.delete()
                    if (tempFile.renameTo(alt)) {
                        finalFile = alt
                        moved = true
                        break
                    }
                }
            }
            if (!moved) {
                val fallback = File(dir, "${System.currentTimeMillis()}_$safeName")
                if (tempFile.renameTo(fallback)) {
                    finalFile = fallback
                } else {
                    finalFile = tempFile
                }
            }
            try {
                MediaScannerConnection.scanFile(
                    context.applicationContext,
                    arrayOf(finalFile.absolutePath),
                    arrayOf(mimeTypeFor(finalFile)),
                    null,
                )
            } catch (e: Exception) {
                Timber.d(e, "MediaScanner scan skipped")
            }
            LocalInboxFile(
                file = finalFile,
                name = finalFile.name,
                sizeBytes = finalFile.length(),
                lastModifiedMs = finalFile.lastModified(),
                kind = classifyFileName(finalFile.name),
                apkVersionLabel = extractApkVersionLabel(context, finalFile),
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to receive remote file: %s", remotePath)
            tempFile.delete()
            null
        }
    }

    /**
     * Chooses a destination file in [dir]. For APKs with the same name we overwrite
     * the previous build so the newest build is always at the top; for other files
     * that cannot be overwritten we append `(1)`, `(2)`, etc.
     */
    internal fun allocateTargetFile(dir: File, fileName: String): File {
        val direct = File(dir, fileName)
        if (!direct.exists() || direct.canWrite()) {
            return direct
        }
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        for (i in 1..999) {
            val candidate = File(dir, "$base ($i)$ext")
            if (!candidate.exists() || candidate.canWrite()) return candidate
        }
        return File(dir, "${System.currentTimeMillis()}_$fileName")
    }

    fun uriForFile(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun openFileExternally(context: Context, item: LocalInboxFile, onError: (String) -> Unit = {}) {
        try {
            if (item.kind == AttacheFileKind.APK && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settingsIntent)
                    onError("请先授予「允许安装未知应用」权限，然后再点安装 APK")
                    return
                }
            }
            val uri = uriForFile(context, item.file)
            val mime = mimeTypeFor(item.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.w(e, "Failed to open file: %s", item.file.absolutePath)
            onError("没有找到可打开此文件的应用 (${item.name})")
        }
    }

    fun shareFile(context: Context, item: LocalInboxFile, onError: (String) -> Unit = {}) {
        try {
            val uri = uriForFile(context, item.file)
            val mime = mimeTypeFor(item.file)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "分享 ${item.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Timber.w(e, "Failed to share file: %s", item.file.absolutePath)
            onError("分享失败: ${e.message}")
        }
    }
}

internal fun formatInboxSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

private fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
}

@Composable
fun AttacheFileInboxDialog(
    control: TmuxControl?,
    activeDownload: FileDownloadProgress?,
    onStartDownload: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var localFiles by remember { mutableStateOf<List<LocalInboxFile>>(emptyList()) }
    var previewImageFile by remember { mutableStateOf<LocalInboxFile?>(null) }
    var previewTextFile by remember { mutableStateOf<LocalInboxFile?>(null) }

    // Remote browser state
    var remoteFilterMode by remember { mutableStateOf("recent") } // "recent", "cwd", "downloads", "tmp"
    var customRemotePath by remember { mutableStateOf("") }
    var remoteLoading by remember { mutableStateOf(false) }
    var currentRemoteDir by remember { mutableStateOf("") }
    var remoteEntries by remember { mutableStateOf<List<RemoteFileEntry>>(emptyList()) }

    // Settings state (Tab 2)
    var localSubdirInput by remember { mutableStateOf(AttacheFileStorage.DEFAULT_LOCAL_SUBDIR) }
    var remoteBinDirInput by remember { mutableStateOf(AttacheFileStorage.DEFAULT_REMOTE_BIN_DIR) }
    var autoSyncRules by remember { mutableStateOf(true) }
    var deployingHelper by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val subdir = AttacheFileStorage.getLocalSubdir(context)
            val binDir = AttacheFileStorage.getRemoteBinDir(context)
            val sync = AttacheFileStorage.isAutoSyncAgentRulesEnabled(context)
            withContext(Dispatchers.Main) {
                localSubdirInput = subdir
                remoteBinDirInput = binDir
                autoSyncRules = sync
            }
        }
    }

    fun refreshLocalFiles() {
        scope.launch(Dispatchers.IO) {
            val loaded = AttacheFileStorage.listInboxFiles(context)
            withContext(Dispatchers.Main) {
                localFiles = loaded
            }
        }
    }

    fun loadRemoteList(mode: String = remoteFilterMode) {
        val ctrl = control ?: return
        scope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { remoteLoading = true }
            val (dir, entries) = when (mode) {
                "recent" -> ctrl.listRemoteFiles(directory = null, recentArtifactsOnly = true)
                "cwd" -> ctrl.listRemoteFiles(directory = null, recentArtifactsOnly = false)
                "downloads" -> ctrl.listRemoteFiles(directory = "~/Downloads", recentArtifactsOnly = false)
                "tmp" -> ctrl.listRemoteFiles(directory = "/tmp", recentArtifactsOnly = false)
                else -> ctrl.listRemoteFiles(directory = mode, recentArtifactsOnly = false)
            }
            withContext(Dispatchers.Main) {
                currentRemoteDir = dir
                remoteEntries = entries
                remoteLoading = false
            }
        }
    }

    LaunchedEffect(activeDownload) {
        if (activeDownload == null) {
            refreshLocalFiles()
        }
    }

    LaunchedEffect(selectedTab, remoteFilterMode, control) {
        if (selectedTab == 1 && control != null) {
            loadRemoteList(remoteFilterMode)
        }
    }

    previewImageFile?.let { imgItem ->
        InboxImagePreviewDialog(
            item = imgItem,
            onDismiss = { previewImageFile = null },
            onOpenExternal = {
                AttacheFileStorage.openFileExternally(context, imgItem, onShowMessage)
            },
            onShare = {
                AttacheFileStorage.shareFile(context, imgItem, onShowMessage)
            },
        )
    }

    previewTextFile?.let { txtItem ->
        InboxTextPreviewDialog(
            item = txtItem,
            onDismiss = { previewTextFile = null },
            onCopyAll = { content ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(txtItem.name, content))
                onShowMessage("已复制文本内容")
            },
            onOpenExternal = {
                AttacheFileStorage.openFileExternally(context, txtItem, onShowMessage)
            },
            onShare = {
                AttacheFileStorage.shareFile(context, txtItem, onShowMessage)
            },
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 16.dp)
                .testTag("attacheFileInboxDialog"),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Attaché 文件收件箱",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "存储位置: Download/$localSubdirInput/ (系统文件管理与相册可直接访问)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }

                // Active download progress banner inside dialog
                activeDownload?.let { progress ->
                    val pct = if (progress.totalBytes > 0L) {
                        ((progress.downloadedBytes * 100L) / progress.totalBytes).coerceIn(0L, 100L)
                    } else {
                        0L
                    }
                    val fraction = if (progress.totalBytes > 0L) {
                        (progress.downloadedBytes.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "正在接收: ${progress.fileName} ($pct%)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${formatInboxSize(progress.downloadedBytes)} / ${formatInboxSize(progress.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp),
                                )
                            }
                            TextButton(onClick = onCancelDownload) {
                                Text("取消")
                            }
                        }
                    }
                }

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("已收文件 (${localFiles.size})") },
                        modifier = Modifier.testTag("inboxTabLocal"),
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        enabled = control != null,
                        text = { Text("从电脑传过来") },
                        modifier = Modifier.testTag("inboxTabRemote"),
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("⚙️ 配置") },
                        modifier = Modifier.testTag("inboxTabSettings"),
                    )
                }

                if (selectedTab == 0) {
                    // Tab 0: Local received files in Download/Attache/
                    if (localFiles.isEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(24.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (activeDownload != null) {
                                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "正在接收文件: ${activeDownload.fileName}…",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "传输完成后将自动出现在此列表中",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Text(
                                        text = "收件箱暂无文件",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "切到「从电脑传过来」一键拉取 APK / 视频 / 图片，\n或直接对电脑上的 Agent 说「把这个文件发到我手机上」",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (control != null) {
                                        Spacer(modifier = Modifier.height(14.dp))
                                        Button(onClick = { selectedTab = 1 }) {
                                            Text("去从电脑拉取文件")
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            items(localFiles, key = { it.file.absolutePath }) { item ->
                                LocalInboxFileCard(
                                    item = item,
                                    onPrimaryAction = {
                                        when {
                                            item.kind == AttacheFileKind.IMAGE -> {
                                                previewImageFile = item
                                            }
                                            isPreviewableTextFile(item.name) -> {
                                                previewTextFile = item
                                            }
                                            else -> {
                                                AttacheFileStorage.openFileExternally(context, item, onShowMessage)
                                            }
                                        }
                                    },
                                    onOpenExternal = {
                                        AttacheFileStorage.openFileExternally(context, item, onShowMessage)
                                    },
                                    onShare = {
                                        AttacheFileStorage.shareFile(context, item, onShowMessage)
                                    },
                                    onDelete = {
                                        scope.launch(Dispatchers.IO) {
                                            runCatching { item.file.delete() }
                                            val loaded = AttacheFileStorage.listInboxFiles(context)
                                            withContext(Dispatchers.Main) {
                                                localFiles = loaded
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else if (selectedTab == 1) {
                    // Tab 1: Pull from computer over SSH
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        // Direct path pull row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            OutlinedTextField(
                                value = customRemotePath,
                                onValueChange = { customRemotePath = it },
                                placeholder = { Text("输入电脑文件路径 (如 /path/to/app.apk)", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("remotePathInput"),
                            )
                            Button(
                                onClick = {
                                    val target = customRemotePath.trim()
                                    if (target.isNotEmpty()) {
                                        onStartDownload(target)
                                        selectedTab = 0
                                    }
                                },
                                enabled = customRemotePath.isNotBlank() && activeDownload == null,
                                modifier = Modifier.testTag("remotePathDownloadButton"),
                            ) {
                                Text("拉取")
                            }
                        }

                        // Filter chips
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                        ) {
                            FilterChip(
                                selected = remoteFilterMode == "recent",
                                onClick = { remoteFilterMode = "recent" },
                                label = { Text("最近 APK / 视频 / 图片", fontSize = 12.sp) },
                            )
                            FilterChip(
                                selected = remoteFilterMode == "cwd",
                                onClick = { remoteFilterMode = "cwd" },
                                label = { Text("当前终端目录", fontSize = 12.sp) },
                            )
                            FilterChip(
                                selected = remoteFilterMode == "downloads",
                                onClick = { remoteFilterMode = "downloads" },
                                label = { Text("~/Downloads", fontSize = 12.sp) },
                            )
                            FilterChip(
                                selected = remoteFilterMode == "tmp",
                                onClick = { remoteFilterMode = "tmp" },
                                label = { Text("/tmp", fontSize = 12.sp) },
                            )
                        }

                        if (currentRemoteDir.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "路径: $currentRemoteDir",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (remoteFilterMode != "recent" && currentRemoteDir.startsWith("/") && currentRemoteDir != "/") {
                                    TextButton(
                                        onClick = {
                                            val parent = currentRemoteDir.substringBeforeLast('/').ifEmpty { "/" }
                                            remoteFilterMode = parent
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(26.dp),
                                    ) {
                                        Text("⬆️ 上一级", fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        HorizontalDivider()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            if (remoteLoading) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            } else if (remoteEntries.isEmpty()) {
                                Text(
                                    text = "该目录下没有找到文件",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(remoteEntries, key = { it.path }) { remote ->
                                        RemoteFileRow(
                                            entry = remote,
                                            baseDir = currentRemoteDir,
                                            enabled = activeDownload == null,
                                            onOpenDirectory = {
                                                remoteFilterMode = remote.path
                                            },
                                            onDownload = {
                                                onStartDownload(remote.path)
                                                selectedTab = 0
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // Computer push helper banner
                        HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "💡 对 Agent 说「把这个文件发给我」或运行 ~/.local/bin/attache-send <文件>",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    val cmd = "${AttacheFileStorage.getRemoteBinDir(context)}/attache-send "
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("attache-send", cmd))
                                    onShowMessage("已复制命令: $cmd")
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp),
                            ) {
                                Text("复制命令", fontSize = 11.5.sp)
                            }
                        }
                    }
                } else {
                    // Tab 2: Self-contained configuration & remote Agent rule provisioning
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        OutlinedTextField(
                            value = localSubdirInput,
                            onValueChange = { localSubdirInput = it },
                            label = { Text("手机保存目录 (Download/<子目录>)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = remoteBinDirInput,
                            onValueChange = { remoteBinDirInput = it },
                            label = { Text("远端电脑命令安装目录 (连接时自动部署 attache-send)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { autoSyncRules = !autoSyncRules },
                        ) {
                            Checkbox(
                                checked = autoSyncRules,
                                onCheckedChange = { autoSyncRules = it },
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "自动将传文件规则注入远端 Agent (CLAUDE.md / GEMINI.md)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "开启后，任何用户连接远端电脑时会自动配置好命令与规则，直接对 Agent 说「把文件发给我」即可收到（即使手机切到后台也能收）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = {
                                    AttacheFileStorage.setLocalSubdir(context, localSubdirInput)
                                    AttacheFileStorage.setRemoteBinDir(context, remoteBinDirInput)
                                    AttacheFileStorage.setAutoSyncAgentRulesEnabled(context, autoSyncRules)
                                    localSubdirInput = AttacheFileStorage.getLocalSubdir(context)
                                    remoteBinDirInput = AttacheFileStorage.getRemoteBinDir(context)
                                    refreshLocalFiles()
                                    val ctrl = control
                                    if (ctrl != null) {
                                        deployingHelper = true
                                        scope.launch(Dispatchers.IO) {
                                            val ok = ctrl.installRemoteSendHelper(remoteBinDirInput, autoSyncRules)
                                            withContext(Dispatchers.Main) {
                                                deployingHelper = false
                                                onShowMessage(
                                                    if (ok) {
                                                        "已保存并同步到远端: $remoteBinDirInput/attache-send"
                                                    } else {
                                                        "本地设置已保存（远端同步失败）"
                                                    },
                                                )
                                            }
                                        }
                                    } else {
                                        onShowMessage("设置已保存")
                                    }
                                },
                                enabled = !deployingHelper,
                            ) {
                                Text(if (deployingHelper) "正在部署到远端…" else "保存并立即部署到当前主机")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isPreviewableTextFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return ext in setOf("txt", "md", "log", "json", "csv", "sh", "xml", "yaml", "yml", "conf", "ini", "toml")
}

@Composable
private fun LocalInboxFileCard(
    item: LocalInboxFile,
    onPrimaryAction: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, item.file.absolutePath) {
        if (item.kind == AttacheFileKind.IMAGE) {
            value = withContext(Dispatchers.IO) {
                try {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(item.file.absolutePath, opts)?.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
    val isTextFile = remember(item.name) { isPreviewableTextFile(item.name) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPrimaryAction() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // Row 1: Thumbnail / Icon + Full Filename & Single-line Size/Time
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        val iconText = when {
                            item.kind == AttacheFileKind.APK -> "📦"
                            item.kind == AttacheFileKind.VIDEO -> "🎬"
                            item.kind == AttacheFileKind.IMAGE -> "🖼"
                            isTextFile -> "📝"
                            else -> "📄"
                        }
                        Text(iconText, fontSize = 20.sp)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val metaPrefix = item.apkVersionLabel?.let { "$it · " } ?: ""
                    Text(
                        text = "$metaPrefix${formatInboxSize(item.sizeBytes)} · ${formatTimestamp(item.lastModifiedMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.apkVersionLabel != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (item.apkVersionLabel != null) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Row 2: Right-aligned Action Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                TextButton(
                    onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
                TextButton(
                    onClick = onShare,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text("分享", fontSize = 12.sp)
                }
                if (isTextFile || item.kind == AttacheFileKind.IMAGE) {
                    TextButton(
                        onClick = onOpenExternal,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("外部打开", fontSize = 12.sp)
                    }
                }
                when {
                    item.kind == AttacheFileKind.APK -> {
                        Button(
                            onClick = onPrimaryAction,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("安装 APK", fontSize = 12.sp)
                        }
                    }
                    item.kind == AttacheFileKind.VIDEO -> {
                        FilledTonalButton(
                            onClick = onPrimaryAction,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("播放视频", fontSize = 12.sp)
                        }
                    }
                    item.kind == AttacheFileKind.IMAGE -> {
                        FilledTonalButton(
                            onClick = onPrimaryAction,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("预览图片", fontSize = 12.sp)
                        }
                    }
                    isTextFile -> {
                        FilledTonalButton(
                            onClick = onPrimaryAction,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("查看文本", fontSize = 12.sp)
                        }
                    }
                    else -> {
                        FilledTonalButton(
                            onClick = onOpenExternal,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("打开文件", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteFileRow(
    entry: RemoteFileEntry,
    baseDir: String,
    enabled: Boolean,
    onOpenDirectory: () -> Unit = {},
    onDownload: () -> Unit,
) {
    val kind = AttacheFileStorage.classifyFileName(entry.name)
    val badge = if (entry.isDirectory) {
        "📁 文件夹"
    } else {
        when (kind) {
            AttacheFileKind.APK -> "📦 APK"
            AttacheFileKind.VIDEO -> "🎬 视频"
            AttacheFileKind.IMAGE -> "🖼 图片"
            AttacheFileKind.OTHER -> "📄 文件"
        }
    }
    val cleanBase = baseDir.substringBefore(" (")
    val relPath = if (cleanBase.isNotEmpty() && entry.path.startsWith("$cleanBase/")) {
        entry.path.removePrefix("$cleanBase/")
    } else {
        entry.path
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (entry.isDirectory) {
                    onOpenDirectory()
                } else if (enabled) {
                    onDownload()
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = badge,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (entry.isDirectory) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (entry.isDirectory) "${entry.name}/" else entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val metaText = if (entry.isDirectory) {
                    "$relPath · ${formatTimestamp(entry.modifiedEpochSec * 1000L)}"
                } else {
                    "$relPath · ${formatInboxSize(entry.sizeBytes)} · ${formatTimestamp(entry.modifiedEpochSec * 1000L)}"
                }
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (entry.isDirectory) {
                    FilledTonalButton(
                        onClick = onOpenDirectory,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("进入目录", fontSize = 12.sp)
                    }
                } else {
                    FilledTonalButton(
                        onClick = onDownload,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("下载到手机", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxTextPreviewDialog(
    item: LocalInboxFile,
    onDismiss: () -> Unit,
    onCopyAll: (String) -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
) {
    val textContent by produceState<String?>(initialValue = null, item.file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            try {
                // Read up to 128 KB for preview
                val maxBytes = 128 * 1024
                val bytes = item.file.inputStream().use { input ->
                    val buf = ByteArray(maxBytes)
                    val read = input.read(buf)
                    if (read <= 0) ByteArray(0) else buf.copyOf(read)
                }
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                "无法读取文本内容: ${e.message}"
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 420.dp)
                        .padding(vertical = 8.dp),
                ) {
                    val loaded = textContent
                    if (loaded == null) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                text = loaded,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onShare) {
                        Text("分享")
                    }
                    TextButton(onClick = onOpenExternal) {
                        Text("外部打开")
                    }
                    Button(
                        onClick = { textContent?.let(onCopyAll) },
                        enabled = textContent != null,
                    ) {
                        Text("复制全文")
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxImagePreviewDialog(
    item: LocalInboxFile,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, item.file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            try {
                BitmapFactory.decodeFile(item.file.absolutePath)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 480.dp)
                        .padding(vertical = 8.dp),
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!,
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onShare) {
                        Text("分享")
                    }
                    Button(
                        onClick = onOpenExternal,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text("用系统图库打开")
                    }
                }
            }
        }
    }
}
