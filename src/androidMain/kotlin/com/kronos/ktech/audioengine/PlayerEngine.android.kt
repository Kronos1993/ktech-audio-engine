package com.kronos.ktech.audioengine

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import com.kronos.ktech.audioengine.eq.LuminaEqualizerAudioProcessor
import com.kronos.ktech.audioengine.domain.AudioOutputDevice
import com.kronos.ktech.audioengine.domain.AudioOutputDeviceType
import com.kronos.ktech.audioengine.domain.OutputSelectionMode
import com.kronos.ktech.audioengine.domain.PlaybackState
import com.kronos.ktech.audioengine.domain.PlaybackStatus
import com.kronos.ktech.audioengine.domain.RepeatMode
import com.kronos.ktech.audioengine.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "PlayerEngine"
private const val FADE_STEP_MS = 50L

// Deliberately excludes types with no meaningful selectable-music-output identity
// (TYPE_BUILTIN_EARPIECE, TYPE_TELEPHONY, TYPE_FM, etc.) that GET_DEVICES_OUTPUTS can
// still return on some devices. TYPE_BLUETOOTH_SCO is deliberately excluded too, even
// though it IS a real selectable route - it's the mono call/voice profile, not the A2DP
// music-streaming profile, and Android exposes BOTH as separate AudioDeviceInfo entries
// for the exact same physical headset. Confirmed via real-device testing: including SCO
// showed the same headset twice in the list, and selecting the SCO entry routed audio to
// the phone's own speaker instead of the headset (ExoPlayer doesn't route media playback
// through a call-audio profile) - only the A2DP entry actually plays music correctly.
private val RELEVANT_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_HDMI,
    AudioDeviceInfo.TYPE_HDMI_ARC,
)

actual class PlayerEngine(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val equalizerAudioProcessor = LuminaEqualizerAudioProcessor()
    internal val exoPlayer: ExoPlayer = ExoPlayer
        .Builder(appContext)
        .setRenderersFactory(LuminaRenderersFactory(appContext, equalizerAudioProcessor))
        .build()

    private val _playbackState = MutableStateFlow(PlaybackState())
    actual val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Ticks every 200ms independently of playbackState, so a composable reading ONLY this
    // (a scrubber/progress bar) can recompose on its own without forcing every composable
    // that reads the whole PlaybackState (track info, controls, etc.) to recompose 5x/sec
    // too - found while chasing a "lyrics fetch hangs while a song is playing" bug where
    // background recomposition/coroutine-scheduling churn every 200ms was a live suspect.
    private val _positionMs = MutableStateFlow(0L)
    actual val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    actual val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _availableOutputDevices = MutableStateFlow<List<AudioOutputDevice>>(emptyList())
    actual val availableOutputDevices: StateFlow<List<AudioOutputDevice>> = _availableOutputDevices.asStateFlow()

    private val _selectedOutputDevice = MutableStateFlow<AudioOutputDevice?>(null)
    actual val selectedOutputDevice: StateFlow<AudioOutputDevice?> = _selectedOutputDevice.asStateFlow()

    // Android can enumerate real output devices and force ExoPlayer onto a specific one via
    // setPreferredAudioDevice() - DevicesScreen renders its own selectable row list here.
    actual val outputSelectionMode: OutputSelectionMode = OutputSelectionMode.IN_APP_LIST

    // AudioManager.registerAudioDeviceCallback pushes add/remove notifications - no polling
    // needed, unlike the JVM/Desktop actual (javax.sound.sampled has no such callback).
    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshAvailableOutputDevices()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshAvailableOutputDevices()
        }

    private var queueTracks: List<Track> = emptyList()
    private var volume: Float = 1f
    private var crossfadeMs: Long = 0L
    private var playbackSpeed: Float = 1f

    // Crossfade: the main exoPlayer (MediaSession, notification, ticker, visualizer) jumps to the
    // next item at the fade point and ramps up, while this headless helper plays the outgoing
    // item's tail and ramps down. Built lazily, only once crossfade is first used.
    private val fadeOutEqualizer = LuminaEqualizerAudioProcessor()
    private var fadeOutPlayer: ExoPlayer? = null
    private var preferredAudioDevice: AudioDeviceInfo? = null

    // The fade prepared for the current item: helper pre-loaded at the fade point, a PlayerMessage
    // armed to start it.
    private var plannedFadeIndex: Int = C.INDEX_UNSET
    private var plannedFadeMessage: PlayerMessage? = null
    private var fadeJob: Job? = null

    // Our own seekToNextMediaItem() at the fade point must not count as a user seek.
    private var ignoreNextSeekDiscontinuity = false
    private var visualizer: Visualizer? = null
    private var visualizerSessionId: Int = 0
    private var tickerJob: Job? = null
    // Deliberately NOT tracked with a "have I already started it" boolean - PlaybackService
    // can die independently of this singleton (e.g. the user swiping away its notification,
    // or the system reclaiming it under memory pressure) with no callback here to reset such
    // a flag, which used to leave ensureServiceStarted() silently skipping the restart on
    // every later play() and permanently orphaning the notification/session. Always calling
    // startForegroundService() instead is the correct fix: Android no-ops it cheaply
    // (onStartCommand only, no onCreate/session rebuild) when the service is already alive.

    init {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        refreshAvailableOutputDevices()
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) cancelCrossfade()
                    updateStatus()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) = updateStatus()

                // MediaSession/notification commands reach exoPlayer directly, not through this
                // class - keep the fade-out helper in step with them here.
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    fadeOutPlayer?.takeIf { fadeJob != null }?.playWhenReady = playWhenReady
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                    if (ignoreNextSeekDiscontinuity) {
                        ignoreNextSeekDiscontinuity = false
                    } else {
                        cancelCrossfade()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _playbackState.update { it.copy(status = PlaybackStatus.ERROR) }
                }
            },
        )
        tickerJob =
            scope.launch {
                while (isActive) {
                    _positionMs.value = exoPlayer.currentPosition
                    // Dedup-aware: MutableStateFlow.update{} only actually emits when the
                    // new value differs from the current one, so returning the SAME state
                    // unchanged (the common case, once index/track/duration are already
                    // known) means playbackState does NOT re-emit every 200ms anymore -
                    // only positionMs above does, and only on a genuine index/track/duration
                    // change does this trigger a real playbackState emission.
                    val newIndex = exoPlayer.currentMediaItemIndex
                    val newTrack = queueTracks.getOrNull(newIndex)
                    val newDuration = exoPlayer.duration.takeIf { d -> d > 0 }
                    _playbackState.update { current ->
                        if (current.currentIndex == newIndex &&
                            current.currentTrack?.uri == newTrack?.uri &&
                            current.durationMs == newDuration
                        ) {
                            current
                        } else {
                            current.copy(currentIndex = newIndex, currentTrack = newTrack, durationMs = newDuration)
                        }
                    }
                    ensureVisualizer()
                    planCrossfadeIfDue()
                    delay(200)
                }
            }
    }

    private fun refreshAvailableOutputDevices() {
        _availableOutputDevices.value = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in RELEVANT_OUTPUT_TYPES }
            .map { it.toAudioOutputDevice() }
    }

    private fun AudioDeviceInfo.toAudioOutputDevice(): AudioOutputDevice = AudioOutputDevice(
        id = id.toString(),
        name = productName?.toString().orEmpty(),
        type = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputDeviceType.BUILT_IN_SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioOutputDeviceType.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioOutputDeviceType.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                AudioOutputDeviceType.USB
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC -> AudioOutputDeviceType.HDMI
            else -> AudioOutputDeviceType.OTHER
        },
    )

    private fun updateStatus() {
        // ExoPlayer transitions to STATE_IDLE right after a fatal error, so
        // checking playerError first is required - otherwise this STATE_IDLE
        // callback (which always follows onPlayerError) falls into the `else`
        // branch below and silently overwrites the ERROR status back to IDLE.
        val status =
            when {
                exoPlayer.playerError != null -> PlaybackStatus.ERROR
                exoPlayer.playbackState == Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
                exoPlayer.playbackState == Player.STATE_ENDED -> PlaybackStatus.IDLE
                exoPlayer.isPlaying -> PlaybackStatus.PLAYING
                exoPlayer.playbackState == Player.STATE_READY -> PlaybackStatus.PAUSED
                else -> PlaybackStatus.IDLE
            }
        _playbackState.update { it.copy(status = status) }
    }

    @OptIn(UnstableApi::class)
    private fun ensureVisualizer() {
        val sessionId = exoPlayer.audioSessionId
        if (sessionId == 0 || sessionId == visualizerSessionId) return
        visualizerSessionId = sessionId
        runCatching {
            visualizer?.release()
            visualizer =
                Visualizer(sessionId).apply {
                    captureSize = Visualizer.getCaptureSizeRange()[0]
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(
                                v: Visualizer?,
                                waveform: ByteArray?,
                                samplingRate: Int,
                            ) {
                                waveform ?: return
                                var peak = 0
                                for (b in waveform) {
                                    val unsigned = b.toInt() and 0xFF
                                    val delta = kotlin.math.abs(unsigned - 128)
                                    if (delta > peak) peak = delta
                                }
                                _audioLevel.value = (peak / 128f).coerceIn(0f, 1f)
                            }

                            override fun onFftDataCapture(
                                v: Visualizer?,
                                fft: ByteArray?,
                                samplingRate: Int,
                            ) = Unit
                        },
                        Visualizer.getMaxCaptureRate() / 2,
                        true,
                        false,
                    )
                    enabled = true
                }
        }
    }

    actual fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
    ) {
        cancelCrossfade()
        queueTracks = tracks
        val mediaItems =
            tracks.map { track ->
                MediaItem
                    .Builder()
                    .setUri(track.uri)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setAlbumTitle(track.album)
                            .apply { track.artworkUri?.let { setArtworkUri(Uri.parse(it)) } }
                            .build(),
                    ).build()
            }
        exoPlayer.setMediaItems(mediaItems, startIndex, 0L)
        exoPlayer.prepare()
        _playbackState.update {
            it.copy(
                queue = tracks,
                currentIndex = startIndex,
                currentTrack = tracks.getOrNull(startIndex),
            )
        }
    }

    actual fun play() {
        ensureServiceStarted()
        reprepareIfIdle()
        exoPlayer.play()
    }

    private fun ensureServiceStarted() {
        runCatching {
            appContext.startForegroundService(Intent(appContext, PlaybackService::class.java))
        }.onFailure {
            Log.e(TAG, "startForegroundService failed", it)
        }
    }

    // Media3's default media-notification-dismiss handling calls player.stop() on this
    // shared exoPlayer, which drops it to STATE_IDLE AND leaves playWhenReady false - from
    // there, prepare() alone re-primes the player but does NOT resume audio (confirmed via
    // real-device testing: pressing play first - which calls exoPlayer.play(), setting
    // playWhenReady true - then skip-next/previous worked fine; skipping directly without
    // pressing play first changed the track but never made a sound, since prepare() alone
    // never touches playWhenReady). So recovering from IDLE must call play() too, not just
    // prepare() - matching exactly what the working "press play first" path does. Only
    // fires on genuine STATE_IDLE, not on a normal user-initiated pause (STATE_READY,
    // playWhenReady false), so skipping while legitimately paused still stays paused.
    //
    // STATE_ENDED is a separate case: reached when the queue has finished playing through
    // to completion (e.g. left idle after the last track ends) - prepare()+play() alone
    // does not restart it, ExoPlayer requires seeking back to a valid position first.
    // Since exoPlayer is a Koin singleton outliving any one Activity, simply reopening the
    // app does NOT reset a player stuck in STATE_ENDED - only loading a brand-new queue
    // (a fresh setMediaItems()+prepare()) sidesteps it, which is why "reopen the app and
    // play something new" appeared to be the only fix before this.
    private fun reprepareIfIdle() {
        when (exoPlayer.playbackState) {
            Player.STATE_IDLE -> {
                exoPlayer.prepare()
                exoPlayer.play()
            }

            Player.STATE_ENDED -> {
                exoPlayer.seekToDefaultPosition()
                exoPlayer.prepare()
                exoPlayer.play()
            }

            else -> {
                Unit
            }
        }
    }

    actual fun pause() {
        exoPlayer.pause()
    }

    actual fun stop() {
        cancelCrossfade()
        exoPlayer.stop()
        _audioLevel.value = 0f
    }

    actual fun seekTo(positionMs: Long) {
        cancelCrossfade()
        reprepareIfIdle()
        exoPlayer.seekTo(positionMs)
        _positionMs.value = positionMs
    }

    actual fun skipNext() {
        cancelCrossfade()
        reprepareIfIdle()
        exoPlayer.seekToNext()
    }

    actual fun skipPrevious() {
        cancelCrossfade()
        reprepareIfIdle()
        exoPlayer.seekToPrevious()
    }

    actual fun setRepeatMode(mode: RepeatMode) {
        exoPlayer.repeatMode =
            when (mode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            }
        _playbackState.update { it.copy(repeatMode = mode) }
    }

    actual fun setShuffle(enabled: Boolean) {
        exoPlayer.shuffleModeEnabled = enabled
        _playbackState.update { it.copy(shuffleEnabled = enabled) }
    }

    actual fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        // During a fade the ramp applies the new volume on its next step.
        if (fadeJob == null) exoPlayer.volume = this.volume
        _playbackState.update { it.copy(volume = this.volume) }
    }

    @OptIn(UnstableApi::class)
    actual fun selectOutputDevice(deviceId: String?) {
        cancelCrossfade()
        val device = deviceId?.let { id -> _availableOutputDevices.value.firstOrNull { it.id == id } }
        preferredAudioDevice =
            deviceId?.let { id -> audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id.toString() == id } }
        exoPlayer.setPreferredAudioDevice(preferredAudioDevice)
        fadeOutPlayer?.setPreferredAudioDevice(preferredAudioDevice)
        // AudioTrack.setPreferredDevice() on an already-open, actively-routed track is
        // best-effort and not reliably honored by the OS while Bluetooth A2DP already owns the
        // route (confirmed real-device: switching to a non-Bluetooth device silently failed on
        // the first attempt while headphones were connected and playing, see decisions.md). The
        // preferred device IS applied reliably, but only at AudioTrack *creation* time
        // (Media3's DefaultAudioSink.initializeAudioOutput() - confirmed by reading its source).
        // A same-position seek forces ExoPlayer to flush and recreate the underlying AudioSink,
        // which re-applies the already-set preferred device through that reliable path - the
        // same technique DefaultAudioSink.setVirtualDeviceId() uses internally (reconfigureAndFlush())
        // for the equivalent problem, just triggered from the outside since setPreferredDevice
        // itself doesn't do this automatically.
        exoPlayer.seekTo(exoPlayer.currentPosition)
        _selectedOutputDevice.value = device
    }

    actual fun setEqualizerEnabled(enabled: Boolean) {
        equalizerAudioProcessor.setEnabled(enabled)
        fadeOutEqualizer.setEnabled(enabled)
    }

    actual fun setEqualizerBands(gainsDb: FloatArray) {
        equalizerAudioProcessor.setBandGains(gainsDb)
        fadeOutEqualizer.setBandGains(gainsDb)
    }

    actual fun setCrossfadeDuration(durationMs: Long) {
        crossfadeMs = durationMs.coerceAtLeast(0L)
        if (crossfadeMs == 0L) cancelCrossfade()
    }

    // Sonic time-stretching inside ExoPlayer's audio sink; pitch stays at 1.
    actual fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(MIN_PLAYBACK_SPEED, MAX_PLAYBACK_SPEED)
        val parameters = PlaybackParameters(playbackSpeed)
        exoPlayer.playbackParameters = parameters
        fadeOutPlayer?.playbackParameters = parameters
    }

    actual val supportsSkipSilence: Boolean = true

    actual fun setSkipSilenceEnabled(enabled: Boolean) {
        exoPlayer.skipSilenceEnabled = enabled
    }

    // Runs from the 200 ms ticker: shortly before the fade point, pre-loads the helper with the
    // current item paused at that point (so starting it costs no buffering) and arms a
    // PlayerMessage that fires exactly there.
    private fun planCrossfadeIfDue() {
        if (crossfadeMs == 0L || fadeJob != null || !exoPlayer.isPlaying) return
        val index = exoPlayer.currentMediaItemIndex
        if (plannedFadeIndex != C.INDEX_UNSET) {
            if (plannedFadeIndex != index) cancelCrossfade()
            return
        }
        val durationMs = exoPlayer.duration.takeIf { it > 0 } ?: return
        val remainingMs = durationMs - exoPlayer.currentPosition
        val nextIndex = exoPlayer.nextMediaItemIndex
        val windowMs = Crossfade.windowMs(
            requestedMs = crossfadeMs,
            outgoing = queueTracks.getOrNull(index),
            outgoingIndex = index,
            outgoingDurationMs = durationMs,
            incoming = queueTracks.getOrNull(nextIndex),
            incomingIndex = nextIndex,
        )
        if (windowMs == 0L || remainingMs > windowMs + Crossfade.PREPARE_LEAD_MS || remainingMs <= windowMs) return

        val fadePointMs = durationMs - windowMs
        val helper = fadeOutPlayer ?: buildFadeOutPlayer().also { fadeOutPlayer = it }
        val item = exoPlayer.currentMediaItem ?: return
        helper.playWhenReady = false
        helper.setMediaItem(item, fadePointMs)
        helper.prepare()
        plannedFadeIndex = index
        plannedFadeMessage = exoPlayer
            .createMessage { _, _ -> startCrossfade(index, windowMs) }
            .setLooper(Looper.getMainLooper())
            .setPosition(index, fadePointMs)
            .setDeleteAfterDelivery(true)
            .send()
    }

    private fun buildFadeOutPlayer(): ExoPlayer = ExoPlayer
        .Builder(appContext)
        .setRenderersFactory(LuminaRenderersFactory(appContext, fadeOutEqualizer))
        .build()
        .also {
            it.setPreferredAudioDevice(preferredAudioDevice)
            it.playbackParameters = PlaybackParameters(playbackSpeed)
        }

    private fun startCrossfade(index: Int, windowMs: Long) {
        val helper = fadeOutPlayer
        val nextIndex = exoPlayer.nextMediaItemIndex
        if (helper == null || index != plannedFadeIndex || index != exoPlayer.currentMediaItemIndex ||
            nextIndex == C.INDEX_UNSET || nextIndex == index
        ) {
            cancelCrossfade()
            return
        }
        plannedFadeMessage = null
        helper.volume = volume
        helper.play()
        exoPlayer.volume = 0f
        ignoreNextSeekDiscontinuity = true
        exoPlayer.seekToNextMediaItem()
        fadeJob = scope.launch {
            var elapsedMs = 0L
            while (isActive && elapsedMs < windowMs) {
                val progress = elapsedMs.toFloat() / windowMs
                exoPlayer.volume = volume * Crossfade.fadeInGain(progress)
                helper.volume = volume * Crossfade.fadeOutGain(progress)
                delay(FADE_STEP_MS)
                // windowMs is track time, which runs playbackSpeed times faster than wall time.
                if (exoPlayer.isPlaying) elapsedMs += (FADE_STEP_MS * playbackSpeed).toLong()
            }
            finishCrossfade()
        }
    }

    private fun finishCrossfade() {
        fadeJob = null
        plannedFadeIndex = C.INDEX_UNSET
        fadeOutPlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
        exoPlayer.volume = volume
    }

    private fun cancelCrossfade() {
        plannedFadeMessage?.cancel()
        plannedFadeMessage = null
        val job = fadeJob
        fadeJob = null
        job?.cancel()
        if (job != null || plannedFadeIndex != C.INDEX_UNSET) finishCrossfade()
        ignoreNextSeekDiscontinuity = false
    }

    actual fun release() {
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        tickerJob?.cancel()
        scope.cancel()
        visualizer?.release()
        cancelCrossfade()
        fadeOutPlayer?.release()
        exoPlayer.release()
        runCatching { appContext.stopService(Intent(appContext, PlaybackService::class.java)) }
    }
}
