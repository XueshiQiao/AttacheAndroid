package org.attache.tmux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatTextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.SelectionMode

/**
 * Floating toolbar displayed when terminal text selection is active, allowing
 * one-tap switching between Word / Line / Character / All selection modes,
 * copying the selection, or opening the full pane text copy sheet.
 */
@Composable
fun AttacheSelectionBar(
    selectionController: SelectionController,
    onCopied: (String) -> Unit,
    onOpenCopySheet: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("attacheSelectionBar"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            SelectionActionChip(
                label = "词",
                tag = "selectionModeWord",
                onClick = { selectionController.setSelectionMode(SelectionMode.WORD) },
            )
            SelectionActionChip(
                label = "行",
                tag = "selectionModeLine",
                onClick = { selectionController.setSelectionMode(SelectionMode.LINE) },
            )
            SelectionActionChip(
                label = "字",
                tag = "selectionModeChar",
                onClick = { selectionController.setSelectionMode(SelectionMode.CHARACTER) },
            )
            SelectionActionChip(
                label = "全屏",
                tag = "selectionModeAll",
                onClick = { selectionController.selectAllVisible() },
            )
            if (onOpenCopySheet != null) {
                SelectionActionChip(
                    label = "自由复制",
                    tag = "selectionOpenCopySheet",
                    onClick = {
                        selectionController.clearSelection()
                        onOpenCopySheet()
                    },
                )
            }
            Button(
                onClick = {
                    val copied = selectionController.copySelection()
                    onCopied(copied)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(30.dp)
                    .padding(start = 4.dp)
                    .testTag("selectionCopyButton"),
            ) {
                Text("复制", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(
                onClick = { selectionController.clearSelection() },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(30.dp)
                    .testTag("selectionCancelButton"),
            ) {
                Text("✕", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SelectionActionChip(
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier
            .height(30.dp)
            .testTag(tag),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Extracts high-value snippets (file paths, URLs, commands) from pane text so
 * the user can copy them with a single tap without even dragging handles.
 */
internal fun extractQuickCopySnippets(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val regex = Regex("""(?:https?://[^\s"'<>]+|(?:/[A-Za-z0-9._-]+){2,}|~/[A-Za-z0-9._/-]+)""")
    return regex.findAll(text)
        .map { it.value.trimEnd('.', ',', ';', ')', ']', ':') }
        .filter { it.length >= 5 }
        .distinct()
        .toList()
        .takeLast(12)
        .reversed()
}

/**
 * Full-screen dialog that captures the active tmux pane's scrollback history
 * (`capture-pane -p -J -S -500`) and presents it in a native Android selectable
 * text view with double-tap word selection, drag handles, quick-copy chips for
 * paths/URLs, and one-tap "Copy All".
 */
@Composable
fun TmuxCopyTextDialog(
    control: TmuxControl,
    onDismiss: () -> Unit,
    onCopied: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var paneText by remember { mutableStateOf("") }
    var historyLines by remember { mutableStateOf(500) }
    val snippets = remember(paneText) { extractQuickCopySnippets(paneText) }

    fun copyToClipboard(text: String, label: String = "终端文本") {
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        onCopied(text)
    }

    LaunchedEffect(control, historyLines) {
        loading = true
        paneText = try {
            control.capturePaneText(historyLines)
        } catch (_: Exception) {
            ""
        }
        loading = false
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
                .testTag("tmuxCopyTextDialog"),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "终端文本自由复制",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "支持双击选词、拖动水滴手柄跨屏选择（已自动合并换行）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                historyLines = if (historyLines == 500) 2000 else 500
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("${historyLines}行", fontSize = 12.sp)
                        }
                        Button(
                            onClick = {
                                copyToClipboard(paneText, "全部终端输出")
                                onDismiss()
                            },
                            enabled = paneText.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("copyAllPaneText"),
                        ) {
                            Text("复制全部", fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = onDismiss,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("关闭", fontSize = 12.sp)
                        }
                    }
                }

                // Quick-copy chips for detected paths & URLs
                if (snippets.isNotEmpty()) {
                    HorizontalDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            text = "一键复制识别到的路径 / 链接：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(snippets) { item ->
                                AssistChip(
                                    onClick = {
                                        copyToClipboard(item, "路径或链接")
                                        onDismiss()
                                    },
                                    label = {
                                        Text(
                                            text = item,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier.height(28.dp),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Main selectable text body
                val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
                val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f).toArgb()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else if (paneText.isEmpty()) {
                        Text(
                            text = "当前窗格暂无可复制文本",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("selectablePaneTextView"),
                            factory = { ctx ->
                                val scrollView = ScrollView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    isFillViewport = true
                                }
                                val textView = AppCompatTextView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    )
                                    typeface = Typeface.MONOSPACE
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                                    setTextColor(textColor)
                                    this.highlightColor = highlightColor
                                    setLineSpacing(0f, 1.18f)
                                    gravity = Gravity.TOP or Gravity.START
                                    setPadding(16, 16, 16, 48)
                                    setTextIsSelectable(true)
                                    isFocusable = true
                                    isFocusableInTouchMode = true
                                }
                                scrollView.addView(textView)
                                scrollView
                            },
                            update = { scrollView ->
                                val textView = scrollView.getChildAt(0) as? AppCompatTextView ?: return@AndroidView
                                textView.setTextColor(textColor)
                                textView.highlightColor = highlightColor
                                if (textView.text?.toString() != paneText) {
                                    textView.text = paneText
                                    scrollView.post {
                                        scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
