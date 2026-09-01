package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi

/**
 * Resolves whether ExoPlayer must prepare an SDR output video graph.
 *
 * Android's HDR capabilities come from the active display/HDMI path. They are the best
 * automatic signal available, but some AVRs, splitters and TVs expose an optimistic or stale
 * EDID. [forceSdrOutput] is therefore a deliberate user override and always wins.
 *
 * Preparing the graph does not make SDR input HDR-like or tone-map it. Media3 checks the input
 * transfer characteristics and only performs HDR-to-SDR conversion for HDR input. Preparing it
 * before the renderer is enabled avoids a first-frame HDR flash and a mid-playback player rebuild.
 */
internal object VideoOutputPolicy {

    data class Decision(
        val outputSupportsHdr: Boolean,
        val prepareSdrOutputGraph: Boolean
    ) {
        fun shouldToneMapInput(inputIsHdr: Boolean): Boolean =
            prepareSdrOutputGraph && inputIsHdr
    }

    fun resolve(context: Context, forceSdrOutput: Boolean): Decision =
        decide(
            forceSdrOutput = forceSdrOutput,
            outputSupportsHdr = detectHdrOutputSupport(context)
        )

    internal fun decide(forceSdrOutput: Boolean, outputSupportsHdr: Boolean): Decision =
        Decision(
            outputSupportsHdr = outputSupportsHdr,
            prepareSdrOutputGraph = forceSdrOutput || !outputSupportsHdr
        )

    fun detectHdrOutputSupport(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return runCatching {
            activeDisplay(context)
                ?.hdrCapabilities
                ?.supportedHdrTypes
                ?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun activeDisplay(context: Context): Display? {
        val manager = context.getSystemService(DisplayManager::class.java)
        return manager?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display else null
    }
}
