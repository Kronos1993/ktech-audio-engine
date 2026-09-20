package com.kronos.ktech.audioengine.domain

// Shared by the commonMain biquad DSP (ktech-audio-engine/.../eq) and the Equalizer UI, so Android's
// custom AudioProcessor, Desktop's decode-loop filter chain, and iOS's AVAudioUnitEQ all
// operate on the exact same 10 band centers - deliberately not Android's native
// audiofx.Equalizer bands, which are device/hardware-dependent (see real-audio-equalizer
// proposal.md §2).
object EqualizerBands {
    val FREQUENCIES_HZ: List<Int> = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val COUNT: Int = FREQUENCIES_HZ.size

    // Indices into the FREQUENCIES_HZ/COUNT-sized arrays that are user-visible/editable for a
    // given band-count mode. The underlying DSP engine on every platform always operates on the
    // full 10-slot array regardless of mode - none of them can be resized at runtime (iOS's
    // AVAudioUnitEQ in particular is sized once, at construction, forever). "5 bands" mode is
    // purely a UI/persistence concern: it hides 5 of the same 10 real slots (evenly spaced
    // across the spectrum) rather than introducing a second, different frequency set or a
    // second DSP engine.
    // Takes a plain band count (5 or 10) rather than the app's own EqualizerBandCount enum -
    // this type is part of the library's public surface and must not depend on an app-level
    // persisted-preference type. Callers convert their own band-count representation to an Int
    // before calling in; any value other than 5 defaults to the full 10-slot list.
    fun visibleIndices(bandCount: Int): List<Int> = when (bandCount) {
        5 -> listOf(1, 3, 5, 7, 9)
        else -> FREQUENCIES_HZ.indices.toList()
    }
}
