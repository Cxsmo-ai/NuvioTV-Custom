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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.ViewModelStore
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
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private val TopNavigationHeight = 52.dp
private val TopNavigationProfileSize = 42.dp
private val TopNavigationIconSize = 21.dp
private val TopNavigationVerticalInset = 22.dp
private val TopNavigationHorizontalInset = 38.dp

@Composable
internal fun TopNavigationScaffold(
    navController: NavHostController,
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
                    top = if (showTopNavigation && currentRoute != Screen.Home.route) {
                        TopNavigationHeight + TopNavigationVerticalInset + 18.dp
                    } else {
                        0.dp
                    }
                )
                .onKeyEvent { keyEvent ->
                    if (
                        showTopNavigation &&
                        !topNavigationHasFocus &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        keyEvent.key == Key.DirectionUp
                    ) {
                        if (focusManager.moveFocus(FocusDirection.Up)) {
                            true
                        } else {
                            selectedFocusRequester?.requestFocus() == true
                        }
                    } else {
                        false
                    }
                }
        ) {
            val navViewModelStoreOwner = remember {
                object : ViewModelStoreOwner {
                    override val viewModelStore: ViewModelStore = ViewModelStore()
                }
            }
            DisposableEffect(navViewModelStoreOwner) {
                onDispose { navViewModelStoreOwner.viewModelStore.clear() }
            }
            CompositionLocalProvider(
                LocalSidebarExpanded provides false,
                LocalContentFocusRequester provides contentFocusRequester,
                LocalViewModelStoreOwner provides navViewModelStoreOwner
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
    routeFocusRequesters: Map<String, FocusRequester>,
    contentFocusRequester: FocusRequester,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onTopNavigationFocusChanged: (Boolean) -> Unit,
    onSwitchProfile: () -> Unit,
    onDrawerItemClick: (String) -> Unit,
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
                colors.media.glassPanelTop.copy(alpha = 0.74f),
                colors.media.glassPanelMiddle.copy(alpha = 0.68f),
                colors.media.glassPanelBottom.copy(alpha = 0.72f)
            )
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TopNavigationHorizontalInset, vertical = TopNavigationVerticalInset)
            .onFocusChanged { onTopNavigationFocusChanged(it.hasFocus) }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyEvent.key) {
                    Key.DirectionDown -> {
                        if (focusManager.moveFocus(FocusDirection.Down)) {
                            true
                        } else {
                            contentFocusRequester.requestFocus()
                        }
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
                .background(brush = panelBrush, shape = panelShape)
                .border(
                    width = NuvioStrokes.tokens.hairline,
                    color = colors.text.onOverlay.copy(alpha = 0.15f),
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

        Spacer(modifier = Modifier.width(30.dp))
        Text(
            text = clockText,
            color = colors.text.onOverlay,
            style = androidx.tv.material3.MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold
            )
        )
        Spacer(modifier = Modifier.width(14.dp))
        Box(
            modifier = Modifier
                .height(20.dp)
                .width(NuvioStrokes.tokens.hairline)
                .background(colors.text.onOverlay.copy(alpha = 0.35f))
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = stringResource(R.string.app_name),
            color = colors.Secondary,
            style = androidx.tv.material3.MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            maxLines = 1
        )
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
            selected -> colors.text.onOverlay.copy(alpha = 0.9f)
            focused -> colors.text.onOverlay.copy(alpha = 0.18f)
            else -> Color.Transparent
        },
        animationSpec = tween(NuvioMotion.tokens.durations.fast),
        label = "topNavigationItemBackground"
    )
    val contentColor = if (selected) colors.Background else colors.text.onOverlay

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
