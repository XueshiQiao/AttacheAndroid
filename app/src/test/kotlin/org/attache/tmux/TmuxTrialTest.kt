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

package org.attache.tmux

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The control channel's attach command, run through a real `/bin/sh` against
 * a real tmux on an isolated server (`-L`), to see that `no-output` is set by
 * the attach itself. Set later with `refresh-client -f`, it can stall the
 * channel for good (see `TmuxTrial.controlCommand`), so this is the property
 * that matters, and it depends on shell quoting no reading of the Kotlin
 * string can vouch for.
 *
 * Only the part from the version check on is run: the orphan reap before it
 * kills `tmux -C` processes, and this test must not touch any on this machine.
 */
class TmuxTrialTest {
    private val socket = "attache-mobile-test-flags-${ProcessHandle.current().pid()}"
    private val tmux = listOf("/opt/homebrew/bin/tmux", "/usr/local/bin/tmux", "/usr/bin/tmux").firstOrNull { File(it).canExecute() }

    @After
    fun stop() {
        if (tmux != null) ProcessBuilder(tmux, "-L", socket, "kill-server").redirectErrorStream(true).start().waitFor()
    }

    @Test
    fun theAttachItselfTurnsOffPaneOutput() {
        assumeTrue("tmux is not installed", tmux != null)
        val full = TmuxTrial.controlCommand(TmuxTrial.Target(socket, "probe"))
        val from = full.indexOf("case ")
        assertTrue("the command checks the tmux version", from > 0)
        val script = "export PATH=${File(tmux!!).parent}:/usr/bin:/bin; " + full.substring(from)
        assertTrue("and still reaches tmux by -L only", script.contains("tmux -u -L $socket -C new-session -A \$F -s 'probe'"))

        val shell = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        shell.outputStream.write("display-message -p '#{client_flags}'\n".toByteArray())
        shell.outputStream.flush()
        Thread.sleep(1_500)
        shell.outputStream.close()
        val out = shell.inputStream.bufferedReader().readText()
        shell.waitFor()
        val flags = out.lines().firstOrNull { it.contains("control-mode") }.orEmpty().split(',')
        assertTrue("no-output is on from the attach: $out", "no-output" in flags)
        assertTrue("and ignore-size: $out", "ignore-size" in flags)
    }
}
