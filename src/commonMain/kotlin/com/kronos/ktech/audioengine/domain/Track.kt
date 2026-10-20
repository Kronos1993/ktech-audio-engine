package com.kronos.ktech.audioengine.domain

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String? = null,
    val durationMs: Long? = null,
    val folderName: String? = null,
    val sizeBytes: Long? = null,
    val artworkUri: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    // Epoch ms when the file entered the device library (MediaStore DATE_ADDED, MPMediaItem.dateAdded, file creation time).
    val dateAddedMs: Long? = null,
    // The caller's title/artist/album/artwork are fallbacks: the engine replaces them with the tags
    // embedded in the stream once it reads them. Off for tracks whose caller metadata is better.
    val useEmbeddedMetadata: Boolean = false,
)

/**
 * Applies tags read from the file itself to a track that opted in with [Track.useEmbeddedMetadata].
 * A non-blank value replaces the caller's; `uri` never changes, so the track keeps its identity.
 */
fun Track.withEmbeddedMetadata(
    title: String?,
    artist: String?,
    album: String?,
    artworkUri: String?,
): Track {
    if (!useEmbeddedMetadata) return this
    return copy(
        title = title?.takeIf { it.isNotBlank() } ?: this.title,
        artist = artist?.takeIf { it.isNotBlank() } ?: this.artist,
        album = album?.takeIf { it.isNotBlank() } ?: this.album,
        artworkUri = artworkUri?.takeIf { it.isNotBlank() } ?: this.artworkUri,
    )
}

/** Cache sub-folder, on each platform, that holds covers read from a stream's embedded tags. */
const val STREAM_ARTWORK_DIR = "stream_artwork"

/**
 * File name the engine stores a track's embedded cover under, inside [STREAM_ARTWORK_DIR].
 * A hash rather than the URI, which may carry credentials.
 */
fun embeddedArtworkFileName(uri: String): String {
    var hash = 0xcbf29ce484222325uL
    for (byte in uri.encodeToByteArray()) {
        hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
    }
    return hash.toString(16).padStart(16, '0') + ".img"
}
