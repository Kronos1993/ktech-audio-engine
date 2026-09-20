package com.kronos.ktech.audioengine.domain

// Not every platform can offer the same output-selection UX. Android/Desktop can enumerate
// devices themselves and switch between them programmatically (IN_APP_LIST - DevicesScreen
// renders its own row list). iOS has no API to enumerate outputs or force-select an arbitrary
// one - AVAudioSession.overrideOutputAudioPort only works under the playAndRecord/multiRoute
// categories, not the plain Playback category this app uses (confirmed on real hardware: the
// override call silently no-ops). The only real, working iOS mechanism is the system's own
// AVRoutePickerView sheet (SYSTEM_PICKER) - DevicesScreen embeds that instead of drawing its
// own list there.
enum class OutputSelectionMode {
    IN_APP_LIST,
    SYSTEM_PICKER,
}
