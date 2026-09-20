package com.kronos.ktech.audioengine.domain

enum class AudioOutputDeviceType {
    BUILT_IN_SPEAKER,
    WIRED,
    BLUETOOTH,
    USB,
    HDMI,
    OTHER,
}

data class AudioOutputDevice(
    val id: String,
    val name: String,
    val type: AudioOutputDeviceType,
)
