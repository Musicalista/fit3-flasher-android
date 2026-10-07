package io.github.yuriyurin.fit3companion.protocol
import org.junit.Assert.*
import org.junit.Test
class Fit3FirmwareSessionTest {
    private fun reply(id: Int, status: Int, feature: Int? = null) =
        SaMessageCodec.fixedResponse(id, SaMessageCodec.byteParam(1, status),
            feature?.let { SaMessageCodec.byteParam(3, it) } ?: byteArrayOf())
    @Test fun opensOnlyAfterBothConfirmations() {
        val s = Fit3FirmwareSession()
        assertArrayEquals(SaMessageCodec.fixedRequest(1, SaMessageCodec.byteParam(3, 21),
            SaMessageCodec.stringParam(4, "R390_Fit3App.bin"), SaMessageCodec.intParam(5, 12345)),
            s.begin("R390_Fit3App.bin", 12345))
        assertNull(s.receive(reply(2, 1)))
        assertNull(s.receive(reply(1, 1, 9)))
        assertArrayEquals(byteArrayOf(2), s.receive(reply(1, 1, 21))!!.request)
        assertTrue(s.receive(reply(2, 1))!!.ready)
        assertNull(s.receive(reply(2, 1)))
    }
    @Test fun rejectionNeverPermitsTransfer() {
        val s = Fit3FirmwareSession(); s.begin("R390_Fit3App.bin", 1)
        assertNotNull(s.receive(reply(1, 0, 21))!!.error)
        assertNull(s.receive(reply(2, 1)))
    }
    @Test fun malformedResponsesDoNotAdvance() {
        val s = Fit3FirmwareSession(); s.begin("R390_Fit3App.bin", 1)
        assertNull(s.receive(byteArrayOf(65, 1)))
        assertNull(s.receive(byteArrayOf(65, 1, 1, 1, 1)))
        assertNull(s.receive(reply(1, 2, 21)))
        assertEquals(Fit3FirmwareSession.Stage.INFO, s.stage)
        s.fail(); assertNull(s.receive(reply(1, 1, 21)))
    }
}
