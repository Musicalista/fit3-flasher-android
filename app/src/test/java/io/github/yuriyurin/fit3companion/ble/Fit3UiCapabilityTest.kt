package io.github.yuriyurin.fit3companion.ble
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
class Fit3UiCapabilityTest {
    @Test fun oldFileBridgeCannotPretendToHaveFaceLoader() {
        val output=ByteArrayOutputStream()
        val result=runCatching { BandFileTransport.requireFit3Ui(ByteArrayInputStream("FIT3FS=1\r\nOK\r\n".toByteArray()),output) }
        assertTrue(result.isFailure);assertEquals("00AT^BLUETOOTH",output.toString())
    }
    @Test fun exactCapabilityIsRequiredBeforeChangingFiles() {
        val output=ByteArrayOutputStream()
        BandFileTransport.requireFit3Ui(ByteArrayInputStream("FIT3FS=1\r\nFIT3UI=2\r\nOK\r\n".toByteArray()),output)
        assertEquals("00AT^BLUETOOTH",output.toString())
        assertTrue(runCatching { BandFileTransport.requireFit3Ui(ByteArrayInputStream("FIT3UI=20\r\nOK\r\n".toByteArray()),ByteArrayOutputStream()) }.isFailure)
    }
    @Test fun weatherBindingRequiresNewFirmwareBeforeInstalling() {
        assertTrue(runCatching { BandFileTransport.requireFit3Ui(
            ByteArrayInputStream("FIT3UI=2\r\nOK\r\n".toByteArray()), ByteArrayOutputStream(), 2) }.isFailure)
        BandFileTransport.requireFit3Ui(ByteArrayInputStream(
            "FIT3UI=2\r\nFIT3UI_FORMAT=2\r\nOK\r\n".toByteArray()), ByteArrayOutputStream(), 2)
        assertTrue(runCatching { BandFileTransport.requireFit3Ui(ByteArrayInputStream(
            "FIT3UI=2\r\nFIT3UI_FORMAT=20\r\nOK\r\n".toByteArray()), ByteArrayOutputStream(), 2) }.isFailure)
    }
}
