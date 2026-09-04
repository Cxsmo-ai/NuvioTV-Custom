package com.nuvio.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex

val LocalAppDimPercent = compositionLocalOf { 0 }

private const val APP_DIMMER_Z_INDEX = 9999f

/**
 * Full app dimmer overlay.
 *
 * A pure GPU color pass drawn above the view/screen hierarchy.
 * It does not intercept touch, mouse, or D-pad events.
 */
@Composable
fun AppDimmerOverlay(
    dimPercent: Int,
    modifier: Modifier = Modifier
) {
    if (dimPercent <= 0) return
    val alpha = (dimPercent.coerceIn(0, 95) / 100f)
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .zIndex(APP_DIMMER_Z_INDEX)
    ) {
        drawRect(Color.Black.copy(alpha = alpha))
    }
}
