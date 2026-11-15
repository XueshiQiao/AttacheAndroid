/*
 * Attaché: Mermaid diagrams in a reply.
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

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.attache.tmux.TmuxControl
import org.json.JSONObject

/**
 * A ```` ```mermaid ```` block as a picture. attache-cli on the host draws
 * it (`mermaid`, 0.3.17) as a PNG, so the desktop shows the same one, and
 * this shows that PNG. No WebView draws anything here and none may: the
 * owner's ruling of 2026-11-15, after a first version that loaded an SVG
 * into one.
 *
 * **When it is asked for.** A block is a diagram only once its closing fence
 * has arrived ([ChatMarkdown]), so a reply still being written is never drawn
 * half-made. Then, when the block comes on screen: the PNG kept on this
 * phone if there is one, else attache-cli is asked to draw it and the file
 * is fetched from the host, and until a picture exists the block is the code
 * it always was. Every failure ends there too.
 *
 * What is remembered differs by what failed, and that is the part to keep:
 * attache-cli *refusing* the source is true of that source forever and is
 * kept for the life of the app; *no answer* (the link dropped, an attache-cli
 * from before `mermaid`, a fetch that failed) says nothing about the source
 * and is asked again after half a minute.
 */
internal object ChatDiagrams {
    sealed interface Answer {
        /** The PNG's place on the host. */
        data class Drawn(val path: String) : Answer
        data class Refused(val why: String) : Answer
        data object Unanswered : Answer
    }

    /** attache-cli refuses more than this itself; not asking keeps it off a command line. */
    const val MAX_SOURCE = 64 * 1024
    private const val RETRY_AFTER_MS = 30_000L

    /** Pixels per point asked for. Three is the densest phone screen in
     * use, and a picture drawn for it is fine on a sparser one. */
    const val SCALE = 3

    /** Raised by hand when attache-cli's defaults change what it draws for the same source. */
    private const val GENERATION = 3

    private val refused = ConcurrentHashMap.newKeySet<String>()
    private val unanswered = ConcurrentHashMap<String, Long>()
    private val loading = ConcurrentHashMap<String, CompletableDeferred<File?>>()

    /** Opening a long conversation is not thirty commands at once on one connection. */
    private val asking = Semaphore(2)

    fun args(source: String, dark: Boolean): String? {
        val bytes = source.toByteArray()
        if (bytes.isEmpty() || bytes.size > MAX_SOURCE) return null
        return "mermaid --source " + Base64.encodeToString(bytes, Base64.NO_WRAP) + " --scale $SCALE" + if (dark) " --dark" else ""
    }

    fun answer(lines: List<String>?): Answer {
        if (lines == null) return Answer.Unanswered
        for (line in lines.asReversed()) {
            val o = try {
                JSONObject(line)
            } catch (_: Exception) {
                continue
            }
            if (!o.has("mermaid")) continue
            if (!o.optBoolean("mermaid")) return Answer.Refused(o.optString("why"))
            val path = o.optString("path")
            // A path is fetched as it stands, so it has to be one: absolute,
            // a PNG, and a single line.
            val sane = path.startsWith("/") && path.endsWith(".png") && path.none { it == '\n' || it == '\u0000' }
            return if (sane) Answer.Drawn(path) else Answer.Unanswered
        }
        return Answer.Unanswered
    }

    fun key(source: String, dark: Boolean): String = MessageDigest.getInstance("SHA-256")
        .digest("$GENERATION\n${if (dark) "dark" else "light"}\n$SCALE\n$source".toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun file(context: Context, key: String) = File(File(context.cacheDir, "mermaid"), "$key.png")

    /** Bounds only: whether the file is a picture, without holding it. */
    private fun isPicture(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    /** The PNG of [source] on this phone, or null when there is none to show now. */
    suspend fun picture(context: Context, control: TmuxControl, source: String, dark: Boolean): File? {
        val key = key(source, dark)
        if (key in refused) return null
        val mine = CompletableDeferred<File?>()
        loading.putIfAbsent(key, mine)?.let { return it.await() }
        try {
            mine.complete(load(context.applicationContext, control, key, source, dark))
        } catch (e: Throwable) {
            mine.complete(null)
            throw e
        } finally {
            loading.remove(key, mine)
        }
        return mine.await()
    }

    private suspend fun load(context: Context, control: TmuxControl, key: String, source: String, dark: Boolean): File? {
        val file = file(context, key)
        if (withContext(Dispatchers.IO) { file.isFile && isPicture(file) }) return file
        unanswered[key]?.let { if (System.currentTimeMillis() - it < RETRY_AFTER_MS) return null }
        val args = args(source, dark) ?: run {
            refused += key
            return null
        }
        return when (val answer = asking.withPermit { answer(AttacheCli.lines(control, args)) }) {
            is Answer.Drawn -> {
                val fetched = ChatMedia.downloading.withLock {
                    withContext(Dispatchers.IO) {
                        // Fetched beside and renamed: a file cut short by the
                        // link dropping is never read back as a picture.
                        val part = File(file.path + ".part")
                        try {
                            file.parentFile?.mkdirs()
                            val ok = FileOutputStream(part).use { control.downloadRemoteFile(answer.path, it) }
                            ok && isPicture(part) && part.renameTo(file)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            false
                        } finally {
                            part.delete()
                        }
                    }
                }
                if (fetched) {
                    unanswered.remove(key)
                    file
                } else {
                    unanswered[key] = System.currentTimeMillis()
                    null
                }
            }

            is Answer.Refused -> {
                refused += key
                null
            }

            Answer.Unanswered -> {
                unanswered[key] = System.currentTimeMillis()
                null
            }
        }
    }

    fun decode(file: File): ImageBitmap? = try {
        BitmapFactory.decodeFile(file.path)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

/**
 * The block: its source as code until there is a picture, then the picture.
 * A tap on the picture opens it over the whole screen, where two fingers
 * zoom. "Source" under it shows the code, and "Show diagram" under the code
 * brings the picture back: either way round, always a way back.
 */
@Composable
internal fun ChatDiagram(block: MdBlock.Diagram, modifier: Modifier = Modifier) {
    val control = LocalChatMedia.current?.control
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    var asSource by remember(block.source) { mutableStateOf(false) }
    var viewing by remember(block.source) { mutableStateOf(false) }
    val picture by produceState<ImageBitmap?>(null, block.source, dark, control) {
        val file = control?.let { ChatDiagrams.picture(context, it, block.source, dark) }
        value = file?.let { withContext(Dispatchers.IO) { ChatDiagrams.decode(it) } }
    }
    val bitmap = picture
    if (bitmap == null || asSource) {
        Column(modifier) {
            CodeBlock(block.source)
            if (bitmap != null) {
                TextButton(onClick = { asSource = false }) { Text("Show diagram") }
            }
        }
        return
    }
    Column(modifier) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth().testTag("chatDiagram"),
        ) {
            BoxWithConstraints(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                // Drawn at the size it was laid out for when the column is
                // wide enough, and scaled down to the column when it is not.
                // Never up: a three-box diagram blown up to the width of a
                // tablet is all box.
                val width = minOf(maxWidth, (bitmap.width / ChatDiagrams.SCALE).dp)
                val height = width * (bitmap.height.toFloat() / bitmap.width)
                Image(
                    bitmap = bitmap,
                    contentDescription = "Diagram",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(width).height(height).clickable { viewing = true },
                )
            }
        }
        TextButton(onClick = { asSource = true }) { Text("Source") }
    }
    if (viewing) DiagramViewer(bitmap) { viewing = false }
}

/** The picture over the whole screen, on the page's own colour — it is
 * transparent, and drawn for this theme: two fingers zoom, one drags. */
@Composable
private fun DiagramViewer(bitmap: ImageBitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .testTag("chatDiagramViewer"),
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Diagram",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}
