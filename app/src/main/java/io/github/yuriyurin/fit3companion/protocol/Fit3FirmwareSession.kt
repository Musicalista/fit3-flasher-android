package io.github.yuriyurin.fit3companion.protocol

/** Agent 30: the official FILE_INFO(FOTA=21) -> BT_OPEN -> RFCOMM handshake. */
internal class Fit3FirmwareSession {
    enum class Stage { IDLE, INFO, OPEN, READY, FAILED }
    data class Action(val request: ByteArray? = null, val ready: Boolean = false, val error: String? = null)
    var stage = Stage.IDLE
        private set

    fun begin(name: String, size: Int): ByteArray {
        require(stage == Stage.IDLE)
        require(name.matches(Regex("R390[A-Za-z0-9_.-]+\\.bin")))
        require(size in 1..32 * 1024 * 1024)
        stage = Stage.INFO
        return SaMessageCodec.fixedRequest(1, SaMessageCodec.byteParam(3, 21),
            SaMessageCodec.stringParam(4, name), SaMessageCodec.intParam(5, size))
    }

    fun receive(bytes: ByteArray): Action? {
        val header = SaMessageCodec.parseHeader(bytes) ?: return null
        val expected = when (stage) { Stage.INFO -> 1; Stage.OPEN -> 2; else -> return null }
        if (header.format != 0 || header.type != SaMessageCodec.TYPE_RESPONSE || header.id != expected) return null
        var status: Int? = null
        var reason: Int? = null
        var feature: Int? = null
        val seen = hashSetOf<Int>()
        var at = 1
        while (at < bytes.size) {
            val id = bytes[at++].toInt() and 255
            val width = when (id) { 1, 2, 3 -> 1; 6 -> 4; else -> return null }
            if (!seen.add(id) || at + width > bytes.size) return null
            val value = bytes[at].toInt() and 255
            when (id) { 1 -> status = value; 2 -> reason = value; 3 -> feature = value }
            at += width
        }
        // Ignore a late response for music/watchfaces; never use it as OTA permission.
        if (feature != null && feature != 21) return null
        if (status !in listOf(0, 1)) return null
        if (status == 0) {
            stage = Stage.FAILED
            return Action(error = "Band rejected OTA request $expected (reason ${reason ?: -1})")
        }
        return if (stage == Stage.INFO) {
            stage = Stage.OPEN
            Action(request = SaMessageCodec.fixedRequest(2))
        } else {
            stage = Stage.READY
            Action(ready = true)
        }
    }

    fun fail() { stage = Stage.FAILED }
}
