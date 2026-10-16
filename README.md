# ktech-audio-engine

A Kotlin Multiplatform audio playback engine for Android, iOS, and Desktop (JVM). It drives one
shared playback state across all three platforms:

- **Android** — Media3/ExoPlayer, with a `MediaSessionService`-backed notification (shuffle/repeat
  buttons, tap-to-open).
- **Desktop/JVM** — JavaCV/ffmpeg decoding + `javax.sound.sampled` output, with native macOS
  Control Center and Windows SMTC now-playing integration.
- **iOS** — `AVAudioEngine`/`AVAudioUnitEQ`.

All three platforms share the same commonMain API (`PlayerEngine`), the same 10-band equalizer DSP
chain, and the same domain model (`Track`, `PlaybackState`, `RepeatMode`, `AudioOutputDevice`,
`EqualizerBands`).

Used today by [Lumina Sound](https://github.com/Kronos1993/Lumina-Sound), a Kotlin Multiplatform
music player — the module's first real-world consumer, and where its Android/iOS/Desktop
implementations are validated on real devices.

## Status

[Lumina Sound](https://github.com/Kronos1993/Lumina-Sound), a Kotlin Multiplatform music player, is
this library's first real-world consumer — currently mid-migration to consuming it via a git
submodule + Gradle composite build (`includeBuild`) rather than an in-repo module.

## Installation

```kotlin
// gradle/libs.versions.toml
[versions]
ktechAudioEngine = "1.0.0" // use the latest released tag, without the leading "v"

[libraries]
ktech-audio-engine = { module = "io.github.kronos1993:ktech-audio-engine", version.ref = "ktechAudioEngine" }
```

```kotlin
// build.gradle.kts
dependencies {
    implementation(libs.ktech.audio.engine)
}
```

Or without a version catalog:

```kotlin
dependencies {
    implementation("io.github.kronos1993:ktech-audio-engine:1.0.0")
}
```

## Requires Koin

**This library requires [Koin](https://insert-koin.io/) — there is no DI-framework-agnostic
alternative.** `playerEngineModule` (below) is the *only* way this library hands you a
`PlayerEngine` instance: internally it's an `expect`/`actual` Koin `Module` that, per platform,
knows how to construct the real engine (Media3 on Android, AVAudioEngine on iOS, JavaCV/ffmpeg on
Desktop) and registers it as a Koin singleton. There's no public `PlayerEngine` constructor you can
call directly, and no factory-function escape hatch — if your app uses a different DI framework (or
none), you still need Koin running *for this one dependency*, even if it's the only thing Koin
manages in your app.

### Setup

```kotlin
// commonMain — call this once, at app startup, before anything asks for a PlayerEngine.
import com.kronos.ktech.audioengine.di.playerEngineModule
import org.koin.core.context.startKoin

fun initKoin() {
    startKoin {
        // playerEngineModule registers the platform-specific PlayerEngine singleton described
        // above. List your own app's modules alongside it — order relative to your own modules
        // doesn't matter, since nothing in this library depends on your app's own bindings.
        modules(
            playerEngineModule,
            // ...your other modules
        )
    }
}
```

```kotlin
// androidMain, e.g. your Application.onCreate() — Android needs one extra thing.
//
// PlayerEngine's Android implementation is built on Media3/ExoPlayer, which needs a Context
// (to create the ExoPlayer instance, register the MediaSessionService, post the playback
// notification, etc.). playerEngineModule's Android actual pulls that Context from Koin's
// androidContext() — a standard koin-android call, not something specific to this library — so
// you must supply it before playerEngineModule is asked to create a PlayerEngine. If you skip
// this, resolving PlayerEngine on Android throws immediately (Koin can't satisfy the
// dependency), not silently — you'll know right away if you forgot it.
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MyApplication)
            modules(playerEngineModule)
        }
    }
}
```

## Usage

```kotlin
import com.kronos.ktech.audioengine.PlayerEngine
import com.kronos.ktech.audioengine.domain.EnqueuePosition
import com.kronos.ktech.audioengine.domain.EqualizerBands
import com.kronos.ktech.audioengine.domain.RepeatMode
import com.kronos.ktech.audioengine.domain.Track
import org.koin.mp.KoinPlatform

// Resolve the singleton PlayerEngine that playerEngineModule registered at startup. Anywhere in
// your app is fine — KoinPlatform.getKoin() reaches the same global Koin instance startKoin()
// created, so you don't need to thread PlayerEngine through constructors by hand.
val playerEngine: PlayerEngine = KoinPlatform.getKoin().get()

// Every piece of state is a StateFlow, so your UI (or ViewModel) just collects it - no polling,
// no callbacks to register/unregister.
playerEngine.playbackState.collect { state -> /* update your now-playing UI */ }
playerEngine.positionMs.collect { ms -> /* update your seek bar */ }

// setQueue replaces whatever was playing and starts at startIndex - this is also how you start
// playback the very first time (there's no separate "load" step).
playerEngine.setQueue(tracks = listOf(track1, track2), startIndex = 0)
playerEngine.play()
playerEngine.pause()
playerEngine.seekTo(30_000) // milliseconds into the current track
playerEngine.skipNext()
playerEngine.skipPrevious()
playerEngine.setRepeatMode(RepeatMode.ALL) // OFF / ALL / ONE
playerEngine.setShuffle(true)
playerEngine.setVolume(0.8f) // 0f (silent) .. 1f (full)

// Edit the queue in place, without interrupting the current track. playbackState.queue and
// currentIndex update together, so queue[currentIndex] is always the playing row.
// NEXT plays right after the current track, even with shuffle on; several NEXT calls play in the
// order they were made. END appends (and joins the shuffle order). With nothing loaded, enqueue
// behaves like setQueue(tracks, 0) - call play() to start.
playerEngine.enqueue(listOf(track3), EnqueuePosition.NEXT)
playerEngine.enqueue(listOf(track4, track5), EnqueuePosition.END)
// Removing the current row moves on to the next one, keeping play/pause; with nothing after it,
// playback ends as at the natural end of the queue.
playerEngine.removeFromQueue(indices = setOf(2, 3))
// Reorder: the playing track keeps playing, and currentIndex follows it.
playerEngine.moveQueueItem(from = 4, to = 1)

// Output device selection: check outputSelectionMode first. Not every platform can enumerate or
// switch output devices programmatically - IN_APP_LIST means you render availableOutputDevices
// yourself and call selectOutputDevice; SYSTEM_PICKER (iOS) means you should embed the
// platform's own picker UI instead, since there's nothing to select from availableOutputDevices
// there.
playerEngine.availableOutputDevices.collect { devices -> /* render your own device list */ }
playerEngine.selectOutputDevice(deviceId = "some-device-id")

// The built-in 10-band equalizer. gainsDb must have exactly EqualizerBands.COUNT (10) entries,
// ordered to match EqualizerBands.FREQUENCIES_HZ (31Hz..16kHz) - here, all bands flat/off.
playerEngine.setEqualizerEnabled(true)
playerEngine.setEqualizerBands(gainsDb = FloatArray(EqualizerBands.COUNT) { 0f })

// Equalizer extras, all bypassed while the equalizer is disabled: a pre-amp before the bands
// (negative = headroom), a 100 Hz bass-boost low shelf, and mid/side stereo widening (0..100%,
// Android and Desktop only).
playerEngine.setEqualizerPreamp(preampDb = -6f)
playerEngine.setBassBoost(gainDb = 4f)
if (playerEngine.supportsStereoWidening) playerEngine.setStereoWidth(percent = 30f)

// Track changes are gapless on every platform. Optionally crossfade natural end-of-track
// transitions (0 = off, the default). Manual skips, seeks, repeat-one and http(s) streams always
// cut, and the fade never exceeds half of either track.
playerEngine.setCrossfadeDuration(durationMs = 6_000)

// Playback speed, 0.5x..3x (1f = normal), pitch preserved. positionMs stays in track time.
playerEngine.setPlaybackSpeed(1.5f)

// Shortens silent passages (pauses longer than ~300 ms) on Android and Desktop. iOS has no PCM
// path to cut on, so there supportsSkipSilence is false and this call is a no-op.
if (playerEngine.supportsSkipSilence) playerEngine.setSkipSilenceEnabled(true)

// ReplayGain normalization (OFF by default). Tags are read from each file as it loads (ID3 TXXX,
// Vorbis comments, MP4 freeform atoms). preampDb is added to tagged tracks; untagged tracks play
// at fallbackDb. A peak tag caps the gain so the track never clips. Independent of setVolume.
playerEngine.setReplayGain(ReplayGainMode.ALBUM, preampDb = 0f, fallbackDb = -6f)

// Shows a line (e.g. the current lyric) in place of the artist on the system now-playing
// surfaces (Android notification/lock screen, iOS lock screen, macOS/Windows media controls).
// null restores the artist; the override is dropped automatically when the track changes.
playerEngine.setNowPlayingLyricLine("Is this the real life?")

// Call this once, when your app is done with playback entirely (not on every screen exit) -
// it tears down the underlying platform player and the engine can't be used again afterward.
playerEngine.release()
```

See `PlayerEngine`'s KDoc for the full API and per-member detail.

### Android Auto / media browsers (Android only)

`PlaybackService` is a Media3 `MediaLibraryService`. To show a browse tree in Android Auto (or any
`MediaBrowser`), bind a `MediaBrowseProvider` in Koin. The service delegates the library callbacks
(root, children, search, `onSetMediaItems`, playback resumption, …) to it, and keeps connection
handling and its notification buttons. With no binding, nothing changes.

A controller's set/add of media items reaches `onSetMediaItemsRequest` / `onAddMediaItemsRequest`
on the main thread before touching the player. Return `true` after applying them yourself, normally
with `PlayerEngine.setQueue`, so the engine's queue and state stay in sync.

Every session controller's play request (`play()`, or `playWhenReady` set to `true`) first asks
`isPlaybackAllowed(session, controller)`; return `false` to ignore it, for example to lock a car
connection. The app's own UI plays through `PlayerEngine` and never reaches it. `onPostConnect` and
`onDisconnected` are forwarded too, so a provider can track which controllers are connected.

Queue items carry the track uri as their media id, so a controller that replays what it saw (an
Assistant suggestion, Android Auto's "For you") sends an id the provider can resolve. The player
also takes media audio focus: it pauses or ducks for calls and other apps.

```kotlin
single<MediaBrowseProvider> { MyBrowseProvider(/* your library */) }
```

For Android Auto, also export the service with both browse actions and declare the car app:

```xml
<service android:name="com.kronos.ktech.audioengine.PlaybackService"
    android:exported="true" android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaLibraryService" />
        <action android:name="android.media.browse.MediaBrowserService" />
    </intent-filter>
</service>
<meta-data android:name="com.google.android.gms.car.application"
    android:resource="@xml/automotive_app_desc" />
```

## Desktop native now-playing integration

On Desktop, macOS Control Center and Windows SMTC integration is implemented via two standalone
native bridge modules (`macosNowPlayingBridge`, `windowsNowPlayingBridge`). This library doesn't
publish them: build them yourself and ship the binary with your app. At runtime the engine
`System.load()`s the first of these that exists:

1. `<ktech.audioengine.nativeLibDir>/<lib>` — explicit system property
2. `<compose.application.resources.dir>/<lib>` — a Compose Desktop app's resources dir; put the
   binary under `appResourcesRootDir`'s `macos-arm64/` or `windows-x64/` folder and it's found in
   packaged builds and in `run` alike
3. `macosNowPlayingBridge/build/bin/macosArm64/releaseShared/<lib>` (or the `windows…/mingwX64`
   equivalent), relative to the working directory or its parent — dev runs from a checkout

`<lib>` is `libmacosNowPlayingBridge.dylib` or `windowsNowPlayingBridge.dll`. If none exists, the
native integration reports unavailable (no crash). Android and iOS don't use a native bridge.
