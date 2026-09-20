package com.kronos.ktech.audioengine

import com.kronos.ktech.audioengine.domain.AudioOutputDevice
import com.kronos.ktech.audioengine.domain.OutputSelectionMode
import com.kronos.ktech.audioengine.domain.PlaybackState
import com.kronos.ktech.audioengine.domain.RepeatMode
import com.kronos.ktech.audioengine.domain.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * A multiplatform audio playback engine (Android/iOS/Desktop) driving one shared [playbackState].
 *
 * Obtain an instance via Koin's [com.kronos.ktech.audioengine.di.playerEngineModule] — see this
 * module's README for the required Koin setup and a full usage walkthrough. All calls are safe
 * to make from any thread; state is observed via [StateFlow].
 */
expect class PlayerEngine {
    /** The current playback state (status, queue, current track index, repeat/shuffle, etc.). */
    val playbackState: StateFlow<PlaybackState>

    /** Current playback position of the active track, in milliseconds. */
    val positionMs: StateFlow<Long>

    /** Real-time playback audio level (0f-1f), suitable for driving a level meter/visualizer. */
    val audioLevel: StateFlow<Float>

    /** Output devices currently available for selection. Empty on platforms with no enumeration API (see [outputSelectionMode]). */
    val availableOutputDevices: StateFlow<List<AudioOutputDevice>>

    /** The currently selected output device, or `null` when using the system default. */
    val selectedOutputDevice: StateFlow<AudioOutputDevice?>

    /**
     * How this platform lets the user pick an output device: [OutputSelectionMode.IN_APP_LIST]
     * (render [availableOutputDevices] yourself and call [selectOutputDevice]) or
     * [OutputSelectionMode.SYSTEM_PICKER] (embed the platform's own picker UI instead).
     */
    val outputSelectionMode: OutputSelectionMode

    /** Replaces the playback queue and starts playback at [startIndex]. */
    fun setQueue(tracks: List<Track>, startIndex: Int)

    /** Resumes/starts playback of the current track. */
    fun play()

    /** Pauses playback without clearing the queue or position. */
    fun pause()

    /** Stops playback and releases the underlying platform player resources for this track. */
    fun stop()

    /** Seeks the current track to [positionMs]. */
    fun seekTo(positionMs: Long)

    /** Advances to the next track in the queue, honoring the current repeat/shuffle mode. */
    fun skipNext()

    /** Returns to the previous track in the queue, honoring the current repeat/shuffle mode. */
    fun skipPrevious()

    /** Sets the repeat mode (off / repeat-all / repeat-one). */
    fun setRepeatMode(mode: RepeatMode)

    /** Enables or disables shuffled playback order. */
    fun setShuffle(enabled: Boolean)

    /** Sets playback volume, from `0f` (silent) to `1f` (full volume). */
    fun setVolume(volume: Float)

    /** Selects an output device by [AudioOutputDevice.id], or `null` to reset to the system default. Only meaningful under [OutputSelectionMode.IN_APP_LIST]. */
    fun selectOutputDevice(deviceId: String?)

    /** Enables or disables the built-in 10-band equalizer (see [setEqualizerBands]). */
    fun setEqualizerEnabled(enabled: Boolean)

    /**
     * Sets the equalizer's per-band gains in dB. [gainsDb] must have exactly
     * [com.kronos.ktech.audioengine.domain.EqualizerBands.COUNT] entries, ordered to match
     * [com.kronos.ktech.audioengine.domain.EqualizerBands.FREQUENCIES_HZ].
     */
    fun setEqualizerBands(gainsDb: FloatArray)

    /** Releases all underlying platform player resources. The engine must not be used afterward. */
    fun release()
}
