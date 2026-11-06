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
 * The real attache-cli this app carries, reached the way the chat mode
 * reaches it: `run-shell` on a tmux control client, the output handed back
 * through a buffer. Reading `run-shell`'s own reply passed every other test
 * and returned nothing on the phone from b58 to b60; this is the test that
 * would have said so.
 *
 * An isolated server (`-L`) on every command, started with a home folder of
 * the test's own, so what attache-cli reads and writes stays there.
 */
@RunWith(AndroidJUnit4::class)
class AttacheCliTmuxTest {
    private val socket = "attache-cli-phone-check-${ProcessHandle.current().pid()}"
    private val tmux = listOf("/opt/homebrew/bin/tmux", "/usr/local/bin/tmux", "/usr/bin/tmux").firstOrNull { File(it).canExecute() }
    private val cli = File(
        "src/main/assets/attache-cli/" + when (System.getProperty("os.arch")) {
            "aarch64" -> "darwin-arm64"
            else -> "darwin-x86_64"
        } + "/attache-cli",
    ).absoluteFile
    private val home = kotlin.io.path.createTempDirectory("attache-cli-home").toFile()
    private var client: Process? = null
    private var socketPath: String? = null

    @Before
    fun start() {
        assumeTrue("tmux is not installed", tmux != null)
        assumeTrue("a macOS build is checked here", System.getProperty("os.name").startsWith("Mac") && cli.isFile)
        cli.setExecutable(true)
        val env = mapOf("PATH" to "${File(tmux!!).parent}:/usr/bin:/bin", "HOME" to home.absolutePath)
        run(env, tmux, "-L", socket, "-f", "/dev/null", "new-session", "-d", "-s", "probe", "-x", "200", "-y", "50", "sleep 300")
        socketPath = ProcessBuilder(tmux, "-L", socket, "display-message", "-p", "-t", "probe", "#{socket_path}").start()
            .inputStream.bufferedReader().readText().trim()
        client = ProcessBuilder(tmux, "-L", socket, "-u", "-C", "attach", "-f", "no-output", "-t", "probe")
            .apply { environment().putAll(env) }.start()
    }

    @After
    fun stop() {
        client?.destroy()
        if (tmux != null) run(emptyMap(), tmux, "-L", socket, "kill-server")
        // kill-server leaves the socket file behind (tmux 3.6a, macOS).
        socketPath?.takeIf { it.endsWith("/$socket") }?.let { File(it).delete() }
        home.deleteRecursively()
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
                if (line.startsWith("%error ")) throw org.attache.tmux.TmuxCommandError(block)
                return block
            }
            block += line
        }
    }

    private val q: suspend (String) -> List<String> = { query(it) }

    @Test
    fun itsOutputComesBackThroughTheBufferAndTheBufferGoes() = runBlocking {
        val out = AttacheCli.linesVia(q, cli.path, "version")!!
        assertEquals(1, JSONObject(out.single()).getInt("protocol"))
        assertTrue(query("list-buffers").none { it.startsWith("attache-cli-") })
        // Nothing printed is no rows, not a failure.
        assertEquals(emptyList<String>(), AttacheCli.linesVia(q, cli.path, "agents"))
    }

    @Test
    fun aPaneWithAClaudeSessionIsListedAndSeenMakesItIdle() = runBlocking {
        val pid = query("display-message -p -t probe '#{pane_pid}'").single().trim()
        File(home, ".claude/sessions").mkdirs()
        val endedMs = (System.currentTimeMillis() / 1000 - 60) * 1000
        File(home, ".claude/sessions/$pid.json").writeText("""{"sessionId":"s1","cwd":"/w","status":"idle","statusUpdatedAt":$endedMs}""")
        File(home, ".claude/projects/-w").mkdirs()
        File(home, ".claude/projects/-w/s1.jsonl").writeText("{}\n")
        val agents = AttacheCli.parseAgents(AttacheCli.linesVia(q, cli.path, "agents")!!)
        assertEquals(1, agents.size)
        assertEquals("claude", agents[0].kind)
        assertEquals("s1", agents[0].conversation)
        assertEquals(AgentPhase.DONE, agents[0].phase)
        assertTrue(AttacheCli.runVia(q, cli.path, "seen claude s1"))
        assertEquals(AgentPhase.IDLE, AttacheCli.parseAgents(AttacheCli.linesVia(q, cli.path, "agents")!!)[0].phase)
    }

    @Test
    fun aMessageTypedThroughItArrives() = runBlocking {
        query("new-window -t probe cat")
        val pane = query("display-message -p -t probe '#{pane_id}'").single().trim()
        val b64 = java.util.Base64.getEncoder().encodeToString("你好 # world".toByteArray())
        assertTrue(AttacheCli.runVia(q, cli.path, "input $pane --text $b64 --enter"))
        var screen = ""
        repeat(30) {
            screen = query("capture-pane -p -J -t $pane").joinToString("\n")
            if (screen.contains("你好 # world\n你好 # world")) return@runBlocking
            Thread.sleep(50)
        }
        error("not on screen:\n$screen")
    }
}
