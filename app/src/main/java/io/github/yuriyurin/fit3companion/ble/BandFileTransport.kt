package io.github.yuriyurin.fit3companion.ble

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.zip.CRC32

data class BandFileEntry(val path: String, val directory: Boolean, val size: Long? = null) {
    val name: String get() = path.substringAfterLast('/')
}
internal interface TimedBandInput {
    fun readWithin(data: ByteArray, offset: Int, length: Int, timeoutMillis: Long): Int
}
internal class BandFileConsoleUnavailable(cause: IOException) : IOException("File console unavailable", cause)

internal object BandFilePaths {
    fun validate(path: String): String {
        require(path.length in 1..99 && path.startsWith('/') && !path.endsWith('/') &&
            path.all { it.code in 32..126 && it !in ",=^\\" } &&
            path.substring(1).split('/').all { it.isNotEmpty() && it == it.trim() && !it.endsWith('.') && it != "." && it != ".." }) { "Invalid watch path" }
        require(path == "/user" || path.startsWith("/user/") || path == "/nand" || path.startsWith("/nand/")) { "Use /user or /nand" }
        return path
    }
    fun writable(path: String): Boolean {
        return runCatching { validate(path) }.isSuccess &&
            (path.startsWith("/user/") || path.startsWith("/nand/user/")) &&
            !listOf("/user/ota", "/nand/user/ota").any { path.equals(it, true) || path.startsWith("$it/", true) }
    }
    fun destination(folder: String, name: String): String {
        validate(folder)
        require(name.isNotBlank() && name == name.trim() && !name.contains('/')) { "Enter a file name" }
        val path = validate("$folder/$name")
        require(writable(path)) { "This folder is read-only" }
        return path
    }
    fun parent(path: String): String? = if (path == "/user" || path == "/nand") null else path.substringBeforeLast('/')
}

/** Firmware-derived FI list/mkdir/rm plus previously tested SPP upload/export.
 * Paths are data; no arbitrary AT command, formatting, recursive delete or OTA.
 */
internal object BandFileTransport {
    const val MAX_BYTES = 16L * 1024 * 1024
    const val WINDOW = 39_600
    private fun send(output: OutputStream, command: String) { output.write(command.toByteArray(Charsets.US_ASCII)); output.flush() }
    private fun read(input: InputStream, n: Int, timeout: Long = 15_000): ByteArray {
        val data = ByteArray(n); var at = 0
        val deadline = System.nanoTime() + timeout * 1_000_000
        while (at < n) {
            if (Thread.currentThread().isInterrupted) throw IOException("Operation cancelled")
            if (System.nanoTime() >= deadline) throw IOException("Watch response timeout")
            val remaining = maxOf(1, (deadline - System.nanoTime()) / 1_000_000)
            val count = if (input is TimedBandInput) input.readWithin(data, at, minOf(960, n - at), remaining)
                else input.read(data, at, minOf(960, n - at))
            if (count < 0) throw IOException("Watch connection closed")
            if (count > 0) at += count
        }
        return data
    }
    private fun expect(input: InputStream, value: String) { require(String(read(input, value.length), Charsets.US_ASCII) == value) { "Watch rejected file operation" } }
    fun checkLink(input: InputStream, output: OutputStream) {
        send(output, "34"); expect(input, "340")
    }
    fun requireFit3Ui(input: InputStream, output: OutputStream, minimumFormat: Int = 1) {
        send(output, "00AT^BLUETOOTH")
        val reply = response(input, false, 5000)
        require(reply.lineSequence().any { it.trim() == "FIT3UI=2" }) { "Для работы с циферблатами нужна прошивка Fit3UI 0.2 или новее" }
        require(minimumFormat <= 1 || reply.lineSequence().any { it.trim() == "FIT3UI_FORMAT=2" }) { "Для погоды на этом циферблате нужна прошивка Fit3UI 0.5 или новее" }
    }
    private fun response(input: InputStream, listing: Boolean, firstTimeout: Long = 20_000): String {
        val result = StringBuilder(); val deadline = System.nanoTime() + 180_000_000_000L
        while (true) {
            require(System.nanoTime() < deadline && result.length < 512_000) { "Folder listing is too large or timed out" }
            val ch = read(input, 1, if (result.isEmpty()) firstTimeout else 5000)[0].toInt() and 255
            require(ch in 9..13 || ch in 32..126) { "Unsupported file name encoding" }
            result.append(ch.toChar())
            if (ch == 10) {
                val line = result.substring(result.lastIndexOf("\n", result.length - 2) + 1).trim()
                if (line == "ERR" || (!listing && line.startsWith("Failed "))) throw IOException("Watch rejected file operation: $line")
                if (line == if (listing) "^LS=OK" else "OK") return result.toString()
            }
        }
    }
    fun parseListing(folder: String, reply: String): List<BandFileEntry> {
        BandFilePaths.validate(folder)
        require(reply.trimEnd().endsWith("^LS=OK") && !reply.contains("open dir error", true)) { "Could not list folder" }
        val entries = linkedMapOf<String, BandFileEntry>()
        for (raw in reply.lineSequence()) {
            val line = raw.trimStart().trimEnd('\r')
            if (line.isBlank() || line == "^LS=OK" || line.endsWith("limited by depth")) continue
            require(line.startsWith("D ") || line.startsWith("F ")) { "Unknown folder response" }
            val directory = line.startsWith("D "); val body = line.substring(2).trimStart()
            val path = (if (directory) body else body.substringBeforeLast('\t')).trimEnd('/')
            BandFilePaths.validate(path)
            val size = if (directory) null else body.substringAfterLast('\t', "").toLongOrNull()
            require(directory || (size != null && size >= 0)) { "Invalid file size" }
            require(path == folder || path.startsWith("$folder/")) { "Unexpected listing path" }
            if (path != folder && path.substringBeforeLast('/') == folder) entries[path] = BandFileEntry(path, directory, size)
        }
        return entries.values.sortedWith(compareBy<BandFileEntry> { !it.directory }.thenBy { it.name.lowercase() })
    }
    fun list(input: InputStream, output: OutputStream, folder: String): List<BandFileEntry> {
        routeConsole(input, output)
        send(output, "00FI^LS=${BandFilePaths.validate(folder)}")
        return parseListing(folder, response(input, true))
    }
    /** Stock AT^BLUETOOTH selects SPP as printf output; otherwise FI replies go to UART. */
    private fun routeConsole(input: InputStream, output: OutputStream) {
        send(output, "00AT^BLUETOOTH")
        try { response(input, false, 5000) } catch (error: IOException) { throw BandFileConsoleUnavailable(error) }
    }
    fun mkdir(input: InputStream, output: OutputStream, path: String) {
        require(BandFilePaths.writable(path)); routeConsole(input, output)
        send(output, "00FI^MKDIR=$path"); response(input, false)
    }
    fun delete(input: InputStream, output: OutputStream, entry: BandFileEntry) {
        require(BandFilePaths.writable(entry.path))
        routeConsole(input, output)
        send(output, "00FI^${if (entry.directory) "RMDIR" else "RM"}=${entry.path}"); response(input, false)
    }
    fun upload(input: InputStream, output: OutputStream, path: String, file: File, progress: (Long, Long) -> Unit) {
        require(BandFilePaths.writable(path) && file.length() in 1..MAX_BYTES)
        send(output, "300"); expect(input, "300")
        send(output, "33bin,$path,${file.length()}"); expect(input, "330")
        val total = file.length(); var sent = 0L; val buffer = ByteArray(WINDOW + 4)
        file.inputStream().use { source ->
            while (sent < total) {
                val n = minOf(WINDOW.toLong(), total - sent).toInt(); var at = 0
                while (at < n) { val k = source.read(buffer, at, n - at); check(k > 0); at += k }
                val crc = CRC32().apply { update(buffer, 0, n) }.value
                for (i in 0..3) buffer[n + i] = (crc ushr (i * 8)).toByte()
                at = 0
                while (at < n + 4) { val k = minOf(960, n + 4 - at); output.write(buffer, at, k); output.flush(); at += k }
                expect(input, "310"); sent += n; progress(sent, total)
            }
            require(source.read() == -1) { "Selected file changed" }
        }
        send(output, "32"); expect(input, "320"); Thread.sleep(250)
        send(output, "34"); expect(input, "340")
    }
    private fun uint(input: InputStream): Long = read(input, 4).fold(0L) { v, b -> (v shl 8) or (b.toLong() and 255) }
    fun download(input: InputStream, output: OutputStream, path: String, target: OutputStream, progress: (Long, Long) -> Unit): Long {
        send(output, "061${BandFilePaths.validate(path)}\u0000")
        require(read(input, 1)[0] == 0x40.toByte()) { "Watch file unavailable" }
        val total = uint(input); require(total <= MAX_BYTES) { "File exceeds 16 MiB" }
        var received = 0L; Thread.sleep(250)
        while (received < total) {
            send(output, "062"); require(read(input, 1)[0] == 0x40.toByte()) { "Invalid file block" }
            val n = uint(input); require(n in 1..1_048_576) { "Invalid file block length" }
            val block = read(input, n.toInt()); val checksum = uint(input)
            require(block.fold(0L) { sum, b -> (sum + (b.toInt() and 255)) and 0xffffffffL } == checksum) { "File block checksum mismatch" }
            val useful = minOf(n, total - received).toInt(); target.write(block, 0, useful); received += useful; progress(received, total)
            if (received < total) Thread.sleep(250)
        }
        send(output, "062") // All advertised bytes are verified; final status is optional on AZA3.
        return total
    }
}
