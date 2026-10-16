package cz.audioreplay.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SegmentStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun pcm(start: Int, frames: Int) = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(frames) { putShort((start + it).toShort()) } }.array()
    private fun read(s: SegmentStore.Snapshot): List<Int> = s.slices.flatMap { slice ->
        val bytes = ByteArray(slice.lengthBytes.toInt()); RandomAccessFile(slice.file,"r").use { it.seek(slice.offsetBytes); it.readFully(bytes) }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); List(bytes.size/2) { b.short.toInt() }
    }
    @Test fun snapshotEndsAtPressAndIncludesOpenSegment() {
        SegmentStore(temporary.newFolder(),30).use { store -> store.beginSession(10,500); store.append(pcm(0,123));
            store.snapshot(10).use { s -> assertEquals((23..122).toList(),read(s)); assertEquals(100L,s.frames); assertEquals(2_300_000_500L,s.startMonotonicNanos) }
        }
    }
    @Test fun shortHistoryExportsOnlyRealFrames() {
        SegmentStore(temporary.newFolder(),30).use { store -> store.beginSession(10,0); store.append(pcm(0,13)); store.snapshot(300).use { assertEquals(13L,it.frames); assertEquals((0..12).toList(),read(it)) } }
    }
    @Test fun rotationKeepsExactTenSeconds() {
        SegmentStore(temporary.newFolder(),10).use { store -> store.beginSession(10,0); store.append(pcm(0,367)); assertEquals(10.0,store.availableSeconds(),0.0); store.snapshot(100).use { assertEquals((267..366).toList(),read(it)) } }
    }
    @Test fun growingBufferDoesNotResurrectExpiredSamples() {
        SegmentStore(temporary.newFolder(),10).use { store -> store.beginSession(10,0); store.append(pcm(0,123)); store.setBufferSeconds(60); assertEquals(10.0,store.availableSeconds(),0.0); store.append(pcm(123,20)); assertEquals(12.0,store.availableSeconds(),0.0) }
    }
    @Test fun shrinkingBufferCannotInvalidateExport() {
        SegmentStore(temporary.newFolder(),30).use { store -> store.beginSession(10,0); store.append(pcm(0,300)); val export = store.snapshot(30); store.setBufferSeconds(10); store.append(pcm(300,300));
            assertEquals((0..299).toList(),read(export)); val pinned = export.slices.first().file; assertTrue(pinned.exists()); export.close(); assertFalse(pinned.exists())
        }
    }
    @Test fun overlappingExportsHaveIndependentPins() {
        SegmentStore(temporary.newFolder(),10).use { store -> store.beginSession(10,0); store.append(pcm(0,100)); val first = store.snapshot(10); store.append(pcm(100,50)); val second = store.snapshot(10); store.append(pcm(150,200)); first.close();
            assertEquals((50..149).toList(),read(second)); second.close(); second.close()
        }
    }
    @Test fun pauseResumeStartsDistinctSession() {
        SegmentStore(temporary.newFolder(),30).use { store -> store.beginSession(10,0); store.append(pcm(0,100)); val old = store.snapshot(10); store.finishWriting(); store.beginSession(20,999); store.append(pcm(1000,20));
            store.snapshot(10).use { assertNotEquals(old.sessionId,it.sessionId); assertEquals((1000..1019).toList(),read(it)); assertEquals(20,it.sampleRate) }; assertEquals((0..99).toList(),read(old)); old.close()
        }
    }
    @Test fun stopDeletesCacheButPreservesReservedExport() {
        val folder = temporary.newFolder(); val store = SegmentStore(folder,10); store.beginSession(10,0); store.append(pcm(0,100)); val export = store.snapshot(10); store.close(); assertEquals(0.0,store.availableSeconds(),0.0); assertEquals((0..99).toList(),read(export)); export.close(); assertTrue(folder.listFiles()!!.isEmpty())
    }
    @Test fun oldCacheCleanupDoesNotTouchSavedMedia() {
        val folder = temporary.newFolder(); File(folder,"stale.pcm").writeBytes(byteArrayOf(1)); val saved = File(folder,"saved.m4a").apply { writeBytes(byteArrayOf(2)) }; SegmentStore.discardStale(folder); assertEquals(listOf(saved),folder.listFiles()!!.toList())
    }
    @Test fun lowStorageFailsWithoutClaimingWrittenFrames() {
        SegmentStore(temporary.newFolder(),10,usableSpace = { 0L }).use { store -> store.beginSession(10,0); assertThrows(java.io.IOException::class.java) { store.append(pcm(0,100)) }; assertEquals(0L,store.availableFrames()) }
    }
    @Test fun concurrentCaptureAndSnapshotsRemainOrdered() {
        SegmentStore(temporary.newFolder(),10).use { store ->
            store.beginSession(100,0); store.append(pcm(0,100)); val pool = Executors.newFixedThreadPool(2)
            val producer = pool.submit { for (i in 1..100) store.append(pcm(i*100,100)) }
            val consumer = pool.submit { repeat(100) { store.snapshot(10).use { s -> val frames = read(s); assertEquals(s.frames.toInt(),frames.size); assertTrue(frames.zipWithNext().all { (a,b) -> b == a+1 }) } } }
            producer.get(10,TimeUnit.SECONDS); consumer.get(10,TimeUnit.SECONDS); pool.shutdown()
        }
    }
}
