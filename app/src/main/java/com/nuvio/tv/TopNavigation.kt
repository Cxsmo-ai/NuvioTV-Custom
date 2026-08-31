package com.nuvio.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.theme.NuvioRadii
import com.nuvio.tv.ui.theme.NuvioStrokes
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberDrawerItemFocusRequesters
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private val TopNavigationHeight = 52.dp
private val TopNavigationProfileSize = 42.dp
private val TopNavigationIconSize = 21.dp
private val TopNavigationVerticalInset = 22.dp
private val TopNavigationHorizontalInset = 38.dp
internal val TopNavigationSafeInset = TopNavigationVerticalInset + TopNavigationHeight + 8.dp

internal val LocalTopNavigationActive = compositionLocalOf { false }
internal val LocalTopNavigationSafeInset = compositionLocalOf { 0.dp }
internal val LocalTopNavigationFocused = compositionLocalOf { false }
internal val LocalTopHeroFocusRequester = compositionLocalOf { FocusRequester() }
internal val LocalTopNavFocusRequester = compositionLocalOf<FocusRequester?> { null }

@Composable
internal fun TopNavigationScaffold(
    navController: NavHostController,
    navViewModelStoreOwner: ViewModelStoreOwner,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    onExitApp: () -> Unit
) {
    val showTopNavigation = currentRoute in rootRoutes
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val contentFocusRequester = remember { FocusRequester() }
    val topHeroFocusRequester = remember { FocusRequester() }
    val topNavigationHazeState = remember { HazeState() }
    val routeFocusRequesters = rememberDrawerItemFocusRequesters(drawerItems)
    var topNavigationHasFocus by remember { mutableStateOf(false) }
    var pendingTopNavigationFocus by remember { mutableStateOf(false) }

    val selectedFocusRequester = (
        selectedDrawerRoute ?: drawerItems.firstOrNull()?.route
    )?.let(routeFocusRequesters::get) ?: routeFocusRequesters.values.firstOrNull()

    LaunchedEffect(showTopNavigation) {
        if (!showTopNavigation) {
            topNavigationHasFocus = false
            pendingTopNavigationFocus = false
        }
    }

    LaunchedEffect(pendingTopNavigationFocus, showTopNavigation, selectedDrawerRoute) {
        if (!showTopNavigation || !pendingTopNavigationFocus) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        selectedFocusRequester?.let { runCatching { it.requestFocus() } }
        pendingTopNavigationFocus = false
    }

    BackHandler(enabled = showTopNavigation && !topNavigationHasFocus) {
        pendingTopNavigationFocus = true
    }
    BackHandler(enabled = showTopNavigation && topNavigationHasFocus) {
        onExitApp()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = when {
                        !showTopNavigation || currentRoute == Screen.Home.route -> 0.dp
                        currentRoute == Screen.Settings.route -> 16.dp
                        else -> TopNavigationSafeInset
                    }
                )
                .haze(topNavigationHazeState)
        ) {
            CompositionLocalProvider(
                LocalSidebarExpanded provides false,
                LocalContentFocusRequester provides contentFocusRequester,
                LocalTopHeroFocusRequester provides topHeroFocusRequester,
                LocalTopNavFocusRequester provides selectedFocusRequester,
                LocalViewModelStoreOwner provides navViewModelStoreOwner,
                LocalTopNavigationActive provides true,
                LocalTopNavigationSafeInset provides TopNavigationSafeInset,
                LocalTopNavigationFocused provides topNavigationHasFocus
            ) {
                NuvioNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    hideBuiltInHeaders = true
                )
            }
        }

        if (showTopNavigation) {
            TopNavigationBar(
                drawerItems = drawerItems,
                selectedDrawerRoute = selectedDrawerRoute,
                currentRoute = currentRoute,
                routeFocusRequesters = routeFocusRequesters,
                contentFocusRequester = contentFocusRequester,
                activeProfileName = activeProfileName,
                activeProfileColorHex = activeProfileColorHex,
                activeProfileAvatarImageUrl = activeProfileAvatarImageUrl,
                showProfileSelector = showProfileSelector,
                onTopNavigationFocusChanged = { topNavigationHasFocus = it },
                onSwitchProfile = onSwitchProfile,
                onDrawerItemClick = { targetRoute ->
                    keyboardController?.hide()
                    onNavigate(targetRoute)
                    navigateToDrawerRoute(
                        navController = navController,
                        currentRoute = currentRoute,
                        targetRoute = targetRoute
                    )
                },
                topHeroFocusRequester = topHeroFocusRequester,
                hazeState = topNavigationHazeState,
                onExitApp = onExitApp,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
private fun TopNavigationBar(
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    currentRoute: String?,
    routeFocusRequesters: Map<String, FocusRequester>,
    contentFocusRequester: FocusRequester,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onTopNavigationFocusChanged: (Boolean) -> Unit,
    onSwitchProfile: () -> Unit,
    onDrawerItemClick: (String) -> Unit,
    topHeroFocusRequester: FocusRequester,
    hazeState: HazeState,
    onExitApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val settingsItem = drawerItems.firstOrNull { it.route == Screen.Settings.route }
    val primaryItems = drawerItems.filterNot { it.route == Screen.Settings.route }
    val clockText by rememberClockText()
    val colors = NuvioTheme.colors
    val panelShape = RoundedCornerShape(NuvioRadii.tokens.full)
    val panelBrush = remember(colors) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.16f),
                colors.Secondary.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.035f)
            )
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TopNavigationHorizontalInset, vertical = TopNavigationVerticalInset)
            .onFocusChanged { onTopNavigationFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { keyEvent ->
                when (keyEvent.key) {
                    Key.DirectionDown -> {
                        // Keep the whole physical press on this level. Moving
                        // focus on key-down lets repeats/key-up arrive at the
                        // newly focused row and can skip a level on Android TV.
                        if (keyEvent.type == KeyEventType.KeyUp) {
                            if (currentRoute == Screen.Home.route) {
                                contentFocusRequester.requestFocus()
                            } else {
                                // Non-home roots do not attach the home content
                                // requester. Let Compose move to the nearest
                                // focusable control below this glass rail.
                                val moved = focusManager.moveFocus(FocusDirection.Down)
                                if (!moved) {
                                    runCatching { contentFocusRequester.requestFocus() }
                                }
                            }
                        }
                        // Consume key-down, repeats, and key-up; transition once
                        // only after the physical press is complete.
                        true
                    }

                    Key.DirectionUp -> true
                    else -> false
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showProfileSelector && activeProfileName.isNotEmpty()) {
            TopProfileButton(
                profileName = activeProfileName,
                profileColorHex = activeProfileColorHex,
                profileAvatarImageUrl = activeProfileAvatarImageUrl,
                onClick = onSwitchProfile
            )
            Spacer(modifier = Modifier.width(16.dp))
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .height(TopNavigationHeight)
                .clip(panelShape)
                .hazeChild(
                    state = hazeState,
                    shape = panelShape,
                    tint = Color.Unspecified,
                    blurRadius = 22.dp,
                    noiseFactor = 0.055f
                )
                .background(brush = panelBrush, shape = panelShape)
                .border(
                    width = NuvioStrokes.tokens.hairline,
                    color = Color.White.copy(alpha = 0.34f),
                    shape = panelShape
                )
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            primaryItems.forEach { item ->
                TopNavigationItem(
                    item = item,
                    selected = selectedDrawerRoute == item.route,
                    modifier = Modifier.focusRequester(routeFocusRequesters.getValue(item.route)),
                    onClick = { onDrawerItemClick(item.route) }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            settingsItem?.let { item ->
                TopNavigationItem(
                    item = item,
                    selected = selectedDrawerRoute == item.route,
                    iconOnly = true,
                    modifier = Modifier.focusRequester(routeFocusRequesters.getValue(item.route)),
                    onClick = { onDrawerItemClick(item.route) }
                )
            }
            TopUtilityButton(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = stringResource(R.string.action_exit_app),
                onClick = onExitApp
            )
        }

        Spacer(modifier = Modifier.width(20.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(NuvioRadii.tokens.full))
                .background(Color.Black.copy(alpha = 0.48f))
                .border(
                    width = NuvioStrokes.tokens.hairline,
                    color = Color.White.copy(alpha = 0.30f),
                    shape = RoundedCornerShape(NuvioRadii.tokens.full)
                )
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = clockText,
                color = Color.White,
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .width(NuvioStrokes.tokens.hairline)
                    .background(Color.White.copy(alpha = 0.48f))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                color = Color.White,
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TopProfileButton(
    profileName: String,
    profileColorHex: String,
    profileAvatarImageUrl: String?,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    Card(
        onClick = onClick,
        modifier = Modifier
            .size(TopNavigationProfileSize)
            .onFocusChanged { focused = it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = colors.text.onOverlay.copy(alpha = 0.16f)
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(
                    NuvioStrokes.tokens.thin,
                    colors.text.onOverlay.copy(alpha = 0.9f)
                ),
                shape = CircleShape
            )
        ),
        shape = CardDefaults.shape(shape = CircleShape)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            ProfileAvatarCircle(
                name = profileName,
                colorHex = profileColorHex,
                size = if (focused) 37.dp else 35.dp,
                avatarImageUrl = profileAvatarImageUrl,
                imageCrossfade = false
            )
        }
    }
}

@Composable
private fun TopNavigationItem(
    item: DrawerItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    iconOnly: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(NuvioRadii.tokens.full)
    val backgroundColor by animateColorAsState(
        targetValue = when {
            focused -> Color.White.copy(alpha = 0.28f)
            selected -> colors.Secondary.copy(alpha = 0.20f)
            else -> Color.Transparent
        },
        animationSpec = tween(NuvioMotion.tokens.durations.fast),
        label = "topNavigationItemBackground"
    )
    val contentColor = colors.text.onOverlay

    Card(
        onClick = onClick,
        modifier = modifier
            .height(42.dp)
            .onFocusChanged { focused = it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = backgroundColor,
            focusedContainerColor = backgroundColor
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(
                    NuvioStrokes.tokens.thin,
                    colors.text.onOverlay.copy(alpha = 0.75f)
                ),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape = shape)
    ) {
        Row(
            modifier = Modifier
                .height(42.dp)
                .padding(horizontal = if (iconOnly) 12.dp else 17.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconOnly) {
                TopNavigationIcon(
                    iconRes = item.iconRes,
                    icon = item.icon,
                    tint = contentColor
                )
            } else {
                Text(
                    text = item.label,
                    color = contentColor,
                    style = androidx.tv.material3.MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TopUtilityButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(NuvioRadii.tokens.full)
    val background by animateColorAsState(
        targetValue = if (focused) colors.text.onOverlay.copy(alpha = 0.18f) else Color.Transparent,
        animationSpec = tween(NuvioMotion.tokens.durations.fast),
        label = "topUtilityBackground"
    )
    Card(
        onClick = onClick,
        modifier = Modifier
            .size(42.dp)
            .onFocusChanged { focused = it.hasFocus },
        colors = CardDefaults.colors(
            containerColor = background,
            focusedContainerColor = background
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(
                    NuvioStrokes.tokens.thin,
                    colors.text.onOverlay.copy(alpha = 0.75f)
                ),
                shape = shape
            )
        ),
        shape = CardDefaults.shape(shape = shape)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                tint = colors.text.onOverlay,
                modifier = Modifier.size(TopNavigationIconSize)
            )
        }
    }
}

@Composable
private fun TopNavigationIcon(
    iconRes: Int?,
    icon: ImageVector?,
    tint: Color
) {
    when {
        icon != null -> Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TopNavigationIconSize)
        )

        iconRes != null -> Icon(
            painter = rememberTopRawSvgPainter(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(TopNavigationIconSize)
        )
    }
}

@Composable
private fun rememberTopRawSvgPainter(rawIconRes: Int): Painter {
    return rememberAsyncImagePainter(
        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data(rawIconRes)
            .size(64)
            .build()
    )
}

@Composable
private fun rememberClockText(): androidx.compose.runtime.State<String> {
    val formatter = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    return produceState(initialValue = formatter.format(Date())) {
        while (true) {
            val now = System.currentTimeMillis()
            delay(60_000L - (now % 60_000L))
            value = formatter.format(Date())
        }
    }
}
