package io.github.yuriyurin.fit3companion
import org.junit.Assert.*
import org.junit.Test
class DebugTapSequenceTest {
    @Test fun opensExactlyOnTenthTap() {
        val taps = DebugTapSequence()
        repeat(9) { assertFalse(taps.tap(it * 100L)) }
        assertTrue(taps.tap(900))
        assertFalse(taps.tap(1000))
    }
    @Test fun slowTapsAndOtherTabsResetSequence() {
        val taps = DebugTapSequence()
        repeat(20) { assertFalse(taps.tap(it * 501L)) }
        taps.reset()
        repeat(9) { assertFalse(taps.tap(it * 100L)) }
        taps.reset()
        assertFalse(taps.tap(900))
    }
}
