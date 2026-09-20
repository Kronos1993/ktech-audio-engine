package com.kronos.ktech.audioengine.eq

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.kronos.ktech.audioengine.domain.EqualizerBands
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Custom Media3 AudioProcessor running the shared biquad EqualizerChain on raw PCM, injected
// into ExoPlayer via LuminaRenderersFactory.buildAudioSink() - deliberately not
// android.media.audiofx.Equalizer, whose band count/center frequencies are device/hardware-
// dependent (real-audio-equalizer proposal.md §2). One EqualizerChain instance per audio
// channel - interleaved multi-channel PCM would otherwise corrupt a single shared filter's
// internal (z1/z2) state by alternating unrelated channels' samples through it.
@OptIn(UnstableApi::class)
class LuminaEqualizerAudioProcessor : BaseAudioProcessor() {
    private var channels: List<EqualizerChain> = emptyList()
    private var enabled = false
    private var gainsDb = FloatArray(EqualizerBands.COUNT)

    fun setEnabled(value: Boolean) {
        enabled = value
        channels.forEach { it.setEnabled(value) }
    }

    fun setBandGains(newGainsDb: FloatArray) {
        gainsDb = newGainsDb.copyOf()
        channels.forEach { it.setBandGains(gainsDb) }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = List(inputAudioFormat.channelCount) {
            EqualizerChain().also { chain ->
                chain.setEnabled(enabled)
                chain.setBandGains(gainsDb)
            }
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0 || channels.isEmpty()) return
        inputBuffer.order(ByteOrder.nativeOrder())
        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.order(ByteOrder.nativeOrder())
        val sampleRateHz = inputAudioFormat.sampleRate
        val channelCount = channels.size
        var channelIndex = 0
        while (inputBuffer.hasRemaining()) {
            val sample = inputBuffer.getShort()
            val processed = channels[channelIndex].processSample(sampleRateHz, sample)
            outputBuffer.putShort(processed)
            channelIndex = (channelIndex + 1) % channelCount
        }
        outputBuffer.flip()
    }

    override fun onFlush() {
        channels.forEach { it.reset() }
    }
}
