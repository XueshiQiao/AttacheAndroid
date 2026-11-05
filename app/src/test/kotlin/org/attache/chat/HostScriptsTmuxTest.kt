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

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * [HostScripts] against a real tmux, through a control client that takes a
 * command's reply the way the phone's `TmuxControl` does: only the lines
 * inside the `%begin`/`%end` block flagged as answering it. Reading the
 * scripts' output straight from `run-shell` passed every other test and
 * returned nothing on the phone; this is the test that would have said so.
 *
 * An isolated server (`-L`) on every command, never the owner's.
 */
@RunWith(AndroidJUnit4::class)
class HostScriptsTmuxTest {
    private val socket = "attache-hostscripts-check-${ProcessHandle.current().pid()}"
    private val tmux = listOf("/opt/homebrew/bin/tmux", "/usr/local/bin/tmux", "/usr/bin/tmux").firstOrNull { File(it).canExecute() }
    private var client: Process? = null
    private var socketPath: String? = null

    /** A provider that reports two sessions, one of them naming its pane
     * through tmux itself, so the `#` doubling is exercised for real. */
    private object Probe : AgentProvider {
        override val kind = "probe"

        override fun recognizes(command: String) = false

        override fun sessionsScript() = "printf '{\"pane\":\"%%1\",\"note\":\"it'\\''s # here\"}\\n'; " +
            "printf '{\"pane\":\"%s\"}\\n' \"\$(tmux display-message -p -t probe '#{pane_id}')\""

        override fun parseSession(line: JSONObject) = AgentSession(kind, line.optString("pane"), sessionName = line.optString("note").ifEmpty { null })
    }

    @Before
    fun start() {
        assumeTrue("tmux is not installed", tmux != null)
        val env = mapOf("PATH" to "${File(tmux!!).parent}:/usr/bin:/bin")
        run(env, tmux, "-L", socket, "-f", "/dev/null", "new-session", "-d", "-s", "probe")
        socketPath = ProcessBuilder(tmux, "-L", socket, "display-message", "-p", "-t", "probe", "#{socket_path}").start()
            .inputStream.bufferedReader().readText().trim()
        client = ProcessBuilder(tmux, "-L", socket, "-u", "-C", "attach", "-f", "no-output", "-t", "probe")
            .apply { environment().putAll(env) }.start()
    }

    @After
    fun stop() {
        client?.destroy()
        if (tmux == null) return
        run(emptyMap(), tmux, "-L", socket, "kill-server")
        // kill-server leaves the socket file behind (tmux 3.6a, macOS).
        socketPath?.takeIf { it.endsWith("/$socket") }?.let { File(it).delete() }
    }

    private fun run(env: Map<String, String>, vararg command: String) {
        ProcessBuilder(*command).apply { environment().putAll(env) }.redirectErrorStream(true).start().waitFor()
    }

    private val reader by lazy { client!!.inputStream.bufferedReader() }

    /** One command, and the lines of the block that answers it. */
    private fun query(command: String): List<String> {
        client!!.outputStream.apply {
            write((command + "\n").toByteArray())
            flush()
        }
        var lines: MutableList<String>? = null
        var ours = false
        while (true) {
            val line = reader.readLine() ?: error("tmux exited")
            val block = lines
            if (block == null) {
                if (line.startsWith("%begin ")) {
                    lines = mutableListOf()
                    ours = line.split(' ').getOrNull(3) == "1"
                }
                continue
            }
            if (line.startsWith("%end ") || line.startsWith("%error ")) {
                lines = null
                if (!ours) continue
                if (line.startsWith("%error ")) throw IllegalStateException(block.joinToString("\n"))
                return block
            }
            block += line
        }
    }

    @Test
    fun theOutputArrivesThroughTheBuffer() = runBlocking {
        val sessions = HostScripts.read(::query, listOf(Probe))
        assertEquals(listOf("%1", "%0"), sessions.map { it.paneId })
        assertEquals("it's # here", sessions[0].sessionName)
        // Nothing left behind.
        assertTrue(query("list-buffers").none { it.startsWith("attache-agents-") })
    }

    @Test
    fun runShellsOwnReplyIsEmptyWhichIsWhyTheBufferIsThere() {
        // What b58 to b60 did: the output follows the reply, outside it.
        val direct = "run-shell 'printf \"%s\\n\" \"@@attache-agent probe\" \"{\\\"pane\\\":\\\"%1\\\"}\"'"
        assertEquals(emptyList<String>(), query(direct))
    }
}
