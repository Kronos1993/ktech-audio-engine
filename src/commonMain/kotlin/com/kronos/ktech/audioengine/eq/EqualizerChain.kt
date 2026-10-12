package com.kronos.ktech.audioengine.eq

import com.kronos.ktech.audioengine.domain.EqualizerBands
import kotlin.math.pow
import kotlin.math.roundToInt

// Preamp, cascaded biquad peaking filters (one per EqualizerBands.FREQUENCIES_HZ entry), then a
// bass-boost low shelf - shared by the Android custom AudioProcessor and the Desktop decode loop,
// so both platforms produce identical output for the same settings (iOS uses AVAudioUnitEQ
// directly instead, not this class - see PlayerEngine.ios.kt). Not thread-safe by design: each
// PlayerEngine actual owns its instances and only ever calls them from its own playback
// thread/coroutine.
class EqualizerChain {
    private val filters: List<BiquadFilter> = List(EqualizerBands.COUNT) { BiquadFilter() }
    private val bassShelf = BiquadFilter(type = BiquadFilter.Type.LOW_SHELF)
    private var gainsDb: FloatArray = FloatArray(EqualizerBands.COUNT)
    private var preampLinear: Float = 1f
    private var bassBoostDb: Float = 0f
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

    fun setPreampDb(db: Float) {
        preampLinear = if (db == 0f) 1f else 10.0.pow(db / 20.0).toFloat()
    }

    fun setBassBoostDb(db: Float) {
        bassBoostDb = db
    }

    // Runs one 16-bit PCM sample through the preamp, all 10 bands and the bass shelf in series.
    // Returns the input unchanged (zero-cost) when disabled.
    fun processSample(sampleRateHz: Int, input: Short): Short {
        if (!enabled) return input
        var value = input.toFloat() * preampLinear
        for (i in filters.indices) {
            filters[i].updateCoefficients(EqualizerBands.FREQUENCIES_HZ[i], sampleRateHz, gainsDb[i])
            value = filters[i].process(value)
        }
        bassShelf.updateCoefficients(BASS_SHELF_HZ, sampleRateHz, bassBoostDb)
        value = bassShelf.process(value)
        if (!value.isFinite()) {
            // Never throw from the audio thread: drop the diverged filter state and pass through.
            reset()
            return input
        }
        return value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    fun reset() {
        filters.forEach { it.reset() }
        bassShelf.reset()
    }

    companion object {
        const val BASS_SHELF_HZ = 100
    }
}
