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

import android.content.Context

/**
 * The attache-cli builds this app carries (`assets/attache-cli/<os>-<arch>/`,
 * from XueshiQiao/Attache `cli/build-all.sh`), and how to pick one for a host.
 * attache-cli is what the chat mode reads agents through; the phone installs
 * it itself, so nothing on the host has to have been set up by anyone else.
 */
internal object AttacheCliBundle {
    private const val DIR = "attache-cli"

    fun version(context: Context): String? = try {
        context.assets.open("$DIR/version.txt").bufferedReader().use { it.readText().trim() }.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    /** The build's folder for what `uname -s` and `uname -m` said, or null
     * when none fits (a host this app carries nothing for). */
    fun buildFor(os: String, machine: String): String? {
        val arch = when (machine.trim().lowercase()) {
            "arm64", "aarch64" -> "arm"
            "x86_64", "amd64" -> "x86"
            else -> return null
        }
        return when (os.trim().lowercase()) {
            "darwin" -> if (arch == "arm") "darwin-arm64" else "darwin-x86_64"
            "linux" -> if (arch == "arm") "linux-aarch64" else "linux-x86_64"
            else -> null
        }
    }

    fun binary(context: Context, build: String): ByteArray? = try {
        context.assets.open("$DIR/$build/attache-cli").use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    /** Whether version [a] is older than [b], comparing `x.y.z` numerically. */
    fun older(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d < 0
        }
        return false
    }
}
