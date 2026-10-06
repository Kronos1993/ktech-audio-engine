package com.kronos.ktech.audioengine

import kotlin.math.sqrt

// Shortens silent passages in interleaved 16-bit PCM. Audio is judged in fixed windows; a run of
// quiet windows longer than MIN_SILENCE_MS is cut down to its first and last KEEP_EDGE_MS, with
// short fades at the cut. Shorter pauses pass through untouched. Output lags input by at most
// one window plus the silent run still being judged.
internal class SilenceSkipper(sampleRate: Int, private val channels: Int) {
    private val windowSamples = (sampleRate * WINDOW_MS / 1000).coerceAtLeast(1) * channels
    private val minSilenceWindows = MIN_SILENCE_MS / WINDOW_MS
    private val edgeWindows = KEEP_EDGE_MS / WINDOW_MS
    private val fadeFrames = sampleRate * FADE_MS / 1000

    private var carry = ShortArray(0)

    // Quiet windows of the current run while it is still shorter than minSilenceWindows.
    private val pending = ArrayDeque<ShortArray>()

    // Once a run is being cut: only its most recent edgeWindows windows.
    private val tail = ArrayDeque<ShortArray>()
    private var cutting = false

    fun process(input: ShortArray, enabled: Boolean): ShortArray {
        if (!enabled) return flush() + input
        val out = ArrayList<ShortArray>()
        val data = if (carry.isEmpty()) input else carry + input
        var offset = 0
        while (data.size - offset >= windowSamples) {
            val window = data.copyOfRange(offset, offset + windowSamples)
            offset += windowSamples
            if (isQuiet(window)) onQuiet(window, out) else onLoud(window, out)
        }
        carry = data.copyOfRange(offset, data.size)
        return concat(out)
    }

    // Everything held back, in order. Used before a crossfade overlap and when turned off.
    fun flush(): ShortArray {
        val out = ArrayList<ShortArray>()
        if (cutting) out += fadeIn(concat(tail)) else out.addAll(pending)
        out += carry
        reset()
        return concat(out)
    }

    // Drops everything held back (after a seek, the held audio is stale).
    fun reset() {
        carry = ShortArray(0)
        pending.clear()
        tail.clear()
        cutting = false
    }

    private fun onQuiet(window: ShortArray, out: MutableList<ShortArray>) {
        if (cutting) {
            tail.addLast(window)
            if (tail.size > edgeWindows) tail.removeFirst()
            return
        }
        pending.addLast(window)
        if (pending.size <= minSilenceWindows) return
        cutting = true
        out += fadeOut(concat(pending.take(edgeWindows)))
        pending.takeLast(edgeWindows).forEach { tail.addLast(it) }
        pending.clear()
    }

    private fun onLoud(window: ShortArray, out: MutableList<ShortArray>) {
        if (cutting) {
            out += fadeIn(concat(tail))
            tail.clear()
            cutting = false
        } else {
            out.addAll(pending)
            pending.clear()
        }
        out += window
    }

    private fun isQuiet(window: ShortArray): Boolean {
        var sum = 0.0
        for (s in window) sum += s.toDouble() * s
        return sqrt(sum / window.size) < THRESHOLD_AMPLITUDE
    }

    private fun fadeOut(samples: ShortArray): ShortArray = ramp(samples, fadeIn = false)

    private fun fadeIn(samples: ShortArray): ShortArray = ramp(samples, fadeIn = true)

    private fun ramp(samples: ShortArray, fadeIn: Boolean): ShortArray {
        val frames = samples.size / channels
        val n = minOf(fadeFrames, frames)
        if (n == 0) return samples
        for (i in 0 until n) {
            val gain = i.toFloat() / n
            val frame = if (fadeIn) i else frames - 1 - i
            for (c in 0 until channels) {
                val idx = frame * channels + c
                samples[idx] = (samples[idx] * gain).toInt().toShort()
            }
        }
        return samples
    }

    private fun concat(parts: List<ShortArray>): ShortArray {
        if (parts.size == 1) return parts[0]
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    private companion object {
        const val WINDOW_MS = 20
        const val MIN_SILENCE_MS = 300
        const val KEEP_EDGE_MS = 60
        const val FADE_MS = 10

        // -50 dBFS RMS.
        const val THRESHOLD_AMPLITUDE = 103.6
    }
}
