package io.github.yuriyurin.fit3companion.ble

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Ready-made firmware packages shipped inside the app (fork addition, GPLv3 like the rest of the project).
 *
 * The original package is stored once as assets/fw/stock.bin and every modified package is a small delta against it (COPY/ADD operations);
 * assets/fw/fw_list.txt lists them as "file;description (pt);description (en);size;sha256". The rebuilt package is checked against its SHA-256
 * and then goes through the normal FirmwarePackage.inspect() validation like any file picked by the user.
 */
data class BundledFirmware(val file: String, val descPt: String, val descEn: String, val size: Long, val sha256: String) {
    val isStock: Boolean get() = file == "stock.bin"
    val title: String get() = if (isStock) "stock-aza3.bin" else file.removeSuffix(".dlt") + ".bin"
}

object FirmwareBundle {
    fun list(context: Context): List<BundledFirmware> = runCatching {
        context.assets.open("fw/fw_list.txt").use { it.readBytes() }.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
            val f = line.trim().split(";")
            if (f.size < 5) null else BundledFirmware(f[0], f[1], f[2], f[3].toLongOrNull() ?: 0L, f[4])
        }.toList()
    }.getOrDefault(emptyList())

    private fun InputStream.readAllBytesCompat(): ByteArray = use { it.readBytes() }
    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24)

    /** Rebuilds the package into the cache folder and returns the file (the SHA-256 is verified). Call it off the main thread. */
    fun build(context: Context, item: BundledFirmware): File {
        val stock = context.assets.open("fw/stock.bin").readAllBytesCompat()
        val result: ByteArray
        if (item.isStock) result = stock
        else {
            val d = context.assets.open("fw/" + item.file).readAllBytesCompat()
            require(d.size >= 9 && d[0] == 'F'.code.toByte() && d[1] == 'D'.code.toByte() && d[2] == 'L'.code.toByte() && d[3] == '1'.code.toByte()) { "bad delta file" }
            val n = le32(d, 4); result = ByteArray(n); var o = 0; var p = 8
            while (true) {
                val op = d[p++].toInt()
                if (op == 0) break
                if (op == 1) {
                    val off = le32(d, p); val len = le32(d, p + 4); p += 8
                    require(off >= 0 && len >= 0 && off + len <= stock.size && o + len <= n) { "bad delta copy" }
                    System.arraycopy(stock, off, result, o, len); o += len
                } else if (op == 2) {
                    val len = le32(d, p); p += 4
                    require(len >= 0 && o + len <= n && p + len <= d.size) { "bad delta add" }
                    System.arraycopy(d, p, result, o, len); p += len; o += len
                } else error("bad delta operation")
            }
            check(o == n) { "delta size mismatch" }
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(result).joinToString("") { "%02x".format(it) }
        check(sha.equals(item.sha256, ignoreCase = true)) { "the rebuilt firmware does not match its checksum" }
        val out = File(context.cacheDir, item.title)
        out.outputStream().use { it.write(result) }
        return out
    }
}
