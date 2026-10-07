package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.*
import org.junit.Test

class Fit3HealthAcceptanceTest {
    private val request = byteArrayOf(0x82.toByte(), 1, 0xE0.toByte(), 0x28, 0x27)

    @Test fun acknowledgmentIsReturnedOnlyAfterDurableCommit() {
        var committed = false
        val incoming = Fit3HealthAcceptance.accept(Fit3HealthCodec.State(), request, 10_000L) { state, raw ->
            assertArrayEquals(request, raw)
            assertEquals(10_000L, state.lastSuccessfulSyncMillis)
            committed = true
        }
        assertTrue(committed)
        assertNotNull(incoming.reply)
    }

    @Test fun storageFailureCannotReturnSuccessReplyOrChangePreviousState() {
        val previous = Fit3HealthCodec.State(steps = 215, sleepMinutes = 350)
        var returned = false
        try {
            Fit3HealthAcceptance.accept(previous, request, 10_000L) { _, _ -> error("Disk full") }
            returned = true
        } catch (_: IllegalStateException) { }
        assertFalse(returned)
        assertEquals(215, previous.steps)
        assertEquals(350, previous.sleepMinutes)
        assertNull(previous.lastSuccessfulSyncMillis)
    }

    @Test fun unknownPacketIsStillArchivedButDoesNotClaimSuccessfulSync() {
        val packet = byteArrayOf(0x1f, 2, 3)
        var saved = false
        val incoming = Fit3HealthAcceptance.accept(Fit3HealthCodec.State(), packet, 10_000L) { _, raw ->
            assertArrayEquals(packet, raw); saved = true
        }
        assertTrue(saved)
        assertNull(incoming.reply)
        assertNull(incoming.state.lastSuccessfulSyncMillis)
    }

    @Test fun validExchangeWithoutNewMeasurementsStillAdvancesSyncTime() {
        val before = Fit3HealthCodec.State(steps = 215, lastSyncMillis = 1L, lastSuccessfulSyncMillis = 2L)
        val incoming = Fit3HealthAcceptance.accept(before, request, 10_000L) { _, _ -> }
        assertEquals(215, incoming.state.steps)
        assertEquals(1L, incoming.state.lastSyncMillis)
        assertEquals(10_000L, incoming.state.lastSuccessfulSyncMillis)
    }
}
