package org.connectbot.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import org.attache.tmux.AttacheShareManager

/**
 * 100% transparent, no-UI receiver Activity for the 4 quick-share buttons
 * (`Attaché · 视频下载`, `Attaché · X推文分析`, `Attaché · YouTube访谈`, `Attaché · 选题灵感库`)
 * and Direct Share shortcuts.
 *
 * Finishes immediately in [onCreate] without bringing Attaché to the foreground,
 * while [AttacheShareManager.dispatchQuickShareInBackground] sends the single-line
 * prompt + URL to the target tmux window in the background and shows a Toast with the URL preview.
 */
class AttacheQuickShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        super.onCreate(savedInstanceState)

        val payload = AttacheShareManager.extractSharePayload(this, intent)
        if (payload != null) {
            val preOpenedStream = payload.streamUri?.let { uri ->
                runCatching { contentResolver.openInputStream(uri) }.getOrNull()
            }
            AttacheShareManager.dispatchQuickShareInBackground(
                appContext = applicationContext,
                payload = payload,
                preOpenedStream = preOpenedStream,
            )
        } else {
            Toast.makeText(
                applicationContext,
                "⚠️ 未检测到可分享的链接或内容",
                Toast.LENGTH_SHORT,
            ).show()
        }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
