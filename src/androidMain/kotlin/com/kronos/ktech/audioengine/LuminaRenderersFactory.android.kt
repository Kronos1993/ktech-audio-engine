package com.kronos.ktech.audioengine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.kronos.ktech.audioengine.eq.LuminaEqualizerAudioProcessor

// Media3's documented extension point for custom PCM effects - see real-audio-equalizer
// proposal.md §2 for why this replaces android.media.audiofx.Equalizer.
@OptIn(UnstableApi::class)
class LuminaRenderersFactory(
    context: Context,
    private val equalizerAudioProcessor: LuminaEqualizerAudioProcessor,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink
            .Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(equalizerAudioProcessor))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
}
