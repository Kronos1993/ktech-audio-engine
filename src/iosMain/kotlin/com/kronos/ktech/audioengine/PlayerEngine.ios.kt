package com.kronos.ktech.audioengine

import com.kronos.ktech.audioengine.domain.AudioOutputDevice
import com.kronos.ktech.audioengine.domain.EqualizerBands
import com.kronos.ktech.audioengine.domain.OutputSelectionMode
import com.kronos.ktech.audioengine.domain.PlaybackState
import com.kronos.ktech.audioengine.domain.PlaybackStatus
import com.kronos.ktech.audioengine.domain.RepeatMode
import com.kronos.ktech.audioengine.domain.Track
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
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
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioFramePosition
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioUnitEQ
import platform.AVFAudio.AVAudioUnitEQFilterParameters
import platform.AVFAudio.AVAudioUnitEQFilterTypeParametric
import platform.AVFAudio.setActive
import platform.Foundation.NSURL
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import kotlin.math.abs

// Real EQ requires AVAudioEngine's node graph - AVAudioPlayer (used previously here) has no
// effects graph at all and cannot host AVAudioUnitEQ (real-audio-equalizer proposal.md §2).
// This is a full swap of the playback primitive, not an additive change: AVAudioPlayerNode has
// no direct currentTime/play()/delegate-based-completion equivalents, all rebuilt below.
@OptIn(ExperimentalForeignApi::class)
actual class PlayerEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playbackState = MutableStateFlow(PlaybackState())
    actual val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Ticks every 200ms independently of playbackState - see the Android actual's matching
    // field for the full rationale.
    private val _positionMs = MutableStateFlow(0L)
    actual val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    actual val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // iOS has no API to enumerate output devices or force-select an arbitrary one - see
    // PlayerEngine.ios.kt's git history / mem:player for the AVRoutePickerView rationale.
    actual val availableOutputDevices: StateFlow<List<AudioOutputDevice>> = MutableStateFlow(emptyList())
    actual val selectedOutputDevice: StateFlow<AudioOutputDevice?> = MutableStateFlow(null)
    actual val outputSelectionMode: OutputSelectionMode = OutputSelectionMode.SYSTEM_PICKER

    private val engine = AVAudioEngine()
    private val playerNode = AVAudioPlayerNode()
    private val eqNode = AVAudioUnitEQ(numberOfBands = EqualizerBands.COUNT.toULong())

    private var audioFile: AVAudioFile? = null
    private var volume: Float = 1f
    private var tickerJob: Job? = null

    // The frame position within the CURRENT audio file that the currently-scheduled segment
    // started at - AVAudioPlayerNode has no direct "current position" getter, only
    // playerTimeForNodeTime(), which is relative to when the segment started playing.
    // currentFrame = segmentStartFrame + playerTime.sampleTime (the standard AVAudioPlayerNode
    // "current time" recipe - confirmed against multiple independent AVFoundation references,
    // not derived from a single product's implementation).
    private var segmentStartFrame: AVAudioFramePosition = 0

    // Bumped on every loadTrack()/stop() call. A completion callback captures the generation at
    // schedule time and only advances if it's still current when it fires - guards against a
    // real AVAudioPlayerNode gotcha: calling playerNode.stop() to load a NEW track also fires
    // the OLD segment's completion handler (stop counts as "playback ended" for this API), which
    // would otherwise incorrectly auto-advance past the track the user just explicitly changed
    // to. A plain pause() does not trigger completion, so this guard is scoped to track changes.
    private var scheduleGeneration = 0

    private val commandCenter = MPRemoteCommandCenter.sharedCommandCenter()

    init {
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        AVAudioSession.sharedInstance().setActive(true, error = null)

        engine.attachNode(playerNode)
        engine.attachNode(eqNode)
        engine.connect(playerNode, to = eqNode, format = null)
        engine.connect(eqNode, to = engine.mainMixerNode, format = null)
        configureEqualizerBands()
        eqNode.bypass = true
        installLevelTap()
        engine.prepare()
        engine.startAndReturnError(outError = null)

        commandCenter.playCommand.addTargetWithHandler { _ ->
            play()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.pauseCommand.addTargetWithHandler { _ ->
            pause()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.nextTrackCommand.addTargetWithHandler { _ ->
            skipNext()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandCenter.previousTrackCommand.addTargetWithHandler { _ ->
            skipPrevious()
            MPRemoteCommandHandlerStatusSuccess
        }

        tickerJob = scope.launch {
            while (isActive) {
                updatePositionFromNode()
                updateNowPlayingInfo()
                delay(200)
            }
        }
    }

    private fun configureEqualizerBands() {
        val bands = eqNode.bands
        for (i in 0 until EqualizerBands.COUNT) {
            val band = bands[i] as AVAudioUnitEQFilterParameters
            band.filterType = AVAudioUnitEQFilterTypeParametric
            band.frequency = EqualizerBands.FREQUENCIES_HZ[i].toFloat()
            band.bandwidth = 1.0f
            band.gain = 0f
        }
    }

    // AVAudioPlayerNode has no built-in metering (unlike AVAudioPlayer.meteringEnabled, used
    // previously here) - a tap on the post-EQ node reads real PCM and computes a peak level,
    // preserving the RhythmVisualizer feature this engine already drove on other platforms.
    private fun installLevelTap() {
        eqNode.installTapOnBus(bus = 0u, bufferSize = 1024u, format = null) { buffer, _ ->
            val channelData = buffer?.floatChannelData ?: return@installTapOnBus
            val frameLength = buffer.frameLength.toInt()
            if (frameLength == 0) return@installTapOnBus
            val channel0 = channelData[0] ?: return@installTapOnBus
            var peak = 0f
            for (i in 0 until frameLength) {
                val sample = abs(channel0[i])
                if (sample > peak) peak = sample
            }
            _audioLevel.value = peak.coerceIn(0f, 1f)
        }
    }

    private fun updatePositionFromNode() {
        val file = audioFile ?: return
        val nodeTime = playerNode.lastRenderTime ?: return
        val playerTime = playerNode.playerTimeForNodeTime(nodeTime) ?: return
        val currentFrame = segmentStartFrame + playerTime.sampleTime
        val sampleRate = file.processingFormat.sampleRate
        if (sampleRate > 0) {
            _positionMs.value = ((currentFrame.toDouble() / sampleRate) * 1000).toLong()
        }
    }

    private fun updateNowPlayingInfo() {
        val state = _playbackState.value
        val track = state.currentTrack
        if (track == null) {
            MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
            return
        }
        val info = buildMap<Any?, Any?> {
            put(MPMediaItemPropertyTitle, track.title)
            track.artist?.let { put(MPMediaItemPropertyArtist, it) }
            track.album?.let { put(MPMediaItemPropertyAlbumTitle, it) }
            put(MPMediaItemPropertyPlaybackDuration, (state.durationMs ?: 0L) / 1000.0)
            put(MPNowPlayingInfoPropertyElapsedPlaybackTime, _positionMs.value / 1000.0)
            put(MPNowPlayingInfoPropertyPlaybackRate, if (state.status == PlaybackStatus.PLAYING) 1.0 else 0.0)
        }
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    actual fun setQueue(tracks: List<Track>, startIndex: Int) {
        _positionMs.value = 0L
        _playbackState.update {
            it.copy(
                queue = tracks,
                currentIndex = startIndex,
                currentTrack = tracks.getOrNull(startIndex),
                positionMs = 0L,
                status = PlaybackStatus.IDLE,
            )
        }
        loadTrack(startIndex, autoplay = false)
    }

    actual fun play() {
        val state = _playbackState.value
        if (audioFile == null && state.currentTrack != null) {
            loadTrack(state.currentIndex, autoplay = true)
            return
        }
        if (!engine.running) engine.startAndReturnError(outError = null)
        playerNode.play()
        _playbackState.update { it.copy(status = PlaybackStatus.PLAYING) }
        updateNowPlayingInfo()
    }

    actual fun pause() {
        playerNode.pause()
        _playbackState.update { it.copy(status = PlaybackStatus.PAUSED) }
        updateNowPlayingInfo()
    }

    actual fun stop() {
        scheduleGeneration++
        playerNode.stop()
        audioFile = null
        _audioLevel.value = 0f
        _positionMs.value = 0L
        _playbackState.update { it.copy(status = PlaybackStatus.IDLE, positionMs = 0L) }
        updateNowPlayingInfo()
    }

    // Seeking on AVAudioPlayerNode means stopping and re-scheduling a fresh segment of the
    // same file starting at the target frame - there is no direct currentTime setter.
    actual fun seekTo(positionMs: Long) {
        val wasPlaying = _playbackState.value.status == PlaybackStatus.PLAYING
        loadTrack(_playbackState.value.currentIndex, autoplay = wasPlaying, resumeAtMs = positionMs)
        _positionMs.value = positionMs
        _playbackState.update { it.copy(positionMs = positionMs) }
        updateNowPlayingInfo()
    }

    actual fun skipNext() {
        advance(direction = 1)
    }

    actual fun skipPrevious() {
        advance(direction = -1)
    }

    actual fun setRepeatMode(mode: RepeatMode) {
        _playbackState.update { it.copy(repeatMode = mode) }
    }

    actual fun setShuffle(enabled: Boolean) {
        _playbackState.update { it.copy(shuffleEnabled = enabled) }
    }

    actual fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        playerNode.volume = this.volume
        _playbackState.update { it.copy(volume = this.volume) }
    }

    // Real, intentional no-op - selection happens through the system's own AVRoutePickerView
    // sheet (see outputSelectionMode/availableOutputDevices above), which the OS handles
    // entirely out of process. DevicesScreen never calls this on iOS.
    actual fun selectOutputDevice(deviceId: String?) = Unit

    actual fun setEqualizerEnabled(enabled: Boolean) {
        eqNode.bypass = !enabled
    }

    actual fun setEqualizerBands(gainsDb: FloatArray) {
        val bands = eqNode.bands
        for (i in 0 until minOf(EqualizerBands.COUNT, gainsDb.size)) {
            (bands[i] as AVAudioUnitEQFilterParameters).gain = gainsDb[i]
        }
    }

    actual fun release() {
        commandCenter.playCommand.removeTarget(null)
        commandCenter.pauseCommand.removeTarget(null)
        commandCenter.nextTrackCommand.removeTarget(null)
        commandCenter.previousTrackCommand.removeTarget(null)
        tickerJob?.cancel()
        scope.cancel()
        scheduleGeneration++
        eqNode.removeTapOnBus(0u)
        playerNode.stop()
        engine.stop()
    }

    private fun advance(direction: Int) {
        val state = _playbackState.value
        val queue = state.queue
        if (queue.isEmpty()) return

        val wasPlaying = state.status == PlaybackStatus.PLAYING
        val nextIndex = nextIndex(state, direction)
        if (nextIndex == null) {
            stop()
            return
        }
        _positionMs.value = 0L
        _playbackState.update { it.copy(currentIndex = nextIndex, currentTrack = queue[nextIndex], positionMs = 0L) }
        loadTrack(nextIndex, autoplay = wasPlaying)
    }

    private fun nextIndex(state: PlaybackState, direction: Int): Int? {
        val size = state.queue.size
        if (size == 0) return null

        if (state.shuffleEnabled && size > 1) {
            var candidate: Int
            do {
                candidate = (0 until size).random()
            } while (candidate == state.currentIndex)
            return candidate
        }

        val raw = state.currentIndex + direction
        return when {
            raw in 0 until size -> raw
            state.repeatMode == RepeatMode.ALL -> ((raw % size) + size) % size
            else -> null
        }
    }

    private fun loadTrack(index: Int, autoplay: Boolean, resumeAtMs: Long = 0L) {
        scheduleGeneration++
        val currentGeneration = scheduleGeneration
        playerNode.stop()
        audioFile = null

        val track = _playbackState.value.queue.getOrNull(index) ?: return
        val url = NSURL(string = track.uri)

        runCatching {
            val file = AVAudioFile(forReading = url, error = null)
            audioFile = file
            val sampleRate = file.processingFormat.sampleRate
            val startFrame: AVAudioFramePosition = if (resumeAtMs > 0 && sampleRate > 0) {
                (resumeAtMs / 1000.0 * sampleRate).toLong()
            } else {
                0L
            }
            segmentStartFrame = startFrame
            val frameCount = (file.length - startFrame).coerceAtLeast(0L).toUInt()
            playerNode.scheduleSegment(
                file = file,
                startingFrame = startFrame,
                frameCount = frameCount,
                atTime = null,
                completionCallbackType = AVAudioPlayerNodeCompletionDataPlayedBack,
                completionHandler = { _ ->
                    if (currentGeneration == scheduleGeneration) {
                        scope.launch { advance(direction = 1) }
                    }
                },
            )
            playerNode.volume = volume
            _playbackState.update {
                it.copy(
                    durationMs = if (sampleRate > 0) ((file.length.toDouble() / sampleRate) * 1000).toLong() else 0L,
                    status = if (autoplay) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                )
            }
            if (autoplay) {
                if (!engine.running) engine.startAndReturnError(outError = null)
                playerNode.play()
            }
            updateNowPlayingInfo()
        }.onFailure {
            _playbackState.update { it.copy(status = PlaybackStatus.ERROR) }
        }
    }
}
