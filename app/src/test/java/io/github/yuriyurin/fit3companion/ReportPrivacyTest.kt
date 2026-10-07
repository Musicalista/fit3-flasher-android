package io.github.yuriyurin.fit3companion

import org.junit.Assert.*
import org.junit.Test

class ReportPrivacyTest {
    @Test fun excludesMessagesPathsAndNotificationData() {
        val error = IllegalStateException("Alice: private message, steps=1234, AA:BB:CC:DD:EE:FF",
            java.io.IOException("/storage/emulated/0/private-user-file.txt token=secret"))
        error.stackTrace = arrayOf(StackTraceElement("io.github.yuriyurin.fit3companion.MainActivity", "onCreate",
            "/private/Alice.kt", 123))
        val report = ReportPrivacy.error(error)
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("MainActivity.onCreate:123"))
        listOf("Alice", "private-user-file", "secret", "1234", "AA:BB", "/storage", "private message")
            .forEach { assertFalse(report.contains(it)) }
    }
    @Test fun excludesThirdPartyFramesAndHandlesCauseCycles() {
        val a = RuntimeException("secret")
        val b = RuntimeException("private", a)
        a.initCause(b)
        a.stackTrace = arrayOf(StackTraceElement("private.user.Alice", "private", "secret.txt", 1))
        val report = ReportPrivacy.error(a)
        assertFalse(report.contains("Alice"))
        assertFalse(report.contains("secret"))
        assertTrue(report.length < 10000)
    }
}
