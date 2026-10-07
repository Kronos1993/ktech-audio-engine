package com.kronos.ktech.audioengine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ReplayGain stage. Must be the first processor in the sink: ReplayGainAudioSink hands it each
// stream's tags at configure() time, when every sample of the previous stream has already
// passed through this processor, so the gain switch lands exactly on the track boundary.
@OptIn(UnstableApi::class)
class ReplayGainAudioProcessor : BaseAudioProcessor() {
    private val gainStage = GainStage()

    @Volatile private var config = ReplayGainConfig()

    @Volatile private var tags = ReplayGainTags.NONE
    private var channelCount = 1
    private var samples = ShortArray(0)

    internal fun setConfig(newConfig: ReplayGainConfig) {
        config = newConfig
        gainStage.set(ReplayGain.linearGain(newConfig, tags), smooth = true)
    }

    internal fun onStreamMetadata(metadata: Metadata?) {
        tags = metadata.toReplayGainTags()
        gainStage.set(ReplayGain.linearGain(config, tags), smooth = false)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val count = remaining / 2
        if (samples.size < count) samples = ShortArray(count)
        inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(samples, 0, count)
        inputBuffer.position(inputBuffer.limit())
        gainStage.process(samples, channelCount, inputAudioFormat.sampleRate, count)
        val outputBuffer = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        outputBuffer.asShortBuffer().put(samples, 0, count)
        outputBuffer.position(remaining)
        outputBuffer.flip()
    }
}

@OptIn(UnstableApi::class)
private fun Metadata?.toReplayGainTags(): ReplayGainTags {
    if (this == null) return ReplayGainTags.NONE
    val entries = (0 until length()).mapNotNull { i ->
        when (val entry = get(i)) {
            is TextInformationFrame -> entry.description?.let { key -> entry.values.firstOrNull()?.let { key to it } }
            is VorbisComment -> entry.key to entry.value
            is InternalFrame -> entry.description to entry.text
            else -> null
        }
    }
    return ReplayGain.parseTags(entries)
}
