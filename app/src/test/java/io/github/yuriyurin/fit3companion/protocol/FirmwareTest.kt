package io.github.yuriyurin.fit3companion.protocol

import io.github.yuriyurin.fit3companion.ble.FirmwarePackage
import io.github.yuriyurin.fit3companion.ble.FirmwareTransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class FirmwareTest {
    private fun word(data: ByteArray, at: Int, value: Long) {
        repeat(4) { data[at + it] = (value ushr (it * 8)).toByte() }
    }
    private fun crc(data: ByteArray) = CRC32().apply { update(data) }.value
    private fun packageBytes(destination: String = "/nand/system/font/FONT_TEST.bin", source: String = "R390XXU0AZA3"): ByteArray {
        val raw = ByteArray(100) { it.toByte() }
        val blob = ByteArrayOutputStream().also { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("resource.bin")); zip.write(raw); zip.closeEntry()
            }
        }.toByteArray()
        val packet = ByteArray(80 + 128 + 4 + blob.size + 4)
        word(packet, 0, 0x7e5a5a5a)
        "R390XXU0AZA3".toByteArray().copyInto(packet, 4)
        packet[69] = 0x18; packet[70] = 1
        word(packet, 76, raw.size.toLong())
        word(packet, 80, 0x00340000)
        word(packet, 84, 212); word(packet, 88, blob.size.toLong())
        destination.toByteArray().copyInto(packet, 92)
        word(packet, 208, crc(packet.copyOfRange(0, 208)))
        blob.copyInto(packet, 212); word(packet, 212 + blob.size, crc(raw))
        val updater = ByteArray(16) { 42 }
        val result = ByteArray(92 + updater.size + 4 + packet.size + 4)
        word(result, 0, 0x7e5a5a60); source.toByteArray().copyInto(result, 4)
        word(result, 72, 1); word(result, 76, 92); word(result, 80, updater.size.toLong())
        word(result, 84, 112); word(result, 88, packet.size.toLong())
        updater.copyInto(result, 92); word(result, 108, crc(updater))
        packet.copyInto(result, 112); word(result, 112 + packet.size, crc(packet))
        return result
    }
    private fun withFile(data: ByteArray, block: (File) -> Unit) {
        val file = Files.createTempFile("fwd-test", ".bin").toFile()
        try { file.writeBytes(data); block(file) } finally { file.delete() }
    }
    @Test fun validatesFullZipProfile() = withFile(packageBytes()) { file ->
        val info = FirmwarePackage.inspect(file, "test.bin")
        assertEquals("R390XXU0AZA3", info.source)
        assertEquals(1, info.components)
        assertEquals(64, info.sha256.length)
    }
    @Test fun rejectsCorruptPackage() = withFile(packageBytes().also { it[95] = 0 }) { file ->
        assertTrue(runCatching { FirmwarePackage.inspect(file, "test.bin") }.isFailure)
    }
    @Test fun rejectsUnsafeDestination() = withFile(packageBytes("/nand/system/../boot.bin")) { file ->
        assertTrue(runCatching { FirmwarePackage.inspect(file, "test.bin") }.isFailure)
    }
    @Test fun rejectsOtherModel() = withFile(packageBytes(source = "R400XXU0AZA3")) { file ->
        assertTrue(runCatching { FirmwarePackage.inspect(file, "test.bin") }.isFailure)
    }
    @Test fun rejectsRawImagesAndWatchfaces() = withFile(ByteArray(500)) { file ->
        assertTrue(runCatching { FirmwarePackage.inspect(file, "test.bin") }.isFailure)
    }
    @Test fun stageNeverStartsInstallationAndProgressRequiresAck() = withFile(ByteArray(40_000) { it.toByte() }) { file ->
        val output = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        FirmwareTransport.stage(ByteArrayInputStream("300330310310320340".toByteArray()), output, file) { sent, _ -> progress += sent }
        assertEquals(listOf(39_600L, 40_000L), progress)
        val text = output.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("30033bin,/user/ota/R390_Fit3App.bin,40000"))
        assertFalse(text.contains("AT^OTA_UPDATE"))
        assertTrue(text.endsWith("3234"))
    }
    @Test fun refusedBlockDoesNotFinalizeOrStart() = withFile(ByteArray(100)) { file ->
        val output = ByteArrayOutputStream()
        assertTrue(runCatching { FirmwareTransport.stage(ByteArrayInputStream("300330311".toByteArray()), output, file) { _, _ -> fail() } }.isFailure)
        assertFalse(output.toString("US-ASCII").contains("AT^OTA_UPDATE"))
    }
    @Test fun verifiesRemoteLengthBeforeExplicitStart() {
        val output = ByteArrayOutputStream()
        FirmwareTransport.verifySize(ByteArrayInputStream(byteArrayOf(0x40, 0, 0, 0, 100)), output, 100)
        assertFalse(output.toString("US-ASCII").contains("OTA_UPDATE"))
        assertTrue(runCatching { FirmwareTransport.verifySize(ByteArrayInputStream(byteArrayOf(0x40, 0, 0, 0, 99)), output, 100) }.isFailure)
        val command = ByteArrayOutputStream()
        FirmwareTransport.start(command)
        assertEquals("00AT^OTA_UPDATE", command.toString("US-ASCII"))
    }
    @Test fun optionalLocalValidatedPackage() {
        val path = System.getenv("FIT3_FWD_TEST_PATH") ?: return
        val info = FirmwarePackage.inspect(File(path), "local-validation.bin")
        assertTrue(info.components > 0)
    }
}
