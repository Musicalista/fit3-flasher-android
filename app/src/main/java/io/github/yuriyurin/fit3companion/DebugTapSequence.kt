package io.github.yuriyurin.fit3companion

/** Ten consecutive taps, no more than 500 ms apart. Uses the monotonic clock. */
internal class DebugTapSequence {
    private var count = 0
    private var last = 0L
    fun reset() { count = 0; last = 0 }
    fun tap(now: Long): Boolean {
        count = if (count > 0 && now >= last && now - last <= 500) count + 1 else 1
        last = now
        if (count < 10) return false
        reset()
        return true
    }
}
