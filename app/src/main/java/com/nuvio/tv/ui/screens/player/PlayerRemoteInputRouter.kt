package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent

/**
 * Small, focus-owner-independent state machine for Android TV remote input.
 *
 * Compose can move focus between the player container and a control while a
 * remote key is held.  Keeping press/release ownership here prevents a
 * release from being interpreted by a different node than the press.  The
 * router deliberately emits preview seeks only; the caller performs the one
 * real seek on [PlayerRemoteAction.CommitPreviewSeek].
 */
internal class PlayerRemoteInputRouter {
    companion object {
        // Shield remotes occasionally emit a second complete press after a
        // long-running playback session. This is intentionally limited to
        // one-shot actions; seek repeats are never filtered by this window.
        const val ONE_SHOT_DEBOUNCE_MS = 180L
    }

    private val consumedDownKeys = mutableSetOf<Int>()
    private val lastReleaseTimeMs = mutableMapOf<Int, Long>()
    private var activeSeekKey: Int? = null

    fun reset() {
        consumedDownKeys.clear()
        lastReleaseTimeMs.clear()
        activeSeekKey = null
    }

    fun handle(
        keyCode: Int,
        action: Int,
        holdDurationMs: Long,
        mode: PlayerRemoteInputMode,
        canceled: Boolean = false,
        eventTimeMs: Long = -1L,
        allowDpadSeek: Boolean = false
    ): PlayerRemoteInputResult {
        if (action == KeyEvent.ACTION_UP) {
            return release(keyCode, canceled, eventTimeMs)
        }
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_MULTIPLE) {
            return PlayerRemoteInputResult.NotConsumed
        }

        val isRepeat = consumedDownKeys.contains(keyCode)
        val actions = mutableListOf<PlayerRemoteAction>()

        val seekDirection = seekDirectionFor(keyCode, mode, allowDpadSeek)
        if (seekDirection != null) {
            if (activeSeekKey != null && activeSeekKey != keyCode) {
                // Some remotes can switch direction without delivering the
                // previous UP. Commit the old gesture before starting the new
                // one so the preview state cannot remain stuck.
                actions += PlayerRemoteAction.CommitPreviewSeek
                consumedDownKeys.remove(activeSeekKey)
                activeSeekKey = null
            }
            consumedDownKeys += keyCode
            activeSeekKey = keyCode
            actions += PlayerRemoteAction.PreviewSeek(
                PlayerScrubRates.deltaMsForHold(
                    holdDurationMs = holdDurationMs.coerceAtLeast(0L),
                    forward = seekDirection
                )
            )
            return PlayerRemoteInputResult(consumed = true, actions = actions)
        }

        if (isRepeat) {
            // A held confirm/media key must not toggle playback repeatedly.
            // Directional repeats are consumed below so they cannot leak into
            // Compose focus movement after a screen-level action was claimed.
            return if (consumedDownKeys.contains(keyCode)) {
                PlayerRemoteInputResult(consumed = true)
            } else {
                PlayerRemoteInputResult.NotConsumed
            }
        }

        // Keep the down/up pair consumed so the duplicate cannot leak to a
        // focused Compose button, but do not dispatch the action a second time.
        val lastRelease = lastReleaseTimeMs[keyCode]
        if (isOneShotKey(keyCode) && eventTimeMs >= 0L && lastRelease != null &&
            eventTimeMs >= lastRelease && eventTimeMs - lastRelease < ONE_SHOT_DEBOUNCE_MS
        ) {
            consumedDownKeys += keyCode
            return PlayerRemoteInputResult(consumed = true)
        }

        when (mode) {
            PlayerRemoteInputMode.PANEL -> return PlayerRemoteInputResult.NotConsumed

            PlayerRemoteInputMode.PAUSE_OVERLAY -> when {
                keyCode.isConfirmKey() || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    consumedDownKeys += keyCode
                    actions += PlayerRemoteAction.TogglePlayback
                }
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_STOP -> {
                    consumedDownKeys += keyCode
                }
                else -> {
                    consumedDownKeys += keyCode
                    actions += PlayerRemoteAction.DismissPauseOverlay
                }
            }

            PlayerRemoteInputMode.CONTROLS_HIDDEN -> when {
                keyCode.isConfirmKey() || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    consumedDownKeys += keyCode
                    actions += PlayerRemoteAction.TogglePlayback
                }
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    consumedDownKeys += keyCode
                    actions += PlayerRemoteAction.PlayIfPaused
                }
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_STOP -> {
                    consumedDownKeys += keyCode
                    actions += PlayerRemoteAction.PauseIfPlaying
                }
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    consumedDownKeys += keyCode
                    actions += PlayerRemoteAction.ToggleControls
                }
            }

            PlayerRemoteInputMode.CONTROLS_VISIBLE -> when {
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    consumedDownKeys += keyCode
                    actions += PlayerRemoteAction.TogglePlayback
                }
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    consumedDownKeys += keyCode
                    actions += PlayerRemoteAction.PlayIfPaused
                }
                keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_STOP -> {
                    consumedDownKeys += keyCode
                    actions += PlayerRemoteAction.PauseIfPlaying
                }
            }
        }

        return if (consumedDownKeys.contains(keyCode)) {
            PlayerRemoteInputResult(consumed = true, actions = actions)
        } else {
            PlayerRemoteInputResult.NotConsumed
        }
    }

    private fun release(keyCode: Int, canceled: Boolean, eventTimeMs: Long = -1L): PlayerRemoteInputResult {
        val wasConsumed = consumedDownKeys.remove(keyCode)
        if (!wasConsumed) return PlayerRemoteInputResult.NotConsumed
        if (isOneShotKey(keyCode) && eventTimeMs >= 0L) {
            lastReleaseTimeMs[keyCode] = eventTimeMs
        }

        return if (activeSeekKey == keyCode) {
            activeSeekKey = null
            PlayerRemoteInputResult(
                consumed = true,
                actions = listOf(
                    if (canceled) PlayerRemoteAction.CancelPreviewSeek
                    else PlayerRemoteAction.CommitPreviewSeek
                )
            )
        } else {
            PlayerRemoteInputResult(consumed = true)
        }
    }

    private fun seekDirectionFor(
        keyCode: Int,
        mode: PlayerRemoteInputMode,
        allowDpadSeek: Boolean
    ): Boolean? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
        KeyEvent.KEYCODE_MEDIA_REWIND -> false
        KeyEvent.KEYCODE_DPAD_LEFT -> if (
            mode == PlayerRemoteInputMode.CONTROLS_HIDDEN || allowDpadSeek
        ) false else null
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (
            mode == PlayerRemoteInputMode.CONTROLS_HIDDEN || allowDpadSeek
        ) true else null
        else -> null
    }

    private fun isOneShotKey(keyCode: Int): Boolean = keyCode.isConfirmKey() ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
        keyCode == KeyEvent.KEYCODE_MEDIA_STOP ||
        keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN
}

internal enum class PlayerRemoteInputMode {
    PANEL,
    PAUSE_OVERLAY,
    CONTROLS_HIDDEN,
    CONTROLS_VISIBLE
}

internal sealed interface PlayerRemoteAction {
    data object TogglePlayback : PlayerRemoteAction
    data object PlayIfPaused : PlayerRemoteAction
    data object PauseIfPlaying : PlayerRemoteAction
    data object ToggleControls : PlayerRemoteAction
    data object DismissPauseOverlay : PlayerRemoteAction
    data class PreviewSeek(val deltaMs: Long) : PlayerRemoteAction
    data object CommitPreviewSeek : PlayerRemoteAction
    data object CancelPreviewSeek : PlayerRemoteAction
}

internal data class PlayerRemoteInputResult(
    val consumed: Boolean,
    val actions: List<PlayerRemoteAction> = emptyList()
) {
    companion object {
        val NotConsumed = PlayerRemoteInputResult(consumed = false)
    }
}

private fun Int.isConfirmKey(): Boolean = this == KeyEvent.KEYCODE_DPAD_CENTER ||
    this == KeyEvent.KEYCODE_ENTER ||
    this == KeyEvent.KEYCODE_NUMPAD_ENTER
