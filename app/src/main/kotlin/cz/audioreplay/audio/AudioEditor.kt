package cz.audioreplay.audio

import android.content.Context
import android.media.*
import android.net.Uri
import cz.audioreplay.core.AudioEdit
import cz.audioreplay.core.SegmentStore
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/** Decodes only the selected interval to a temporary PCM file. Originals are never modified. */
class AudioEditor(private val context: Context) {
    fun decode(uri: Uri, edit: AudioEdit, pcm: File, progress: (Float) -> Unit = {}, checkActive: () -> Unit = {}): SegmentStore.Snapshot {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var complete = false
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error("Soubor neobsahuje zvuk.")
            val format = extractor.getTrackFormat(track)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            require(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) { "Editor podporuje mono nahrávky AudioReplay." }
            extractor.selectTrack(track)
            extractor.seekTo((edit.startUs - 100_000).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder; decoder.configure(format, null, null, 0); decoder.start()
            val info = MediaCodec.BufferInfo()
            val gain = 10.0.pow(edit.gainDb / 20.0)
            var inputDone = false; var outputDone = false
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var framesWritten = 0L
            var lastProgress = System.nanoTime()
            pcm.parentFile!!.mkdirs()
            FileOutputStream(pcm).use { out ->
                while (!outputDone) {
                    checkActive()
                    check(pcm.parentFile!!.usableSpace > 2L * 1024 * 1024) { "Nedostatek místa pro úpravu nahrávky." }
                    var madeProgress = false
                    if (!inputDone) {
                        val index = decoder.dequeueInputBuffer(10000)
                        if (index >= 0) {
                            val input = requireNotNull(decoder.getInputBuffer(index)); input.clear()
                            val time = extractor.sampleTime
                            val n = if (time < 0 || time >= edit.endUs) -1 else extractor.readSampleData(input, 0)
                            decoder.queueInputBuffer(index, 0, n.coerceAtLeast(0), time.coerceAtLeast(0),
                                if (n < 0) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (n < 0) inputDone = true else extractor.advance()
                            madeProgress = true
                        }
                    }
                    val index = decoder.dequeueOutputBuffer(info, 10000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = decoder.outputFormat
                        check(output.getInteger(MediaFormat.KEY_SAMPLE_RATE) == rate && output.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        check(encoding in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) { "Nepodporovaný formát dekódovaného zvuku." }
                        madeProgress = true
                    } else if (index >= 0) {
                        val buffer = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                        val bytesPerFrame = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        val frameCount = info.size / bytesPerFrame
                        fun boundary(timeUs: Long): Int = if (timeUs <= info.presentationTimeUs) 0 else
                            (((timeUs - info.presentationTimeUs) * rate + 999999) / 1_000_000L).coerceAtMost(frameCount.toLong()).toInt()
                        val from = boundary(edit.startUs); val to = boundary(edit.endUs)
                        if (to > from) {
                            val bytes = ByteBuffer.allocate((to - from) * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (frame in from until to) {
                                val at = info.offset + frame * bytesPerFrame
                                val sample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.getFloat(at) * 32767.0 else buffer.getShort(at).toDouble()
                                bytes.putShort((sample * gain).roundToInt().coerceIn(-32768,32767).toShort())
                            }
                            out.write(bytes.array()); framesWritten += to - from
                        }
                        progress(((info.presentationTimeUs - edit.startUs).toDouble() / (edit.endUs - edit.startUs)).toFloat().coerceIn(0f,1f))
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(index, false); madeProgress = true
                    }
                    if (madeProgress) lastProgress = System.nanoTime()
                    check(System.nanoTime() - lastProgress < 15_000_000_000L) { "Dekodér přestal odpovídat." }
                }
                out.fd.sync()
            }
            check(framesWritten > 0) { "Vybraný úsek neobsahuje zvuk." }
            complete = true; progress(1f)
            return SegmentStore.pcmFileSnapshot(pcm, rate)
        } finally {
            runCatching { codec?.stop() }; codec?.release(); extractor.release()
            if (!complete) pcm.delete()
        }
    }
}
