package com.kronos.ktech.audioengine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.kronos.ktech.audioengine.domain.RepeatMode
import kotlinx.coroutines.runBlocking
import com.kronos.ktech.audioengine.generated.resources.Res
import com.kronos.ktech.audioengine.generated.resources.playback_notification_channel_name
import com.kronos.ktech.audioengine.generated.resources.playback_notification_text
import com.kronos.ktech.audioengine.generated.resources.playback_notification_title
import com.kronos.ktech.audioengine.generated.resources.playback_repeat_button_label
import com.kronos.ktech.audioengine.generated.resources.playback_shuffle_button_label
import org.jetbrains.compose.resources.getString
import org.koin.android.ext.android.inject

private const val NOTIFICATION_CHANNEL_ID = "playback_channel"
private const val NOTIFICATION_ID = 1
private const val SESSION_ACTIVITY_REQUEST_CODE = 1
private const val ACTION_TOGGLE_SHUFFLE = "com.kronos.multiplatform.lumina.sound.TOGGLE_SHUFFLE"
private const val ACTION_CYCLE_REPEAT = "com.kronos.multiplatform.lumina.sound.CYCLE_REPEAT"

// Read by MainActivity (androidApp module) off the launch Intent produced by
// buildSessionActivityPendingIntent() below. Public/non-private on purpose - shared's
// androidMain cannot reference androidApp's MainActivity directly (dependency points
// the other way), so this is the only channel connecting the two.
const val EXTRA_OPEN_NOW_PLAYING = "com.kronos.multiplatform.lumina.sound.OPEN_NOW_PLAYING"

class PlaybackService : MediaSessionService() {
    private val playerEngine: PlayerEngine by inject()
    private var mediaSession: MediaSession? = null
    private var shuffleButtonLabel: String = "Shuffle"
    private var repeatButtonLabel: String = "Repeat"

    // Keeps the notification's shuffle/repeat icons in sync no matter where the toggle
    // originated - the notification's own buttons (via PlaybackSessionCallback below) or
    // the in-app NowPlayingScreen controls (via PlayerViewModel -> PlayerEngine.setShuffle/
    // setRepeatMode, which mutate exoPlayer directly). Stored as a field (rather than an
    // anonymous object passed straight to addListener) so onDestroy() can remove it -
    // exoPlayer is a Koin-singleton shared across service instances, so an un-removed
    // listener here would leak every prior PlaybackService instance across restarts.
    private val playbackListener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            mediaSession?.setMediaButtonPreferences(buildMediaButtonPreferences())
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            mediaSession?.setMediaButtonPreferences(buildMediaButtonPreferences())
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Kept synchronous (a blocking runBlocking read of composeResources' bundled, local
        // string assets - no network/disk contention, effectively instant) rather than a
        // launched coroutine: this whole method must complete atomically before Android's
        // "call startForeground within a few seconds of startForegroundService()" deadline,
        // and a suspended-mid-onCreate service is vulnerable to being torn down (e.g. by a
        // fast notification dismiss) before addSession() ever runs, leaving onGetSession()
        // permanently null for this instance.
        val notificationChannelName = runBlocking { getString(Res.string.playback_notification_channel_name) }
        val notificationTitle = runBlocking { getString(Res.string.playback_notification_title) }
        val notificationText = runBlocking { getString(Res.string.playback_notification_text) }
        shuffleButtonLabel = runBlocking { getString(Res.string.playback_shuffle_button_label) }
        repeatButtonLabel = runBlocking { getString(Res.string.playback_repeat_button_label) }

        val session = MediaSession.Builder(this, playerEngine.exoPlayer)
            .setCallback(PlaybackSessionCallback())
            .setMediaButtonPreferences(buildMediaButtonPreferences())
            .apply { buildSessionActivityPendingIntent()?.let { setSessionActivity(it) } }
            .build()
        mediaSession = session
        // Registers the session with this service's own notification manager — without
        // this call, Media3 never learns a session exists to build a real notification
        // for (its automatic media-style notification with title/artist/artwork and
        // play/pause/skip actions is driven entirely by sessions added here).
        addSession(session)
        playerEngine.exoPlayer.addListener(playbackListener)

        // Android 12+ kills the service if startForeground() isn't called within a few
        // seconds of Context.startForegroundService() — post a placeholder immediately
        // so the service survives that window deterministically; Media3's own
        // notification manager (triggered by addSession() above) replaces this with the
        // real media-style notification (metadata + transport controls) as soon as it
        // processes the session's current player state, normally within one frame.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildPlaceholderNotification(notificationChannelName, notificationTitle, notificationText),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun buildPlaceholderNotification(
        channelName: String,
        title: String,
        text: String,
    ): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    // Tapping the notification (Android 13+ reads this straight off the session; below
    // 33 it becomes the notification's contentIntent) re-opens the app already on the
    // Now Playing screen. Goes through packageManager.getLaunchIntentForPackage rather
    // than a direct `Intent(this, MainActivity::class.java)` because MainActivity lives
    // in the androidApp module, which depends on shared - not the other way around.
    // MainActivity is singleTask (see AndroidManifest.xml), so this always routes into
    // the existing instance via onNewIntent rather than spawning a second one.
    private fun buildSessionActivityPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.putExtra(EXTRA_OPEN_NOW_PLAYING, true)
        return PendingIntent.getActivity(
            this,
            SESSION_ACTIVITY_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // Deliberately no .setSlots(...) call - the official media button preferences example
    // (developer.android.com/media/implement/surfaces/mobile#config-action-buttons) adds
    // its custom CommandButton unslotted and relies on the system placing overflow buttons
    // (i.e. anything beyond the automatic play/pause + previous/next) into the remaining
    // slots in the order they were added here; forcing an explicit slot risked the system
    // rejecting a placement it didn't expect, which is the likely reason an earlier version
    // of this using .setSlots(SLOT_BACK_SECONDARY/SLOT_FORWARD_SECONDARY) rendered no
    // shuffle/repeat buttons at all on a real device. Icon reflects current state (ON/OFF,
    // ALL/ONE/OFF) since Media3 doesn't flip these itself - callers must rebuild and re-set
    // this list on change (see playbackListener above).
    private fun buildMediaButtonPreferences(): List<CommandButton> {
        val shuffleIcon = if (playerEngine.exoPlayer.shuffleModeEnabled) {
            CommandButton.ICON_SHUFFLE_ON
        } else {
            CommandButton.ICON_SHUFFLE_OFF
        }
        val repeatIcon = when (playerEngine.exoPlayer.repeatMode) {
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
            else -> CommandButton.ICON_REPEAT_OFF
        }
        val shuffleButton = CommandButton.Builder(shuffleIcon)
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
            .setDisplayName(shuffleButtonLabel)
            .build()
        val repeatButton = CommandButton.Builder(repeatIcon)
            .setSessionCommand(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
            .setDisplayName(repeatButtonLabel)
            .build()
        return listOf(shuffleButton, repeatButton)
    }

    private fun nextRepeatMode(): RepeatMode = when (playerEngine.exoPlayer.repeatMode) {
        Player.REPEAT_MODE_OFF -> RepeatMode.ALL
        Player.REPEAT_MODE_ALL -> RepeatMode.ONE
        else -> RepeatMode.OFF
    }

    // Handles the notification's shuffle/repeat buttons. Routed through custom
    // SessionCommands (rather than the raw Player.COMMAND_SET_SHUFFLE_MODE /
    // COMMAND_SET_REPEAT_MODE player commands) so repeat can cycle OFF -> ALL -> ONE ->
    // OFF - the same order App.kt's onCycleRepeat uses for the in-app button - instead
    // of relying on unspecified default toggle behavior for a 3-state control.
    private inner class PlaybackSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_SHUFFLE -> playerEngine.setShuffle(!playerEngine.exoPlayer.shuffleModeEnabled)
                ACTION_CYCLE_REPEAT -> playerEngine.setRepeatMode(nextRepeatMode())
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        playerEngine.exoPlayer.removeListener(playbackListener)
        mediaSession?.let { session ->
            removeSession(session)
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
