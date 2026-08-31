package com.nuvio.tv.ui.screens.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.HeroCarouselBackdrop
import com.nuvio.tv.ui.components.TrailerPlayer
import com.nuvio.tv.ui.util.formatHeroRuntime
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.localizedGenreLabel
import com.nuvio.tv.ui.theme.NuvioRadii
import com.nuvio.tv.ui.theme.NuvioStrokes
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.StableList
import kotlinx.coroutines.delay

private const val TOP_HERO_ADVANCE_MS = 12_000L

@Composable
internal fun TopCyclingHeroBanner(
    items: StableList<MetaPreview>,
    trailerPreviewUrls: Map<String, String>,
    trailerPreviewAudioUrls: Map<String, String>,
    trailerEnabled: Boolean,
    trailerDelaySeconds: Int,
    trailerMuted: Boolean,
    showImdbRatings: Boolean,
    visibleAlpha: Float = 1f,
    onTopNavFocusRequest: () -> Unit = {},
    onContentFocusRequest: () -> Unit = {},
    onRequestTrailerPreview: (String, String, String?, String) -> Unit,
    onItemClick: (MetaPreview) -> Unit,
    onItemFocus: (MetaPreview) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    enabled: Boolean = true,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return

    var activeIndex by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    var trailerFinishedForId by remember { mutableStateOf<String?>(null) }
    val currentOnRequestTrailer by rememberUpdatedState(onRequestTrailerPreview)
    val currentOnItemClick by rememberUpdatedState(onItemClick)
    val currentOnItemFocus by rememberUpdatedState(onItemFocus)
    val currentOnTopNavFocusRequest by rememberUpdatedState(onTopNavFocusRequest)
    val currentOnContentFocusRequest by rememberUpdatedState(onContentFocusRequest)
    val activeItem = items.getOrNull(activeIndex.coerceIn(0, items.size - 1)) ?: return

    LaunchedEffect(items.size) {
        activeIndex = activeIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    LaunchedEffect(focused, items.size) {
        if (items.size <= 1) return@LaunchedEffect
        while (true) {
            delay(TOP_HERO_ADVANCE_MS)
            if (!focused) {
                activeIndex = (activeIndex + 1) % items.size
            }
        }
    }

    LaunchedEffect(activeItem.id, focused) {
        trailerFinishedForId = null
        if (focused) currentOnItemFocus(activeItem)
    }

    LaunchedEffect(activeItem.id, trailerEnabled, trailerDelaySeconds, visibleAlpha > 0.5f) {
        if (!trailerEnabled || visibleAlpha <= 0.5f) return@LaunchedEffect
        delay((trailerDelaySeconds.coerceAtLeast(0) * 1_000L).coerceAtLeast(250L))
        currentOnRequestTrailer(
            activeItem.id,
            activeItem.name,
            activeItem.releaseInfo,
            activeItem.apiType
        )
    }

    val trailerUrl = trailerPreviewUrls[activeItem.id]
    val trailerAudioUrl = trailerPreviewAudioUrls[activeItem.id]
    val playTrailer = trailerEnabled &&
        visibleAlpha > 0.5f &&
        !trailerUrl.isNullOrBlank() &&
        trailerFinishedForId != activeItem.id
    val shape = RoundedCornerShape(NuvioRadii.tokens.lg)
    val focusColor = NuvioTheme.colors.FocusRing
    val context = LocalContext.current
    val focusRingColor = NuvioTheme.colors.FocusRing
    val isInteractive = visibleAlpha > 0.4f

    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .focusProperties { canFocus = isInteractive }
            .focusable(isInteractive)
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                        currentOnTopNavFocusRequest()
                        true
                    }

                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                        currentOnContentFocusRequest()
                        true
                    }

                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft -> {
                        if (activeIndex > 0) activeIndex--
                        else activeIndex = items.size - 1
                        true
                    }

                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight -> {
                        if (activeIndex < items.size - 1) activeIndex++
                        else activeIndex = 0
                        true
                    }

                    event.type == KeyEventType.KeyUp &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                        currentOnItemClick(activeItem)
                        true
                    }

                    else -> false
                }
            }
    ) {
        Crossfade(
            targetState = activeIndex,
            animationSpec = tween(420),
            label = "topCyclingHero"
        ) { index ->
            val item = items.getOrNull(index) ?: return@Crossfade
            val contentType = remember(context, item.apiType) {
                localizedContentType(context, item.apiType).takeIf { it.isNotBlank() }
            }
            val genreText = remember(context, item.genres) {
                item.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { localizedGenreLabel(context, it) }
            }
            val runtimeText = remember(item.runtime) { formatHeroRuntime(item.runtime) }
            val metaLine = listOfNotNull(contentType, genreText, item.releaseInfo, runtimeText)
                .joinToString(" • ")

            Box(modifier = Modifier.fillMaxSize()) {
                HeroCarouselBackdrop(
                    item = item,
                    fullPage = true,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.84f),
                                    Color.Black.copy(alpha = 0.42f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.65f),
                                    Color.Black.copy(alpha = 0.95f)
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth(0.58f)
                        .padding(start = 52.dp, top = 96.dp, end = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!item.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = item.logo,
                            contentDescription = item.name,
                            modifier = Modifier
                                .height(96.dp)
                                .widthIn(min = 100.dp, max = 260.dp)
                                .fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart
                        )
                    } else {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = NuvioTheme.colors.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (metaLine.isNotBlank()) {
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.labelMedium,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    item.description?.takeIf { it.isNotBlank() }?.let { desc ->
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.90f),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        TrailerPlayer(
            trailerUrl = trailerUrl,
            trailerAudioUrl = trailerAudioUrl,
            isPlaying = playTrailer,
            muted = trailerMuted,
            cropToFill = true,
            onEnded = { trailerFinishedForId = activeItem.id },
            modifier = Modifier.fillMaxSize()
        )

        if (showImdbRatings && activeItem.imdbRating != null) {
            Text(
                text = String.format(java.util.Locale.US, "IMDb %.1f", activeItem.imdbRating),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = NuvioTheme.colors.Secondary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 52.dp, top = 370.dp)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 52.dp, top = 405.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(items.size.coerceAtMost(12)) { index ->
                val selected = index == activeIndex
                Box(
                    modifier = Modifier
                        .size(width = if (selected) 24.dp else 8.dp, height = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) focusRingColor else Color.White.copy(alpha = 0.38f)
                        )
                )
            }
        }
    }
}
