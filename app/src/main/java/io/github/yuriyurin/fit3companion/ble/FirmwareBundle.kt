package io.github.yuriyurin.fit3companion.ble

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

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
    /** Public half of the "Fit3 mods" project's own release-signing key (RSA-3072, SubjectPublicKeyInfo DER, base64).
     * This proves a bundled package was built and signed by that project; it is NOT a fix for the watch's missing firmware
     * signature check (only Samsung can fix that -- see FORK.md) and never applies to the stock Samsung package. */
    private const val PUBLIC_KEY_SPKI_B64 = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAm2mPmXWF2Cyp1NVz3VoDtfAKX0bh+9HaLBpmh50IT9irI35zaYGrhiq5JuJd5zEmQvUSbStKbY2xMBPuQBWx4xgNshplPZZgODiD0i6R8fuVBy4QBRS6XNBpPlU+kPNsEJbe0tyrMoDK4sgEgnXt2HNcFUV2wj8xyObOEH/IKKclsjFCr5GHjjvkkNGlXHd28MtpSpgH/M6zkfa79CvzlPMzvhwtdeB+YKRbKla4EIPOet5TDCo364Jl8jr1jUAJs2Z3CsjorAA+5IfHPs9tkRthNMlOu7oGTLonZ8+1ZfZcBkpx9uG3OlnG1T9D/+I2mgJLeurkuk9j6M0uJYQL4gGf8+WVXrQFDpAd5UYvOJuori6Z0Rjiud2OUJ+KCrcgPPx7VTqTr2rlxnhxK7LkdccCZkAZhVuq9pakeXPgqlotuLGhlG/PkCV6oDO7Bxh5oe8IyxpIgQbP5wEdDjEMGY/dlOTzVx6KKFbVfWr8RyWOjHN7uphRotO8tymLHDvZAgMBAAE="
    private val publicKey: PublicKey by lazy {
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_SPKI_B64)))
    }
    /** true only if `sig` (raw PKCS#1 v1.5 / SHA-256 bytes) verifies over `data` with the project's key. Never throws. */
    fun verifySignature(data: ByteArray, sig: ByteArray?): Boolean {
        if (sig == null || sig.isEmpty()) return false
        return runCatching { Signature.getInstance("SHA256withRSA").apply { initVerify(publicKey); update(data) }.verify(sig) }.getOrDefault(false)
    }
    /** Reads the sibling .sig asset for a bundled item (fw/<name>.bin.sig), or null if this copy of the app does not have it. */
    fun signatureBytes(context: Context, item: BundledFirmware): ByteArray? = runCatching {
        val sigName = (if (item.isStock) "stock-aza3.bin" else item.file.removeSuffix(".dlt") + ".bin") + ".sig"
        context.assets.open("fw/$sigName").readAllBytesCompat()
    }.getOrNull()

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
