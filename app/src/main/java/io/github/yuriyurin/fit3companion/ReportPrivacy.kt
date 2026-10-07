package io.github.yuriyurin.fit3companion

/** Never include exception messages, file paths, thread names or runtime objects. */
object ReportPrivacy {
    private val namespaces = listOf("io.github.yuriyurin.fit3companion.", "java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.")
    private fun safeName(value: String): String = value.take(180).takeIf {
        it.matches(Regex("[A-Za-z0-9_.$<>-]+"))
    } ?: "unknown"

    fun error(error: Throwable): String = buildString {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        repeat(4) {
            val item = current ?: return@buildString
            if (!seen.add(item)) return@buildString
            val type = item.javaClass.name
            appendLine(if (namespaces.any(type::startsWith)) safeName(type) else "Throwable")
            item.stackTrace.filter { frame -> namespaces.any(frame.className::startsWith) }.take(40).forEach { frame ->
                appendLine("  at ${safeName(frame.className)}.${safeName(frame.methodName)}:${frame.lineNumber.coerceAtLeast(-1)}")
            }
            current = item.cause
            if (current != null) appendLine("Caused by:")
        }
    }
}
