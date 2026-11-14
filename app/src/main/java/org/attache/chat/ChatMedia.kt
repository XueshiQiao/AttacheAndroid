/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.attache.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.attache.tmux.AttacheFileStorage
import org.attache.tmux.TmuxControl
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal enum class MediaKind { IMAGE, VIDEO }

/** What the host said of a file: there, with the size and the time it was
 * last written, or not there. */
internal sealed interface RemoteFile {
    data class There(val path: String, val size: Long, val modified: Long) : RemoteFile

    data object Missing : RemoteFile
}

/** Where the files a reply names are: which connection reaches the host,
 * which host that is, and the folder a relative path is relative to. */
internal data class ChatMediaSource(val control: TmuxControl?, val host: String, val cwd: String)

/** Null outside a chat page, where a card can be shown and not loaded. */
internal val LocalChatMedia = staticCompositionLocalOf<ChatMediaSource?> { null }

/**
 * The pictures and videos a reply names by a path on the host, which is how
 * an agent shows its work: `![what it is](/tmp/shot.png)`. Nothing is
 * fetched until the card is tapped. A fetched file is kept in the app's
 * cache under the host, the path and the file's size and time on the host,
 * so a picture drawn again at the same path is fetched again and an
 * unchanged one opens at once.
 */
internal object ChatMedia {
    private val IMAGES = setOf("png", "jpg", "jpeg", "gif", "webp", "heic")
    private val VIDEOS = setOf("mp4", "mov", "webm", "m4v")

    /** Past these the card asks before it downloads. */
    private const val IMAGE_ASK_BYTES = 20L * 1024 * 1024
    private const val VIDEO_ASK_BYTES = 100L * 1024 * 1024

    fun asksFirst(kind: MediaKind, size: Long) = size > if (kind == MediaKind.IMAGE) IMAGE_ASK_BYTES else VIDEO_ASK_BYTES

    /** A picture or a video on the host, by its name; null for anything
     * else, and for the web, which is a link as before. */
    fun kindOf(destination: String): MediaKind? {
        val scheme = destination.substringBefore("://", "").lowercase()
        if (scheme.isNotEmpty() && scheme != "file") return null
        return when (pathOf(destination).substringAfterLast('.', "").lowercase()) {
            in IMAGES -> MediaKind.IMAGE
            in VIDEOS -> MediaKind.VIDEO
            else -> null
        }
    }

    /** The path a Markdown destination names. */
    fun pathOf(destination: String): String = if (destination.startsWith("file://", ignoreCase = true)) {
        // Percent-escapes only: to URLDecoder a `+` is a space, which in a
        // path it is not.
        try {
            java.net.URLDecoder.decode(destination.substring("file://".length).replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            destination.substring("file://".length)
        }
    } else {
        destination
    }

    // `~/` is the person's home and anything else not from the root is
    // under the agent's folder. Prints the path it settled on, since that
    // is the one to download.
    private const val STAT =
        "my (\$p, \$cwd) = @ARGV; \$p =~ s{^~/}{\$ENV{HOME}/}; " +
            "\$p = \"\$cwd/\$p\" unless \$p =~ m{^/}; " +
            "if (-f \$p) { my @s = stat(_); print \"OK \$s[7] \$s[9] \$p\\n\" } else { print \"NO\\n\" }"

    private fun quote(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"

    /** Null when the host could not be asked or did not answer. */
    suspend fun stat(control: TmuxControl, path: String, cwd: String): RemoteFile? {
        if (path.isBlank() || path.any { it == '\n' || it == '\u0000' }) return RemoteFile.Missing
        val out = control.run("perl -e ${quote(STAT)} ${quote(path)} ${quote(cwd)}") ?: return null
        for (line in out.lines()) {
            if (line.trim() == "NO") return RemoteFile.Missing
            if (!line.startsWith("OK ")) continue
            val f = line.split(" ", limit = 4)
            val size = f.getOrNull(1)?.toLongOrNull() ?: continue
            val modified = f.getOrNull(2)?.toLongOrNull() ?: continue
            val full = f.getOrNull(3)?.takeIf { it.startsWith("/") } ?: continue
            return RemoteFile.There(full, size, modified)
        }
        return null
    }

    /** The folder one named file's copies are kept in. */
    fun folder(context: Context, source: ChatMediaSource?, path: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest("${source?.host}\n${source?.cwd}\n$path".toByteArray())
        return File(File(context.cacheDir, "chat-media"), digest.joinToString("") { "%02x".format(it) })
    }

    private fun extension(path: String) = path.substringAfterLast('.', "bin").lowercase()

    /** The copy of exactly this version of the file. */
    fun copyOf(folder: File, there: RemoteFile.There) = File(folder, "${there.size}-${there.modified}.${extension(there.path)}")

    /** Whatever copy there is, for showing before the host is asked. */
    fun anyCopy(folder: File): File? = folder.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.maxByOrNull { it.lastModified() }

    // One at a time: they share one transfer connection with the inbox.
    private val downloading = Mutex()

    suspend fun download(
        control: TmuxControl,
        folder: File,
        there: RemoteFile.There,
        onProgress: (Long, Long) -> Unit,
    ): File? = downloading.withLock {
        withContext(Dispatchers.IO) {
            folder.mkdirs()
            val target = copyOf(folder, there)
            val part = File(folder, "${target.name}.part")
            try {
                val ok = FileOutputStream(part).use { out -> control.downloadRemoteFile(there.path, out, onProgress) }
                if (!ok || !part.renameTo(target)) return@withContext null
                // An older version of the same file is of no more use.
                folder.listFiles()?.forEach { if (it != target) it.delete() }
                target
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "could not fetch %s", there.path)
                null
            } finally {
                part.delete()
            }
        }
    }

    /** Sampled down while decoding, so a huge picture is not held whole. */
    fun decode(file: File, longestSide: Int): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > longestSide) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    } catch (e: Throwable) {
        Timber.w(e, "could not decode %s", file)
        null
    }

    /** Hands a video to whichever app on the phone plays videos. */
    fun play(context: Context, file: File): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(AttacheFileStorage.uriForFile(context, file), AttacheFileStorage.mimeTypeFor(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: Exception) {
        Timber.w(e, "could not play %s", file)
        false
    }

    /** A copy into `Download/Attache/`, under the name it has on the host. */
    suspend fun save(context: Context, file: File, name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { file.name }
            val target = AttacheFileStorage.allocateTargetFile(AttacheFileStorage.getInboxDirectory(context), safe)
            file.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(target.absolutePath), null, null)
            true
        } catch (e: Exception) {
            Timber.w(e, "could not save %s", file)
            false
        }
    }
}

/**
 * One picture or video a reply names. Before it is fetched: what it is and
 * its file name. A tap asks the host whether the file is still there and
 * how big, fetches it (asking first when it is large) and shows it: a
 * picture over the whole screen, a video in the phone's own player. Once
 * fetched, a picture is drawn in the reply itself.
 */
@Composable
internal fun ChatMediaCard(media: MdBlock.Media, modifier: Modifier = Modifier) {
    val source = LocalChatMedia.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val path = remember(media.destination) { ChatMedia.pathOf(media.destination) }
    val name = path.substringAfterLast('/')
    val folder = remember(source?.host, source?.cwd, path) { ChatMedia.folder(context, source, path) }
    var local by remember(folder) { mutableStateOf<File?>(null) }
    var status by remember(folder) { mutableStateOf<String?>(null) }
    var job by remember(folder) { mutableStateOf<Job?>(null) }
    var asking by remember(folder) { mutableStateOf<RemoteFile.There?>(null) }
    var viewing by remember(folder) { mutableStateOf<File?>(null) }

    LaunchedEffect(folder) { local = withContext(Dispatchers.IO) { ChatMedia.anyCopy(folder) } }
    val thumbnail by produceState<ImageBitmap?>(null, local) {
        val file = local?.takeIf { media.kind == MediaKind.IMAGE }
        value = file?.let { withContext(Dispatchers.IO) { ChatMedia.decode(it, 1280) } }
    }

    fun show(file: File) {
        local = file
        status = null
        if (media.kind == MediaKind.IMAGE) {
            viewing = file
        } else if (!ChatMedia.play(context, file)) {
            status = "No app on this phone plays it. Save it and open it from Download/Attache."
        }
    }

    fun fetch(control: TmuxControl, there: RemoteFile.There) {
        job = scope.launch {
            status = "Downloading…"
            val file = ChatMedia.download(control, folder, there) { got, total ->
                status = if (total > 0) "Downloading ${got * 100 / total}% · tap to cancel" else "Downloading… · tap to cancel"
            }
            job = null
            if (file != null) show(file) else status = "Could not download. Tap to try again."
        }
    }

    fun tap() {
        job?.let {
            it.cancel()
            job = null
            status = null
            return
        }
        val control = source?.control
        if (control == null) {
            local?.let(::show) ?: run { status = "Not connected to the host" }
            return
        }
        job = scope.launch {
            status = "Asking the host…"
            val there = try {
                ChatMedia.stat(control, path, source.cwd)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            job = null
            when (there) {
                is RemoteFile.There -> {
                    val copy = ChatMedia.copyOf(folder, there)
                    when {
                        withContext(Dispatchers.IO) { copy.isFile } -> show(copy)

                        ChatMedia.asksFirst(media.kind, there.size) -> {
                            status = null
                            asking = there
                        }

                        else -> fetch(control, there)
                    }
                }

                // The copy from before is still worth looking at.
                RemoteFile.Missing -> local?.let(::show) ?: run { status = "No longer on the host" }

                null -> local?.let(::show) ?: run { status = "The host did not answer. Tap to try again." }
            }
        }
    }

    asking?.let { there ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Download ${there.size / (1024 * 1024)} MB?") },
            text = { Text(name) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    source?.control?.let { fetch(it, there) }
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }

    fun save(file: File) {
        scope.launch {
            val ok = ChatMedia.save(context, file, name)
            Toast.makeText(context, if (ok) "Saved to Download/Attache" else "Could not save", Toast.LENGTH_SHORT).show()
        }
    }

    viewing?.let { file -> ChatImageViewer(file, onSave = { save(file) }, onDismiss = { viewing = null }) }

    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable { tap() }
            .testTag("chatMedia"),
    ) {
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = media.label.ifBlank { name },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Icon(
                if (media.kind == MediaKind.IMAGE) Icons.Filled.Image else Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(media.label.ifBlank { name }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    status ?: when {
                        local == null -> "$name · tap to load"
                        media.kind == MediaKind.IMAGE -> "$name · tap to view"
                        else -> "$name · tap to play"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            local?.takeIf { job == null }?.let { file -> TextButton(onClick = { save(file) }) { Text("Save") } }
        }
    }
}

/** A picture over the whole screen: two fingers zoom, one drags. */
@Composable
private fun ChatImageViewer(file: File, onSave: () -> Unit, onDismiss: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, file) { value = withContext(Dispatchers.IO) { ChatMedia.decode(file, 4096) } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .testTag("chatImageViewer"),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            }
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                TextButton(onClick = onSave) { Text("Save", color = Color.White) }
                TextButton(onClick = onDismiss) { Text("Close", color = Color.White) }
            }
        }
    }
}
