package cz.audioreplay.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import cz.audioreplay.core.SegmentStore
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class AudioCapture(private val context: Context, private val store: SegmentStore, private val onFailure: (String) -> Unit) {
    private val running = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission", "WakelockTimeout")
    fun start(): Int {
        check(!running.get())
        var chosen: AudioRecord? = null
        for (rate in listOf(24000, 48000, 44100, 16000)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val candidate = runCatching {
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(min * 2, rate / 5 * 2)).build()
            }.getOrNull() ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) { chosen = candidate; break }
            candidate.release()
        }
        val record = checkNotNull(chosen) { "Mikrofon nepodporuje žádný z dostupných formátů PCM." }
        val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioReplay:capture")
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Mikrofon se nepodařilo spustit." }
            store.beginSession(record.sampleRate, SystemClock.elapsedRealtimeNanos())
            wake.acquire()
            recorder = record; running.set(true)
            worker = Thread({
                var failure: String? = null
                var lastDevice: Int? = record.routedDevice?.id
                val callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        val config = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
                        if (running.get() && config.isClientSilenced) {
                            failure = "Android přerušil přístup k mikrofonu (například kvůli hovoru). Spusťte záznam znovu."
                            requestStop()
                        }
                        val device = config.audioDevice?.id
                        if (running.get() && device != null && lastDevice != null && device != lastDevice) {
                            failure = "Změnil se zvukový vstup. Záznam byl ukončen; pro novou souvislou historii ho znovu spusťte."
                            requestStop()
                        }
                        lastDevice = device
                    }
                }
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    record.registerAudioRecordingCallback(Executor { it.run() }, callback)
                    val pcm = ByteArray(record.sampleRate / 10 * 2)
                    var emptyReads = 0
                    var lastData = SystemClock.elapsedRealtime()
                    while (running.get()) {
                        val count = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                        if (!running.get()) break
                        if (count < 0) error("Mikrofon byl odpojen nebo přerušen (kód $count).")
                        if (count == 0) {
                            emptyReads++
                            check(emptyReads < 100 && SystemClock.elapsedRealtime() - lastData < 5000) { "Mikrofon neposkytuje data." }
                            Thread.sleep(10)
                        } else {
                            check(count % 2 == 0) { "Neúplný PCM vzorek." }
                            store.append(pcm, count); emptyReads = 0; lastData = SystemClock.elapsedRealtime()
                        }
                    }
                } catch (e: Exception) { if (running.get()) failure = e.localizedMessage ?: "Chyba mikrofonu." }
                finally {
                    running.set(false)
                    runCatching { record.unregisterAudioRecordingCallback(callback) }
                    runCatching { record.stop() }; runCatching { record.release() }; recorder = null
                    runCatching { store.finishWriting() }
                    if (wake.isHeld) wake.release()
                    failure?.let(onFailure)
                }
            }, "AudioReplay-capture").also { it.start() }
            return record.sampleRate
        } catch (e: Exception) {
            runCatching { record.stop() }; record.release(); recorder = null
            if (wake.isHeld) wake.release()
            throw e
        }
    }
    fun requestStop() { running.set(false); runCatching { recorder?.stop() } }
    fun stop() {
        requestStop()
        worker?.let { if (it !== Thread.currentThread()) { it.join(2000); check(!it.isAlive) { "Mikrofon se stále ukončuje." } } }
        worker = null
    }
}
