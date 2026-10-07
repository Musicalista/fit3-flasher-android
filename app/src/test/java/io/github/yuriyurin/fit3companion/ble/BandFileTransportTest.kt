package io.github.yuriyurin.fit3companion.ble

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import io.github.yuriyurin.fit3companion.protocol.*

class BandFileTransportTest {
    @Test fun stockMkdirFailureReturnsImmediately() {
        val commands = ByteArrayOutputStream()
        try {
            BandFileTransport.mkdir(ByteArrayInputStream("OK\r\nFailed /user/new -28\r\n".toByteArray()), commands, "/user/new")
            fail("Accepted failed mkdir")
        } catch (error: java.io.IOException) { assertTrue(error.message!!.contains("Failed")) }
        assertEquals("00AT^BLUETOOTH00FI^MKDIR=/user/new", String(commands.toByteArray()))
    }
    @Test fun exhaustedInputFailsWithoutWaitingForAvailable() {
        try {
            BandFileTransport.download(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), "/user/a", ByteArrayOutputStream()) { _, _ -> }
            fail("Accepted closed input")
        } catch (error: java.io.IOException) { assertEquals("Watch connection closed", error.message) }
    }
    @Test fun listRoutesNativeConsoleToBluetoothBeforeRequest() {
        val commands = ByteArrayOutputStream()
        val entries = BandFileTransport.list(ByteArrayInputStream("OK\r\nF /user/a.jar\t10\r\n^LS=OK\r\n".toByteArray()), commands, "/user")
        assertEquals("00AT^BLUETOOTH00FI^LS=/user", String(commands.toByteArray()))
        assertEquals("a.jar", entries.single().name)
    }
    private fun rejects(action: () -> Unit) { try { action(); fail("Accepted invalid operation") } catch (_: IllegalArgumentException) {} }
    @Test fun recursiveListingShowsOnlyImmediateChildrenAndStripsNativeIndent() {
        val entries = BandFileTransport.parseListing("/user", "D /user\r\nD     /user/games\r\nF         /user/games/a.jar\t300\r\nF     /user/z.bin\t17\r\nF     /user/a b.jar\t0\r\n^LS=OK\r\n")
        assertEquals(listOf("games", "a b.jar", "z.bin"), entries.map { it.name })
        assertTrue(entries[0].directory); assertEquals(17L, entries[2].size)
    }
    @Test fun rejectListingErrorsEvenWithSuccessfulTerminator() {
        rejects { BandFileTransport.parseListing("/user", "open dir error -2\r\n^LS=OK\r\n") }
        rejects { BandFileTransport.parseListing("/user", "F /nand/other\t10\r\n^LS=OK\r\n") }
        rejects { BandFileTransport.parseListing("/user", "F /user/a\t-1\r\n^LS=OK\r\n") }
    }
    @Test fun pathsRejectTraversalCommandInjectionAndOtaWrites() {
        listOf("/user/../ota/a", "/user/a^RM=b", "/user/a\n", "/user/a,b", "/user//x", "/user/a=b", "/user/кириллица").forEach { rejects { BandFilePaths.validate(it) } }
        assertFalse(BandFilePaths.writable("/user/ota/a")); assertFalse(BandFilePaths.writable("/nand/system/a"))
        assertFalse(BandFilePaths.writable("/user/OTA/a"))
        rejects { BandFilePaths.validate("/user/ota./a") }
        assertFalse(BandFilePaths.writable("/user")); assertTrue(BandFilePaths.writable("/user/fit3me.jar"))
        assertEquals("/user/fit3me.jar", BandFilePaths.destination("/user", "fit3me.jar"))
    }
    @Test fun fileSessionUsesOnlyOpenAndRejectsOtherResponses() {
        val s = Fit3FileSession()
        assertArrayEquals(SaMessageCodec.fixedRequest(2), s.begin())
        assertNull(s.receive(SaMessageCodec.fixedResponse(1, SaMessageCodec.byteParam(1, 1))))
        assertNull(s.receive(SaMessageCodec.fixedResponse(2, SaMessageCodec.byteParam(1, 1), SaMessageCodec.byteParam(1, 1))))
        assertEquals(true, s.receive(SaMessageCodec.fixedResponse(2, SaMessageCodec.byteParam(1, 1), SaMessageCodec.intParam(6, 960))))
        assertNull(s.receive(SaMessageCodec.fixedResponse(2, SaMessageCodec.byteParam(1, 1))))
    }
    @Test fun uploadFragmentsAndEncodesEveryBlockCrcWithoutOtaCommand() {
        val bytes = ByteArray(BandFileTransport.WINDOW + 11) { it.toByte() }
        val file = File.createTempFile("fit3-upload", ".bin")
        try {
            file.writeBytes(bytes); val out = ByteArrayOutputStream(); val progress = mutableListOf<Long>()
            BandFileTransport.upload(ByteArrayInputStream("300330310310320340".toByteArray()), out, "/user/a.jar", file) { n, _ -> progress += n }
            val header = "30033bin,/user/a.jar,${bytes.size}".toByteArray()
            val expected = ByteArrayOutputStream().apply { write(header)
                listOf(bytes.copyOfRange(0, BandFileTransport.WINDOW), bytes.copyOfRange(BandFileTransport.WINDOW, bytes.size)).forEach { block ->
                    write(block); val crc = CRC32().apply { update(block) }.value
                    repeat(4) { write((crc ushr (it * 8)).toInt() and 255) }
                }; write("3234".toByteArray()) }.toByteArray()
            assertArrayEquals(expected, out.toByteArray()); assertEquals(listOf(BandFileTransport.WINDOW.toLong(), bytes.size.toLong()), progress)
        } finally { file.delete() }
    }
    @Test fun failedUploadAckStopsBeforeCommit() {
        val f = File.createTempFile("fit3-upload", ".bin")
        try { f.writeBytes(byteArrayOf(1)); val out = ByteArrayOutputStream()
            rejects { BandFileTransport.upload(ByteArrayInputStream("300331".toByteArray()), out, "/user/a", f) { _, _ -> } }
            assertFalse(String(out.toByteArray()).endsWith("3234"))
        } finally { f.delete() }
    }
    private fun uint(n: Long) = ByteBuffer.allocate(4).putInt(n.toInt()).array()
    private fun stream(payload: ByteArray, sum: Long): ByteArray = byteArrayOf(0x40) + uint(payload.size.toLong()) + byteArrayOf(0x40) + uint(payload.size.toLong()) + payload + uint(sum)
    @Test fun downloadChecksSumBeforeWritingAndHandlesFragmentedInput() {
        val bytes = byteArrayOf(0, 127, -1)
        val input = object : ByteArrayInputStream(stream(bytes, 382)) {
            override fun available() = 0 // Android packet sockets can have data even when available() says zero.
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1, len))
        }
        val commands = ByteArrayOutputStream(); val out = ByteArrayOutputStream()
        assertEquals(3L, BandFileTransport.download(input, commands, "/user/a", out) { _, _ -> })
        assertArrayEquals(bytes, out.toByteArray()); assertEquals("061/user/a\u0000062062", String(commands.toByteArray()))
    }
    @Test fun corruptDownloadDoesNotWriteBadBlock() {
        val out = ByteArrayOutputStream()
        rejects { BandFileTransport.download(ByteArrayInputStream(stream(byteArrayOf(3), 4)), ByteArrayOutputStream(), "/user/a", out) { _, _ -> } }
        assertEquals(0, out.size())
    }
    @Test fun oversizedDownloadRejectedBeforePayloadRequest() {
        val commands = ByteArrayOutputStream()
        rejects { BandFileTransport.download(ByteArrayInputStream(byteArrayOf(0x40) + uint(BandFileTransport.MAX_BYTES + 1)), commands, "/user/a", ByteArrayOutputStream()) { _, _ -> } }
        assertFalse(String(commands.toByteArray()).contains("062"))
    }
}
