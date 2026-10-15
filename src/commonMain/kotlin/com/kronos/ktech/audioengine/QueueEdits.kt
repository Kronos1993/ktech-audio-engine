package com.kronos.ktech.audioengine

import kotlin.concurrent.Volatile

// Index arithmetic for in-place queue edits. Indices are positions in PlaybackState.queue.
internal object QueueIndex {
    fun afterInsert(index: Int, at: Int, count: Int): Int = if (index >= at) index + count else index

    // null when [index] itself was removed.
    fun afterRemove(index: Int, removed: Set<Int>): Int? = if (index in removed) null else index - removed.count { it < index }

    fun afterMove(index: Int, from: Int, to: Int): Int = when {
        index == from -> to
        from < to && index in (from + 1)..to -> index - 1
        from > to && index in to until from -> index + 1
        else -> index
    }

    // What plays once the current row is removed: the first pending play-next row, else the row
    // that slid into its slot (wrapping under repeat-all). null ends the queue.
    fun replacementForRemovedCurrent(current: Int, removed: Set<Int>, newSize: Int, repeatAll: Boolean, upNextFirst: Int?): Int? {
        if (newSize == 0) return null
        if (upNextFirst != null && upNextFirst in 0 until newSize) return upNextFirst
        val slot = current - removed.count { it < current }
        return when {
            slot < newSize -> slot
            repeatAll -> 0
            else -> null
        }
    }
}

internal fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }

// Queue indices added with EnqueuePosition.NEXT that have not played yet, in play order. They play
// right after the current track even under shuffle; later NEXT inserts go behind them.
internal class UpNext {
    @Volatile
    var indices: List<Int> = emptyList()
        private set

    fun clear() {
        indices = emptyList()
    }

    // Where a NEXT insert goes: after the current track and the pending play-next rows behind it.
    fun insertionIndex(currentIndex: Int, queueSize: Int): Int {
        val last = indices.filter { it > currentIndex }.maxOrNull() ?: currentIndex
        return (last + 1).coerceIn(0, queueSize)
    }

    fun peek(currentIndex: Int): Int? = indices.firstOrNull { it != currentIndex }

    fun onCurrent(index: Int) {
        if (index in indices) indices = indices - index
    }

    fun onInsert(at: Int, count: Int, asNext: Boolean) {
        val shifted = indices.map { QueueIndex.afterInsert(it, at, count) }
        indices = if (asNext) shifted + (at until at + count) else shifted
    }

    fun onRemove(removed: Set<Int>) {
        indices = indices.mapNotNull { QueueIndex.afterRemove(it, removed) }
    }

    fun onMove(from: Int, to: Int) {
        indices = indices.map { QueueIndex.afterMove(it, from, to) }
    }
}
