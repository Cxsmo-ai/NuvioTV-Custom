package com.nuvio.tv.ui.util

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager

val LocalFastHorizontalNavigationEnabled = compositionLocalOf { false }

/**
 * Throttles D-pad key repeats to prevent HWUI overload and focus jank
 * when a directional key is held down.  Consumes rapid repeats and
 * manually moves focus at a controlled rate.
 *
 * @param horizontalGateMs minimum interval between horizontal repeats
 * @param verticalGateMs   minimum interval between vertical repeats
 */
fun Modifier.dpadRepeatThrottle(
    horizontalGateMs: Long = DpadNavigationTiming.STANDARD_HORIZONTAL_REPEAT_MS,
    verticalGateMs: Long = DpadNavigationTiming.VERTICAL_REPEAT_MS
): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val fastHorizontalNavigationEnabled = LocalFastHorizontalNavigationEnabled.current
    val repeatGate = remember { DirectionalRepeatGate() }

    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        val directionIndex = when (native.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> 0
            KeyEvent.KEYCODE_DPAD_UP -> 1
            KeyEvent.KEYCODE_DPAD_LEFT -> 2
            KeyEvent.KEYCODE_DPAD_RIGHT -> 3
            else -> -1
        }

        // A new physical press or release starts a fresh repeat sequence for
        // that direction. First presses still fall through to native Compose
        // focus handling and therefore remain exactly one focus step.
        if (directionIndex >= 0 &&
            (native.action == KeyEvent.ACTION_UP ||
                (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0))
        ) {
            repeatGate.reset(directionIndex)
            return@onPreviewKeyEvent false
        }

        if (native.action == KeyEvent.ACTION_DOWN &&
            native.repeatCount > 0 &&
            directionIndex >= 0
        ) {
            val isVertical = native.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                native.keyCode == KeyEvent.KEYCODE_DPAD_UP
            val gateMs = if (isVertical) {
                verticalGateMs
            } else if (fastHorizontalNavigationEnabled) {
                DpadNavigationTiming.FAST_HORIZONTAL_REPEAT_MS
            } else {
                horizontalGateMs
            }
            val now = SystemClock.uptimeMillis()
            if (!repeatGate.tryAcquire(directionIndex, now, gateMs)) {
                return@onPreviewKeyEvent true
            }
            val direction = when (native.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                else -> null
            }
            if (direction != null) focusManager.moveFocus(direction)
            return@onPreviewKeyEvent true
        }
        false
    }
}
