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
)
