package org.attache.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxCopyTextTest {
    @Test
    fun extractQuickCopySnippets_findsPathsAndUrls() {
        val sample = """
            Build output saved to /Users/joey/Code/AttacheAndroid/app/build/outputs/apk/oss/debug/app-oss-debug.apk.
            See https://tailscale.com/kb/1257/connection-types for relay info;
            Config in ~/.config/tmux/tmux.conf:
        """.trimIndent()

        val snippets = extractQuickCopySnippets(sample)
        assertTrue(snippets.contains("/Users/joey/Code/AttacheAndroid/app/build/outputs/apk/oss/debug/app-oss-debug.apk"))
        assertTrue(snippets.contains("https://tailscale.com/kb/1257/connection-types"))
        assertTrue(snippets.contains("~/.config/tmux/tmux.conf"))
        assertEquals("~/.config/tmux/tmux.conf", snippets.first())
    }
}
