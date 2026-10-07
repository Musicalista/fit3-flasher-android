package io.github.yuriyurin.fit3companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fit3PedometerIntervalTest {
    private fun hex(value: String): ByteArray = value.split(Regex("\\s+"))
        .filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test fun decodesCapturedIntervalsWithoutDoubleCounting() {
        // Two type-7 records from the supplied AZA3 trace: 8 + 21 steps.
        val packet = hex(
            "82 03 E0 19 27 07 01 " +
                "05 30 8F 19 45 01 F4 8E 19 45 02 30 8F 19 45 03 80 C8 A4 00 " +
                "10 08 00 00 00 11 E0 16 F2 40 12 67 67 EB 3E 13 F2 19 E0 3E " +
                "14 00 00 00 00 15 08 00 00 00 16 C8 1B 00 00 17 03 00 " +
                "05 E4 8F 19 45 01 A8 8F 19 45 02 E4 8F 19 45 03 80 C8 A4 00 " +
                "10 15 00 00 00 11 E0 A6 67 41 12 C3 02 EE 3F 13 13 50 A4 3F " +
                "14 00 00 00 00 15 15 00 00 00 16 E0 38 00 00 17 03 00"
        )
        val now = 1_790_454_960_000L // same local day as captured intervals
        val first = Fit3HealthCodec.handle(Fit3HealthCodec.State(), packet, now)
        assertEquals(29, first.state.steps)
        assertEquals(29, first.state.walkSteps)
        assertTrue(first.state.stepsPartial)
        assertTrue(Fit3HealthCodec.DATA_PEDO_STEP_COUNT in first.parsedDataTypes)
        val repeated = Fit3HealthCodec.handle(first.state, packet, now + 1_000L)
        assertEquals(29, repeated.state.steps)
        assertEquals(2, repeated.state.stepRecords.size)
    }

    @Test fun decodesFourIntervalsAcrossMidnightFromBeta15Trace() {
        val originalZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Moscow"))
        try {
            val packet = hex(
                "82 03 E0 33 27 07 01 05 30 8F 19 45 01 F4 8E 19 45 02 30 8F 19 45 03 80 " +
                "CB A4 00 10 0B 00 00 00 11 50 16 F2 40 12 57 67 E8 3E 13 F2 19 E0 3E 14 " +
                "00 00 00 00 15 0B 00 00 00 16 58 1B 00 00 17 03 01 05 E4 8F 19 45 01 A8 " +
                "8F 19 45 02 E4 8F 19 45 03 80 CB A4 00 10 15 00 00 00 11 E0 A6 67 41 12 " +
                "C3 62 5E 3F 13 13 50 44 3F 14 00 00 00 00 15 15 00 00 00 16 B0 36 00 00 " +
                "17 03 01 05 24 9B 19 45 01 E8 9A 19 45 02 24 9B 19 45 03 80 CB A4 00 10 " +
                "0B 00 00 00 11 0F 0D 07 41 12 23 A6 01 3F 13 CA 60 C7 3E 14 00 00 00 00 " +
                "15 0B 00 00 00 16 58 1B 00 00 17 03 00 05 D4 9F 19 45 01 98 9F 19 45 02 " +
                "D4 9F 19 45 03 80 CB A4 00 10 18 00 00 00 11 45 9A 88 41 12 76 23 83 3F " +
                "13 6B 4E 64 3F 14 00 00 00 00 15 18 00 00 00 16 80 3E 00 00 17 03 05 19 " +
                "01 19 02 4C 27 03 05 03 04 66 A8 19 45 05 DB A8 19 45 06 DB A8 19 45 07 " +
                "00"
            )
            val now = java.time.Instant.parse("2026-09-26T22:30:00Z").toEpochMilli()
            val incoming = Fit3HealthCodec.handle(Fit3HealthCodec.State(), packet, now)
            assertEquals(35, incoming.state.steps)
            assertEquals(35, incoming.state.walkSteps)
            assertEquals(2, incoming.state.stepRecords.size)
            assertTrue(incoming.state.stepsPartial)
        } finally {
            java.util.TimeZone.setDefault(originalZone)
        }
    }
}
