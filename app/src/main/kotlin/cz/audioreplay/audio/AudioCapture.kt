package cz.audioreplay.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import cz.audioreplay.core.SegmentStore
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class AudioCapture(private val context: Context, private val store: SegmentStore) : CaptureDriver {
    @Volatile private var runFlag: AtomicBoolean? = null
    @Volatile private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private val audio get() = context.getSystemService(AudioManager::class.java)

    private fun systemBlocksInput(): Boolean =
        audio.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION) ||
            audio.isMicrophoneMute

    override fun blockedReason(): String? {
        if (systemBlocksInput()) return "Mikrofon je používán hovorem nebo vypnutý v Androidu. Čekám na jeho uvolnění."
        // Android anonymizes other clients; only their active capture state is needed.
        if (runCatching { audio.activeRecordingConfigurations.any { !it.isClientSilenced } }.getOrDefault(false))
            return "Mikrofon používá jiná aplikace. Po jeho uvolnění budu automaticky pokračovat."
        return null
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    override fun start(resumeRate: Int?, listener: CaptureListener): Int {
        check(worker?.isAlive != true) { "Mikrofon se stále ukončuje." }
        var chosen: AudioRecord? = null
        for (rate in resumeRate?.let { listOf(it) } ?: listOf(24000, 48000, 44100, 16000)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val candidate = try {
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(min * 2, rate / 5 * 2)).build()
            } catch (e: SecurityException) { throw e }
            catch (_: Exception) { continue }
            if (candidate.state == AudioRecord.STATE_INITIALIZED) { chosen = candidate; break }
            candidate.release()
        }
        val record = checkNotNull(chosen) { "Mikrofon zatím nelze otevřít v požadovaném formátu." }
        val keepRunning = AtomicBoolean(true)
        val silenced = AtomicBoolean(false)
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }?.let { silenced.set(it.isClientSilenced) }
            }
        }
        val wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioReplay:capture")
        try {
            record.registerAudioRecordingCallback(Executor { it.run() }, callback)
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Mikrofon se nepodařilo spustit." }
            if (resumeRate == null) store.beginSession(record.sampleRate, SystemClock.elapsedRealtimeNanos())
            else store.resumeSession(record.sampleRate)
            wake.acquire()
            recorder = record; runFlag = keepRunning
            worker = Thread({
                var failure: CaptureFailure? = null
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val pcm = ByteArray(record.sampleRate / 10 * 2)
                    var available: Boolean? = null
                    var systemBlocked = false
                    var lastConfig = 0L
                    var lastData = SystemClock.elapsedRealtime()
                    fun availability(value: Boolean) {
                        if (available == value) return
                        if (!value) store.finishWriting()
                        available = value
                        listener.onAvailability(value)
                    }
                    while (keepRunning.get()) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastConfig >= 250) {
                            record.activeRecordingConfiguration?.let { silenced.set(it.isClientSilenced) }
                            systemBlocked = systemBlocksInput()
                            lastConfig = now
                        }
                        // A stopped driver must not trap the worker in a blocking read.
                        val count = record.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                        if (!keepRunning.get()) break
                        if (count < 0) throw IllegalStateException("Mikrofon byl odpojen nebo přerušen (kód $count).")
                        if (silenced.get() || systemBlocked) {
                            availability(false)
                            lastData = now
                            // Keep the stream alive for Android to unsilence it; do not fight
                            // another app by repeatedly taking capture priority. Skip muted PCM.
                        } else if (count > 0) {
                            require(count % 2 == 0) { "Neúplný PCM vzorek." }
                            store.append(pcm, count)
                            lastData = now
                            availability(true)
                        } else {
                            check(now - lastData < 5000) { "Mikrofon přestal poskytovat data. Obnovuji připojení." }
                        }
                        if (count == 0) Thread.sleep(10)
                    }
                } catch (e: Exception) {
                    if (keepRunning.get()) failure = CaptureFailure(e.localizedMessage ?: "Chyba mikrofonu.",
                        e !is SecurityException && e !is IOException && e !is IllegalArgumentException)
                } finally {
                    keepRunning.set(false)
                    runCatching { record.unregisterAudioRecordingCallback(callback) }
                    runCatching { record.stop() }; runCatching { record.release() }
                    if (recorder === record) recorder = null
                    runCatching { store.finishWriting() }.onFailure { failure = CaptureFailure("Buffer nelze dokončit: " + it.localizedMessage, false) }
                    if (wake.isHeld) wake.release()
                    failure?.let(listener::onFailure)
                }
            }, "AudioReplay-capture").also { it.start() }
            return record.sampleRate
        } catch (e: Exception) {
            keepRunning.set(false)
            runCatching { record.unregisterAudioRecordingCallback(callback) }
            runCatching { record.stop() }; record.release(); recorder = null
            if (wake.isHeld) wake.release()
            throw e
        }
    }

    override fun stop() {
        runFlag?.set(false)
        runCatching { recorder?.stop() }
        worker?.let { if (it !== Thread.currentThread()) { it.join(2000); check(!it.isAlive) { "Mikrofon se stále ukončuje." } } }
        worker = null; runFlag = null
    }
}
