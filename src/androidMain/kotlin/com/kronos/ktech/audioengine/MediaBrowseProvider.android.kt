package com.kronos.ktech.audioengine

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession

/**
 * Host-supplied browse tree for Android Auto and other media browsers. Bind one in Koin to
 * expose it; without a binding [PlaybackService] serves no browse tree.
 *
 * [PlaybackService] delegates the library callbacks to it (root, children, item, subscribe,
 * search, `onSetMediaItems`, `onAddMediaItems`, playback resumption), `onPostConnect` and `onDisconnected`,
 * and keeps connection and its own notification commands.
 */
interface MediaBrowseProvider : MediaLibrarySession.Callback {
    /**
     * Called on the main thread when a controller sets media items, after `onSetMediaItems`
     * resolved them. Return true when the host applied them itself (normally through
     * [PlayerEngine.setQueue], so the engine's queue stays the source of truth); false applies
     * them to the player as-is.
     */
    fun onSetMediaItemsRequest(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): Boolean = false

    /**
     * Called on the main thread when a controller adds media items at [index], after
     * `onAddMediaItems` resolved them. Return true when handled; false applies them as-is.
     */
    fun onAddMediaItemsRequest(index: Int, mediaItems: List<MediaItem>): Boolean = false

    /**
     * Called on the main thread before a session controller starts playback (play, or
     * playWhenReady set to true). Return false to ignore the request. The app's own UI plays
     * through [PlayerEngine] directly and never reaches this.
     */
    fun isPlaybackAllowed(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean = true
}
