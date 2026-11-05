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

import android.app.Activity
import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A chat-mode colour theme, given as the handful of colours a terminal theme
 * names: the page, two steps of raised surface, the accent and two text
 * tones. The rest of a Material scheme is derived from these, so every theme
 * is complete and none can leave a role at a default that clashes with it.
 * [palette] null means "the app's own theme", light or dark as Settings says.
 */
enum class ChatThemeChoice(val label: String, val palette: ChatPalette?) {
    APP("App", null),
    LIGHT(
        "Light",
        ChatPalette(dark = false, background = 0xFFFAF9FC, raised = 0xFFF0EEF5, raisedMore = 0xFFE6E3EE, accent = 0xFF3F5AA8, text = 0xFF1B1B21, muted = 0xFF5B5D6B),
    ),
    MIDNIGHT(
        "Midnight",
        ChatPalette(dark = true, background = 0xFF101828, raised = 0xFF18233A, raisedMore = 0xFF22304B, accent = 0xFF7EA6F6, text = 0xFFE3E8F2, muted = 0xFF9AA6BD),
    ),
    TOKYO_NIGHT(
        "Tokyo Night",
        ChatPalette(dark = true, background = 0xFF1A1B26, raised = 0xFF222433, raisedMore = 0xFF2C2F44, accent = 0xFF7AA2F7, text = 0xFFC0CAF5, muted = 0xFF9AA5CE),
    ),
    NORD(
        "Nord",
        ChatPalette(dark = true, background = 0xFF2E3440, raised = 0xFF3B4252, raisedMore = 0xFF434C5E, accent = 0xFF88C0D0, text = 0xFFECEFF4, muted = 0xFFC2C9D6),
    ),
    SOLARIZED(
        "Solarized",
        ChatPalette(dark = true, background = 0xFF002B36, raised = 0xFF073642, raisedMore = 0xFF0E4452, accent = 0xFF4AA3DF, text = 0xFFEEE8D5, muted = 0xFF9DB0B2),
    ),
    DRACULA(
        "Dracula",
        ChatPalette(dark = true, background = 0xFF282A36, raised = 0xFF313442, raisedMore = 0xFF3B3E50, accent = 0xFFBD93F9, text = 0xFFF8F8F2, muted = 0xFFB4B8D0),
    ),
    GRUVBOX(
        "Gruvbox",
        ChatPalette(dark = true, background = 0xFF282828, raised = 0xFF32302F, raisedMore = 0xFF3C3836, accent = 0xFF83A598, text = 0xFFEBDBB2, muted = 0xFFBDAE93),
    ),
    ;

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: APP
    }
}

data class ChatPalette(
    val dark: Boolean,
    val background: Long,
    val raised: Long,
    val raisedMore: Long,
    val accent: Long,
    val text: Long,
    val muted: Long,
) {
    fun scheme(): ColorScheme {
        val bg = Color(background)
        val up = Color(raised)
        val upMore = Color(raisedMore)
        val accentColor = Color(accent)
        val ink = Color(text)
        val faint = Color(muted)
        // Containers are the accent laid over the page, so a user bubble or a
        // selected row is unmistakably this theme's and still reads.
        val accentContainer = accentColor.copy(alpha = if (dark) 0.32f else 0.16f).compositeOver(bg)
        val onAccent = if (accentColor.luminance() > 0.4f) Color(0xFF0B1020) else Color.White
        // Tonal buttons sit on dialogs, which take the highest surface; tinted
        // with the accent they stay visible there instead of matching it.
        val tonal = accentColor.copy(alpha = if (dark) 0.24f else 0.14f).compositeOver(upMore)
        val outline = faint.copy(alpha = 0.55f).compositeOver(bg)
        val outlineSoft = faint.copy(alpha = 0.25f).compositeOver(bg)
        return if (dark) {
            darkColorScheme(
                primary = accentColor, onPrimary = onAccent,
                primaryContainer = accentContainer, onPrimaryContainer = ink,
                secondary = accentColor, onSecondary = onAccent,
                secondaryContainer = tonal, onSecondaryContainer = ink,
                tertiary = accentColor, onTertiary = onAccent,
                tertiaryContainer = upMore, onTertiaryContainer = ink,
                background = bg, onBackground = ink,
                surface = bg, onSurface = ink,
                surfaceVariant = upMore, onSurfaceVariant = faint,
                surfaceTint = accentColor,
                surfaceContainerLowest = bg, surfaceContainerLow = up, surfaceContainer = up,
                surfaceContainerHigh = upMore, surfaceContainerHighest = upMore,
                surfaceBright = upMore, surfaceDim = bg,
                outline = outline, outlineVariant = outlineSoft,
            )
        } else {
            lightColorScheme(
                primary = accentColor, onPrimary = onAccent,
                primaryContainer = accentContainer, onPrimaryContainer = ink,
                secondary = accentColor, onSecondary = onAccent,
                secondaryContainer = tonal, onSecondaryContainer = ink,
                tertiary = accentColor, onTertiary = onAccent,
                tertiaryContainer = upMore, onTertiaryContainer = ink,
                background = bg, onBackground = ink,
                surface = bg, onSurface = ink,
                surfaceVariant = upMore, onSurfaceVariant = faint,
                surfaceTint = accentColor,
                surfaceContainerLowest = Color.White, surfaceContainerLow = up, surfaceContainer = up,
                surfaceContainerHigh = upMore, surfaceContainerHighest = upMore,
                surfaceBright = Color.White, surfaceDim = upMore,
                outline = outline, outlineVariant = outlineSoft,
            )
        }
    }
}

/**
 * The chat mode's appearance: its theme and how large its text is. Kept on
 * the phone with the app's other display preferences; it changes how the
 * chat mode looks and nothing else, the terminal mode's colours included.
 */
object ChatAppearance {
    private const val THEME_KEY = "attache_chat_theme"
    private const val TEXT_SCALE_KEY = "attache_chat_text_scale"
    const val MIN_SCALE = 0.85f
    const val MAX_SCALE = 1.4f

    private val _theme = MutableStateFlow(ChatThemeChoice.APP)
    val theme: StateFlow<ChatThemeChoice> = _theme.asStateFlow()

    private val _textScale = MutableStateFlow(1f)
    val textScale: StateFlow<Float> = _textScale.asStateFlow()

    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        _theme.value = ChatThemeChoice.fromName(prefs.getString(THEME_KEY, null))
        _textScale.value = prefs.getFloat(TEXT_SCALE_KEY, 1f).coerceIn(MIN_SCALE, MAX_SCALE)
        loaded = true
    }

    fun setTheme(context: Context, choice: ChatThemeChoice) {
        _theme.value = choice
        PreferenceManager.getDefaultSharedPreferences(context).edit { putString(THEME_KEY, choice.name) }
    }

    fun setTextScale(context: Context, scale: Float) {
        val clamped = (Math.round(scale * 20f) / 20f).coerceIn(MIN_SCALE, MAX_SCALE)
        _textScale.value = clamped
        PreferenceManager.getDefaultSharedPreferences(context).edit { putFloat(TEXT_SCALE_KEY, clamped) }
    }
}

/**
 * Wraps a chat-mode screen in the chosen theme and text size. The status bar
 * takes the theme's page colour while the screen is up, and is handed back
 * as it was when the screen goes, so the host list keeps the app's own.
 */
@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ChatAppearance.ensureLoaded(context) }
    val choice by ChatAppearance.theme.collectAsState()
    val scale by ChatAppearance.textScale.collectAsState()
    val scheme = choice.palette?.scheme() ?: MaterialTheme.colorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(scheme.background) {
            val window = (view.context as? Activity)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }

            @Suppress("DEPRECATION")
            val previousColor = window?.statusBarColor
            val previousLight = controller?.isAppearanceLightStatusBars
            @Suppress("DEPRECATION")
            window?.statusBarColor = scheme.background.toArgb()
            controller?.isAppearanceLightStatusBars = scheme.background.luminance() > 0.5f
            onDispose {
                @Suppress("DEPRECATION")
                if (previousColor != null) window.statusBarColor = previousColor
                if (previousLight != null) controller.isAppearanceLightStatusBars = previousLight
            }
        }
    }

    val density = LocalDensity.current
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * scale)) {
            content()
        }
    }
}
