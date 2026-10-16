package cz.audioreplay

import android.content.Context
import android.media.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.audioreplay.audio.AacExporter
import cz.audioreplay.core.SegmentStore
import cz.audioreplay.core.AudioEdit
import cz.audioreplay.audio.AudioEditor
import androidx.core.content.FileProvider
import cz.audioreplay.data.RecordingsRepository
import cz.audioreplay.data.RecordingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioPipelineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun folder() = File(context.cacheDir,"test-${UUID.randomUUID()}").also { it.mkdirs() }
    private fun pcm(seconds: Int, rate: Int = 24000): ByteArray = ByteBuffer.allocate(seconds * rate * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(seconds * rate) { putShort((sin(it * 2 * Math.PI * 440 / rate) * 10000).toInt().toShort()) }
    }.array()
    private fun assertAudio(file: File, seconds: Int, rate: Int = 24000) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals(1,extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals("audio/mp4a-latm",format.getString(MediaFormat.KEY_MIME))
            assertEquals(rate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertTrue("Duration: $format", abs(format.getLong(MediaFormat.KEY_DURATION) - seconds*1_000_000L) < 200_000)
            extractor.selectTrack(0)
            val bytes = ByteBuffer.allocate(16384)
            var previous = -1L; var packets = 0
            while (extractor.readSampleData(bytes,0) >= 0) {
                assertTrue(extractor.sampleTime >= previous)
                previous = extractor.sampleTime; packets++; extractor.advance()
            }
            assertTrue(packets > 10)
        } finally { extractor.release() }
    }
    private fun assertDecodable(file: File) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath); extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            decoder = MediaCodec.createDecoderByType("audio/mp4a-latm")
            decoder.configure(format,null,null,0); decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false; var decoded = 0L; var nonZero = false
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!outputDone) {
                check(System.nanoTime() < deadline)
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!; buffer.clear()
                        val n = extractor.readSampleData(buffer,0)
                        decoder.queueInputBuffer(index,0,maxOf(0,n),maxOf(0,extractor.sampleTime),if(n < 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (n < 0) inputDone = true else extractor.advance()
                    }
                }
                val index = decoder.dequeueOutputBuffer(info,10000)
                if (index >= 0) {
                    val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    decoded += info.size/2
                    while (buffer.remaining() >= 2) if (abs(buffer.short.toInt()) > 500) nonZero = true
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index,false)
                }
            }
            assertTrue(decoded >= 24000L * 9); assertTrue("Decoded audio is silent",nonZero)
        } finally { runCatching { decoder?.stop() }; decoder?.release(); extractor.release() }
    }
    @Test fun realAacEncoderProducesValidM4a() {
        val dir = folder()
        try { SegmentStore(File(dir,"pcm"),30).use { store ->
            store.beginSession(24000,0); store.append(pcm(12))
            store.snapshot(10).use { s -> val out = File(dir,"result.m4a"); AacExporter().encode(s,out,64000); assertAudio(out,10); assertDecodable(out) }
        } } finally { dir.deleteRecursively() }
    }
    @Test fun captureContinuesWhileExportIsReservedAndBufferShrinks() {
        val dir = folder()
        try { SegmentStore(File(dir,"pcm"),30).use { store ->
            store.beginSession(24000,0); store.append(pcm(20)); val snapshot = store.snapshot(20)
            val pool = Executors.newSingleThreadExecutor()
            try {
                val task = pool.submit { AacExporter().encode(snapshot,File(dir,"result.m4a"),64000) }
                store.setBufferSeconds(10); repeat(40) { store.append(pcm(1)) }
                task.get(60,TimeUnit.SECONDS); assertAudio(File(dir,"result.m4a"),20)
                assertEquals(10.0,store.availableSeconds(),0.0)
            } finally { snapshot.close(); pool.shutdownNow() }
        } } finally { dir.deleteRecursively() }
    }
    @Test fun overlappingExportsSurviveStop() {
        val dir = folder()
        try { val store = SegmentStore(File(dir,"pcm"),30)
            store.beginSession(24000,0); store.append(pcm(15)); val a = store.snapshot(10)
            store.append(pcm(5)); val b = store.snapshot(15); store.close()
            a.use { AacExporter().encode(it,File(dir,"a.m4a"),32000) }
            b.use { AacExporter().encode(it,File(dir,"b.m4a"),128000) }
            assertAudio(File(dir,"a.m4a"),10); assertAudio(File(dir,"b.m4a"),15)
            assertTrue(File(dir,"pcm").listFiles()!!.isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun interruptedEncoderDoesNotPublishPartialFile() {
        val dir = folder()
        try { SegmentStore(File(dir,"pcm"),10).use { store -> store.beginSession(24000,0); store.append(pcm(10))
            store.snapshot(10).use { snapshot -> val target = File(dir,"bad.m4a")
                assertThrows(IllegalStateException::class.java) { AacExporter().encode(snapshot,target,64000) { error("Simulated cancellation") } }
                assertFalse(target.exists())
            }
        } } finally { dir.deleteRecursively() }
    }
    @Test fun diskFullDoesNotIncreaseHistory() {
        val dir = folder()
        try { SegmentStore(File(dir,"pcm"),10,usableSpace = { 1L }).use { store -> store.beginSession(24000,0)
            assertThrows(java.io.IOException::class.java) { store.append(pcm(1)) }; assertEquals(0L,store.availableFrames())
        } } finally { dir.deleteRecursively() }
    }
    @Test fun privatePublicationRenameAndDelete() = runBlocking {
        val dir = folder(); val database = Room.inMemoryDatabaseBuilder(context,RecordingDatabase::class.java).build()
        try {
            val repository = RecordingsRepository(context,database.recordings())
            SegmentStore(File(dir,"pcm"),10).use { store -> store.beginSession(24000,0); store.append(pcm(10))
                store.snapshot(10).use { snapshot ->
                    val source = File(dir,"encoded.tmp"); AacExporter().encode(snapshot,source,64000)
                    val recording = repository.publish(source,snapshot,64000,false)
                    assertFalse(source.exists()); assertTrue(File(recording.location).exists()); assertEquals(1,database.recordings().all().size)
                    repository.rename(recording,"Test_${UUID.randomUUID()}")
                    val renamed = database.recordings().all().single(); assertTrue(File(renamed.location).exists()); assertFalse(File(recording.location).exists())
                    repository.delete(renamed); assertTrue(database.recordings().all().isEmpty()); assertFalse(File(renamed.location).exists())
                }
            }
        } finally { database.close(); dir.deleteRecursively() }
    }
    @Test fun publicMediaStorePublicationAndDelete() = runBlocking {
        val dir = folder(); val database = Room.inMemoryDatabaseBuilder(context,RecordingDatabase::class.java).build()
        try {
            val repository = RecordingsRepository(context,database.recordings())
            SegmentStore(File(dir,"pcm"),10).use { store -> store.beginSession(24000,0); store.append(pcm(10)); store.snapshot(10).use { snapshot ->
                val source = File(dir,"encoded.tmp"); AacExporter().encode(snapshot,source,64000)
                val recording = repository.publish(source,snapshot,64000,true)
                try { context.contentResolver.openFileDescriptor(repository.uri(recording),"r")!!.use { assertTrue(it.statSize > 0) } }
                finally { repository.delete(recording) }
            } }
        } finally { database.close(); dir.deleteRecursively() }
    }
    @Test fun completedFileIsRecoveredAfterProcessDeathBeforeDatabaseInsert() = runBlocking {
        val dir = folder(); val database = Room.inMemoryDatabaseBuilder(context,RecordingDatabase::class.java).build()
        val id = UUID.randomUUID().toString()
        val finalFile = File(context.filesDir,"recordings/Recovered_$id.m4a").also { it.parentFile!!.mkdirs() }
        val journal = File(context.filesDir,"publication.json")
        try {
            val repository = RecordingsRepository(context,database.recordings())
            SegmentStore(File(dir,"pcm"),10).use { store -> store.beginSession(24000,0); store.append(pcm(10)); store.snapshot(10).use { snapshot ->
                AacExporter().encode(snapshot,finalFile,64000)
                journal.writeText(JSONObject().apply {
                    put("id",id); put("name",finalFile.name); put("location",finalFile.absolutePath); put("public",false)
                    put("created",System.currentTimeMillis()); put("duration",snapshot.durationUs); put("size",finalFile.length()); put("rate",24000); put("bitrate",64000)
                }.toString())
                repository.recover()
                val restored = database.recordings().all().single()
                assertEquals(id,restored.id); assertTrue(finalFile.exists()); assertFalse(journal.exists())
                repository.delete(restored)
            } }
        } finally { finalFile.delete(); journal.delete(); database.close(); dir.deleteRecursively() }
    }
    @Test fun interruptedPublicationIsDiscardedDuringRecovery() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context,RecordingDatabase::class.java).build()
        val id = UUID.randomUUID().toString()
        val partial = File(context.filesDir,"recordings/Partial_$id.m4a").also { it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1,2,3)) }
        val journal = File(context.filesDir,"publication.json")
        try {
            journal.writeText(JSONObject().apply {
                put("id",id); put("name",partial.name); put("location",partial.absolutePath); put("public",false)
                put("created",System.currentTimeMillis()); put("duration",10_000_000); put("size",10000); put("rate",24000); put("bitrate",64000)
            }.toString())
            RecordingsRepository(context,database.recordings()).recover()
            assertFalse(partial.exists()); assertFalse(journal.exists()); assertTrue(database.recordings().all().isEmpty())
        } finally { partial.delete(); journal.delete(); database.close() }
    }
    @Test fun trimAndGainProduceExactPcmAndPreserveOriginal() {
        val dir = folder()
        val source = File(context.filesDir,"recordings/edit-${UUID.randomUUID()}.m4a").also { it.parentFile!!.mkdirs() }
        try {
            SegmentStore(File(dir,"pcm"),10).use { store -> store.beginSession(24000,0); store.append(pcm(10)); store.snapshot(10).use { AacExporter().encode(it,source,64000) } }
            val original = source.readBytes()
            val uri = FileProvider.getUriForFile(context,"${context.packageName}.files",source)
            val editor = AudioEditor(context)
            val plainFile = File(dir,"plain.pcm"); val louderFile = File(dir,"louder.pcm")
            editor.decode(uri,AudioEdit(2_125_000,6_750_000,0),plainFile).use { plain ->
                editor.decode(uri,AudioEdit(2_125_000,6_750_000,6),louderFile).use { louder ->
                    assertTrue("Frames: ${louder.frames}",abs(louder.frames - 111000) < 24)
                    assertEquals(plain.frames,louder.frames)
                    fun energy(file: File): Double { val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN); var sum=0.0; while(b.remaining()>=2) { val s=b.short.toDouble(); sum+=s*s }; return sum }
                    val ratio = kotlin.math.sqrt(energy(louderFile)/energy(plainFile))
                    assertTrue("Gain ratio $ratio",ratio in 1.98..2.01)
                    val output = File(dir,"edited.m4a"); AacExporter().encode(louder,output,64000)
                    val extractor = MediaExtractor()
                    try { extractor.setDataSource(output.absolutePath); assertTrue(abs(extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION) - 4_625_000) < 200_000) }
                    finally { extractor.release() }
                }
            }
            assertArrayEquals(original,source.readBytes())
        } finally { source.delete(); dir.deleteRecursively() }
    }
    @Test fun upgradingLegacyNamesPreservesCustomNamesAndMetadata() = runBlocking {
        val dir=folder(); val database=Room.inMemoryDatabaseBuilder(context,RecordingDatabase::class.java).build()
        val repo=RecordingsRepository(context,database.recordings())
        try {
            SegmentStore(File(dir,"pcm"),10).use { store -> store.beginSession(24000,0); store.append(pcm(10)); store.snapshot(10).use { snapshot ->
                val source=File(dir,"encoded.tmp"); AacExporter().encode(snapshot,source,64000)
                val saved=repo.publish(source,snapshot,64000,false)
                repo.rename(saved,"AudioReplay_2026-10-15_12-34-56_abc123")
                repo.compactLegacyNames()
                val compact=database.recordings().all().single()
                assertTrue(compact.name.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}( [0-9]+)?\\.m4a")))
                assertEquals(saved.id,compact.id); assertEquals(saved.durationUs,compact.durationUs)
                repo.rename(compact,"Custom_${UUID.randomUUID()}")
                val custom=database.recordings().all().single(); repo.compactLegacyNames()
                assertEquals(custom,database.recordings().all().single())
            } }
        } finally { database.recordings().all().forEach { repo.delete(it) }; database.close(); dir.deleteRecursively() }
    }
    @Test fun movingPrivateRecordingToPublicFolderPreservesIdAndAudio() = runBlocking {
        val dir=folder(); val database=Room.inMemoryDatabaseBuilder(context,RecordingDatabase::class.java).build()
        val repo=RecordingsRepository(context,database.recordings())
        try {
            SegmentStore(File(dir,"pcm"),10).use { store -> store.beginSession(24000,0); store.append(pcm(10)); store.snapshot(10).use { snapshot ->
                val source=File(dir,"encoded.tmp"); AacExporter().encode(snapshot,source,64000)
                val saved=repo.publish(source,snapshot,64000,false)
                repo.makePrivateRecordingsPublic()
                val moved=database.recordings().all().single()
                assertEquals(saved.id,moved.id); assertEquals(saved.sizeBytes,moved.sizeBytes); assertTrue(moved.publicStorage)
                assertFalse(File(saved.location).exists())
                context.contentResolver.openFileDescriptor(repo.uri(moved),"r")!!.use { assertEquals(saved.sizeBytes,it.statSize) }
            } }
        } finally { database.recordings().all().forEach { repo.delete(it) }; database.close(); dir.deleteRecursively() }
    }
}
