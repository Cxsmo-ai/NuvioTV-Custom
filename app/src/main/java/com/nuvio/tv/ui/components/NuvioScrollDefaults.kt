package com.nuvio.tv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import com.nuvio.tv.ui.theme.NuvioFocus

internal const val RESPONSIVE_SCROLL_STIFFNESS = 700f

@OptIn(ExperimentalFoundationApi::class)
object NuvioScrollDefaults {
    val smoothScrollSpec = object : BringIntoViewSpec {
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override val scrollAnimationSpec: AnimationSpec<Float> = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = RESPONSIVE_SCROLL_STIFFNESS
        )

        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float
        ): Float {
            if (containerSize <= 0f || size <= 0f) return 0f
            val itemCenter = offset + size / 2f
            val viewportTarget = containerSize * NuvioFocus.tokens.scrollViewportTarget
            return itemCenter - viewportTarget
        }
    }
}
