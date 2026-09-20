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

This repository is this library's real home as of 2026-09-20, split out of Lumina Sound's monorepo
where it originally lived as an internal Gradle module. Lumina Sound's own consumption path is
mid-migration to a git submodule + Gradle composite build (`includeBuild`) pointing at this repo —
until that lands, Lumina Sound's `develop` branch may still carry its own transitional copy of this
code from before the split. Not yet published to an external Maven repository (Maven Central /
GitHub Packages) for use by other, unrelated repos — that's a separate, later phase.

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

// Call this once, when your app is done with playback entirely (not on every screen exit) -
// it tears down the underlying platform player and the engine can't be used again afterward.
playerEngine.release()
```

See `PlayerEngine`'s KDoc for the full API and per-member detail.

## Known limitation: native now-playing integration is not portable out of the box

On Desktop, macOS Control Center and Windows SMTC integration is implemented via two standalone
native bridge modules (`macosNowPlayingBridge`, `windowsNowPlayingBridge`) that live alongside this
module in its host repo, loaded at runtime via a **repo-relative path**. This works as long as this
module and the two bridges stay sibling directories in whichever repo builds them together, but a
consumer that only depends on the published `ktech-audio-engine` artifact — without also bringing
those two bridge modules along — will not get working native now-playing integration on Desktop.
The relevant `PlayerEngine` calls fail soft in that case (no crash; native integration simply
reports unavailable) rather than throwing.

Fixing this for real external consumers (bundling the native binaries as classpath resources
instead of a repo-relative path) is tracked as a separate, not-yet-started follow-on. Android and
iOS now-playing integration are unaffected by this limitation — both use standard platform APIs
(Media3 `MediaSessionService`, `AVAudioEngine`) with no native-bridge dependency at all.
