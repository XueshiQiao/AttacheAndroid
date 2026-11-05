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

import java.io.ByteArrayOutputStream

/**
 * Following an agent's transcript file on the host: the remote command, and
 * turning the bytes it streams back into whole lines.
 */
object TranscriptFollower {
    /** The first line each command prints: the file offset of the first
     * whole line that follows it. */
    const val HEADER = "ATTACHE-OFFSET "

    /** How much is sent when a conversation is opened, and again each time
     * the top is scrolled into view: bytes crossing the link, after [SHRINK].
     * On a relayed 5G link 600 KB is a few seconds. */
    const val SEND_BUDGET = 600_000L

    /** Lines longer than this are not sent as they are; see [SHRINK]. */
    const val LONG_LINE = 262_144

    /**
     * Turns a tool result too long to send into a short one with the same
     * `tool_use_id`, so its step still shows as finished.
     *
     * Nearly all of these are images a tool read, stored inline as base64:
     * measured on a 21.8 MB transcript on this machine, 27 such lines made
     * up 15 MB of it, one of them 773 KB on its own, which used to fill the
     * whole first read and leave a screen with two messages on it. Any other
     * long line is passed through. `fflush` because awk buffers a pipe, and a
     * line held back is a reply that does not appear.
     */
    private const val SHRINK =
        "awk '{ if (length(\$0) > $LONG_LINE && match(\$0, /\"tool_use_id\":\"[^\"]+\"/)) { " +
            "id = substr(\$0, RSTART + 15, RLENGTH - 16); u = \"\"; " +
            "if (match(\$0, /\"uuid\":\"[^\"]+\"/)) u = substr(\$0, RSTART + 8, RLENGTH - 9); " +
            "printf \"{\\\"type\\\":\\\"user\\\",\\\"uuid\\\":\\\"%s\\\",\\\"message\\\":{\\\"role\\\":\\\"user\\\"," +
            "\\\"content\\\":[{\\\"type\\\":\\\"tool_result\\\",\\\"tool_use_id\\\":\\\"%s\\\"," +
            "\\\"content\\\":\\\"[large output not sent to the phone]\\\"}]}}\\n\", u, id " +
            "} else print; fflush() }'"

    /** A transcript path is interpolated into a shell command, so only an
     * absolute `.jsonl` path with nothing a terminal or a shell line would
     * act on gets that far; it is single-quoted on top of that. */
    fun isFollowablePath(path: String): Boolean = path.startsWith("/") && path.endsWith(".jsonl") &&
        path.length <= 4096 && path.none { it < ' ' || it == '\u007f' }

    private fun quote(path: String) = "'" + path.replace("'", "'\\''") + "'"

    /**
     * Prints the offset of the line where the last [budget] bytes *as sent*
     * begin, reading the file's lines in its own bytes (`LC_ALL=C`, so
     * `length` counts bytes, not characters).
     *
     * Counting what is sent rather than what is on disk is the point:
     * measured on a 21.8 MB transcript, its last 2 MB were nearly all images
     * and came to 101 KB and eleven rows once shrunk, while the same budget
     * in a transcript of plain text is hundreds of rows. A long line that
     * [SHRINK] will cut is counted as its replacement. At least one line is
     * always taken, so reading earlier always gets somewhere.
     */
    private fun startOffset(budget: Long) = "LC_ALL=C awk -v B=$budget '{ r[NR] = length(\$0) + 1; " +
        "s[NR] = (r[NR] > $LONG_LINE && index(\$0, \"\\\"tool_use_id\\\":\\\"\") ? 300 : r[NR]) } " +
        "END { k = NR + 1; t = 0; for (i = NR; i >= 1; i--) { if (t + s[i] > B && i < NR) break; t += s[i]; k = i } " +
        "o = 0; for (i = 1; i < k; i++) o += r[i]; print o }'"

    /**
     * Prints [HEADER], then streams the file from where the last [budget]
     * bytes as sent begin, and keeps streaming as it grows. The start is a
     * line boundary, measured on the host: once [SHRINK] has rewritten lines,
     * the bytes the phone receives no longer say where anything is.
     *
     * **The watchdog is the point of the second half.** Closing an SSH exec
     * channel does not end a non-tty child, and `tail -f` on a file nobody
     * writes to never writes, so it would never find out its reader is gone
     * and would live on the Mac forever, one per chat screen ever opened. The
     * loop writes a byte to stderr every ten seconds; once the phone has
     * closed the channel that write fails, the loop ends, and the EXIT trap
     * kills `tail` and `awk` by the ids it holds. SIGPIPE is ignored so the
     * failure arrives as an error the loop can see rather than a signal that
     * skips the trap. stderr, because a heartbeat byte interleaved into the
     * file stream could land in the middle of a record. A named pipe joins
     * the two rather than `|`, so that `$!` is `tail`'s id and not only
     * `awk`'s. Measured by `TranscriptFollowerTest`: both gone within one
     * heartbeat of the reader closing.
     */
    fun command(path: String, budget: Long = SEND_BUDGET): String {
        require(isFollowablePath(path)) { "not a transcript path" }
        return "P=${quote(path)}; [ -f \"\$P\" ] || exit 3; " +
            "O=\$(${startOffset(budget)} \"\$P\"); " +
            "printf '$HEADER%s\\n' \"\$O\"; trap '' PIPE; " +
            "F=\$(mktemp -u \"\${TMPDIR:-/tmp}/attache-follow.XXXXXX\") && mkfifo \"\$F\" || exit 4; " +
            "tail -c +\$((O + 1)) -f \"\$P\" > \"\$F\" & T=\$!; " +
            "$SHRINK < \"\$F\" & A=\$!; " +
            "trap 'kill \$T \$A 2>/dev/null; rm -f \"\$F\"' EXIT; " +
            "while kill -0 \$T 2>/dev/null && kill -0 \$A 2>/dev/null; do sleep 10; printf . >&2 || break; done"
    }

    /**
     * The history that ends at [end] (a line boundary): about [budget] bytes
     * of it as sent, for scrolling up past what was loaded. Prints [HEADER]
     * with the offset of the first line it sends, then the lines, shrunk.
     */
    fun earlierCommand(path: String, end: Long, budget: Long = SEND_BUDGET): String {
        require(isFollowablePath(path)) { "not a transcript path" }
        return "P=${quote(path)}; O=\$(head -c $end \"\$P\" | ${startOffset(budget)}); " +
            "printf '$HEADER%s\\n' \"\$O\"; " +
            "[ \"\$O\" -lt $end ] && tail -c +\$((O + 1)) \"\$P\" | head -c \$(($end - O)) | $SHRINK"
    }
}

/**
 * Bytes in, complete lines out.
 *
 * The unfinished tail is kept as raw bytes and never decoded on its own: a
 * read can end in the middle of a multi-byte character, and decoding the
 * first half by itself turns it into a replacement character that no later
 * read can repair. Chinese text is most of what this app's owner writes.
 */
class LineSplitter {
    private val pending = ByteArrayOutputStream()
    private var headerDone = false

    /** The file offset of the first line that follows the header; null
     * until the header has arrived. Earlier history is read up to here. */
    var startOffset: Long? = null
        private set

    /** Feeds one chunk and returns the transcript lines it completed. */
    fun feed(chunk: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        var start = 0
        for (i in chunk.indices) {
            if (chunk[i] != '\n'.code.toByte()) continue
            pending.write(chunk, start, i - start)
            start = i + 1
            val line = pending.toByteArray().toString(Charsets.UTF_8)
            pending.reset()
            accept(line)?.let(lines::add)
        }
        pending.write(chunk, start, chunk.size - start)
        return lines
    }

    private fun accept(line: String): String? {
        if (!headerDone) {
            headerDone = true
            startOffset = line.removePrefix(TranscriptFollower.HEADER).trim().toLongOrNull() ?: 0L
            return null
        }
        return line.ifBlank { null }
    }
}
