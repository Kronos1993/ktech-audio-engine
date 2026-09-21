package com.kronos.ktech.audioengine

import co.touchlab.kermit.Logger
import com.kronos.ktech.audioengine.eq.EqualizerChain
import com.kronos.ktech.audioengine.domain.AudioOutputDevice
import com.kronos.ktech.audioengine.domain.AudioOutputDeviceType
import com.kronos.ktech.audioengine.domain.EqualizerBands
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.FloatControl
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.log10

// javax.sound.sampled has no device-connected/disconnected push notification (unlike
// Android's AudioManager.registerAudioDeviceCallback) - available outputs are refreshed on
// this fixed interval instead.
private const val OUTPUT_DEVICE_POLL_INTERVAL_MS = 3000L

actual class PlayerEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _playbackState = MutableStateFlow(PlaybackState())
    actual val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Updated on every decoded audio frame (far more often than 200ms) independently of
    // playbackState - see the Android actual's matching field for the full rationale.
    private val _positionMs = MutableStateFlow(0L)
    actual val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    actual val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _availableOutputDevices = MutableStateFlow<List<AudioOutputDevice>>(emptyList())
    actual val availableOutputDevices: StateFlow<List<AudioOutputDevice>> = _availableOutputDevices.asStateFlow()

    private val _selectedOutputDevice = MutableStateFlow<AudioOutputDevice?>(null)
    actual val selectedOutputDevice: StateFlow<AudioOutputDevice?> = _selectedOutputDevice.asStateFlow()

    // Desktop can enumerate real mixers and pin javax.sound.sampled lines to a specific one -
    // DevicesScreen renders its own selectable row list here.
    actual val outputSelectionMode: OutputSelectionMode = OutputSelectionMode.IN_APP_LIST

    private var playbackJob: Job? = null
    private var currentLine: SourceDataLine? = null
    private var volume: Float = 1f

    // The mixer currently backing currentLine (null = system default) - read by
    // startTrack() when opening a fresh line for a new track, so an in-progress device
    // selection survives a track change, not just a mid-track hot-swap.
    private var currentMixerInfo: Mixer.Info? = null

    @Volatile private var isPaused = false

    @Volatile private var pendingSeekMs: Long? = null

    // One EqualizerChain per audio channel of the CURRENT track (rebuilt in startTrack() once
    // grabber.audioChannels is known) - interleaved multi-channel PCM would otherwise corrupt
    // a single shared filter's internal state by alternating unrelated channels' samples
    // through it. equalizerEnabled/equalizerGainsDb are the source of truth applied to every
    // newly (re)built channel list, since a track change / device switch discards and
    // recreates equalizerChannels entirely.
    private var equalizerEnabled = false
    private var equalizerGainsDb = FloatArray(EqualizerBands.COUNT)
    private var equalizerChannels: List<EqualizerChain> = emptyList()

    init {
        scope.launch {
            while (isActive) {
                refreshAvailableOutputDevices()
                delay(OUTPUT_DEVICE_POLL_INTERVAL_MS)
            }
        }
        if (PlayerEngineJniBridge.isAvailable) {
            PlayerEngineJniBridge.nativeRegisterCommands()
            PlayerEngineJniBridge.onCommand = { command ->
                when (command) {
                    NowPlayingCommand.PLAY -> play()
                    NowPlayingCommand.PAUSE -> pause()
                    NowPlayingCommand.NEXT -> skipNext()
                    NowPlayingCommand.PREVIOUS -> skipPrevious()
                }
            }
        }
    }

    private fun syncNowPlaying() {
        if (!PlayerEngineJniBridge.isAvailable) return
        val state = _playbackState.value
        val track = state.currentTrack
        if (track == null) {
            PlayerEngineJniBridge.nativeClearNowPlaying()
            return
        }
        PlayerEngineJniBridge.nativeUpdateNowPlaying(
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkPath = track.artworkUri?.let { resolveMediaPath(it) },
            durationMs = state.durationMs ?: 0L,
            positionMs = _positionMs.value,
            queueIndex = state.currentIndex.toLong(),
            queueCount = state.queue.size.toLong(),
            isPlaying = state.status == PlaybackStatus.PLAYING,
        )
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
        startTrack(startIndex, autoplay = false)
        syncNowPlaying()
    }

    actual fun play() {
        val state = _playbackState.value
        if (state.currentTrack == null) return
        if (playbackJob?.isActive == true) {
            isPaused = false
            _playbackState.update { it.copy(status = PlaybackStatus.PLAYING) }
            syncNowPlaying()
        } else {
            startTrack(state.currentIndex, autoplay = true, resumeAtMs = state.positionMs)
        }
    }

    actual fun pause() {
        // isPaused is @Volatile - the playback loop (running on its own thread) reads
        // it and calls line.stop()/start() itself; this function must never touch
        // currentLine directly (see startTrack()'s comment for why that was unreliable).
        isPaused = true
        _playbackState.update { it.copy(status = PlaybackStatus.PAUSED) }
        syncNowPlaying()
    }

    actual fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        closeLine()
        _audioLevel.value = 0f
        _positionMs.value = 0L
        _playbackState.update { it.copy(status = PlaybackStatus.IDLE, positionMs = 0L) }
        if (PlayerEngineJniBridge.isAvailable) PlayerEngineJniBridge.nativeClearNowPlaying()
    }

    actual fun seekTo(positionMs: Long) {
        pendingSeekMs = positionMs
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
        applyVolume(currentLine)
        _playbackState.update { it.copy(volume = this.volume) }
    }

    actual fun selectOutputDevice(deviceId: String?) {
        val mixerInfo = deviceId?.let { id -> AudioSystem.getMixerInfo().firstOrNull { it.name == id } }
        currentMixerInfo = mixerInfo
        _selectedOutputDevice.value = deviceId?.let { id -> _availableOutputDevices.value.firstOrNull { it.id == id } }

        // A genuine restart, not an in-place line hot-swap: ffmpeg's resampler (see
        // startTrack()'s own comment on FFmpegFrameGrabber.sampleRate) must be configured
        // BEFORE grabber.start(), so the only reliable way to pick up a new device's native
        // sample rate mid-track is to re-run startTrack() for the same track/position with a
        // fresh grabber - this reuses the exact same job-cancellation/grabber-creation path a
        // real track change already goes through, not a new/parallel code path. Real-device
        // testing confirmed the earlier in-place hot-swap (same grabber, same decoded format,
        // just reopening the line on the new mixer) silently failed for any device requiring
        // a different native sample rate than the current grabber's - the failure was caught
        // by openLine()'s own fallback, but that meant the device could never actually play,
        // which read as "can't select it" even though the state itself updated correctly.
        val state = _playbackState.value
        if (state.currentTrack != null) {
            startTrack(state.currentIndex, autoplay = state.status == PlaybackStatus.PLAYING, resumeAtMs = _positionMs.value)
        }
    }

    actual fun setEqualizerEnabled(enabled: Boolean) {
        equalizerEnabled = enabled
        equalizerChannels.forEach { it.setEnabled(enabled) }
    }

    actual fun setEqualizerBands(gainsDb: FloatArray) {
        equalizerGainsDb = gainsDb.copyOf()
        equalizerChannels.forEach { it.setBandGains(equalizerGainsDb) }
    }

    actual fun release() {
        playbackJob?.cancel()
        playbackJob = null
        closeLine()
        scope.cancel()
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
        startTrack(nextIndex, autoplay = wasPlaying)
        syncNowPlaying()
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

    private fun startTrack(index: Int, autoplay: Boolean, resumeAtMs: Long = 0L) {
        val previousJob = playbackJob
        val track = _playbackState.value.queue.getOrNull(index) ?: return

        isPaused = !autoplay
        pendingSeekMs = if (resumeAtMs > 0) resumeAtMs else null
        _playbackState.update {
            it.copy(status = if (autoplay) PlaybackStatus.BUFFERING else PlaybackStatus.PAUSED)
        }

        playbackJob = scope.launch {
            // cancel() alone only *requests* cancellation - it doesn't interrupt a
            // blocking (non-suspending) line.write() call already in flight on the
            // previous job's thread. Without joining, this new coroutine could open a
            // fresh line and start writing to it while the old coroutine's write()
            // call was still blocked mid-call on the *old* line, and both coroutines'
            // _playbackState updates could land in either order - observed as "skip
            // next changes the track but doesn't actually start playing" when the old
            // job's cleanup (closeLine(), the ERROR/finally path) won the race and ran
            // after this one's PLAYING update.
            previousJob?.cancelAndJoin()
            closeLine()

            val grabber = FFmpegFrameGrabber(resolveMediaPath(track.uri))
            try {
                // Must be set BEFORE start() - FFmpegFrameGrabber only wires ffmpeg's
                // swresample filter into the decode pipeline at start() time; setting
                // sampleRate afterward has no effect on already-decoding frames. When a
                // specific output device is selected and declares a fixed native sample rate
                // (common for Bluetooth audio), forcing the grabber to decode straight to
                // that rate is what lets the line-open below actually succeed on that device,
                // instead of opening at the source file's own native rate and having
                // javax.sound.sampled reject it as an unsupported format for that mixer.
                preferredSampleRateFor(currentMixerInfo)?.let { grabber.sampleRate = it }
                grabber.start()
                equalizerChannels = List(grabber.audioChannels) {
                    EqualizerChain().also { chain ->
                        chain.setEnabled(equalizerEnabled)
                        chain.setBandGains(equalizerGainsDb)
                    }
                }
                // Reads the *current* isPaused flag, not the autoplay param this
                // coroutine was launched with: setQueue() always calls startTrack with
                // autoplay=false (so restoring a session doesn't auto-play), then
                // PlayerViewModel.playQueue() immediately calls play() right after -
                // which flips isPaused synchronously, but this coroutine's own status
                // update happens asynchronously and could otherwise land *after*
                // play()'s update using the stale, already-outdated autoplay=false it
                // captured at launch, overwriting PLAYING back to PAUSED. This is
                // exactly what showed the mini-player's Play icon (implying paused)
                // immediately after starting a track that was actually already playing.
                _playbackState.update {
                    it.copy(
                        durationMs = grabber.lengthInTime / 1000,
                        status = if (!isPaused) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                    )
                }
                syncNowPlaying()

                val format = AudioFormat(grabber.sampleRate.toFloat(), 16, grabber.audioChannels, true, false)
                val line = openLine(format)
                currentLine = line
                applyVolume(line)

                var lastNowPlayingSyncAt = System.currentTimeMillis()

                while (isActive) {
                    val seekMs = pendingSeekMs
                    if (seekMs != null) {
                        grabber.timestamp = seekMs * 1000L
                        pendingSeekMs = null
                        _positionMs.value = seekMs
                        _playbackState.update { it.copy(positionMs = seekMs) }
                    }

                    if (isPaused) {
                        // Driven from this same thread (the only one that also calls
                        // write() on this line) rather than calling stop()/start()
                        // directly from pause()/play() on a different thread - mixing
                        // write() on one thread with stop()/start() on another is not
                        // reliably synchronous across all javax.sound.sampled mixer
                        // implementations, and was the cause of pause sometimes needing
                        // a second tap to actually silence the line.
                        if (line.isRunning) line.stop()
                        delay(50)
                        continue
                    }
                    if (!line.isRunning) line.start()

                    val frame = grabber.grabSamples()
                    if (frame == null) {
                        // End of stream — advance according to repeat mode. RepeatMode.ONE
                        // replays the same track rather than going through nextIndex(), which
                        // has no ONE case (it only special-cases ALL) — nextIndex() is shared
                        // with manual skip, where repeat-one must NOT stop skip-to-next/previous
                        // from actually changing tracks.
                        val state = _playbackState.value
                        if (state.repeatMode == RepeatMode.ONE) {
                            startTrack(state.currentIndex, autoplay = true)
                        } else {
                            val next = nextIndex(state, direction = 1)
                            if (next == null) {
                                stop()
                            } else {
                                _positionMs.value = 0L
                                _playbackState.update { it.copy(currentIndex = next, currentTrack = state.queue[next], positionMs = 0L) }
                                startTrack(next, autoplay = true)
                            }
                        }
                        return@launch
                    }

                    val samples = frame.samples?.getOrNull(0) as? ShortBuffer ?: continue
                    val shortArray = ShortArray(samples.remaining())
                    samples.get(shortArray)

                    if (equalizerChannels.isNotEmpty()) {
                        val sampleRateHz = grabber.sampleRate
                        val channelCount = equalizerChannels.size
                        for (i in shortArray.indices) {
                            shortArray[i] = equalizerChannels[i % channelCount].processSample(sampleRateHz, shortArray[i])
                        }
                    }

                    var peak = 0
                    for (s in shortArray) {
                        val a = abs(s.toInt())
                        if (a > peak) peak = a
                    }
                    _audioLevel.value = (peak / Short.MAX_VALUE.toFloat()).coerceIn(0f, 1f)

                    val byteBuffer = ByteBuffer.allocate(shortArray.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    byteBuffer.asShortBuffer().put(shortArray)
                    line.write(byteBuffer.array(), 0, byteBuffer.array().size)

                    _positionMs.value = frame.timestamp / 1000

                    // Now Playing position sync is throttled to ~1/sec — pushing it on
                    // every audio frame (tens of times/sec) would be wasteful native-call
                    // churn for no perceptible Control Center benefit.
                    val now = System.currentTimeMillis()
                    if (now - lastNowPlayingSyncAt >= 1000L) {
                        lastNowPlayingSyncAt = now
                        syncNowPlaying()
                    }
                }
            } catch (t: Throwable) {
                _playbackState.update { it.copy(status = PlaybackStatus.ERROR) }
            } finally {
                runCatching { grabber.stop() }
                runCatching { grabber.release() }
            }
        }
    }

    // DeviceMusicScanner.jvm.kt hands us File.toURI() strings (percent-encoded, e.g. spaces
    // as %20). FFmpeg's "file:" protocol does NOT URL-decode — it treats %20 as literal
    // characters — so a raw "file:" URI silently fails to open for any path with spaces or
    // other escaped characters. Round-trip through java.net.URI/File to get the real path.
    private fun resolveMediaPath(uri: String): String = try {
        val parsed = URI(uri)
        if (parsed.scheme == "file") File(parsed).absolutePath else uri
    } catch (t: Throwable) {
        uri
    }

    private fun refreshAvailableOutputDevices() {
        val sourceLineInfo = DataLine.Info(SourceDataLine::class.java, null)
        val mixers = AudioSystem.getMixerInfo()
            .filter { runCatching { AudioSystem.getMixer(it).isLineSupported(sourceLineInfo) }.getOrDefault(false) }

        // javax.sound.sampled has no built-in way to tell a real hardware output from a
        // virtual/software one - apps like Teams/Zoom register their own virtual audio
        // devices that otherwise look identical to a real speaker (confirmed via real-machine
        // testing on macOS: "Microsoft Teams Audio"/"ZoomAudioDevice" both showed up
        // unfiltered). Both native bridges' nativeGetPhysicalOutputDeviceNames() query the
        // real OS-level answer directly (CoreAudio's kAudioDevicePropertyTransportType on
        // macOS, PKEY_Device_EnumeratorName's PnP-enumerator prefix on Windows - see each
        // module's own NowPlayingBridge.kt) and also return each device's coarse type, encoded
        // as "TYPE|name" per entry (avoids a second native round-trip per device just to also
        // learn its type). Falls back to the unfiltered list, all typed OTHER, on Linux (no
        // native bridge exists there) or if the native call itself fails for any reason (e.g.
        // a stale dev-mode dylib/dll built before this existed) — better to show a virtual
        // device than to silently show nothing.
        val physicalDeviceTypes = if (PlayerEngineJniBridge.isAvailable) {
            runCatching {
                PlayerEngineJniBridge.nativeGetPhysicalOutputDeviceNames().associate { entry ->
                    val (type, name) = entry.split("|", limit = 2)
                    name to runCatching { AudioOutputDeviceType.valueOf(type) }.getOrDefault(AudioOutputDeviceType.OTHER)
                }
            }.getOrNull()
        } else {
            null
        }

        _availableOutputDevices.value = mixers
            .filter { physicalDeviceTypes == null || it.name in physicalDeviceTypes }
            .map { AudioOutputDevice(id = it.name, name = it.name, type = physicalDeviceTypes?.get(it.name) ?: AudioOutputDeviceType.OTHER) }
    }

    // Opens a line for the given (already device-appropriate, see startTrack()'s
    // preferredSampleRateFor() call) format on the currently selected mixer, falling back to
    // the system default if that specific mixer still rejects it for any reason - better to
    // play on the wrong device than not play at all. Deliberately does NOT clear
    // currentMixerInfo/_selectedOutputDevice on fallback (an earlier version did) - real-device
    // testing showed that reverting the visible selection whenever the pinned line fails reads
    // to the user as "I can't select this device" rather than "it's using a fallback", even
    // when the fallback is functionally harmless (e.g. the picked device already IS the OS
    // default). Keeping the user's picked device "sticky" also means a later track/retry gets
    // another real attempt at the pinned mixer, instead of silently giving up on it forever
    // after the first failure.
    private fun openLine(format: AudioFormat): SourceDataLine {
        val mixerInfo = currentMixerInfo
        return try {
            openLineOn(format, mixerInfo)
        } catch (t: Throwable) {
            if (mixerInfo == null) throw t
            // Logged, not swallowed - the previous version's bare catch-and-fallback gave no
            // visible signal at all of what actually failed, which made this genuinely
            // undiagnosable from a user-supplied terminal log.
            Logger.e(
                messageString = "openLine: getSourceDataLine failed for mixer '${mixerInfo.name}', falling back to system default",
                throwable = t,
                tag = "PlayerEngine",
            )
            openLineOn(format, mixerInfo = null)
        }
    }

    private fun openLineOn(format: AudioFormat, mixerInfo: Mixer.Info?): SourceDataLine {
        val line = if (mixerInfo != null) AudioSystem.getSourceDataLine(format, mixerInfo) else AudioSystem.getSourceDataLine(format)
        line.open(format)
        return line
    }

    // The first fixed (non-negotiable/"any rate") sample rate the mixer's own SourceDataLine
    // formats declare, if any - used to pre-configure FFmpegFrameGrabber (see startTrack())
    // so its decoded output already matches what the target device actually accepts, rather
    // than discovering the mismatch only once the line fails to open. Returns null for the
    // system default (mixerInfo == null, needs no forcing) or if the mixer's declared formats
    // don't pin a specific rate (any is fine, or nothing could be determined) - startTrack()
    // leaves the grabber's rate untouched in that case, matching its original behavior.
    private fun preferredSampleRateFor(mixerInfo: Mixer.Info?): Int? {
        mixerInfo ?: return null
        val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull() ?: return null
        val sourceLineInfos = runCatching { mixer.sourceLineInfo }.getOrDefault(emptyArray())
        for (info in sourceLineInfos) {
            val dataLineInfo = info as? DataLine.Info ?: continue
            for (candidate in dataLineInfo.formats) {
                if (candidate.sampleRate > 0) return candidate.sampleRate.toInt()
            }
        }
        return null
    }

    private fun applyVolume(line: SourceDataLine?) {
        val control = line?.takeIf { it.isControlSupported(FloatControl.Type.MASTER_GAIN) }
            ?.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl ?: return
        val dB = (20f * log10(volume.coerceIn(0.0001f, 1f))).coerceIn(control.minimum, control.maximum)
        control.value = dB
    }

    private fun closeLine() {
        currentLine?.let { line ->
            runCatching { line.stop() }
            runCatching { line.close() }
        }
        currentLine = null
    }
}
