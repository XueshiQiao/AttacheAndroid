package org.attache.tmux

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.service.TerminalManager
import timber.log.Timber

/**
 * Payload captured from an incoming Android `Intent.ACTION_SEND` (either via the main
 * Attaché share icon or one of the 4 quick-share / Direct Share window targets).
 */
data class AttacheSharePayload(
    val id: Long = SystemClock.uptimeMillis(),
    val sharedText: String,
    val sharedSubject: String = "",
    val streamUri: Uri? = null,
    val mimeType: String = "",
    val targetWindowId: String? = null,
    val targetSessionName: String? = null,
    val targetWindowPosition: Int? = null,
    val targetWindowNameHint: String? = null,
    val defaultPreset: ShareActionPreset = ShareActionPreset.DIRECT,
) {
    val isQuickDirectTarget: Boolean
        get() = targetWindowId != null || targetWindowPosition != null

    val initialEditableText: String
        get() {
            val subjectClean = sharedSubject.replace(Regex("[\\r\\n]+"), " ").trim()
            val textClean = sharedText.replace(Regex("[\\r\\n]+"), " ").trim()
            return when {
                subjectClean.isNotBlank() && textClean.isNotBlank() && !textClean.contains(subjectClean) ->
                    "$subjectClean $textClean"
                textClean.isNotBlank() -> textClean
                subjectClean.isNotBlank() -> subjectClean
                else -> ""
            }
        }
}

enum class ShareActionPreset(
    val label: String,
    val prefix: String,
    val submitWithEnter: Boolean = true,
) {
    DIRECT(
        label = "💬 直接发送",
        prefix = "",
        submitWithEnter = true,
    ),
    DOWNLOAD_VIDEO(
        label = "📥 下载视频发回手机",
        prefix = "请用 yt-dlp 下载这个链接的视频到 /Users/joey/Downloads/AttacheShare/ 完成后用 ~/.local/bin/attache-send 发回我手机： ",
        submitWithEnter = true,
    ),
    DEEP_SUMMARY(
        label = "🧠 深度总结分析",
        prefix = "请帮我深度分析并总结这个内容的核心要点： ",
        submitWithEnter = true,
    ),
    YOUTUBE_INTERVIEW(
        label = "🎬 YouTube访谈精读",
        prefix = "请用 youtube-interview-processor 技能处理这个视频链接： ",
        submitWithEnter = true,
    ),
    PASTE_ONLY(
        label = "📋 仅粘贴不回车",
        prefix = "",
        submitWithEnter = false,
    ),
}

object AttacheShareManager {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _pendingShare = MutableStateFlow<AttacheSharePayload?>(null)
    val pendingShare: StateFlow<AttacheSharePayload?> = _pendingShare.asStateFlow()

    fun extractSharePayload(context: Context?, intent: Intent?): AttacheSharePayload? {
        if (intent == null || intent.action != Intent.ACTION_SEND) return null
        val extraCharSeq = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim() }.getOrNull().orEmpty()
        val extraStr = runCatching { intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() }.getOrNull().orEmpty()
        val extraText = extraCharSeq.ifBlank { extraStr }
        val dataStr = intent.dataString?.trim().orEmpty()
        val clipText = runCatching {
            val clip = intent.clipData
            if (clip != null && clip.itemCount > 0) {
                (0 until clip.itemCount).mapNotNull { idx ->
                    val item = clip.getItemAt(idx) ?: return@mapNotNull null
                    val rawText = item.text?.toString()?.trim()
                    val itemUri = item.uri
                    val coerced = if (rawText.isNullOrBlank() && context != null && itemUri == null) {
                        runCatching { item.coerceToText(context)?.toString()?.trim() }.getOrNull()
                    } else {
                        rawText
                    }
                    val itemUriStr = itemUri?.toString()?.trim()
                    when {
                        !coerced.isNullOrBlank() && !itemUriStr.isNullOrBlank() && !coerced.contains(itemUriStr) && itemUriStr.startsWith("http") ->
                            "$coerced $itemUriStr"
                        !coerced.isNullOrBlank() -> coerced
                        !itemUriStr.isNullOrBlank() && itemUriStr.startsWith("http") -> itemUriStr
                        else -> null
                    }
                }.joinToString(" ")
            } else {
                ""
            }
        }.getOrDefault("")

        val pieces = linkedSetOf<String>()
        if (extraText.isNotBlank()) pieces.add(extraText)
        if (clipText.isNotBlank() && pieces.none { it.contains(clipText) }) pieces.add(clipText)
        if (dataStr.startsWith("http") && pieces.none { it.contains(dataStr) }) pieces.add(dataStr)
        val sharedText = pieces.joinToString(" ").trim()
        val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
        val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (sharedText.isBlank() && sharedSubject.isBlank() && streamUri == null) {
            return null
        }

        val shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID).orEmpty()
        val shortcutWindowId = shortcutId
            .takeIf { it.startsWith(TmuxControl.SHORTCUT_ID_PREFIX) }
            ?.removePrefix(TmuxControl.SHORTCUT_ID_PREFIX)
            ?.takeIf { it.isNotBlank() }
        val directWindowId = intent.getStringExtra(TmuxControl.EXTRA_SHARE_WINDOW_ID)
            ?.takeIf { it.isNotBlank() }
            ?: shortcutWindowId
        val componentClass = intent.component?.className.orEmpty()
        val aliasSlot = when {
            componentClass.endsWith("ShareWindow1Alias") -> 1
            componentClass.endsWith("ShareWindow2Alias") -> 2
            componentClass.endsWith("ShareWindow3Alias") -> 3
            componentClass.endsWith("ShareWindow4Alias") -> 4
            else -> null
        }
        val aliasNameHint = when (aliasSlot) {
            1 -> "视频下载"
            2 -> "X推文分析"
            3 -> "YouTube访谈"
            4 -> "选题灵感库"
            else -> null
        }
        val aliasDefaultPreset = when (aliasSlot) {
            1 -> ShareActionPreset.DOWNLOAD_VIDEO
            2 -> ShareActionPreset.DEEP_SUMMARY
            3 -> ShareActionPreset.YOUTUBE_INTERVIEW
            else -> ShareActionPreset.DIRECT
        }
        val directSessionName = intent.getStringExtra(TmuxControl.EXTRA_SHARE_SESSION_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: if (aliasSlot != null) "Share" else null

        // Clear action so activity recreation does not re-trigger the consumed share intent
        intent.action = null
        return AttacheSharePayload(
            sharedText = sharedText,
            sharedSubject = sharedSubject,
            streamUri = streamUri,
            mimeType = intent.type.orEmpty(),
            targetWindowId = directWindowId,
            targetSessionName = directSessionName,
            targetWindowPosition = aliasSlot,
            targetWindowNameHint = aliasNameHint,
            defaultPreset = aliasDefaultPreset,
        )
    }

    fun consumeShareIntent(context: Context?, intent: Intent?): Boolean {
        val payload = extractSharePayload(context, intent) ?: return false
        _pendingShare.value = payload
        return true
    }

    fun consumeSharePayload(payload: AttacheSharePayload) {
        _pendingShare.value = payload
    }

    fun clearPendingShare() {
        _pendingShare.value = null
    }

    fun resolveTargetSessionAndWindow(
        payload: AttacheSharePayload,
        sessions: List<TmuxSession>,
        currentSessionId: String?,
    ): Pair<TmuxSession, TmuxWindow>? {
        if (sessions.isEmpty()) return null
        val sessionByTargetWindow = payload.targetWindowId?.let { winId ->
            sessions.firstOrNull { s -> s.windows.any { it.id == winId } }
        }
        val sessionByTargetName = payload.targetSessionName?.let { sName ->
            sessions.firstOrNull { it.name.equals(sName, ignoreCase = true) }
        }
        val shareSession = sessions.firstOrNull { it.name.equals("Share", ignoreCase = true) }
        val attachedSession = sessions.firstOrNull { it.id == currentSessionId }
        val chosenSession = sessionByTargetWindow
            ?: sessionByTargetName
            ?: shareSession
            ?: attachedSession
            ?: sessions.first()

        val targetWinInChosen = payload.targetWindowId?.let { winId ->
            chosenSession.windows.firstOrNull { it.id == winId }
        } ?: payload.targetWindowNameHint?.let { hint ->
            chosenSession.windows.firstOrNull { it.name.contains(hint, ignoreCase = true) }
        } ?: payload.targetWindowPosition?.let { pos ->
            chosenSession.windows.firstOrNull { it.index == pos }
                ?: chosenSession.windows.getOrNull(pos - 1)
        }
        val defaultWin = targetWinInChosen
            ?: if (chosenSession.name.equals("Share", ignoreCase = true)) {
                chosenSession.windows.firstOrNull()
            } else {
                chosenSession.windows.firstOrNull { it.active } ?: chosenSession.windows.firstOrNull()
            }
        return defaultWin?.let { chosenSession to it }
    }

    fun inferPresetForWindow(payload: AttacheSharePayload, window: TmuxWindow): ShareActionPreset {
        if (payload.defaultPreset != ShareActionPreset.DIRECT) return payload.defaultPreset
        val name = window.name.lowercase()
        return when {
            name.contains("视频") || name.contains("下载") || name.contains("yt-dlp") -> ShareActionPreset.DOWNLOAD_VIDEO
            name.contains("推文") || name.contains("分析") || name.contains("总结") -> ShareActionPreset.DEEP_SUMMARY
            name.contains("访谈") || name.contains("youtube") || name.contains("精读") -> ShareActionPreset.YOUTUBE_INTERVIEW
            else -> ShareActionPreset.DIRECT
        }
    }

    fun buildSingleLinePrompt(
        preset: ShareActionPreset,
        uploadedRemotePath: String?,
        bodyText: String,
    ): String {
        val raw = buildString {
            append(preset.prefix)
            uploadedRemotePath?.let { path ->
                append(path)
                if (bodyText.isNotBlank()) append(" ")
            }
            append(bodyText.trim())
        }
        return raw.replace(Regex("[\\r\\n]+"), " ").trim()
    }

    /**
     * Dispatches [payload] to the resolved tmux window completely in the background
     * without launching or showing `MainActivity`.
     */
    fun dispatchQuickShareInBackground(
        appContext: Context,
        payload: AttacheSharePayload,
        preOpenedStream: java.io.InputStream? = null,
    ) {
        backgroundScope.launch {
            var connection: ServiceConnection? = null
            try {
                val preReadStreamBytes = preOpenedStream?.use { stream ->
                    runCatching { stream.readBytes() }.getOrNull()
                }
                val managerDeferred = CompletableDeferred<TerminalManager?>()
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        val binder = service as? TerminalManager.TerminalBinder
                        managerDeferred.complete(binder?.getService())
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        if (!managerDeferred.isCompleted) managerDeferred.complete(null)
                    }
                }
                connection = conn
                withContext(Dispatchers.Main) {
                    val serviceIntent = Intent(appContext, TerminalManager::class.java)
                    runCatching { appContext.startService(serviceIntent) }
                    appContext.bindService(serviceIntent, conn, Context.BIND_AUTO_CREATE)
                }

                val manager = withTimeoutOrNull(5000L) { managerDeferred.await() }
                if (manager == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "⚠️ 后台连接 Attaché 服务超时", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Ensure a tmux bridge is open (auto-connect on Dispatchers.IO if cold-started)
                var bridge = manager.bridgesFlow.value.firstOrNull {
                    it.tmuxControl.value != null || TmuxTrial.targetFor(it.host.nickname) != null
                } ?: manager.bridgesFlow.value.firstOrNull()

                if (bridge == null) {
                    val targetLabel = payload.targetWindowNameHint ?: payload.targetWindowId ?: "目标窗口"
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "⏳ 正在连接 tmux 并投递给 [$targetLabel]…", Toast.LENGTH_SHORT).show()
                    }
                    val hosts = manager.hostRepository.getHosts()
                    val targetHost = hosts.firstOrNull {
                        TmuxTrial.targetFor(it.nickname) != null
                    } ?: hosts.firstOrNull()
                    if (targetHost != null) {
                        bridge = runCatching { manager.openConnection(targetHost.getUri()) }.getOrNull()
                    }
                }

                // Wait up to 10s for TmuxControl to connect and populate sessions
                var activeControl: TmuxControl? = null
                var sessions: List<TmuxSession> = emptyList()
                val deadline = SystemClock.uptimeMillis() + 10_000L
                while (SystemClock.uptimeMillis() < deadline) {
                    val candidateBridge = manager.bridgesFlow.value.firstOrNull {
                        it.tmuxControl.value?.state?.value?.sessions?.isNotEmpty() == true
                    } ?: bridge ?: manager.bridgesFlow.value.firstOrNull()
                    val ctrl = candidateBridge?.tmuxControl?.value
                    val sList = ctrl?.state?.value?.sessions.orEmpty()
                    if (ctrl != null && sList.isNotEmpty()) {
                        activeControl = ctrl
                        sessions = sList
                        break
                    }
                    delay(150L)
                }

                val control = activeControl
                if (control == null || sessions.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "⚠️ 未检测到已连接的 tmux 会话", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val resolved = resolveTargetSessionAndWindow(
                    payload = payload,
                    sessions = sessions,
                    currentSessionId = control.state.value.sessionId,
                )
                if (resolved == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "⚠️ 未找到目标 tmux 窗口", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val (chosenSession, targetWin) = resolved
                val preset = inferPresetForWindow(payload, targetWin)

                var uploadedRemotePath: String? = null
                if (payload.streamUri != null) {
                    val (_, ext) = resolveUriDisplayNameAndExt(appContext, payload.streamUri, payload.mimeType)
                    val bytes = preReadStreamBytes ?: runCatching {
                        appContext.contentResolver.openInputStream(payload.streamUri)?.use { it.readBytes() }
                    }.getOrNull()
                    if (bytes != null && bytes.isNotEmpty()) {
                        uploadedRemotePath = runCatching { control.uploadImage(bytes, ext) }.getOrNull()
                    }
                }

                val prompt = buildSingleLinePrompt(
                    preset = preset,
                    uploadedRemotePath = uploadedRemotePath,
                    bodyText = payload.initialEditableText,
                )
                if (prompt.isBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "⚠️ 分享内容为空", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                control.sendPromptToWindow(
                    windowId = targetWin.id,
                    text = prompt,
                    switchToTarget = false,
                    submitWithEnter = preset.submitWithEnter,
                )

                val urlMatch = Regex("https?://\\S+").find(payload.initialEditableText)?.value
                val previewSnippet = (urlMatch ?: payload.initialEditableText.ifBlank { uploadedRemotePath.orEmpty() }).take(48)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        appContext,
                        "🚀 已丢给 [${targetWin.name}]: $previewSnippet",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to dispatch quick share in background")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        appContext,
                        "分享发送失败: ${e.message ?: "请检查连接"}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } finally {
                connection?.let { conn ->
                    withContext(Dispatchers.Main) {
                        runCatching { appContext.unbindService(conn) }
                    }
                }
            }
        }
    }
}

private fun resolveUriDisplayNameAndExt(context: Context, uri: Uri, mimeType: String): Pair<String, String> {
    var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "shared-file"
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) displayName = name
                }
            }
        }
    }
    val extFromName = displayName.substringAfterLast('.', "").lowercase()
        .takeIf { it.matches(Regex("^[a-z0-9]{1,8}$")) }
    val resolvedMime = context.contentResolver.getType(uri) ?: mimeType
    val extFromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime)?.lowercase()
        ?.takeIf { it.matches(Regex("^[a-z0-9]{1,8}$")) }
    val ext = extFromName ?: extFromMime ?: when {
        resolvedMime.startsWith("image/") -> "png"
        resolvedMime.startsWith("video/") -> "mp4"
        resolvedMime.startsWith("text/") -> "txt"
        else -> "bin"
    }
    return displayName to ext
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AttacheShareBottomSheet(
    payload: AttacheSharePayload,
    control: TmuxControl?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tmuxState = control?.state?.collectAsState()?.value

    var selectedSessionId by remember(payload.id) { mutableStateOf<String?>(null) }
    var selectedWindowId by remember(payload.id) { mutableStateOf<String?>(payload.targetWindowId) }
    var selectedPreset by remember(payload.id) { mutableStateOf(payload.defaultPreset) }
    var editedText by remember(payload.id) { mutableStateOf(payload.initialEditableText) }
    var sending by remember(payload.id) { mutableStateOf(false) }

    // Optional stream attachment upload state
    var attachmentName by remember(payload.id) { mutableStateOf<String?>(null) }
    var uploadedRemotePath by remember(payload.id) { mutableStateOf<String?>(null) }
    var uploadProgress by remember(payload.id) { mutableFloatStateOf(0f) }
    var uploadingStream by remember(payload.id) { mutableStateOf(false) }
    var uploadFailed by remember(payload.id) { mutableStateOf(false) }

    // Automatically resolve default Session & Window when tmux sessions arrive
    val sessions = tmuxState?.sessions.orEmpty()
    LaunchedEffect(payload.id, sessions, tmuxState?.sessionId) {
        if (sessions.isEmpty()) return@LaunchedEffect
        val currentSelectedSession = sessions.firstOrNull { it.id == selectedSessionId }
        if (currentSelectedSession == null) {
            val resolved = AttacheShareManager.resolveTargetSessionAndWindow(
                payload = payload,
                sessions = sessions,
                currentSessionId = tmuxState?.sessionId,
            )
            if (resolved != null) {
                selectedSessionId = resolved.first.id
                selectedWindowId = resolved.second.id
                if (selectedPreset == ShareActionPreset.DIRECT) {
                    selectedPreset = AttacheShareManager.inferPresetForWindow(payload, resolved.second)
                }
            }
        } else if (selectedWindowId == null || currentSelectedSession.windows.none { it.id == selectedWindowId }) {
            val defaultWin = if (currentSelectedSession.name.equals("Share", ignoreCase = true)) {
                currentSelectedSession.windows.firstOrNull()
            } else {
                currentSelectedSession.windows.firstOrNull { it.active } ?: currentSelectedSession.windows.firstOrNull()
            }
            selectedWindowId = defaultWin?.id
        }
    }

    // Upload shared stream URI (image/video/file) once TmuxControl is ready
    LaunchedEffect(payload.id, payload.streamUri, control) {
        val uri = payload.streamUri ?: return@LaunchedEffect
        val activeControl = control ?: return@LaunchedEffect
        if (uploadedRemotePath != null || uploadingStream) return@LaunchedEffect
        uploadingStream = true
        uploadFailed = false
        withContext(Dispatchers.IO) {
            try {
                val (name, ext) = resolveUriDisplayNameAndExt(context, uri, payload.mimeType)
                withContext(Dispatchers.Main) { attachmentName = name }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        uploadingStream = false
                        uploadFailed = true
                    }
                    return@withContext
                }
                val remote = activeControl.uploadImage(bytes, ext) { uploaded, total ->
                    if (total > 0L) {
                        uploadProgress = (uploaded.toFloat() / total.toFloat()).coerceIn(0.01f, 0.99f)
                    }
                }
                withContext(Dispatchers.Main) {
                    uploadingStream = false
                    if (remote != null) {
                        uploadedRemotePath = remote
                        uploadProgress = 1f
                    } else {
                        uploadFailed = true
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to upload shared stream URI")
                withContext(Dispatchers.Main) {
                    uploadingStream = false
                    uploadFailed = true
                }
            }
        }
    }

    val selectedSession = sessions.firstOrNull { it.id == selectedSessionId } ?: sessions.firstOrNull()
    val windows = selectedSession?.windows.orEmpty()
    val selectedWindow = windows.firstOrNull { it.id == selectedWindowId } ?: windows.firstOrNull()

    fun buildFinalPrompt(): String = AttacheShareManager.buildSingleLinePrompt(
        preset = selectedPreset,
        uploadedRemotePath = uploadedRemotePath,
        bodyText = editedText,
    )

    fun dispatchShare(switchToTarget: Boolean) {
        val activeControl = control ?: return
        val targetWin = selectedWindow ?: return
        val prompt = buildFinalPrompt()
        if (prompt.isBlank()) {
            Toast.makeText(context, "分享内容为空", Toast.LENGTH_SHORT).show()
            return
        }
        sending = true
        scope.launch {
            try {
                activeControl.sendPromptToWindow(
                    windowId = targetWin.id,
                    text = prompt,
                    switchToTarget = switchToTarget,
                    submitWithEnter = selectedPreset.submitWithEnter,
                )
                Toast.makeText(
                    context,
                    "🚀 已发送到 [${targetWin.name}]",
                    Toast.LENGTH_SHORT,
                ).show()
                onDismiss()
                if (!switchToTarget) {
                    (context as? Activity)?.moveTaskToBack(true)
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to send shared prompt to window %s", targetWin.id)
                sending = false
                Toast.makeText(
                    context,
                    "发送失败: ${e.message ?: "请检查连接"}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 18.dp)
                .verticalScroll(rememberScrollState())
                .testTag("attacheShareSheet"),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "分享到 Attaché Window",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (selectedSession != null && selectedWindow != null) {
                        Text(
                            text = "目标：${selectedSession.name} · ${selectedWindow.index}: ${selectedWindow.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            HorizontalDivider()

            if (control == null || sessions.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Text(
                        text = "正在连接 tmux 并加载 Session / Window 列表…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // 1. Session Selector
                Text(
                    text = "选择 Session",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    sessions.forEach { session ->
                        val isSelected = session.id == selectedSession?.id
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedSessionId = session.id
                                val nextWin = if (session.name.equals("Share", ignoreCase = true)) {
                                    session.windows.firstOrNull()
                                } else {
                                    session.windows.firstOrNull { it.active } ?: session.windows.firstOrNull()
                                }
                                selectedWindowId = nextWin?.id
                            },
                            label = {
                                Text(
                                    text = "${session.name} (${session.windows.size})",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                )
                            },
                            modifier = Modifier.height(34.dp).testTag("shareSession-${session.name}"),
                        )
                    }
                }

                // 2. Window Selector (ALL windows displayed via FlowRow)
                Text(
                    text = "选择目标 Window（共 ${windows.size} 个）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    windows.forEach { window ->
                        val isSelected = window.id == selectedWindow?.id
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedWindowId = window.id
                                selectedPreset = AttacheShareManager.inferPresetForWindow(payload, window)
                            },
                            label = {
                                Text(
                                    text = "${window.index}: ${window.name}",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                )
                            },
                            modifier = Modifier.height(36.dp).testTag("shareWindow-${window.id}"),
                        )
                    }
                }
            }

            // 3. Action Presets
            Text(
                text = "动作胶囊",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ShareActionPreset.entries.forEach { preset ->
                    val isSelected = preset == selectedPreset
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedPreset = preset },
                        label = {
                            Text(
                                text = preset.label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                        modifier = Modifier.height(34.dp).testTag("sharePreset-${preset.name}"),
                    )
                }
            }

            // Attachment badge if streamUri was provided
            if (payload.streamUri != null) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        val statusText = when {
                            uploadingStream -> "📎 正在上传附件 ${attachmentName.orEmpty()} (${(uploadProgress * 100).toInt()}%)…"
                            uploadedRemotePath != null -> "📎 已上传附件: $uploadedRemotePath"
                            uploadFailed -> "⚠️ 附件上传失败，将仅发送文本内容"
                            else -> "📎 待上传附件: ${attachmentName ?: payload.streamUri.lastPathSegment.orEmpty()}"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (uploadingStream) {
                            LinearProgressIndicator(
                                progress = { uploadProgress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // 4. Editable Content / Note Preview
            OutlinedTextField(
                value = editedText,
                onValueChange = { editedText = it },
                label = {
                    val labelPrefix = if (selectedPreset.prefix.isNotEmpty()) {
                        "分享内容（已附加「${selectedPreset.label}」指令前缀）"
                    } else {
                        "分享内容 / 补充说明"
                    }
                    Text(labelPrefix)
                },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("shareContentField"),
            )

            // 5. Action Buttons
            val canSend = control != null &&
                selectedWindow != null &&
                !sending &&
                !uploadingStream &&
                (editedText.isNotBlank() || uploadedRemotePath != null)

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                FilledTonalButton(
                    onClick = { dispatchShare(switchToTarget = false) },
                    enabled = canSend,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("shareSendBackground"),
                ) {
                    Text(
                        text = "🚀 后台发送",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }

                Button(
                    onClick = { dispatchShare(switchToTarget = true) },
                    enabled = canSend,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("shareSendAndSwitch"),
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = "👁️ 发送并前往窗口",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
