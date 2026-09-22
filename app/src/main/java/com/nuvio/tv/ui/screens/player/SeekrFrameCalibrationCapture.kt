package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Captures frames from the already-rendered ExoPlayer surface.
 *
 * This class never opens the media URL and never creates a second player. It
 * temporarily seeks the existing player while the player UI is covered, copies
 * the local surface, and restores the exact prior position/play state.
 */
internal class ExoSeekrFrameCapture(
    private val player: ExoPlayer,
    private val playerView: PlayerView
) {
    suspend fun captureAt(positionMs: Long): Bitmap? {
        val surface = playerView.videoSurfaceView
        return try {
            player.volume = 0f
            player.playWhenReady = false
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
            player.seekTo(positionMs.coerceAtLeast(0L))
            // CLOSEST_SYNC is deliberately paired with a short settle window:
            // the preview is a keyframe-oriented image, not an arbitrary exact
            // presentation timestamp.
            waitForSeekToSettle(positionMs)
            copySurface(surface)
        } catch (_: CancellationException) {
            throw CancellationException("Seekr frame calibration cancelled")
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun waitForSeekToSettle(targetMs: Long) {
        repeat(8) {
            if (player.playbackState == Player.STATE_READY &&
                kotlin.math.abs(player.currentPosition - targetMs) <= 1_500L
            ) return
            delay(45L)
        }
        delay(90L)
    }

    private suspend fun copySurface(surface: android.view.View?): Bitmap? =
        withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            when (surface) {
                is TextureView -> surface.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                is SurfaceView -> copySurfaceView(surface)
                else -> null
            }
        }

    private suspend fun copySurfaceView(surface: SurfaceView): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val width = surface.width
            val height = surface.height
            if (width <= 0 || height <= 0 || !surface.holder.surface.isValid) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val bitmap = runCatching {
                // Calibration only needs a comparison fingerprint. Keep the
                // copy at Seekr's native 320x180 tile size so a 4K stream does
                // not allocate or copy a full-resolution bitmap repeatedly.
                Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
            }.getOrNull()
            if (bitmap == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            PixelCopy.request(
                surface,
                Rect(0, 0, width, height),
                bitmap,
                { result ->
                    if (!continuation.isActive) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@request
                    }
                    if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap)
                    } else {
                        bitmap.recycle()
                        continuation.resume(null)
                    }
                },
                Handler(Looper.getMainLooper())
            )
            continuation.invokeOnCancellation {
                // PixelCopy has no cancellation handle. The callback checks the
                // continuation state before handing the bitmap back.
                if (!continuation.isActive && !bitmap.isRecycled) bitmap.recycle()
            }
        }

    private companion object {
        const val CAPTURE_TIMEOUT_MS = 700L
    }
}
