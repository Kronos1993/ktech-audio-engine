package com.kronos.ktech.audioengine.domain

/** Where [com.kronos.ktech.audioengine.PlayerEngine.enqueue] puts the tracks. */
enum class EnqueuePosition {
    /** Right after the current track, behind any tracks already queued with [NEXT]. Plays next even with shuffle on. */
    NEXT,

    /** At the end of the queue. With shuffle on, the tracks join the shuffled order. */
    END,
}
