package cz.audioreplay.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecordingGainTest {
    private fun pcm(vararg samples: Int) = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it.toShort()) } }.array()
    private fun samples(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).let { buffer -> List(bytes.size/2) { buffer.short.toInt() } }

    @Test fun sixDecibelsAmplifiesBothSignsAndClipsWithoutWrapping() {
        val data = pcm(1000,-1000,20000,-20000,0)
        PcmGain.applyInPlace(data,data.size,6)
        assertEquals(listOf(1995,-1995,32767,-32768,0),samples(data))
    }
    @Test fun zeroGainIsBitExactAndPartialReadDoesNotTouchTail() {
        val original = pcm(1234,-1234,32767,-32768)
        val unchanged = original.clone(); PcmGain.applyInPlace(unchanged,unchanged.size,0)
        assertArrayEquals(original,unchanged)
        PcmGain.applyInPlace(unchanged,4,6)
        assertEquals(listOf(2462,-2462,32767,-32768),samples(unchanged))
        assertThrows(IllegalArgumentException::class.java) { PcmGain.applyInPlace(unchanged,3,6) }
    }
    @Test fun fixedDefaultIsTwelveDecibelsAndQuietInputKeepsRequestedGain() {
        assertEquals(12,PcmGain.DEFAULT_DB)
        assertEquals(3.9810717,PcmGain.safeFactor(1000,12),0.00001)
    }
    @Test fun hotInputReducesGainWithHeadroomInsteadOfClipping() {
        val data = pcm(20000,-20000,32767,-32768)
        PcmGain.applyFactorInPlace(data,data.size,PcmGain.safeFactor(32768,12))
        assertTrue(samples(data).all { kotlin.math.abs(it) <= 29490 })
        assertThrows(IllegalArgumentException::class.java) { PcmGain.safeFactor(40000,12) }
    }
}
