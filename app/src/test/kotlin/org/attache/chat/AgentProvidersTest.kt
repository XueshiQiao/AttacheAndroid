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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Shapes from this machine, 2026-11-05: Claude Code 2.1.311, agy 1.4.12. */
@RunWith(AndroidJUnit4::class)
class AgentProvidersTest {
    private val claudeLine =
        """{"pid":71258,"home":"/Users/joey","sessionId":"d1b426c9-a057-4445-8a5a-249112240c54","cwd":"/Users/joey/Code/Attache","tmux":"Dev:@4.%145","status":"waiting","statusUpdatedAt":1793887810768}"""

    @Test
    fun claudeSessionNamesItsPaneStateAndTranscript() {
        val s = HostScripts.parse(listOf("@@attache-agent claude", claudeLine)).single()
        assertEquals("claude", s.kind)
        assertEquals("%145", s.paneId)
        assertEquals("Dev", s.sessionName)
        assertEquals("@4", s.windowId)
        assertEquals(AgentPhase.NEEDS_YOU, s.phase)
        assertEquals(1793887810L, s.sinceEpochSec)
        assertEquals("/Users/joey/.claude/projects/-Users-joey-Code-Attache/d1b426c9-a057-4445-8a5a-249112240c54.jsonl", s.transcriptPath)
    }

    @Test
    fun theProjectFolderReplacesEveryCharacterThatIsNotALetterOrDigit() {
        // As the folder is named on this machine.
        assertEquals(
            "/h/.claude/projects/-private-tmp-claude-501--Users-joey-23687748-8af9-scratchpad/s.jsonl",
            ClaudeProvider.transcriptPath("/h", "/private/tmp/claude-501/-Users-joey-23687748-8af9/scratchpad", "s"),
        )
        assertEquals("/h/.claude/projects/-Users-joey-Code-Attach-/s.jsonl", ClaudeProvider.transcriptPath("/h", "/Users/joey/Code/Attaché", "s"))
    }

    @Test
    fun claudeStates() {
        fun phase(status: String) = HostScripts.parse(listOf("@@attache-agent claude", claudeLine.replace("\"waiting\"", "\"$status\""))).single().phase
        assertEquals(AgentPhase.WORKING, phase("busy"))
        assertEquals(AgentPhase.IDLE, phase("idle"))
        assertNull(phase("something-new"))
    }

    @Test
    fun agyReportsItsVerdictAndModelPerPane() {
        val lines = listOf(
            "@@attache-agent claude",
            claudeLine,
            "@@attache-agent agy",
            """{"pane":"%110","where":"Dev:@71","status":"waiting","model": "Gemini 4 Argon (Medium)"}""",
            """{"pane":"%73","where":"Dev:@55","status":"busy","model":null}""",
            "garbage",
        )
        val sessions = HostScripts.parse(lines)
        assertEquals(listOf("claude", "agy", "agy"), sessions.map { it.kind })
        val agy = sessions[1]
        assertEquals("%110", agy.paneId)
        assertEquals("Dev", agy.sessionName)
        assertEquals("@71", agy.windowId)
        assertEquals(AgentPhase.NEEDS_YOU, agy.phase)
        assertEquals("Gemini 4 Argon (Medium)", agy.model)
        assertNull(sessions[2].model)
        assertEquals(AgentPhase.WORKING, sessions[2].phase)
    }

    @Test
    fun runShellDoublesEveryHashSoTmuxLeavesItAlone() {
        val command = HostScripts.sessionsCommand("attache-agents-1")!!
        assertTrue(command.startsWith("run-shell '"))
        // agy's script asks tmux for `#{pane_id}`; run-shell would expand a
        // single `#` itself, in the wrong pane.
        assertTrue(command.contains("##{pane_id}"))
        assertFalse(command.contains(Regex("(?<!#)#\\{")))
    }

    @Test
    fun anAgentsOwnWordWinsAndTheOptionsOnlyRefineIt() {
        val pane = AgentPane("%145", "Dev", "@4", "Attaché", "/", "claude", AgentPhase.DONE, 100L, "t")
        val idle = AgentSession("claude", "%145", "Dev", "@4", AgentPhase.IDLE, 200L)
        val waiting = idle.copy(phase = AgentPhase.NEEDS_YOU)
        // Idle in Claude's file, done by the hook: a finished turn.
        assertEquals(AgentPhase.DONE, AgentPanes.merge(listOf(pane), listOf(idle)).single().phase)
        assertEquals(AgentPhase.NEEDS_YOU, AgentPanes.merge(listOf(pane), listOf(waiting)).single().phase)
        assertEquals(200L, AgentPanes.merge(listOf(pane), listOf(waiting)).single().sinceEpochSec)
        // No hooks at all: the pane says IDLE, the file says waiting.
        assertEquals(AgentPhase.NEEDS_YOU, AgentPanes.merge(listOf(pane.copy(phase = AgentPhase.IDLE)), listOf(waiting)).single().phase)
    }

    @Test
    fun aSessionFromAnotherTmuxServerIsNotThisPanes() {
        // Claude in an isolated `-L` server records `probe:@0.%0`; this
        // server's %0 is somewhere else.
        val pane = AgentPane("%0", "Dev", "@1", "w", "/", "claude", AgentPhase.IDLE, null, "t")
        val other = AgentSession("claude", "%0", "probe", "@0", AgentPhase.NEEDS_YOU)
        assertEquals(AgentPhase.IDLE, AgentPanes.merge(listOf(pane), listOf(other)).single().phase)
    }

    @Test
    fun theTranscriptSuppliesTheModelAndThePayloadTheWindow() {
        val files = AgentStatus(source = "claude", model = "claude-opus-5-5", modelId = "claude-opus-5-5", effort = "high", contextTokens = 250_000)
        val payload = AgentStatus(source = "claude", model = "Opus 5.5", modelId = "claude-opus-5-5", contextWindow = 1_000_000, contextPercent = 20, cacheWarm = true)
        val merged = AgentStatusMerge.merge(payload, files)!!
        assertEquals("Opus 5.5", merged.model)
        assertEquals(25, merged.contextPercent)
        assertEquals(true, merged.cacheWarm)
        // Later, with no payload, the window learned above still gives a percent.
        val alone = AgentStatusMerge.merge(null, files.copy(contextTokens = 500_000))!!
        assertEquals("Opus 5.5", alone.model)
        assertEquals(50, alone.contextPercent)
    }

    /** The real script, through /bin/sh, against a made-up home folder. */
    @Test
    fun theClaudeScriptReadsLiveSessionsAndSkipsFinishedOnes() {
        val home = kotlin.io.path.createTempDirectory("attache-home").toFile()
        try {
            val sessions = File(home, ".claude/sessions").apply { mkdirs() }
            val alive = ProcessHandle.current().pid()
            File(sessions, "$alive.json").writeText(
                """{"pid":$alive,"sessionId":"s1","cwd":"/w/it's here","startedAt":1,"tmux":"Dev:@4.%145","status":"busy","statusUpdatedAt":1793887810768,"peerFeatures":["a"]}""",
            )
            File(sessions, "999999.json").writeText("""{"pid":999999,"sessionId":"gone","tmux":"Dev:@1.%1","status":"idle"}""")
            File(sessions, "123.abc.key").writeText("not a session")
            val process = ProcessBuilder("/bin/sh", "-c", ClaudeProvider.sessionsScript()).apply {
                environment()["HOME"] = home.absolutePath
            }.start()
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
            process.waitFor()
            assertEquals(1, out.size)
            val s = ClaudeProvider.parseSession(org.json.JSONObject(out.single()))!!
            assertEquals("%145", s.paneId)
            assertEquals(AgentPhase.WORKING, s.phase)
            assertEquals("${home.absolutePath}/.claude/projects/-w-it-s-here/s1.jsonl", s.transcriptPath)
        } finally {
            home.deleteRecursively()
        }
    }

    /** As 2.1.311 left them, 2026-11-06, after a session moved to a
     * background process: the pane's file frozen at `busy`, the running
     * session in a file with no pane. */
    @Test
    fun aSessionHandedToABackgroundProcessIsReadFromThatProcess() {
        val home = kotlin.io.path.createTempDirectory("attache-home").toFile()
        try {
            val sessions = File(home, ".claude/sessions").apply { mkdirs() }
            val viewer = ProcessHandle.current().pid()
            val runner = ProcessHandle.current().parent().get().pid()
            File(sessions, "$viewer.json").writeText(
                """{"pid":$viewer,"sessionId":"old","cwd":"/w","tmux":"Dev:@4.%145","status":"busy","statusUpdatedAt":1,"parkedJobId":"c94cb7d8"}""",
            )
            File(sessions, "$runner.json").writeText(
                """{"pid":$runner,"sessionId":"new","cwd":"/w","kind":"bg","jobId":"c94cb7d8","status":"idle","statusUpdatedAt":2}""",
            )
            val process = ProcessBuilder("/bin/sh", "-c", ClaudeProvider.sessionsScript()).apply {
                environment()["HOME"] = home.absolutePath
            }.start()
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
            process.waitFor()
            val s = out.mapNotNull { ClaudeProvider.parseSession(org.json.JSONObject(it)) }.single()
            assertEquals("%145", s.paneId)
            assertEquals(AgentPhase.IDLE, s.phase)
            assertEquals("${home.absolutePath}/.claude/projects/-w/new.jsonl", s.transcriptPath)
        } finally {
            home.deleteRecursively()
        }
    }
}
