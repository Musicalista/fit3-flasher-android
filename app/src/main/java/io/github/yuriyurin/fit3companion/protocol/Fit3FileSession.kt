package io.github.yuriyurin.fit3companion.protocol

/** Agent 30 BT_OPEN/BT_CLOSE only. No FILE_INFO(FOTA), no OTA start. */
internal class Fit3FileSession {
    private var waiting = false
    fun begin(): ByteArray { check(!waiting); waiting = true; return SaMessageCodec.fixedRequest(2) }
    fun receive(bytes: ByteArray): Boolean? {
        val h = SaMessageCodec.parseHeader(bytes) ?: return null
        if (!waiting || h.format != 0 || h.type != SaMessageCodec.TYPE_RESPONSE || h.id != 2) return null
        var at = 1
        var status: Int? = null
        val seen = hashSetOf<Int>()
        while (at < bytes.size) {
            val id = bytes[at++].toInt() and 255
            val width = when (id) { 1, 2 -> 1; 6 -> 4; else -> return null }
            if (!seen.add(id) || at + width > bytes.size) return null
            if (id == 1) status = bytes[at].toInt() and 255
            at += width
        }
        if (status !in 0..1) return null
        waiting = false
        return status == 1
    }
}
