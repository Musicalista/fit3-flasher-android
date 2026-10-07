package io.github.yuriyurin.fit3companion.ble

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.zip.CRC32

/** Fixed FOTA-only commands from the successfully tested PC SPP transport. */
internal object FirmwareTransport {
    const val REMOTE = "/user/ota/R390_Fit3App.bin"
    const val WINDOW = 39_600
    fun stage(input: InputStream, output: OutputStream, file: File, progress: (Long, Long) -> Unit) {
        require(file.length() in 96..FirmwarePackage.MAX_BYTES)
        send(output, "300")
        expect(input, "300")
        send(output, "33bin,$REMOTE,${file.length()}")
        expect(input, "330")
        val total = file.length()
        var sent = 0L
        val buffer = ByteArray(WINDOW + 4)
        file.inputStream().use { source ->
            while (sent < total) {
                val length = minOf(WINDOW.toLong(), total - sent).toInt()
                var read = 0
                while (read < length) {
                    val n = source.read(buffer, read, length - read)
                    if (n < 0) throw IOException("Firmware file changed")
                    read += n
                }
                val crc = CRC32().apply { update(buffer, 0, length) }.value
                for (i in 0..3) buffer[length + i] = (crc ushr (i * 8)).toByte()
                var at = 0
                while (at < length + 4) {
                    val chunk = minOf(960, length + 4 - at)
                    output.write(buffer, at, chunk); output.flush(); at += chunk
                }
                // No automatic restart/retransmission after an ambiguous firmware reply.
                expect(input, "310")
                sent += length; progress(sent, total)
            }
            require(source.read() == -1) { "Firmware file changed" }
        }
        send(output, "32"); expect(input, "320", 20_000)
        Thread.sleep(250)
        send(output, "34"); expect(input, "340")
    }

    fun verifySize(input: InputStream, output: OutputStream, expected: Long) {
        send(output, "061$REMOTE\u0000")
        val reply = read(input, 5)
        require(reply[0] == 0x40.toByte()) { "Staged firmware is unavailable" }
        val size = (1..4).fold(0L) { value, i -> (value shl 8) or (reply[i].toLong() and 255) }
        require(size == expected) { "Staged firmware size mismatch" }
    }

    fun start(output: OutputStream) = send(output, "00AT^OTA_UPDATE")

    private fun send(output: OutputStream, command: String) {
        output.write(command.toByteArray(Charsets.US_ASCII)); output.flush()
    }
    private fun expect(input: InputStream, expected: String, timeout: Long = 12_000) {
        require(String(read(input, expected.length, timeout), Charsets.US_ASCII) == expected) { "Unexpected FOTA reply" }
    }
    private fun read(input: InputStream, count: Int, timeout: Long = 12_000): ByteArray {
        val result = ByteArray(count)
        val deadline = System.nanoTime() + timeout * 1_000_000
        var at = 0
        while (at < count) {
            if (Thread.currentThread().isInterrupted) throw IOException("FOTA interrupted")
            if (System.nanoTime() >= deadline) throw IOException("FOTA response timeout")
            if (input.available() == 0) { Thread.sleep(5); continue }
            val value = input.read()
            if (value < 0) throw IOException("FOTA connection closed")
            result[at++] = value.toByte()
        }
        return result
    }
}
