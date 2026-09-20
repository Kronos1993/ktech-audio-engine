package com.kronos.ktech.audioengine.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// A single peaking-EQ biquad, RBJ Audio EQ Cookbook coefficients (https://www.w3.org/TR/audio-eq-cookbook/,
// peakingEQ section - a standard, publicly documented DSP formula, not derived from any one
// product's implementation), Direct Form II Transpose. Shared by every platform's real-EQ
// integration so Android/Desktop/iOS all shape the same frequency the same way.
class BiquadFilter(private val q: Float = 1.0f) {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    private var z1 = 0f
    private var z2 = 0f

    private var lastFrequencyHz = -1
    private var lastSampleRateHz = -1
    private var lastGainDb = 0f

    // Coefficients depend on frequency, sample rate, AND gain - recomputed only when one of
    // the three actually changes (cheap no-op check on every call otherwise), since this runs
    // once per band per sample in the hot playback path.
    fun updateCoefficients(frequencyHz: Int, sampleRateHz: Int, gainDb: Float) {
        if (frequencyHz == lastFrequencyHz && sampleRateHz == lastSampleRateHz && gainDb == lastGainDb) return
        lastFrequencyHz = frequencyHz
        lastSampleRateHz = sampleRateHz
        lastGainDb = gainDb

        if (gainDb == 0f) {
            // Identity filter - avoids unnecessary floating-point drift when a band is flat.
            b0 = 1f
            b1 = 0f
            b2 = 0f
            a1 = 0f
            a2 = 0f
            return
        }

        val amplitude = 10.0.pow(gainDb / 40.0).toFloat()
        val w0 = (2.0 * PI * frequencyHz / sampleRateHz).toFloat()
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2f * q)

        val a0 = 1f + alpha / amplitude
        b0 = (1f + alpha * amplitude) / a0
        b1 = (-2f * cosW0) / a0
        b2 = (1f - alpha * amplitude) / a0
        a1 = (-2f * cosW0) / a0
        a2 = (1f - alpha / amplitude) / a0
    }

    fun process(input: Float): Float {
        val output = b0 * input + z1
        z1 = b1 * input - a1 * output + z2
        z2 = b2 * input - a2 * output
        return output
    }

    fun reset() {
        z1 = 0f
        z2 = 0f
    }
}
