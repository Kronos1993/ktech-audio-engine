package com.kronos.ktech.audioengine.eq

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// A single biquad, RBJ Audio EQ Cookbook coefficients (https://www.w3.org/TR/audio-eq-cookbook/,
// peakingEQ and lowShelf sections - a standard, publicly documented DSP formula, not derived from
// any one product's implementation), Direct Form II Transpose. Shared by every platform's real-EQ
// integration so Android/Desktop/iOS all shape the same frequency the same way.
// Default Q is one octave (sqrt 2), matching iOS AVAudioUnitEQ bandwidth = 1.0 and AutoEq's
// fixed-band profiles.
class BiquadFilter(
    private val q: Float = ONE_OCTAVE_Q,
    private val type: Type = Type.PEAKING,
) {
    enum class Type { PEAKING, LOW_SHELF }

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

        if (gainDb == 0f || sampleRateHz <= 0 || frequencyHz >= NYQUIST_GUARD_RATIO * sampleRateHz) {
            // Identity filter - a flat band needs no processing, and a band at/near Nyquist
            // (w0 >= pi) yields an unstable filter that diverges to Inf/NaN.
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
        when (type) {
            Type.PEAKING -> {
                val alpha = sin(w0) / (2f * q)
                val a0 = 1f + alpha / amplitude
                b0 = (1f + alpha * amplitude) / a0
                b1 = (-2f * cosW0) / a0
                b2 = (1f - alpha * amplitude) / a0
                a1 = (-2f * cosW0) / a0
                a2 = (1f - alpha / amplitude) / a0
            }
            Type.LOW_SHELF -> {
                // Shelf slope S = 1 (alpha = sin(w0) / sqrt 2); q is unused.
                val alpha = sin(w0) / sqrt(2f)
                val twoSqrtAAlpha = 2f * sqrt(amplitude) * alpha
                val ap1 = amplitude + 1f
                val am1 = amplitude - 1f
                val a0 = ap1 + am1 * cosW0 + twoSqrtAAlpha
                b0 = amplitude * (ap1 - am1 * cosW0 + twoSqrtAAlpha) / a0
                b1 = 2f * amplitude * (am1 - ap1 * cosW0) / a0
                b2 = amplitude * (ap1 - am1 * cosW0 - twoSqrtAAlpha) / a0
                a1 = -2f * (am1 + ap1 * cosW0) / a0
                a2 = (ap1 + am1 * cosW0 - twoSqrtAAlpha) / a0
            }
        }
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

    companion object {
        const val ONE_OCTAVE_Q = 1.4142135f

        // Bands at or above this fraction of the sample rate are bypassed; the stream has no
        // content there, and the formulas become unstable as w0 approaches pi.
        private const val NYQUIST_GUARD_RATIO = 0.45f
    }
}
