package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRemoteInputRouterTest {
    @Test
    fun hiddenCenterTogglesOnceAndConsumesRelease() {
        val router = PlayerRemoteInputRouter()

        val down = router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            action = KeyEvent.ACTION_DOWN,
            holdDurationMs = 0L,
            mode = PlayerRemoteInputMode.CONTROLS_HIDDEN
        )
        val repeat = router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            action = KeyEvent.ACTION_DOWN,
            holdDurationMs = 400L,
            mode = PlayerRemoteInputMode.CONTROLS_VISIBLE
        )
        val up = router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
            action = KeyEvent.ACTION_UP,
            holdDurationMs = 400L,
            mode = PlayerRemoteInputMode.CONTROLS_VISIBLE
        )

        assertEquals(listOf(PlayerRemoteAction.TogglePlayback), down.actions)
        assertTrue(repeat.consumed)
        assertTrue(up.consumed)
        assertTrue(up.actions.isEmpty())
    }

    @Test
    fun strayReleaseDoesNotCommitASeek() {
        val router = PlayerRemoteInputRouter()

        val result = router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            action = KeyEvent.ACTION_UP,
            holdDurationMs = 0L,
            mode = PlayerRemoteInputMode.CONTROLS_HIDDEN
        )

        assertFalse(result.consumed)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun heldSeekPreviewsOnRepeatsAndCommitsExactlyOnRelease() {
        val router = PlayerRemoteInputRouter()

        val first = router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            action = KeyEvent.ACTION_DOWN,
            holdDurationMs = 0L,
            mode = PlayerRemoteInputMode.CONTROLS_HIDDEN
        )
        val repeat = router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            action = KeyEvent.ACTION_DOWN,
            holdDurationMs = 3_000L,
            mode = PlayerRemoteInputMode.CONTROLS_HIDDEN
        )
        val release = router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            action = KeyEvent.ACTION_UP,
            holdDurationMs = 3_000L,
            mode = PlayerRemoteInputMode.CONTROLS_HIDDEN
        )

        assertEquals(listOf(PlayerRemoteAction.PreviewSeek(PlayerScrubRates.STEP_SHORT_MS)), first.actions)
        assertEquals(listOf(PlayerRemoteAction.PreviewSeek(PlayerScrubRates.STEP_MEDIUM_MS)), repeat.actions)
        assertEquals(listOf(PlayerRemoteAction.CommitPreviewSeek), release.actions)
        assertTrue(release.consumed)
        assertFalse(
            router.handle(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                action = KeyEvent.ACTION_UP,
                holdDurationMs = 0L,
                mode = PlayerRemoteInputMode.CONTROLS_HIDDEN
            ).consumed
        )
    }

    @Test
    fun directionChangeCommitsOldGestureBeforeStartingNewOne() {
        val router = PlayerRemoteInputRouter()

        router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            action = KeyEvent.ACTION_DOWN,
            holdDurationMs = 0L,
            mode = PlayerRemoteInputMode.CONTROLS_HIDDEN
        )
        val change = router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
            action = KeyEvent.ACTION_DOWN,
            holdDurationMs = 0L,
            mode = PlayerRemoteInputMode.CONTROLS_HIDDEN
        )

        assertEquals(
            listOf(
                PlayerRemoteAction.CommitPreviewSeek,
                PlayerRemoteAction.PreviewSeek(PlayerScrubRates.STEP_SHORT_MS)
            ),
            change.actions
        )
        assertFalse(
            router.handle(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                action = KeyEvent.ACTION_UP,
                holdDurationMs = 0L,
                mode = PlayerRemoteInputMode.CONTROLS_HIDDEN
            ).consumed
        )
        assertEquals(
            listOf(PlayerRemoteAction.CommitPreviewSeek),
            router.handle(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                action = KeyEvent.ACTION_UP,
                holdDurationMs = 0L,
                mode = PlayerRemoteInputMode.CONTROLS_HIDDEN
            ).actions
        )
    }

    @Test
    fun controlsVisibleLeavesDpadFocusNavigationAloneButAllowsMediaSeek() {
        val router = PlayerRemoteInputRouter()

        val dpad = router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            action = KeyEvent.ACTION_DOWN,
            holdDurationMs = 0L,
            mode = PlayerRemoteInputMode.CONTROLS_VISIBLE
        )
        val media = router.handle(
            keyCode = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            action = KeyEvent.ACTION_DOWN,
            holdDurationMs = 0L,
            mode = PlayerRemoteInputMode.CONTROLS_VISIBLE
        )

        assertFalse(dpad.consumed)
        assertEquals(
            listOf(PlayerRemoteAction.PreviewSeek(PlayerScrubRates.STEP_SHORT_MS)),
            media.actions
        )
    }

    @Test
    fun resetDropsStaleReleaseAndPendingCommit() {
        val router = PlayerRemoteInputRouter()
        router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            action = KeyEvent.ACTION_DOWN,
            holdDurationMs = 0L,
            mode = PlayerRemoteInputMode.CONTROLS_HIDDEN
        )
        router.reset()

        val release = router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            action = KeyEvent.ACTION_UP,
            holdDurationMs = 0L,
            mode = PlayerRemoteInputMode.PANEL
        )

        assertFalse(release.consumed)
        assertTrue(release.actions.isEmpty())
    }

    @Test
    fun canceledSeekReleaseCancelsInsteadOfCommitting() {
        val router = PlayerRemoteInputRouter()
        router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            action = KeyEvent.ACTION_DOWN,
            holdDurationMs = 0L,
            mode = PlayerRemoteInputMode.CONTROLS_HIDDEN
        )

        val release = router.handle(
            keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
            action = KeyEvent.ACTION_UP,
            holdDurationMs = 100L,
            mode = PlayerRemoteInputMode.CONTROLS_HIDDEN,
            canceled = true
        )

        assertEquals(listOf(PlayerRemoteAction.CancelPreviewSeek), release.actions)
        assertTrue(release.consumed)
    }
}
