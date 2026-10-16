package com.kronos.ktech.audioengine

import java.io.File

// JNI bridge into the standalone `:macosNowPlayingBridge`/`:windowsNowPlayingBridge`
// Kotlin/Native modules (see specs/_archive/desktop-macos-now-playing/decisions.md
// for why they're separate Gradle modules rather than normal expect/actual
// targets). Every `external fun` here must match both modules' `@CName`-exported
// symbol names exactly — see each module's NowPlayingBridge.kt header comment for
// the JNI mangled-name convention.
//
// macOS or Windows only: `isAvailable` gates every call site in PlayerEngine.jvm.kt
// so this stays fully inert (no System.load(), no native call of any kind) on
// Linux, which shares this same jvmMain source set.
internal object PlayerEngineJniBridge {
    val isAvailable: Boolean by lazy { (isMac || isWindows) && loadNative() }

    private val osName = System.getProperty("os.name")?.lowercase().orEmpty()
    private val isMac = osName.contains("mac")
    private val isWindows = osName.contains("win")

    var onCommand: ((NowPlayingCommand) -> Unit)? = null

    external fun nativeUpdateNowPlaying(
        title: String?,
        artist: String?,
        album: String?,
        artworkPath: String?,
        durationMs: Long,
        positionMs: Long,
        queueIndex: Long,
        queueCount: Long,
        isPlaying: Boolean,
    )

    external fun nativeClearNowPlaying()

    external fun nativeRegisterCommands()

    // macOS AND Windows both export this now (isAvailable alone is enough to gate it,
    // same as the other 3 functions above). Returns the display names of real hardware
    // audio outputs (matching what AudioSystem.getMixerInfo() itself reports), excluding
    // virtual/software devices like Teams/Zoom's own virtual audio devices, which
    // javax.sound.sampled has no way to distinguish from real hardware on its own.
    external fun nativeGetPhysicalOutputDeviceNames(): Array<String>

    // Called from native code (NowPlayingBridge.kt's onRemoteCommand/onSmtcButtonPressed,
    // via GetStaticMethodID/CallStaticVoidMethodA) — must stay @JvmStatic so it's
    // reachable as a true static JVM method, not just an instance method on this
    // object's singleton.
    @JvmStatic
    fun onNativeCommand(command: Int) {
        val mapped = NowPlayingCommand.entries.getOrNull(command) ?: return
        onCommand?.invoke(mapped)
    }

    private fun loadNative(): Boolean = runCatching {
        System.load(resolveNativeLibPath())
    }.isSuccess

    // Lookup order: an explicit `ktech.audioengine.nativeLibDir`, then a packaged Compose
    // Desktop app's resources dir (`compose.application.resources.dir`), then the bridge
    // modules' build output relative to the working directory (dev runs).
    private fun resolveNativeLibPath(): String {
        val (fileName, devPath) = when {
            isMac -> "libmacosNowPlayingBridge.dylib" to "macosNowPlayingBridge/build/bin/macosArm64/releaseShared"
            isWindows -> "windowsNowPlayingBridge.dll" to "windowsNowPlayingBridge/build/bin/mingwX64/releaseShared"
            else -> error("resolveNativeLibPath() called on an unsupported OS")
        }
        val packagedDirs = listOf("ktech.audioengine.nativeLibDir", "compose.application.resources.dir")
            .mapNotNull { System.getProperty(it) }
        val candidates = packagedDirs.map { File(it, fileName) } +
            listOf(File(devPath, fileName), File(File("..", devPath), fileName))
        return candidates.map { it.absoluteFile }.firstOrNull { it.exists() }?.path
            ?: error("$fileName not found — build the matching native bridge module first")
    }
}

internal enum class NowPlayingCommand { PLAY, PAUSE, NEXT, PREVIOUS }
