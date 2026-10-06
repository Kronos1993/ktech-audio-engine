package com.kronos.ktech.audioengine

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import java.nio.ShortBuffer

// Pitch-preserving speed change for interleaved 16-bit PCM via ffmpeg's atempo. The filter is
// rebuilt when the speed changes (dropping a few ms it had buffered) and bypassed at 1x.
internal class TempoFilter(private val sampleRate: Int, private val channels: Int) {
    private var speed = 1f
    private var filter: FFmpegFrameFilter? = null

    fun process(samples: ShortArray, speed: Float): ShortArray {
        if (speed != this.speed) {
            reset()
            this.speed = speed
        }
        if (speed == 1f || samples.isEmpty()) return samples
        val active = filter ?: open(speed).also { filter = it }
        active.pushSamples(0, channels, sampleRate, avutil.AV_SAMPLE_FMT_S16, ShortBuffer.wrap(samples))
        val parts = ArrayList<ShortArray>()
        while (true) {
            val frame = active.pullSamples() ?: break
            val buffer = frame.samples?.getOrNull(0) as? ShortBuffer ?: continue
            parts += ShortArray(buffer.remaining()).also { buffer.get(it) }
        }
        return when (parts.size) {
            0 -> ShortArray(0)
            1 -> parts[0]
            else -> ShortArray(parts.sumOf { it.size }).also { out ->
                var pos = 0
                for (p in parts) {
                    p.copyInto(out, pos)
                    pos += p.size
                }
            }
        }
    }

    // Drops buffered audio, e.g. after a seek.
    fun reset() {
        filter?.let { f ->
            runCatching { f.stop() }
            runCatching { f.release() }
        }
        filter = null
    }

    // Float.toString() always uses '.', unlike a locale-dependent format().
    private fun open(speed: Float): FFmpegFrameFilter =
        FFmpegFrameFilter("atempo=$speed,aformat=sample_fmts=s16", channels).apply {
            sampleRate = this@TempoFilter.sampleRate
            sampleFormat = avutil.AV_SAMPLE_FMT_S16
            start()
        }
}
