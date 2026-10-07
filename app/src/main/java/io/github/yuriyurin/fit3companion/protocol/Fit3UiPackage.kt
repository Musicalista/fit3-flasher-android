package io.github.yuriyurin.fit3companion.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32

data class Fit3UiLayer(val type: Int, val scale: Int, val action: Int, val x: Int, val y: Int,
    val width: Int, val height: Int, val foreground: Int, val background: Int, val text: String)
data class Fit3UiPackage(val title: String, val key: String, val bytes: ByteArray,
    val layers: List<Fit3UiLayer>, val imageOffset: Int?) {
    val formatVersion get() = (bytes[4].toInt() and 255) or ((bytes[5].toInt() and 255) shl 8)
    val fileName get() = "fit3ui_$key.f3ui"
    val watchPath get() = "/user/$fileName"
}

/** One bounded file shared with the independent watch renderer. No scripts. */
object Fit3UiCodec {
    const val HEADER = 96
    const val RECORD = 48
    const val MAX_BYTES = HEADER + 256 * 402 * 2 + 32 * RECORD
    const val ACTIVE_PATH = "/user/fit3ui_active.f3ui"
    fun ownedPath(path: String) = Regex("/user/fit3ui_[0-9a-f]{16}\\.f3ui").matches(path)
    fun decode(bytes: ByteArray): Fit3UiPackage {
        require(bytes.size in HEADER..MAX_BYTES) { "Неверный размер файла Fit3UI" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.take(4).toByteArray().contentEquals("F3UI".toByteArray())) { "Выберите файл .f3ui, а не прошивку или циферблат Samsung" }
        require(b.getShort(4).toInt() in 1..2 && b.getShort(6).toInt() == HEADER) { "Эта версия циферблата не поддерживается" }
        require(b.getInt(8) == bytes.size) { "Файл циферблата обрезан" }
        require(CRC32().apply { update(bytes, HEADER, bytes.size - HEADER) }.value == (b.getInt(12).toLong() and 0xffffffffL)) { "Повреждён файл циферблата" }
        require(b.getShort(16).toInt() == 256 && b.getShort(18).toInt() == 402) { "Нужен циферблат 256 × 402" }
        val count = b.getShort(20).toInt(); val flags = b.getShort(22).toInt()
        require(count in 1..32 && flags in 0..1 && bytes.sliceArray(88..95).all { it == 0.toByte() }) { "Неверный заголовок Fit3UI" }
        val titleBytes = bytes.sliceArray(24..87); val end = titleBytes.indexOf(0)
        require(end > 0 && titleBytes.drop(end).all { it == 0.toByte() }) { "Неверное название циферблата" }
        val title = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(titleBytes, 0, end)).toString()
        require(title.isNotBlank() && title.none { it.isISOControl() }) { "Неверное название циферблата" }
        val start = HEADER + if (flags == 1) 256 * 402 * 2 else 0
        require(bytes.size == start + count * RECORD) { "Неверная структура циферблата" }
        val layers = (0 until count).map { i ->
            val p = start + i * RECORD
            val type = bytes[p].toInt() and 255; val scale = bytes[p + 1].toInt() and 255
            val action = bytes[p + 2].toInt() and 255
            val x = b.getShort(p + 4).toInt(); val y = b.getShort(p + 6).toInt()
            val w = b.getShort(p + 8).toInt(); val h = b.getShort(p + 10).toInt()
            val fg = b.getInt(p + 12); val bg = b.getInt(p + 16)
            require(type in 1..(if (b.getShort(4).toInt() == 2) 6 else 5) && scale in 1..8 && bytes[p + 3] == 0.toByte() &&
                action in 0..3 && (type == 5 || action == 0) && x >= 0 && y >= 0 && w > 0 && h > 0 &&
                x + w <= 256 && y + h <= 402 && fg in 0..0xffffff && bg in 0..0xffffff) { "Неверные параметры элемента ${i + 1}" }
            val textBytes = bytes.sliceArray(p + 20 until p + RECORD); val stop = textBytes.indexOf(0)
            require(stop >= 0 && textBytes.take(stop).all { (it.toInt() and 255) in 32..126 } &&
                textBytes.drop(stop).all { it == 0.toByte() }) { "Текст элемента ${i + 1} не поддерживается" }
            val text = String(textBytes, 0, stop, Charsets.US_ASCII)
            val chars = when(type) { 3 -> 5; 4 -> 10; else -> text.length }
            require(type in listOf(1,6) || (chars * 6 * scale <= w && 10 * scale <= h)) { "Текст элемента ${i + 1} выходит за границы" }
            Fit3UiLayer(type, scale, action, x, y, w, h, fg, bg, text)
        }
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
        return Fit3UiPackage(title, key, bytes.copyOf(), layers, if (flags == 1) HEADER else null)
    }
}
