package com.kronos.ktech.audioengine

import com.kronos.ktech.audioengine.domain.Track
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Shared rules for the natural end-of-track transition, used by every platform actual.
internal object Crossfade {
    // How far ahead of a track's end the next one is prepared (decoder opened, file pre-scheduled).
    const val PREPARE_LEAD_MS = 2_000L

    fun isRemoteStream(uri: String): Boolean =
        uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)

    // The fade length for an outgoing -> incoming transition, or 0 when it must be a plain
    // (gapless) cut: crossfade off, same queue item (repeat-one), a stream on either side, or an
    // unknown duration. Never longer than half of either track.
    fun windowMs(
        requestedMs: Long,
        outgoing: Track?,
        outgoingIndex: Int,
        outgoingDurationMs: Long?,
        incoming: Track?,
        incomingIndex: Int,
    ): Long {
        if (requestedMs <= 0 || outgoing == null || incoming == null || incomingIndex == outgoingIndex) return 0
        if (isRemoteStream(outgoing.uri) || isRemoteStream(incoming.uri)) return 0
        val outgoingMs = outgoingDurationMs?.takeIf { it > 0 } ?: return 0
        var window = minOf(requestedMs, outgoingMs / 2)
        incoming.durationMs?.takeIf { it > 0 }?.let { window = minOf(window, it / 2) }
        return window.coerceAtLeast(0)
    }

    // Equal-power curves: progress 0..1 across the fade, keeping perceived loudness constant.
    fun fadeOutGain(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    fun fadeInGain(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)
}
