package com.kronos.ktech.audioengine

/**
 * Maps a track's artwork URI before the engine publishes it in the media session, which other
 * processes read (notification, lock screen, Android Auto). Bind one in Koin when some artwork
 * URIs are private to the app (e.g. `file://` paths in its own storage) and need a `content://`
 * URI those processes can open; without a binding the URI is published as-is.
 */
fun interface ArtworkUriMapper {
    fun map(artworkUri: String): String
}
