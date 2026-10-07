package com.kronos.ktech.audioengine

import kotlin.concurrent.Volatile
import kotlin.math.pow
import kotlin.math.roundToInt

/** Which ReplayGain value normalizes each track. */
enum class ReplayGainMode {
    /** No normalization. */
    OFF,

    /** The track's own gain. */
    TRACK,

    /** The album gain, falling back to the track gain when the file has none. */
    ALBUM,
}

internal data class ReplayGainConfig(
    val mode: ReplayGainMode = ReplayGainMode.OFF,
    val preampDb: Float = 0f,
    val fallbackDb: Float = 0f,
)

internal data class ReplayGainTags(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
) {
    companion object {
        val NONE = ReplayGainTags()
    }
}

internal object ReplayGain {
    private const val MAX_GAIN_DB = 24f
    private val NUMBER = Regex("""[-+]?\d+(?:[.,]\d+)?""")

    // Keys are matched by suffix, case-insensitively: ID3 TXXX descriptions, Vorbis comments,
    // MP4 freeform names ("----:com.apple.iTunes:replaygain_track_gain") and AVFoundation
    // identifiers all end with the standard name.
    fun parseTags(entries: Iterable<Pair<String, String>>): ReplayGainTags {
        var tags = ReplayGainTags.NONE
        for ((rawKey, value) in entries) {
            val key = rawKey.trim().lowercase()
            tags = when {
                key.endsWith("replaygain_track_gain") -> tags.copy(trackGainDb = parseNumber(value) ?: tags.trackGainDb)
                key.endsWith("replaygain_album_gain") -> tags.copy(albumGainDb = parseNumber(value) ?: tags.albumGainDb)
                key.endsWith("replaygain_track_peak") -> tags.copy(trackPeak = parsePeak(value) ?: tags.trackPeak)
                key.endsWith("replaygain_album_peak") -> tags.copy(albumPeak = parsePeak(value) ?: tags.albumPeak)
                else -> tags
            }
        }
        return tags
    }

    // Linear gain for one track. Untagged tracks get [ReplayGainConfig.fallbackDb] instead of
    // tag + pre-amp; a peak tag caps the gain so the loudest sample never exceeds full scale.
    fun linearGain(config: ReplayGainConfig, tags: ReplayGainTags): Float {
        if (config.mode == ReplayGainMode.OFF) return 1f
        val useAlbum = config.mode == ReplayGainMode.ALBUM && tags.albumGainDb != null
        val tagDb = if (useAlbum) tags.albumGainDb else tags.trackGainDb
        val peak = if (useAlbum) tags.albumPeak else tags.trackPeak
        val db = if (tagDb != null) tagDb + config.preampDb else config.fallbackDb
        var gain = 10f.pow(db.coerceAtMost(MAX_GAIN_DB) / 20f)
        if (tagDb != null && peak != null) gain = minOf(gain, 1f / peak)
        return gain
    }

    private fun parseNumber(value: String): Float? =
        NUMBER.find(value)?.value?.replace(',', '.')?.toFloatOrNull()?.takeIf { it.isFinite() }

    private fun parsePeak(value: String): Float? = parseNumber(value)?.takeIf { it > 0f }
}

// A per-source linear gain applied to interleaved 16-bit PCM. set() may be called from any
// thread; process() runs on the audio thread. A smooth change ramps over RAMP_MS, a hard one
// (new track) jumps so no gain from the previous track bleeds into the next.
internal class GainStage {
    @Volatile private var target = 1f

    @Volatile private var jumpRequested = false
    private var current = 1f
    private var rampTarget = 1f
    private var rampStep = 0f
    private var rampFrames = 0

    fun set(gain: Float, smooth: Boolean) {
        target = gain
        if (!smooth) jumpRequested = true
    }

    // Scales the first [count] of [samples] (interleaved, [channels] per frame) in place.
    fun process(samples: ShortArray, channels: Int, sampleRate: Int, count: Int = samples.size) {
        prepare(sampleRate)
        if (current == 1f && rampFrames == 0) return
        val channelCount = channels.coerceAtLeast(1)
        var i = 0
        while (i < count) {
            val gain = nextFrameGain()
            val frameEnd = minOf(i + channelCount, count)
            while (i < frameEnd) {
                samples[i] = scale(samples[i], gain)
                i++
            }
        }
    }

    private fun prepare(sampleRate: Int) {
        val newTarget = target
        if (jumpRequested) {
            jumpRequested = false
            current = newTarget
            rampTarget = newTarget
            rampFrames = 0
        } else if (newTarget != rampTarget) {
            rampTarget = newTarget
            rampFrames = (sampleRate.coerceAtLeast(1) * RAMP_MS / 1000).coerceAtLeast(1)
            rampStep = (newTarget - current) / rampFrames
        }
    }

    private fun nextFrameGain(): Float {
        if (rampFrames > 0) {
            rampFrames--
            current = if (rampFrames == 0) rampTarget else current + rampStep
        }
        return current
    }

    private fun scale(sample: Short, gain: Float): Short =
        (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private companion object {
        const val RAMP_MS = 50
    }
}
