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

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import org.attache.tmux.AttacheChrome

/**
 * The space to keep clear at the bottom of a chat-mode screen: the keyboard
 * while it is up, otherwise the system's gesture area.
 *
 * The terminal mode's rule, shared rather than re-derived: the largest of
 * the gesture insets, the safe drawing inset and a floor. Compose's default
 * (the navigation bar inset) is not enough, because some systems report a
 * zero navigation bar under a gesture handle, and on the owner's tablet the
 * chat page's input box sat under the handle exactly that way.
 */
@Composable
internal fun bottomSafeInset(): Dp {
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    if (ime > 0) return with(density) { ime.toDp() }
    val gesture = maxOf(
        WindowInsets.mandatorySystemGestures.getBottom(density),
        WindowInsets.systemGestures.getBottom(density),
        WindowInsets.safeDrawing.getBottom(density),
    )
    return maxOf(with(density) { gesture.toDp() }, AttacheChrome.MinBottomSafeInset)
}
