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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.attache.tmux.TmuxControl
import org.json.JSONObject

/**
 * A ```` ```mermaid ```` block as a picture. attache-cli on the host lays it
 * out (`mermaid`, 0.3.14), so the desktop shows the same one; this draws the
 * SVG that comes back.
 *
 * **When it is asked for.** A block is a diagram only once its closing fence
 * has arrived ([ChatMarkdown]), so a reply still being written is never drawn
 * half-made. Then, when the block comes on screen: the SVG kept on this
 * phone if there is one, else attache-cli is asked, and until a picture
 * exists the block is the code it always was. Every failure ends there too.
 *
 * What is remembered differs by what failed, and that is the part to keep:
 * attache-cli *refusing* the source is true of that source forever and is
 * kept for the life of the app; *no answer* (the link dropped, an attache-cli
 * from before `mermaid`) says nothing about the source and is asked again
 * after half a minute.
 */
internal object ChatDiagrams {
    sealed interface Answer {
        data class Svg(val svg: String) : Answer
        data class Refused(val why: String) : Answer
        data object Unanswered : Answer
    }

    /** attache-cli refuses more than this itself; not asking keeps it off a command line. */
    const val MAX_SOURCE = 64 * 1024
    private const val RETRY_AFTER_MS = 30_000L

    /** Raised by hand when attache-cli's defaults change what it draws for the same source. */
    private const val GENERATION = 1

    private val refused = ConcurrentHashMap.newKeySet<String>()
    private val unanswered = ConcurrentHashMap<String, Long>()
    private val loading = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /** Opening a long conversation is not thirty commands at once on one connection. */
    private val asking = Semaphore(2)

    fun args(source: String, dark: Boolean): String? {
        val bytes = source.toByteArray()
        if (bytes.isEmpty() || bytes.size > MAX_SOURCE) return null
        return "mermaid --source " + Base64.encodeToString(bytes, Base64.NO_WRAP) + if (dark) " --dark" else ""
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
            val svg = o.optString("svg")
            return if (svg.startsWith("<svg")) Answer.Svg(svg) else Answer.Unanswered
        }
        return Answer.Unanswered
    }

    fun key(source: String, dark: Boolean): String = MessageDigest.getInstance("SHA-256")
        .digest("$GENERATION\n${if (dark) "dark" else "light"}\n$source".toByteArray())
        .joinToString("") { "%02x".format(it) }

    /** The picture's width and height, from the root `viewBox`: the only
     * place merman says them (`width` is `100%`). Null for anything that is
     * not a sane picture. */
    fun size(svg: String): Pair<Float, Float>? {
        val root = svg.substringBefore('>', "")
        val box = Regex("viewBox=\"([^\"]*)\"").find(root)?.groupValues?.get(1) ?: return null
        val n = box.split(' ', ',').filter { it.isNotEmpty() }.map { it.toFloatOrNull() ?: return null }
        if (n.size != 4) return null
        val (w, h) = n[2] to n[3]
        return if (w.isFinite() && h.isFinite() && w >= 1f && h >= 1f && w <= 8000f && h <= 8000f) w to h else null
    }

    /**
     * The page the picture is drawn from: the SVG and nothing else. The
     * policy line is the second lock on a door the WebView already shuts
     * (no JavaScript, no network): the SVG is laid out from an agent's
     * reply, and nothing in it may fetch anything or run.
     */
    fun page(svg: String, zoomable: Boolean): String = "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1" + (if (zoomable) ", maximum-scale=8" else ", user-scalable=no") + "\">" +
        "<style>html,body{margin:0;padding:0;background:transparent}" +
        "body>svg{display:block;width:100%!important;height:auto!important;max-width:none!important}</style>" +
        "</head><body>$svg</body></html>"

    private fun file(context: Context, key: String) = File(File(context.cacheDir, "mermaid"), "$key.svg")

    /** The SVG of [source], or null when there is none to show now. */
    suspend fun svg(context: Context, control: TmuxControl, source: String, dark: Boolean): String? {
        val key = key(source, dark)
        if (key in refused) return null
        val mine = CompletableDeferred<String?>()
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

    private suspend fun load(context: Context, control: TmuxControl, key: String, source: String, dark: Boolean): String? {
        val file = file(context, key)
        withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() }
            ?.takeIf { it.startsWith("<svg") && size(it) != null }
            ?.let { return it }
        unanswered[key]?.let { if (System.currentTimeMillis() - it < RETRY_AFTER_MS) return null }
        val args = args(source, dark) ?: run {
            refused += key
            return null
        }
        return when (val answer = asking.withPermit { answer(AttacheCli.lines(control, args)) }) {
            is Answer.Svg -> if (size(answer.svg) == null) {
                refused += key
                null
            } else {
                unanswered.remove(key)
                withContext(Dispatchers.IO) {
                    runCatching {
                        // Written beside and renamed: a file cut short by the
                        // app dying is never read back as a picture.
                        file.parentFile?.mkdirs()
                        val part = File(file.path + ".part")
                        part.writeText(answer.svg)
                        part.renameTo(file)
                    }
                }
                answer.svg
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
}

/**
 * The block: its source as code until there is a picture, then the picture.
 * A tap opens it over the whole screen, where two fingers zoom; from there
 * "Source" puts the code back in the reply.
 */
@Composable
internal fun ChatDiagram(block: MdBlock.Diagram, modifier: Modifier = Modifier) {
    val control = LocalChatMedia.current?.control
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    var asSource by remember(block.source) { mutableStateOf(false) }
    var viewing by remember(block.source) { mutableStateOf(false) }
    val svg by produceState<String?>(null, block.source, dark, control) {
        value = control?.let { ChatDiagrams.svg(context, it, block.source, dark) }
    }
    val picture = svg
    val size = remember(picture) { picture?.let(ChatDiagrams::size) }
    if (picture == null || size == null || asSource) {
        Column(modifier) {
            CodeBlock(block.source)
            if (picture != null && size != null) {
                TextButton(onClick = { asSource = false }) { Text("Show diagram") }
            }
        }
        return
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = modifier.fillMaxWidth().testTag("chatDiagram"),
    ) {
        BoxWithConstraints(Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
            // The SVG's units are CSS pixels, which are dp: drawn at its own
            // size when the column is wide enough, and scaled down to the
            // column when it is not. Never up — a three-box diagram blown up
            // to the width of a tablet is all box.
            val width = minOf(maxWidth, size.first.dp)
            val height = width * (size.second / size.first)
            Box(Modifier.width(width).height(height)) {
                DiagramWeb(picture, zoomable = false, Modifier.fillMaxSize())
                // Over the WebView, which would otherwise take every touch
                // that starts on it and the list could not be scrolled from
                // a diagram.
                Box(Modifier.fillMaxSize().clickable { viewing = true })
            }
        }
    }
    if (viewing) {
        Dialog(onDismissRequest = { viewing = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("chatDiagramViewer")) {
                DiagramWeb(picture, zoomable = true, Modifier.fillMaxSize().statusBarsPadding().padding(top = 48.dp))
                Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(horizontal = 8.dp)) {
                    TextButton(onClick = {
                        asSource = true
                        viewing = false
                    }) { Text("Source") }
                    TextButton(onClick = { viewing = false }) { Text("Close") }
                }
            }
        }
    }
}

/**
 * A WebView, and only as a drawing engine: the SVG is styled by a `<style>`
 * element, which is what needs one. Nothing is browsed — JavaScript stays
 * off, the network and files are blocked, and no link is followed.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun DiagramWeb(svg: String, zoomable: Boolean, modifier: Modifier = Modifier) {
    val background = if (zoomable) MaterialTheme.colorScheme.surface.toArgb() else AndroidColor.TRANSPARENT
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = zoomable
                settings.displayZoomControls = false
                isVerticalScrollBarEnabled = zoomable
                isHorizontalScrollBarEnabled = zoomable
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                }
            }
        },
        update = { web ->
            web.setBackgroundColor(background)
            if (web.tag != svg) {
                web.tag = svg
                web.loadDataWithBaseURL(null, ChatDiagrams.page(svg, zoomable), "text/html", "utf-8", null)
            }
        },
    )
}
