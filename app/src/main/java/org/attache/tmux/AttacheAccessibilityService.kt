package org.attache.tmux

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Trigger modes for 1-step WeType (`com.tencent.wetype`) voice input.
 */
enum class AttacheVoiceTriggerMode(
    val id: String,
    val shortLabel: String,
    val toastTitle: String,
) {
    SPACEBAR_HOLD(
        id = "spacebar_hold",
        shortLabel = "按住空格",
        toastTitle = "模式1：自动长按微信输入法空格键 (通用推荐)",
    ),
    TOOLBAR_MIC_TAP(
        id = "toolbar_mic_tap",
        shortLabel = "点工具栏话筒",
        toastTitle = "模式2：自动点击微信输入法候选栏右侧话筒",
    ),
    ;

    companion object {
        fun fromId(id: String?): AttacheVoiceTriggerMode =
            entries.firstOrNull { it.id == id } ?: SPACEBAR_HOLD
    }
}

/**
 * Minimal, zero-overhead [AccessibilityService] used strictly to dispatch
 * a touch gesture onto WeType's spacebar (or toolbar microphone icon) when the
 * user taps `🎤说` in Attaché, eliminating the 2-step manual long-press friction.
 *
 * Does NOT listen to accessibility events or retrieve window content.
 */
class AttacheAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _serviceConnectedFlow.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op: we do not monitor window content or events.
    }

    override fun onInterrupt() {
        stopSpacebarHoldInternal(notifyCallback = null)
    }

    override fun onDestroy() {
        stopSpacebarHoldInternal(notifyCallback = null)
        if (instance === this) {
            instance = null
            _serviceConnectedFlow.value = false
        }
        super.onDestroy()
    }

    companion object {
        private const val PREF_TRIGGER_MODE = "attache_voice_trigger_mode"
        private const val HOLD_SLICE_MS = 180L
        private const val RELEASE_SLICE_MS = 25L
        private const val MAX_HOLD_DURATION_MS = 45_000L

        @Volatile
        private var instance: AttacheAccessibilityService? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        private val _serviceConnectedFlow = MutableStateFlow(false)
        val serviceConnectedFlow: StateFlow<Boolean> = _serviceConnectedFlow.asStateFlow()

        private val _isHoldingSpacebarFlow = MutableStateFlow(false)
        val isHoldingSpacebarFlow: StateFlow<Boolean> = _isHoldingSpacebarFlow.asStateFlow()

        private val _triggerModeFlow = MutableStateFlow(AttacheVoiceTriggerMode.SPACEBAR_HOLD)
        val triggerModeFlow: StateFlow<AttacheVoiceTriggerMode> = _triggerModeFlow.asStateFlow()

        @Volatile
        private var keepHoldingSpacebar = false

        @Volatile
        private var holdStartUptimeMs = 0L

        @Volatile
        private var pendingReleaseCallbacks = mutableListOf<() -> Unit>()

        fun syncPrefs(context: Context): AttacheVoiceTriggerMode {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val mode = AttacheVoiceTriggerMode.fromId(prefs.getString(PREF_TRIGGER_MODE, null))
            _triggerModeFlow.value = mode
            return mode
        }

        fun toggleTriggerMode(context: Context): AttacheVoiceTriggerMode {
            val current = syncPrefs(context)
            val next = when (current) {
                AttacheVoiceTriggerMode.SPACEBAR_HOLD -> AttacheVoiceTriggerMode.TOOLBAR_MIC_TAP
                AttacheVoiceTriggerMode.TOOLBAR_MIC_TAP -> AttacheVoiceTriggerMode.SPACEBAR_HOLD
            }
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putString(PREF_TRIGGER_MODE, next.id)
            }
            _triggerModeFlow.value = next
            return next
        }

        /**
         * Returns true if [AttacheAccessibilityService] is both enabled in Android Settings
         * and currently bound (`instance != null`).
         */
        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expectedComponent = ComponentName(context, AttacheAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabledServices.split(':')
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it == expectedComponent }
        }

        /**
         * Triggers WeType voice input according to the active [AttacheVoiceTriggerMode].
         *
         * - [AttacheVoiceTriggerMode.SPACEBAR_HOLD]: Holds down the center of WeType's bottom
         *   spacebar (`x = screenWidthPx * 0.5f`, `y = screenHeightPx - navBarBottomPx - 34dp`).
         * - [AttacheVoiceTriggerMode.TOOLBAR_MIC_TAP]: Performs a single 65ms tap on WeType's
         *   candidate/toolbar bar microphone position (`x = screenWidthPx - 68dp`,
         *   `y = (screenHeightPx - imeBottomPx) + 22dp`).
         */
        fun triggerVoiceInput(
            context: Context,
            screenWidthPx: Int,
            screenHeightPx: Int,
            imeBottomPx: Int,
            navBarBottomPx: Int,
        ): Boolean {
            val service = instance ?: return false
            val density = context.resources.displayMetrics.density
            val mode = syncPrefs(context)

            return when (mode) {
                AttacheVoiceTriggerMode.SPACEBAR_HOLD -> {
                    val x = (screenWidthPx * 0.5f).coerceIn(1f, (screenWidthPx - 1).toFloat())
                    val y = (screenHeightPx - navBarBottomPx - 34f * density)
                        .coerceIn(1f, (screenHeightPx - 1).toFloat())
                    service.startSpacebarHoldInternal(x, y)
                }

                AttacheVoiceTriggerMode.TOOLBAR_MIC_TAP -> {
                    val imeTopY = (screenHeightPx - imeBottomPx).toFloat()
                    val x = (screenWidthPx - 68f * density).coerceIn(1f, (screenWidthPx - 1).toFloat())
                    val y = (imeTopY + 22f * density).coerceIn(1f, (screenHeightPx - 1).toFloat())
                    service.performTapInternal(x, y)
                }
            }
        }

        /**
         * Releases the ongoing spacebar hold gesture (if active) so WeType commits the
         * recognized voice text via a clean `ACTION_UP`.
         */
        fun stopSpacebarHold(onReleased: (() -> Unit)? = null) {
            val service = instance
            if (service == null) {
                keepHoldingSpacebar = false
                _isHoldingSpacebarFlow.value = false
                onReleased?.let { mainHandler.post(it) }
                return
            }
            service.stopSpacebarHoldInternal(onReleased)
        }
    }

    private fun performTapInternal(x: Float, y: Float): Boolean {
        stopSpacebarHoldInternal(notifyCallback = null)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, mainHandler)
    }

    private fun startSpacebarHoldInternal(x: Float, y: Float): Boolean {
        stopSpacebarHoldInternal(notifyCallback = null)
        val holdPath = Path().apply { moveTo(x, y) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            keepHoldingSpacebar = true
            holdStartUptimeMs = SystemClock.uptimeMillis()
            _isHoldingSpacebarFlow.value = true

            val firstStroke = GestureDescription.StrokeDescription(
                holdPath,
                0L,
                HOLD_SLICE_MS,
                true,
            )
            val dispatched = dispatchHoldSliceApi26(firstStroke, holdPath)
            if (!dispatched) {
                keepHoldingSpacebar = false
                _isHoldingSpacebarFlow.value = false
            }
            return dispatched
        } else {
            // Fallback for API < 26: single 8-second hold stroke
            val stroke = GestureDescription.StrokeDescription(holdPath, 0L, 8_000L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            _isHoldingSpacebarFlow.value = true
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        _isHoldingSpacebarFlow.value = false
                        flushReleaseCallbacks()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        _isHoldingSpacebarFlow.value = false
                        flushReleaseCallbacks()
                    }
                },
                mainHandler,
            )
            if (!dispatched) {
                _isHoldingSpacebarFlow.value = false
            }
            return dispatched
        }
    }

    private fun dispatchHoldSliceApi26(
        stroke: GestureDescription.StrokeDescription,
        holdPath: Path,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    val elapsed = SystemClock.uptimeMillis() - holdStartUptimeMs
                    if (keepHoldingSpacebar && elapsed < MAX_HOLD_DURATION_MS) {
                        val nextStroke = stroke.continueStroke(
                            holdPath,
                            0L,
                            HOLD_SLICE_MS,
                            true,
                        )
                        if (!dispatchHoldSliceApi26(nextStroke, holdPath)) {
                            finishHoldState()
                        }
                    } else {
                        // Dispatch the final continuation slice with willContinue = false
                        // so WeType receives a clean MotionEvent.ACTION_UP on the spacebar.
                        val releaseStroke = stroke.continueStroke(
                            holdPath,
                            0L,
                            RELEASE_SLICE_MS,
                            false,
                        )
                        val releaseGesture = GestureDescription.Builder()
                            .addStroke(releaseStroke)
                            .build()
                        val released = dispatchGesture(
                            releaseGesture,
                            object : GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription?) {
                                    finishHoldState()
                                }

                                override fun onCancelled(gestureDescription: GestureDescription?) {
                                    finishHoldState()
                                }
                            },
                            mainHandler,
                        )
                        if (!released) {
                            finishHoldState()
                        }
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    // Cancelled because user physically touched the screen or keyboard hid.
                    finishHoldState()
                }
            },
            mainHandler,
        )
    }

    private fun stopSpacebarHoldInternal(notifyCallback: (() -> Unit)?) {
        synchronized(pendingReleaseCallbacks) {
            if (notifyCallback != null) {
                pendingReleaseCallbacks.add(notifyCallback)
            }
        }
        if (!keepHoldingSpacebar && !_isHoldingSpacebarFlow.value) {
            flushReleaseCallbacks()
            return
        }
        keepHoldingSpacebar = false
    }

    private fun finishHoldState() {
        keepHoldingSpacebar = false
        _isHoldingSpacebarFlow.value = false
        flushReleaseCallbacks()
    }

    private fun flushReleaseCallbacks() {
        val callbacks = synchronized(pendingReleaseCallbacks) {
            val copy = pendingReleaseCallbacks.toList()
            pendingReleaseCallbacks.clear()
            copy
        }
        callbacks.forEach { cb -> mainHandler.post(cb) }
    }
}
