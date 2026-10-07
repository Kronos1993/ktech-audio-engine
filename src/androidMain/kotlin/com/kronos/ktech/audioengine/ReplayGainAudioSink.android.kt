package com.kronos.ktech.audioengine

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

// The renderer calls configure() with each new stream's format (including its metadata) in
// sample order, which AudioProcessor.onConfigure never sees: forward the tags to the gain stage.
@OptIn(UnstableApi::class)
class ReplayGainAudioSink(
    sink: AudioSink,
    private val replayGainProcessor: ReplayGainAudioProcessor,
) : ForwardingAudioSink(sink) {
    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        replayGainProcessor.onStreamMetadata(audioSinkConfig.format.metadata)
        super.configure(audioSinkConfig)
    }
}
