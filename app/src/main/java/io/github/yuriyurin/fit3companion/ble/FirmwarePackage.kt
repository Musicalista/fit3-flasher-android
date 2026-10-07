package io.github.yuriyurin.fit3companion.ble

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

data class FirmwareInfo(val name: String, val size: Long, val sha256: String,
    val source: String, val target: String, val components: Int)

/** The full-ZIP FWD profile verified by tools/fwd_tool.py. Never repairs a package. */
internal object FirmwarePackage {
    const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_EXPANDED = 128L * 1024 * 1024
    private const val MAIN = "/user/ota/app/best1502x_b319_user.bin"

    fun inspect(file: File, name: String): FirmwareInfo = RandomAccessFile(file, "r").use { f ->
        require(f.length() in 96..MAX_BYTES) { "Invalid FWD size (maximum 32 MiB)" }
        fun word(at: Long): Long {
            require(at >= 0 && at + 4 <= f.length()) { "Truncated FWD" }
            f.seek(at)
            return (0..3).fold(0L) { value, i -> value or (f.readUnsignedByte().toLong() shl (i * 8)) }
        }
        fun bytes(at: Long, count: Int): ByteArray {
            require(at >= 0 && at + count <= f.length()) { "Truncated FWD" }
            return ByteArray(count).also { f.seek(at); f.readFully(it) }
        }
        fun text(at: Long, count: Int): String {
            val data = bytes(at, count)
            val end = data.indexOf(0)
            require(end > 0 && data.take(end).all { it.toInt() in 32..126 }) { "Invalid FWD text" }
            return String(data, 0, end, Charsets.US_ASCII)
        }
        fun crc(at: Long, count: Long, expected: Long) {
            require(at >= 0 && count >= 0 && at + count <= f.length()) { "Invalid FWD range" }
            val checksum = CRC32()
            val buffer = ByteArray(64 * 1024)
            f.seek(at)
            var remaining = count
            while (remaining > 0) {
                val n = minOf(remaining, buffer.size.toLong()).toInt()
                f.readFully(buffer, 0, n); checksum.update(buffer, 0, n); remaining -= n
            }
            require(checksum.value == expected) { "FWD checksum mismatch" }
        }
        require(word(0) == 0x7e5a5a60L && word(76) == 92L) { "Select an unpacked Fit3 FWD package, not a watchface or raw image" }
        val source = text(4, 64)
        require(source.matches(Regex("R390[A-Z0-9]{4,30}"))) { "Not an SM-R390 firmware" }
        require(word(72) and 3L != 0L) { "Source-version protection is disabled" }
        val updaterSize = word(80)
        val packet = word(84)
        val packetSize = word(88)
        require(updaterSize in 16..0x38000 && packet == 92 + updaterSize + 4 &&
            packetSize >= 84 && packet + packetSize + 4 == f.length()) { "Invalid FWD boundaries" }
        crc(92, updaterSize, word(packet - 4))
        crc(packet, packetSize, word(packet + packetSize))
        require(word(packet) == 0x7e5a5a5aL) { "Invalid FWD packet" }
        val target = text(packet + 4, 64)
        require(target.matches(Regex("R390[A-Z0-9]{4,30}"))) { "Invalid target firmware" }
        val profile = bytes(packet + 69, 2)
        require(profile[0] == 0x18.toByte()) { "Unsupported FWD compression" }
        val count = profile[1].toInt() and 255
        val tableSize = 80L + count * 128L
        require(count > 0 && tableSize + 4 <= packetSize) { "Invalid FWD table" }
        crc(packet, tableSize, word(packet + tableSize))
        var cursor = tableSize + 4
        var expanded = 0L
        var firmwareSize = 0L
        var resourceSize = 0L
        val destinations = hashSetOf<String>()
        repeat(count) { i ->
            val record = packet + 80 + i * 128L
            val typeAndFlags = word(record)
            val kind = (typeAndFlags and 65535).toInt()
            val flags = typeAndFlags ushr 16
            require((kind == 0 && flags == 0x34L) || (kind in setOf(1, 4, 7) && flags == 0x74L)) {
                "Delta/unknown FWD components are not supported"
            }
            val start = word(record + 4)
            val length = word(record + 8)
            require(start == cursor && length > 0 && start + length + 4 <= packetSize) { "Invalid component range" }
            val destination = text(record + 12, 116)
            require(destination.startsWith('/') && '\\' !in destination && ':' !in destination &&
                destination.drop(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
                destinations.add(destination.lowercase(java.util.Locale.ROOT))) { "Invalid component destination" }
            require(when (kind) {
                1 -> destination == MAIN
                4 -> destination == "/user/ota/sensorhub/sensor_hub.bin"
                7 -> destination == "/user/ota/tp/tp_firmware_stx.bin"
                else -> destination.startsWith("/nand/system/") || destination.startsWith("/user/rescue/")
            }) { "Unsupported component destination" }
            val main = if (kind == 1) ByteArrayOutputStream() else null
            val checksum = CRC32()
            var rawSize = 0L
            FileInputStream(file).use { input ->
                input.channel.position(packet + start)
                ZipInputStream(LimitedInput(input, length)).use { zip ->
                    val entry = zip.nextEntry ?: error("Empty component ZIP")
                    require(!entry.isDirectory && entry.method in setOf(0, 8)) { "Unsupported component ZIP" }
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        rawSize += n; expanded += n
                        require(expanded <= MAX_EXPANDED && (main == null || rawSize <= 0x380004)) { "FWD expansion limit exceeded" }
                        checksum.update(buffer, 0, n); main?.write(buffer, 0, n)
                    }
                    require(zip.nextEntry == null) { "Multiple ZIP members" }
                }
            }
            require(rawSize > 0 && checksum.value == word(packet + start + length)) { "Component checksum mismatch" }
            main?.let { validateMain(it.toByteArray()) }
            if (kind == 0) resourceSize += rawSize else firmwareSize += rawSize
            cursor = start + length + 4
        }
        require(cursor == packetSize && firmwareSize == word(packet + 72) && resourceSize == word(packet + 76)) {
            "FWD totals mismatch"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        FirmwareInfo(name, f.length(), digest.digest().joinToString("") { "%02x".format(it) }, source, target, count)
    }

    private fun validateMain(raw: ByteArray) {
        fun word(at: Int) = (0..3).fold(0L) { value, i -> value or ((raw[at + i].toLong() and 255) shl (i * 8)) }
        require(raw.size >= 0x1010 && word(0x1000) == 0x11223344L && word(0x1004) == 0xdeaddeadL) { "Invalid main-image header" }
        val size = word(0x100c).toInt()
        require(size in 0x1010..0x380000 && raw.size == size + 4) { "Invalid main-image length" }
        val crc = CRC32().apply {
            update(byteArrayOf(-1, -1, -1, -1)); update(raw, 4, 0xffc)
            update(ByteArray(16)); update(raw, 0x1010, size - 0x1010)
        }
        require(crc.value == word(0x1008)) { "Main-image boot checksum mismatch" }
    }

    private class LimitedInput(private val source: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining <= 0) -1 else source.read().also { if (it >= 0) remaining-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int = if (remaining <= 0) -1 else
            source.read(b, off, minOf(len.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
    }
}
