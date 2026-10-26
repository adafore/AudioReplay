package cz.audioreplay.audio

import android.media.*
import cz.audioreplay.core.SegmentStore
import cz.audioreplay.core.PcmGain
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.pow

/** Encodes one exact PCM snapshot into a single AAC track; capture never waits for this. */
class AacExporter {
    fun encode(snapshot: SegmentStore.Snapshot, target: File, bitrate: Int, gainDb: Int = 0, preventClipping: Boolean = false, onProgress: (Float) -> Unit = {}, checkActive: () -> Unit = {}) {
        require(gainDb in 0..18)
        var peak = 0
        if (preventClipping) PcmReader(snapshot.slices).use { reader ->
            val bytes = ByteArray(16384)
            while (true) {
                checkActive()
                val count = reader.read(bytes,bytes.size)
                if (count == 0) break
                for (i in 0 until count step 2) {
                    val sample = (((bytes[i+1].toInt() and 255) shl 8) or (bytes[i].toInt() and 255)).toShort().toInt()
                    peak = maxOf(peak,kotlin.math.abs(sample))
                }
            }
        }
        val factor = if (preventClipping) PcmGain.safeFactor(peak,gainDb) else 10.0.pow(gainDb/20.0)
        target.parentFile?.mkdirs()
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var started = false
        var complete = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, snapshot.sampleRate, 1)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var inputEnded = false
            var outputEnded = false
            var fedFrames = 0L
            var written = 0
            var lastProgress = System.nanoTime()
            PcmReader(snapshot.slices).use { reader ->
                val scratch = ByteArray(16384)
                while (!outputEnded) {
                    checkActive()
                    check(target.parentFile!!.usableSpace > 2L * 1024 * 1024) { "Nedostatek místa pro export." }
                    var progress = false
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = requireNotNull(codec.getInputBuffer(index))
                            buffer.clear()
                            val count = reader.read(scratch, minOf(buffer.remaining(), scratch.size) / 2 * 2)
                            PcmGain.applyFactorInPlace(scratch, count, factor)
                            buffer.put(scratch, 0, count)
                            codec.queueInputBuffer(index, 0, count, fedFrames * 1_000_000L / snapshot.sampleRate,
                                if (count == 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            fedFrames += count / 2; inputEnded = count == 0; progress = true
                            onProgress((fedFrames.toDouble() / snapshot.frames).toFloat().coerceIn(0f,1f))
                        }
                    }
                    var output = codec.dequeueOutputBuffer(info, 10_000)
                    while (output != MediaCodec.INFO_TRY_AGAIN_LATER) {
                        when {
                            output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                check(!started) { "Enkodér neočekávaně změnil formát." }
                                track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true
                                progress = true
                            }
                            output >= 0 -> {
                                val buffer: ByteBuffer = requireNotNull(codec.getOutputBuffer(output))
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                                if (info.size > 0) {
                                    check(started)
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                    muxer.writeSampleData(track, buffer, info); written++
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(output, false); progress = true
                            }
                        }
                        if (outputEnded) break
                        output = codec.dequeueOutputBuffer(info, 0)
                    }
                    if (progress) lastProgress = System.nanoTime()
                    check(System.nanoTime() - lastProgress < 15_000_000_000L) { "Enkodér přestal odpovídat." }
                }
            }
            check(written > 0 && fedFrames == snapshot.frames) { "Export neobsahuje kompletní zvuk." }
            muxer.stop(); started = false
            RandomAccessFile(target, "rw").use { it.fd.sync() }
            check(target.length() > 0)
            complete = true
        } finally {
            runCatching { codec.stop() }; codec.release()
            if (started) runCatching { muxer?.stop() }
            muxer?.release()
            if (!complete) target.delete()
        }
    }

    private class PcmReader(private val slices: List<SegmentStore.Slice>) : AutoCloseable {
        private var index = 0
        private var file: RandomAccessFile? = null
        private var remaining = 0L
        fun read(bytes: ByteArray, max: Int): Int {
            require(max > 0)
            var count = 0
            while (count < max) {
                if (remaining == 0L) {
                    file?.close(); file = null
                    if (index == slices.size) break
                    val slice = slices[index++]
                    file = RandomAccessFile(slice.file, "r").also { it.seek(slice.offsetBytes) }
                    remaining = slice.lengthBytes
                }
                val n = file!!.read(bytes, count, minOf((max - count).toLong(), remaining).toInt())
                check(n > 0) { "Chybí část rezervovaného bufferu." }
                count += n; remaining -= n
            }
            return count
        }
        override fun close() { file?.close() }
    }
}
