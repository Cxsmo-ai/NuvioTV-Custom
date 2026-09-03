package com.nuvio.tv.core.player.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * A one-sample, one-pass Android port of aston89's Smart Vibrance Plus shader.
 *
 * The ReShade Plus variant is the self-contained reference implementation: it
 * adaptively boosts low-chroma pixels while rolling off the effect for already
 * saturated colors and smoothly protecting near-neutral detail. The PotPlayer
 * Plus variant also declares a host-provided previous-frame constant, but does
 * not update that state itself. Media3 intentionally uses the deployable
 * single-pass Plus core here so playback never needs CPU readback, an extra
 * frame buffer, or a second render pass.
 *
 * Upstream:
 * https://github.com/aston89/Smart-vibrance-for-reshade
 * https://github.com/aston89/Smart-Vibrance-for-PotPlayer
 *
 * The upstream work and this port are GPL-3.0.
 */
class SmartVibrancePlusEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return SinglePassShaderProgram(
            useHdr = useHdr,
            fragmentShader = if (useHdr) {
                SMART_VIBRANCE_PLUS_HDR_FRAGMENT_SHADER
            } else {
                SMART_VIBRANCE_PLUS_FRAGMENT_SHADER
            }
        )
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

/**
 * Keeps Media3's playback graph active so its output can be explicitly SDR.
 * The effect reports itself as a no-op, allowing Media3 to omit a redundant
 * color pass while retaining the graph's HDR-to-SDR output conversion.
 */
class ForceSdrOutputEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        SinglePassShaderProgram(useHdr, FORCE_SDR_PASSTHROUGH_FRAGMENT_SHADER)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = true
}

enum class SmartVibranceRuntimeStatus {
    OFF,
    WAITING_FOR_VIDEO,
    ACTIVE,
    ACTIVE_NATIVE_HDR,
    ACTIVE_TONEMAPPED_HDR,
    BYPASSED_HDR,
    BYPASSED_MPV,
    FAILED
}

internal fun shouldApplySmartVibrance(status: SmartVibranceRuntimeStatus): Boolean =
    status == SmartVibranceRuntimeStatus.ACTIVE ||
        status == SmartVibranceRuntimeStatus.ACTIVE_NATIVE_HDR ||
        status == SmartVibranceRuntimeStatus.ACTIVE_TONEMAPPED_HDR

/**
 * HDR10/HLG is processed in Media3's high-precision linear working buffer. The
 * configured output policy remains responsible for either preserving HDR or
 * tone-mapping it to SDR after this effect. Native Dolby Vision stays on its
 * decoder/display path because routing DV frames through a generic GL effect
 * cannot preserve the RPU-driven presentation. DV converted or stripped to an
 * HDR10 base layer is reported as HEVC and is therefore supported here.
 */
internal fun supportsSmartVibrancePlus(format: Format?): Boolean {
    if (format == null) return false
    if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return false
    val codecs = format.codecs.orEmpty().lowercase()
    if (codecs.contains("dvhe") || codecs.contains("dvh1")) return false
    return true
}

internal const val SMART_VIBRANCE_PLUS_VERTEX_SHADER = """
    #version 100
    attribute vec4 aFramePosition;
    uniform mat4 uTransformationMatrix;
    uniform mat4 uTexTransformationMatrix;
    varying vec2 vTexSamplingCoord;

    void main() {
        gl_Position = uTransformationMatrix * aFramePosition;
        vec4 texturePosition = vec4(
            aFramePosition.x * 0.5 + 0.5,
            aFramePosition.y * 0.5 + 0.5,
            0.0,
            1.0
        );
        vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
    }
"""

internal const val SMART_VIBRANCE_PLUS_FRAGMENT_SHADER = """
    #version 100
    precision highp float;

    uniform sampler2D uTexSampler;
    varying vec2 vTexSamplingCoord;

    const float kIntensity = 1.5;
    const float kSatPivot = 0.5;
    const float kGrayPivot = 0.003;
    const float kGraySharpness = 45.0;

    float sigmoid(float value) {
        return 1.0 / (1.0 + exp(-value));
    }

    void main() {
        vec4 inputColor = texture2D(uTexSampler, vTexSamplingCoord);
        vec3 color = inputColor.rgb;

        float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
        vec3 chroma = color - vec3(luminance);
        float chromaEnergy = dot(chroma, chroma);
        float chromaMagnitude = sqrt(max(chromaEnergy, 0.0));

        float normalizedSaturation = clamp(chromaMagnitude / kSatPivot, 0.0, 1.0);
        float rolloff = 1.0 - normalizedSaturation;
        float graySoft = sigmoid((kGrayPivot - chromaEnergy) * kGraySharpness);
        float response = mix(rolloff, 1.0, graySoft);
        float gain = (kIntensity - 1.0) * response;

        vec3 outputColor = clamp(
            vec3(luminance) + chroma + chroma * gain,
            0.0,
            1.0
        );
        gl_FragColor = vec4(outputColor, inputColor.a);
    }
"""

internal const val SMART_VIBRANCE_PLUS_HDR_FRAGMENT_SHADER = """
    #version 100
    precision highp float;

    uniform sampler2D uTexSampler;
    varying vec2 vTexSamplingCoord;

    const float kIntensity = 1.5;
    const float kSatPivot = 0.5;
    const float kGrayPivot = 0.003;
    const float kGraySharpness = 45.0;

    float sigmoid(float value) {
        return 1.0 / (1.0 + exp(-value));
    }

    void main() {
        vec4 inputColor = texture2D(uTexSampler, vTexSamplingCoord);

        // Media3 supplies HDR effects with linear BT.2020 values. Move into a
        // perceptual working space for the upstream SDR-domain vibrance math,
        // then return to linear extended range for Media3/display tone mapping.
        vec3 workingColor = pow(max(inputColor.rgb, vec3(0.0)), vec3(1.0 / 2.2));
        float luminance = dot(workingColor, vec3(0.2627, 0.6780, 0.0593));
        vec3 chroma = workingColor - vec3(luminance);
        float chromaEnergy = dot(chroma, chroma);
        float chromaMagnitude = sqrt(max(chromaEnergy, 0.0));

        float normalizedSaturation = clamp(chromaMagnitude / kSatPivot, 0.0, 1.0);
        float rolloff = 1.0 - normalizedSaturation;
        float graySoft = sigmoid((kGrayPivot - chromaEnergy) * kGraySharpness);
        float response = mix(rolloff, 1.0, graySoft);
        float gain = (kIntensity - 1.0) * response;

        vec3 adjustedWorkingColor = max(
            vec3(luminance) + chroma + chroma * gain,
            vec3(0.0)
        );
        vec3 outputColor = pow(adjustedWorkingColor, vec3(2.2));
        gl_FragColor = vec4(outputColor, inputColor.a);
    }
"""

internal const val FORCE_SDR_PASSTHROUGH_FRAGMENT_SHADER = """
    #version 100
    precision highp float;

    uniform sampler2D uTexSampler;
    varying vec2 vTexSamplingCoord;

    void main() {
        gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
    }
"""

private class SinglePassShaderProgram(
    useHdr: Boolean,
    fragmentShader: String
) :
    BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ useHdr,
        /* texturePoolCapacity = */ 1
    ) {

    private val glProgram: GlProgram = try {
        GlProgram(
            SMART_VIBRANCE_PLUS_VERTEX_SHADER,
            fragmentShader
        ).apply {
            setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
            )
            val identityMatrix = GlUtil.create4x4IdentityMatrix()
            setFloatsUniform("uTransformationMatrix", identityMatrix)
            setFloatsUniform("uTexTransformationMatrix", identityMatrix)
        }
    } catch (error: GlUtil.GlException) {
        throw VideoFrameProcessingException(error)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size =
        Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform(
                "uTexSampler",
                inputTexId,
                /* texUnitIndex = */ 0
            )
            glProgram.bindAttributesAndUniforms()
            // Match Media3's own one-pass programs: draw without polling the
            // context-wide GL error flag. A stale driver error from another
            // stage must not suppress publication of an otherwise valid frame.
            GLES20.glDrawArrays(
                GLES20.GL_TRIANGLE_STRIP,
                /* first = */ 0,
                /* count = */ 4
            )
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error, presentationTimeUs)
        }
    }

    override fun release() {
        var releaseFailure: VideoFrameProcessingException? = null
        try {
            super.release()
        } catch (error: VideoFrameProcessingException) {
            releaseFailure = error
        }
        try {
            glProgram.delete()
        } catch (error: GlUtil.GlException) {
            if (releaseFailure == null) {
                releaseFailure = VideoFrameProcessingException(error)
            }
        }
        releaseFailure?.let { throw it }
    }
}
