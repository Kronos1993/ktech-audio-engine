package com.kronos.ktech.audioengine.eq

import com.kronos.ktech.audioengine.domain.EqualizerBands
import kotlin.math.roundToInt

// Cascaded biquad peaking filters, one per EqualizerBands.FREQUENCIES_HZ entry - shared by the
// Android custom AudioProcessor and the Desktop decode loop, so both platforms produce
// identical output for the same gains (iOS uses AVAudioUnitEQ directly instead, not this
// class - see PlayerEngine.ios.kt). Not thread-safe by design: each PlayerEngine actual owns
// exactly one instance and only ever calls it from its own playback thread/coroutine.
class EqualizerChain {
    private val filters: List<BiquadFilter> = List(EqualizerBands.COUNT) { BiquadFilter() }
    private var gainsDb: FloatArray = FloatArray(EqualizerBands.COUNT)
    private var enabled: Boolean = false

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun setBandGains(newGainsDb: FloatArray) {
        require(newGainsDb.size == EqualizerBands.COUNT) {
            "Expected ${EqualizerBands.COUNT} band gains, got ${newGainsDb.size}"
        }
        gainsDb = newGainsDb.copyOf()
    }

    // Runs one 16-bit PCM sample through all 10 bands in series. Returns the input unchanged
    // (zero-cost) when disabled.
    fun processSample(sampleRateHz: Int, input: Short): Short {
        if (!enabled) return input
        var value = input.toFloat()
        for (i in filters.indices) {
            filters[i].updateCoefficients(EqualizerBands.FREQUENCIES_HZ[i], sampleRateHz, gainsDb[i])
            value = filters[i].process(value)
        }
        return value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    fun reset() {
        filters.forEach { it.reset() }
    }
}
