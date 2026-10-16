package cz.audioreplay.core

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** PCM16 mono; all offsets are sample frames, never wall-clock timestamps.
 * The lock covers only one small write or metadata update. Export reads outside it.
 */
class SegmentStore(
    private val directory: File,
    bufferSeconds: Int = 1800,
    private val segmentSeconds: Int = 5,
    private val usableSpace: () -> Long = { directory.usableSpace },
) : AutoCloseable {
    private data class Segment(val file: File, val start: Long, var frames: Long = 0, var pins: Int = 0)
    data class Slice(val file: File, val offsetBytes: Long, val lengthBytes: Long, val startFrame: Long)
    class Snapshot internal constructor(
        val slices: List<Slice>, val sampleRate: Int, val frames: Long,
        val sessionId: String, val startMonotonicNanos: Long, private val release: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        val durationUs get() = frames * 1_000_000L / sampleRate
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }
    private val segments = mutableListOf<Segment>()
    private val retired = mutableListOf<Segment>()
    private var writer: RandomAccessFile? = null
    private var active: Segment? = null
    private var rate = 24000
    private var total = 0L
    private var floorFrame = 0L
    private var session = ""
    private var originNanos = 0L
    private var retention = bufferSeconds.coerceIn(10, 3600)
    init { require(segmentSeconds > 0); directory.mkdirs() }

    @Synchronized fun beginSession(sampleRate: Int, monotonicNanos: Long) {
        require(sampleRate > 0)
        finishWriting()
        segments.forEach { if (it.pins == 0) delete(it) else retired.add(it) }
        segments.clear()
        rate = sampleRate; total = 0; floorFrame = 0; originNanos = monotonicNanos; session = UUID.randomUUID().toString()
    }
    @Synchronized fun append(pcm16: ByteArray, length: Int = pcm16.size) {
        require(length in 0..pcm16.size && length % 2 == 0)
        check(session.isNotEmpty()) { "Session has not started" }
        var offset = 0
        while (offset < length) {
            if (usableSpace() < 2L * 1024 * 1024) throw java.io.IOException("Nedostatek místa pro průběžný buffer.")
            if (active == null) {
                val next = Segment(File(directory, "$session-$total.pcm"), total)
                writer = RandomAccessFile(next.file, "rw"); active = next; segments.add(next)
            }
            val segment = active!!
            val bytes = min(length - offset, ((segmentSeconds.toLong() * rate - segment.frames) * 2).toInt())
            writer!!.write(pcm16, offset, bytes)
            segment.frames += bytes / 2; total += bytes / 2; offset += bytes
            if (segment.frames >= segmentSeconds.toLong() * rate) finishWriting()
        }
        trim()
    }
    @Synchronized fun setBufferSeconds(seconds: Int) { retention = seconds.coerceIn(10, 3600); trim() }
    @Synchronized fun availableFrames(): Long = min(retention.toLong() * rate, total - max(floorFrame, segments.firstOrNull()?.start ?: total)).coerceAtLeast(0)
    @Synchronized fun availableSeconds(): Double = availableFrames().toDouble() / rate
    @Synchronized fun cacheBytes(): Long = (segments + retired).sumOf { it.frames * 2 }

    @Synchronized fun snapshot(seconds: Int): Snapshot {
        require(seconds > 0)
        val frames = min(seconds.toLong() * rate, availableFrames())
        check(frames > 0) { "Zatím není dostupný žádný zvuk." }
        val start = total - frames
        val selected = segments.filter { it.start < total && it.start + it.frames > start }
        val slices = selected.map {
            val from = max(start, it.start); val end = min(total, it.start + it.frames)
            Slice(it.file, (from - it.start) * 2, (end - from) * 2, from)
        }
        selected.forEach { it.pins++ }
        return Snapshot(slices, rate, frames, session, originNanos + start * 1_000_000_000L / rate) {
            synchronized(this) { selected.forEach { it.pins-- }; trim() }
        }
    }
    private fun trim() {
        val cutoff = total - retention.toLong() * rate
        floorFrame = max(floorFrame, cutoff)
        val expired = segments.filter { it !== active && it.start + it.frames <= cutoff }
        expired.forEach { segments.remove(it); if (it.pins == 0) delete(it) else retired.add(it) }
        retired.removeAll { if (it.pins == 0) { delete(it); true } else false }
    }
    private fun delete(segment: Segment) { if (segment.file.exists() && !segment.file.delete()) segment.file.deleteOnExit() }
    @Synchronized fun finishWriting() { writer?.close(); writer = null; active = null }
    /** Used on explicit stop: pinned export inputs survive until their jobs complete. */
    @Synchronized override fun close() {
        finishWriting()
        segments.forEach { if (it.pins == 0) delete(it) else retired.add(it) }
        segments.clear(); total = 0; floorFrame = 0; trim()
    }
    companion object {
        /** Owns a decoded edit's temporary PCM file, and deletes it after export. */
        fun pcmFileSnapshot(file: File, sampleRate: Int): Snapshot {
            require(sampleRate > 0 && file.length() > 0 && file.length() % 2 == 0L)
            val frames = file.length() / 2
            return Snapshot(listOf(Slice(file, 0, file.length(), 0)), sampleRate, frames, UUID.randomUUID().toString(), 0) { file.delete() }
        }
        /** Only call before constructing the process's single store. Cache is never resumed. */
        fun discardStale(directory: File) {
            directory.mkdirs()
            directory.listFiles()?.filter { it.isFile && it.extension == "pcm" }?.forEach { it.delete() }
        }
    }
}
