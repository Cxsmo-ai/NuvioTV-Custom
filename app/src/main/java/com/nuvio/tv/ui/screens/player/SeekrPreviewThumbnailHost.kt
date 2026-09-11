package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.seekr.previews.android.SeekrTrack
import kotlin.math.max

private const val PREVIEW_STEP_MS = 10_000L
private const val PREVIEW_LINGER_MS = 1_500L

private data class PreviewFrames(
    val previous: Bitmap? = null,
    val center: Bitmap? = null,
    val next: Bitmap? = null
)

/**
 * Seekr-backed three-frame preview. The center thumbnail is requested first so
 * a slow neighbor never blocks the frame the user actually asked to see.
 * Every request is cancellable through LaunchedEffect, so fast remote presses
 * cannot leave stale network/crop work painting over the current seek target.
 */
@Composable
fun SeekrPreviewThumbnailHost(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val track by viewModel.seekrTrack.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val timeline by viewModel.playbackTimeline.collectAsStateWithLifecycle()
    val requestedPosition = uiState.previewThumbPositionMs
    val duration = timeline.duration
    val visible = track != null && requestedPosition != null && duration > 0L

    var frames by remember(track) { mutableStateOf(PreviewFrames()) }
    var visiblePosition by remember(track) { mutableStateOf<Long?>(null) }

    LaunchedEffect(track, requestedPosition, duration) {
        val active = track ?: return@LaunchedEffect
        val position = requestedPosition ?: return@LaunchedEffect
        val centerPosition = position.coerceIn(0L, max(0L, duration - 1L))
        visiblePosition = centerPosition

        // Center-first means the first useful frame can render without waiting
        // for the context frames. Seekr's internal sheet cache makes subsequent
        // adjacent lookups cheap when they share the same sprite sheet.
        val center = active.thumbnailAt(centerPosition)
        frames = PreviewFrames(center = center)

        val previous = if (centerPosition >= PREVIEW_STEP_MS) {
            active.thumbnailAt(centerPosition - PREVIEW_STEP_MS)
        } else {
            null
        }
        val next = if (centerPosition + PREVIEW_STEP_MS < duration) {
            active.thumbnailAt(centerPosition + PREVIEW_STEP_MS)
        } else {
            null
        }
        frames = PreviewFrames(previous = previous, center = center, next = next)
        delay(PREVIEW_LINGER_MS)
        if (visiblePosition == centerPosition) visiblePosition = null
    }

    AnimatedVisibility(
        visible = visible && visiblePosition != null,
        enter = fadeIn(tween(100)),
        exit = fadeOut(tween(150)),
        modifier = modifier
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PreviewFrame(frames.previous, emphasized = false)
            PreviewFrame(frames.center, emphasized = true)
            PreviewFrame(frames.next, emphasized = false)
        }
    }
}

@Composable
private fun PreviewFrame(bitmap: Bitmap?, emphasized: Boolean) {
    Box(
        modifier = Modifier
            .size(if (emphasized) 176.dp else 112.dp, if (emphasized) 99.dp else 63.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.86f))
            .border(
                width = if (emphasized) 1.5.dp else 0.dp,
                color = if (emphasized) Color.White.copy(alpha = 0.8f) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}
