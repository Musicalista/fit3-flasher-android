package io.github.yuriyurin.fit3companion.protocol

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class Fit3UiPackageTest {
    private fun sample() = File("src/main/assets/fit3ui/midnight.f3ui").readBytes()
    private fun bad(bytes: ByteArray) { assertTrue(runCatching { Fit3UiCodec.decode(bytes) }.isFailure) }
    private fun repair(bytes: ByteArray): ByteArray {
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12,CRC32().apply { update(bytes,96,bytes.size-96) }.value.toInt());return bytes
    }
    @Test fun validPackageHasStableIdentityAndBoundedPaths() {
        val face=Fit3UiCodec.decode(sample());assertEquals("Полночь",face.title);assertEquals(7,face.layers.size)
        assertEquals(listOf(1,2,3),face.layers.filter{it.type==5}.map{it.action})
        assertTrue(Fit3UiCodec.ownedPath(face.watchPath));assertEquals(face.key,Fit3UiCodec.decode(face.bytes).key)
        assertFalse(Fit3UiCodec.ownedPath(Fit3UiCodec.ACTIVE_PATH));assertFalse(Fit3UiCodec.ownedPath("/user/ota/fit3ui_0123456789abcdef.f3ui"))
    }
    @Test fun truncationCorruptionAndForeignFilesAreRejected() {
        for(n in listOf(0,95,96,431))bad(sample().copyOf(n))
        bad(sample()+byteArrayOf(0));bad(sample().apply { this[lastIndex]=1 });bad(ByteArray(512))
        bad(sample().apply { this[4]=3 });bad(sample().apply { this[22]=2 });bad(ByteArray(Fit3UiCodec.MAX_BYTES+1))
    }
    @Test fun RecomputedChecksumCannotBypassGeometryOrTextValidation() {
        for(offset in listOf(96,97,98,99,88))bad(repair(sample().apply { this[offset]=127 }))
        bad(repair(sample().apply { this[104]=0;this[105]=0 }))
        bad(repair(sample().apply { this[100]=-1;this[101]=127 }))
        bad(repair(sample().apply { this[96+48+1]=8 })) // A long header no longer fits.
        bad(repair(sample().apply { for(i in 96+48+20 until 96+48+48)this[i]=65 })) // Missing terminator.
        bad(sample().apply { this[24]=0xc0.toByte();this[25]=0xaf.toByte() }) // Invalid UTF-8 title.
    }
    @Test fun fullBackgroundAndOverlayCanTravelAsOneFile() {
        val file=sample();val expanded=file.copyOfRange(0,96)+ByteArray(256*402*2)+file.copyOfRange(96,file.size)
        ByteBuffer.wrap(expanded).order(ByteOrder.LITTLE_ENDIAN).putInt(8,expanded.size).putShort(22,1)
        val face=Fit3UiCodec.decode(repair(expanded));assertEquals(96,face.imageOffset);assertEquals(7,face.layers.size)
    }
    @Test fun weatherBindingHasAnExplicitFormatVersion() {
        val file=sample().apply { this[4]=2;this[96]=6 }
        val face=Fit3UiCodec.decode(repair(file))
        assertEquals(2,face.formatVersion);assertEquals(6,face.layers.first().type)
        bad(repair(file.copyOf().apply { this[4]=1 }))
        bad(repair(file.copyOf().apply { this[96]=7 }))
    }
}
