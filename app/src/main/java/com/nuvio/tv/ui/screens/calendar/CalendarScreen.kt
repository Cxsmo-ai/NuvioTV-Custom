package com.nuvio.tv.ui.screens.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalTopNavigationActive
import com.nuvio.tv.LocalTopNavigationSafeInset
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CalendarDay
import com.nuvio.tv.domain.model.CalendarEpisode
import com.nuvio.tv.domain.model.CalendarFilter
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioPrimitives
import com.nuvio.tv.ui.theme.NuvioRadii
import com.nuvio.tv.ui.theme.NuvioStrokes
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val CalendarCardWidth = 276.dp
private val CalendarCardHeight = 156.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CalendarScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?, returnFocusSeason: Int?, returnFocusEpisode: Int?) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val contentFocusRequester = LocalContentFocusRequester.current
    val topNavActive = LocalTopNavigationActive.current
    val topSafeInset = LocalTopNavigationSafeInset.current
    val colors = NuvioTheme.colors

    val verticalListState = rememberLazyListState()
    val firstCardFocusRequester = remember { FocusRequester() }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.filteredDays) {
        if (uiState.filteredDays.isNotEmpty() && !hasRequestedInitialFocus) {
            hasRequestedInitialFocus = true
            // Auto-scroll to today or the first upcoming date if available
            val today = LocalDate.now()
            val targetIndex = uiState.filteredDays.indexOfFirst { !it.date.isBefore(today) }
            if (targetIndex > 0) {
                verticalListState.scrollToItem(targetIndex)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background)
            .padding(
                top = if (topNavActive) topSafeInset else 0.dp,
                start = 0.dp,
                end = 0.dp,
                bottom = 0.dp
            )
    ) {
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator(modifier = Modifier.size(48.dp))
                }
            }

            uiState.filteredDays.isEmpty() && !uiState.isRefreshing -> {
                EmptyCalendarState(
                    selectedFilter = uiState.selectedFilter,
                    onFilterSelect = viewModel::setFilter,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier
                        .fillMaxSize()
                )
            }

            else -> {
                LazyColumn(
                    state = verticalListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(contentFocusRequester),
                    contentPadding = PaddingValues(bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    item(key = "calendar_header") {
                        CalendarHeader(
                            selectedFilter = uiState.selectedFilter,
                            isRefreshing = uiState.isRefreshing,
                            onFilterSelect = viewModel::setFilter,
                            onRefresh = viewModel::refresh,
                            showTitle = showBuiltInHeader && !topNavActive,
                            modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp)
                        )
                    }

                    itemsIndexed(
                        items = uiState.filteredDays,
                        key = { _, day -> "day_" + day.date.toString() }
                    ) { dayIndex, day ->
                        CalendarDaySection(
                            day = day,
                            onEpisodeClick = { episode ->
                                onNavigateToDetail(
                                    episode.showId,
                                    "series",
                                    episode.sourceAddonBaseUrl,
                                    episode.seasonNumber,
                                    episode.episodeNumber
                                )
                            },
                            firstCardFocusRequester = if (dayIndex == 0) firstCardFocusRequester else null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarHeader(
    selectedFilter: CalendarFilter,
    isRefreshing: Boolean,
    onFilterSelect: (CalendarFilter) -> Unit,
    onRefresh: () -> Unit,
    showTitle: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = NuvioTheme.colors
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (showTitle) {
            Text(
                text = stringResource(R.string.calendar_title),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CalendarFilterChip(
                label = stringResource(R.string.calendar_filter_all),
                selected = selectedFilter == CalendarFilter.ALL,
                onClick = { onFilterSelect(CalendarFilter.ALL) }
            )
            CalendarFilterChip(
                label = stringResource(R.string.calendar_filter_upcoming),
                selected = selectedFilter == CalendarFilter.UPCOMING,
                onClick = { onFilterSelect(CalendarFilter.UPCOMING) }
            )
            CalendarFilterChip(
                label = stringResource(R.string.calendar_filter_past),
                selected = selectedFilter == CalendarFilter.PAST,
                onClick = { onFilterSelect(CalendarFilter.PAST) }
            )

            Spacer(modifier = Modifier.weight(1f))

            if (isRefreshing) {
                LoadingIndicator(modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(NuvioRadii.tokens.full)

    val containerColor = when {
        focused -> Color.White.copy(alpha = 0.28f)
        selected -> colors.Secondary.copy(alpha = 0.22f)
        else -> Color.White.copy(alpha = 0.08f)
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .height(36.dp)
            .onFocusChanged { focused = it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = containerColor
        ),
        border = CardDefaults.border(
            border = if (selected) Border(border = BorderStroke(1.dp, colors.Secondary.copy(alpha = 0.65f))) else Border.None,
            focusedBorder = Border(border = BorderStroke(2.dp, Color.White))
        ),
        shape = CardDefaults.shape(shape = shape)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (selected || focused) Color.White else Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium
                )
            )
        }
    }
}

@Composable
private fun CalendarDaySection(
    day: CalendarDay,
    onEpisodeClick: (CalendarEpisode) -> Unit,
    firstCardFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier
) {
    val colors = NuvioTheme.colors
    val formattedDate = remember(day.date) { formatCalendarDateHeader(day.date) }
    val relativeTag = remember(day.date, day.episodes.size) { formatCalendarRelativeTag(day.date, day.episodes.size) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Date Header matching screenshot: "| Friday, August 28 past • 4 episodes"
        Row(
            modifier = Modifier.padding(horizontal = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Blue/amber vertical accent bar
            Box(
                modifier = Modifier
                    .height(22.dp)
                    .width(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.Secondary)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = formattedDate,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = relativeTag,
                color = Color.White.copy(alpha = 0.52f),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.sp
                )
            )
        }

        // Horizontal episode cards
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(
                items = day.episodes,
                key = { _, ep -> "ep_" + ep.showId + "_" + ep.seasonNumber + "_" + ep.episodeNumber }
            ) { index, episode ->
                CalendarEpisodeCard(
                    episode = episode,
                    onClick = { onEpisodeClick(episode) },
                    focusRequester = if (index == 0) firstCardFocusRequester else null
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarEpisodeCard(
    episode: CalendarEpisode,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val cardShape = RoundedCornerShape(10.dp)

    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1.0f,
        animationSpec = tween(durationMillis = 180),
        label = "calendarCardScale"
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .width(CalendarCardWidth)
            .height(CalendarCardHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = Color(0xFF141418),
            focusedContainerColor = Color(0xFF1C1C24)
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(3.dp, Color(0xFFE5A00D)), // Glowing amber/gold border on focus
                shape = cardShape
            )
        ),
        shape = CardDefaults.shape(shape = cardShape)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Episode thumbnail / Show backdrop
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(episode.backdropUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = episode.showTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Dark gradient scrim from top to bottom
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.40f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f),
                                Color.Black.copy(alpha = 0.96f)
                            )
                        )
                    )
            )

            // Top row: Show Title & Watched checkmark
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .align(Alignment.TopStart),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = episode.showTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (episode.isWatched) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2E7D32)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.episodes_cd_watched),
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Bottom row: Amber Pill (S01E06) + Episode Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .align(Alignment.BottomStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Amber capsule pill: "S01E06"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFE5A00D))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = episode.episodeCode,
                        color = Color(0xFF1E1400),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                        )
                    )
                }

                // Episode name
                Text(
                    text = episode.episodeTitle,
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun EmptyCalendarState(
    selectedFilter: CalendarFilter,
    onFilterSelect: (CalendarFilter) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DateRange,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(56.dp)
            )

            Text(
                text = stringResource(R.string.calendar_empty_title),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )

            Text(
                text = stringResource(R.string.calendar_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.60f)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CalendarFilterChip(
                    label = stringResource(R.string.calendar_filter_all),
                    selected = selectedFilter == CalendarFilter.ALL,
                    onClick = { onFilterSelect(CalendarFilter.ALL) }
                )
                CalendarFilterChip(
                    label = stringResource(R.string.calendar_refresh),
                    selected = false,
                    onClick = onRefresh
                )
            }
        }
    }
}

private fun formatCalendarDateHeader(date: LocalDate): String {
    val today = LocalDate.now()
    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
    return when {
        date == today -> "Today, " + date.format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()))
        date == today.plusDays(1) -> "Tomorrow, " + date.format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()))
        date == today.minusDays(1) -> "Yesterday, " + date.format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()))
        else -> date.format(formatter)
    }
}

private fun formatCalendarRelativeTag(date: LocalDate, episodeCount: Int): String {
    val today = LocalDate.now()
    val daysDiff = ChronoUnit.DAYS.between(today, date)
    val epText = if (episodeCount == 1) "1 episode" else "$episodeCount episodes"

    return when {
        daysDiff < 0 -> "past • " + epText
        daysDiff == 0L -> "today • " + epText
        daysDiff == 1L -> "tomorrow • " + epText
        daysDiff in 2..7 -> "in " + daysDiff + " days • " + epText
        else -> epText
    }
}
