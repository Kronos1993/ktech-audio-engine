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

    @Volatile private var crossfadeMs: Long = 0L

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

    actual fun setCrossfadeDuration(durationMs: Long) {
        crossfadeMs = durationMs.coerceAtLeast(0L)
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

            var source: PcmSource? = null
            // The next track while a crossfade is in progress; promoted to source when the
            // outgoing one ends or the fade completes.
            var incoming: PcmSource? = null
            var fadeTotalFrames = 0L
            var fadeDoneFrames = 0L
            // The source whose fade-or-cut decision was already made (decided once per track).
            var fadeDecidedFor: PcmSource? = null
            try {
                val grabber = FFmpegFrameGrabber(resolveMediaPath(track.uri))
                // Must be set BEFORE start() - FFmpegFrameGrabber only wires ffmpeg's
                // swresample filter into the decode pipeline at start() time; setting
                // sampleRate afterward has no effect on already-decoding frames. When a
                // specific output device is selected and declares a fixed native sample rate
                // (common for Bluetooth audio), forcing the grabber to decode straight to
                // that rate is what lets the line-open below actually succeed on that device,
                // instead of opening at the source file's own native rate and having
                // javax.sound.sampled reject it as an unsupported format for that mixer.
                preferredSampleRateFor(currentMixerInfo)?.let { grabber.sampleRate = it }
                source = PcmSource(index, grabber)
                grabber.start()
                val sampleRate = grabber.sampleRate
                val channels = grabber.audioChannels
                source.configure(sampleRate, channels)
                equalizerChannels = List(channels) {
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
                        durationMs = source.durationMs,
                        status = if (!isPaused) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                    )
                }
                syncNowPlaying()

                // The line stays open for every natural track change below (gapless); later
                // tracks are decoded straight to this format. Only startTrack() reopens it.
                val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
                val line = openLine(format)
                currentLine = line
                applyVolume(line)

                var lastNowPlayingSyncAt = System.currentTimeMillis()

                while (isActive) {
                    val seekMs = pendingSeekMs
                    if (seekMs != null) {
                        pendingSeekMs = null
                        // A seek during a fade belongs to the incoming track (already current).
                        incoming?.let { next ->
                            source?.close()
                            source = next
                            incoming = null
                        }
                        source!!.seek(seekMs)
                        fadeDecidedFor = null
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

                    val outgoing = source!!
                    if (incoming == null && fadeDecidedFor !== outgoing && isInCrossfadeWindow(outgoing)) {
                        fadeDecidedFor = outgoing
                        incoming = beginCrossfade(outgoing, sampleRate, channels)
                        if (incoming != null) {
                            val windowMs = (outgoing.durationMs - outgoing.positionMs).coerceAtLeast(1L)
                            fadeTotalFrames = windowMs * sampleRate / 1000
                            fadeDoneFrames = 0L
                        }
                    }

                    var samples = outgoing.next()
                    if (samples == null) {
                        outgoing.close()
                        val next = incoming
                        if (next != null) {
                            source = next
                            incoming = null
                            continue
                        }
                        // End of stream: continue on the same open line. RepeatMode.ONE
                        // replays the same track rather than going through nextIndex(), which
                        // has no ONE case (it only special-cases ALL) — nextIndex() is shared
                        // with manual skip, where repeat-one must NOT stop skip-to-next/previous
                        // from actually changing tracks.
                        val state = _playbackState.value
                        val nextIndex = if (state.repeatMode == RepeatMode.ONE) outgoing.index else nextIndex(state, direction = 1)
                        if (nextIndex == null) {
                            source = null
                            stop()
                            return@launch
                        }
                        source = openNextSource(nextIndex, sampleRate, channels)
                        continue
                    }

                    val fading = incoming
                    if (fading != null) {
                        samples = mix(samples, fading.take(samples.size), channels, fadeDoneFrames, fadeTotalFrames)
                        fadeDoneFrames += samples.size / channels
                    }

                    if (equalizerChannels.isNotEmpty()) {
                        val channelCount = equalizerChannels.size
                        for (i in samples.indices) {
                            samples[i] = equalizerChannels[i % channelCount].processSample(sampleRate, samples[i])
                        }
                    }

                    var peak = 0
                    for (s in samples) {
                        val a = abs(s.toInt())
                        if (a > peak) peak = a
                    }
                    _audioLevel.value = (peak / Short.MAX_VALUE.toFloat()).coerceIn(0f, 1f)

                    val byteBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    byteBuffer.asShortBuffer().put(samples)
                    line.write(byteBuffer.array(), 0, byteBuffer.array().size)

                    if (fading != null && fadeDoneFrames >= fadeTotalFrames) {
                        // The outgoing track is silent from here on: drop it.
                        outgoing.close()
                        source = fading
                        incoming = null
                    }
                    _positionMs.value = (incoming ?: source)!!.positionMs

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
                source?.close()
                incoming?.close()
            }
        }
    }

    // Opens the queue item at [index] decoded to the open line's format (swresample converts any
    // rate/channel difference), and makes it the current track.
    private fun openNextSource(index: Int, sampleRate: Int, channels: Int): PcmSource {
        val state = _playbackState.value
        val track = state.queue[index]
        val grabber = FFmpegFrameGrabber(resolveMediaPath(track.uri))
        grabber.sampleRate = sampleRate
        grabber.audioChannels = channels
        val source = PcmSource(index, grabber)
        try {
            grabber.start()
        } catch (t: Throwable) {
            source.close()
            throw t
        }
        source.configure(sampleRate, channels)
        _positionMs.value = 0L
        _playbackState.update {
            it.copy(currentIndex = index, currentTrack = track, positionMs = 0L, durationMs = source.durationMs)
        }
        syncNowPlaying()
        return source
    }

    private fun isInCrossfadeWindow(outgoing: PcmSource): Boolean {
        val durationMs = outgoing.durationMs
        if (crossfadeMs == 0L || durationMs <= 0) return false
        return durationMs - outgoing.positionMs <= minOf(crossfadeMs, durationMs / 2)
    }

    // Opens the next track for a crossfade, or returns null when this transition must stay a
    // plain gapless cut (see Crossfade.windowMs).
    private fun beginCrossfade(outgoing: PcmSource, sampleRate: Int, channels: Int): PcmSource? {
        val durationMs = outgoing.durationMs
        val remainingMs = durationMs - outgoing.positionMs
        val state = _playbackState.value
        if (state.repeatMode == RepeatMode.ONE) return null
        val nextIndex = nextIndex(state, direction = 1) ?: return null
        val windowMs = Crossfade.windowMs(
            requestedMs = crossfadeMs,
            outgoing = state.queue.getOrNull(outgoing.index),
            outgoingIndex = outgoing.index,
            outgoingDurationMs = durationMs,
            incoming = state.queue.getOrNull(nextIndex),
            incomingIndex = nextIndex,
        )
        if (windowMs == 0L || remainingMs > windowMs) return null
        return runCatching { openNextSource(nextIndex, sampleRate, channels) }.getOrNull()
    }

    // Equal-power mix of one outgoing chunk with the same number of incoming samples.
    private fun mix(outgoing: ShortArray, incoming: ShortArray, channels: Int, doneFrames: Long, totalFrames: Long): ShortArray {
        val out = ShortArray(outgoing.size)
        val frames = outgoing.size / channels
        for (f in 0 until frames) {
            val progress = ((doneFrames + f).toFloat() / totalFrames).coerceIn(0f, 1f)
            val outGain = Crossfade.fadeOutGain(progress)
            val inGain = Crossfade.fadeInGain(progress)
            for (c in 0 until channels) {
                val i = f * channels + c
                val incomingSample = if (i < incoming.size) incoming[i] else 0
                val mixed = outgoing[i] * outGain + incomingSample * inGain
                out[i] = mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        return out
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

// One decoded queue item, read as interleaved 16-bit PCM. Position is derived from the samples
// handed out, so it stays exact when chunks are split across a crossfade mix.
private class PcmSource(
    val index: Int,
    private val grabber: FFmpegFrameGrabber,
) {
    private var sampleRate = 1
    private var channels = 1
    private var buffer = ShortArray(0)
    private var offset = 0
    private var startMs = 0L
    private var consumedFrames = 0L

    val durationMs: Long get() = grabber.lengthInTime / 1000

    val positionMs: Long get() = startMs + consumedFrames * 1000 / sampleRate

    fun configure(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate.coerceAtLeast(1)
        this.channels = channels.coerceAtLeast(1)
    }

    fun seek(positionMs: Long) {
        grabber.timestamp = positionMs * 1000L
        buffer = ShortArray(0)
        offset = 0
        startMs = positionMs
        consumedFrames = 0L
    }

    // The next chunk of samples (leftover from take() first), or null at end of stream.
    fun next(): ShortArray? {
        val chunk = if (offset < buffer.size) {
            buffer.copyOfRange(offset, buffer.size).also { offset = buffer.size }
        } else {
            decode() ?: return null
        }
        consumedFrames += chunk.size / channels
        return chunk
    }

    // Exactly [count] samples, fewer only at end of stream.
    fun take(count: Int): ShortArray {
        val out = ShortArray(count)
        var filled = 0
        while (filled < count) {
            if (offset >= buffer.size) {
                buffer = decode() ?: break
                offset = 0
            }
            val n = minOf(count - filled, buffer.size - offset)
            buffer.copyInto(out, filled, offset, offset + n)
            offset += n
            filled += n
        }
        consumedFrames += filled / channels
        return if (filled == count) out else out.copyOf(filled)
    }

    private fun decode(): ShortArray? {
        while (true) {
            val frame = grabber.grabSamples() ?: return null
            val samples = frame.samples?.getOrNull(0) as? ShortBuffer ?: continue
            return ShortArray(samples.remaining()).also { samples.get(it) }
        }
    }

    fun close() {
        runCatching { grabber.stop() }
        runCatching { grabber.release() }
    }
}
