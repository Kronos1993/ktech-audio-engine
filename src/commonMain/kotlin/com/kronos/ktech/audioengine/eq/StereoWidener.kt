package com.kronos.ktech.audioengine.eq

import kotlin.math.roundToInt

// Mid/side stereo widening on interleaved 16-bit stereo PCM: side is scaled by (1 + width/100),
// mid is untouched. Width 0 is an exact bypass. Stateless apart from the setting.
class StereoWidener {
    private var sideGain: Float = 1f

    fun setWidthPercent(percent: Float) {
        sideGain = 1f + percent.coerceIn(0f, MAX_WIDTH_PERCENT) / 100f
    }

    val isActive: Boolean get() = sideGain != 1f

    // Processes the first [size] interleaved stereo samples in place. Only call with 2-channel audio.
    fun processInterleaved(samples: ShortArray, size: Int = samples.size) {
        if (!isActive) return
        var i = 0
        while (i + 1 < size) {
            val left = samples[i].toFloat()
            val right = samples[i + 1].toFloat()
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f * sideGain
            samples[i] = clamp(mid + side)
            samples[i + 1] = clamp(mid - side)
            i += 2
        }
    }

    private fun clamp(value: Float): Short =
        value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    companion object {
        const val MAX_WIDTH_PERCENT = 100f
    }
}
