/*
 * NuvioTV-Fork - seek-thumbnail workstream (T-series)
 * Copyright (C) 2026 NuvioTV-Fork contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.player.thumbnail.SeekFilmstripFrame
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import java.util.Locale
import java.util.concurrent.TimeUnit

/** How long the seek-thumbnail pane lingers after the last preview input before dismissing. */
private const val PREVIEW_THUMB_LINGER_MS = 1_800L

/**
 * Filmstrip seek preview overlay matching the native cinema scrubber design:
 * - Darkened backdrop over the playing video
 * - Horizontal filmstrip of 7 preview frames centered on screen
 * - Active center frame highlighted with a crisp white border
 * - Red scrub progress bar with current and remaining timestamps
 */
@Composable
fun SeekThumbnailOverlayHost(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val timeline by viewModel.playbackTimeline.collectAsState()
    val tick by SeekThumbnails.tick
    val requestedPositionMs = uiState.previewThumbPositionMs
    var shownPositionMs by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(requestedPositionMs) {
        val req = requestedPositionMs
        if (req == null) {
            shownPositionMs = null
        } else {
            shownPositionMs = req
            kotlinx.coroutines.delay(PREVIEW_THUMB_LINGER_MS)
            if (shownPositionMs == req) shownPositionMs = null
        }
    }

    val previewPositionMs = shownPositionMs ?: return
    val durationMs = timeline.duration
    if (durationMs <= 0L) return

    val frames = remember(previewPositionMs, tick) {
        SeekThumbnails.filmstripFor(previewPositionMs, halfWindow = 3)
    }
    val hasAnyFrames = frames.any { it.bitmap != null }
    if (!hasAnyFrames && SeekThumbnails.thumbFor(previewPositionMs) == null) return

    val fraction = (previewPositionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val remainingMs = (durationMs - previewPositionMs).coerceAtLeast(0L)

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(200)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 48.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                // Horizontal filmstrip of 7 thumbnails
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    frames.forEach { frame ->
                        FilmstripThumbnailCard(
                            frame = frame,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }

                // Scrubber and time text row matching reference screenshot
                Row(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = formatTime(previewPositionMs),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        ),
                        color = Color.White
                    )

                    // Red progress bar matching screenshot
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.28f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE50914))
                        )
                    }

                    Text(
                        text = formatTime(remainingMs),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 17.sp
                        ),
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FilmstripThumbnailCard(
    frame: SeekFilmstripFrame,
    modifier: Modifier = Modifier
) {
    val cardHeight = if (frame.isCenter) 116.dp else 108.dp
    val cardWidth = cardHeight * (16f / 9f)

    Box(
        modifier = modifier
            .width(cardWidth)
            .height(cardHeight)
            .graphicsLayer {
                alpha = if (frame.isCenter) 1.0f else 0.65f
                scaleX = if (frame.isCenter) 1.04f else 1.0f
                scaleY = if (frame.isCenter) 1.04f else 1.0f
            }
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF16161C))
            .then(
                if (frame.isCenter) {
                    Modifier.border(
                        width = 2.5.dp,
                        color = Color.White,
                        shape = RoundedCornerShape(3.dp)
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (frame.bitmap != null) {
            Image(
                bitmap = frame.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1C1C26)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatTime(frame.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.40f)
                )
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
