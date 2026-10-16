package cz.audioreplay.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import cz.audioreplay.*
import cz.audioreplay.audio.AudioCapture
import cz.audioreplay.controls.*
import cz.audioreplay.core.Durations
import cz.audioreplay.core.PressGate
import cz.audioreplay.core.AudioEdit
import cz.audioreplay.core.SegmentStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class ReplayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controls = Mutex()
    private val encoder = Mutex()
    private val gate = PressGate()
    private lateinit var capture: AudioCapture
    private val g get() = graph
    private var stopping = false
    private var foreground = false
    private var captureSession = false
    private var editingJob: String? = null

    override fun onCreate() {
        super.onCreate(); instance = this
        g.state.update { it.copy(serviceRunning = true) }
        capture = AudioCapture(this, g.store) { error -> scope.launch { controls.withLock {
            stopCapture(CaptureStatus.ERROR, error, false); updateNotification()
        } } }
        RecordingNotice.ensure(this)
        scope.launch {
            g.settings.collect { settings ->
                g.store.setBufferSeconds(settings.bufferDurationSec)
                if (foreground) updateNotification()
                updateControls(this@ReplayService)
            }
        }
        scope.launch {
            g.state.map { Triple(it.status, it.availableSeconds > 0, it.pendingExports) }.distinctUntilChanged().collect {
                updateControls(this@ReplayService)
            }
        }
        scope.launch {
            while (isActive) {
                g.state.update { it.copy(availableSeconds = g.store.availableSeconds(), cacheBytes = g.store.cacheBytes()) }
                if (foreground) updateNotification()
                delay(1000)
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == EDIT) {
            try {
                startForeground(NOTIFICATION, notification(), if (captureSession) microphoneType() else processingType())
                foreground = true
                edit(intent)
            } catch (e: Exception) {
                g.message("Úpravu nelze spustit: ${e.localizedMessage}"); if (!captureSession) stopSelf()
            }
            return START_NOT_STICKY
        }
        if (intent?.action != START) { if (!foreground) stopSelf(); return START_NOT_STICKY }
        try {
            captureSession = true
            startForeground(NOTIFICATION, notification(), microphoneType())
            foreground = true
        } catch (e: Exception) {
            g.state.update { it.copy(status = CaptureStatus.ERROR, message = "Android nepovolil spuštění mikrofonu: ${e.localizedMessage}") }
            stopSelf(); return START_NOT_STICKY
        }
        scope.launch { controls.withLock {
            if (g.state.value.status == CaptureStatus.RECORDING) return@withLock
            stopping = false
            g.settingsReady.await()
            if (intent.getStringExtra("source") == "widget" && !g.settings.value.widgetEnabled) {
                if (g.state.value.status == CaptureStatus.IDLE && g.state.value.pendingExports == 0) stopSelf()
                return@withLock
            }
            g.state.update { it.copy(status = CaptureStatus.STARTING, message = null) }
            try {
                val rate = withContext(Dispatchers.IO) { capture.start() }
                g.state.update { it.copy(status = CaptureStatus.RECORDING, sampleRate = rate, availableSeconds = 0.0) }
            } catch (e: Exception) {
                g.state.update { it.copy(status = CaptureStatus.ERROR, message = e.localizedMessage ?: "Mikrofon se nepodařilo spustit.") }
            }
            updateNotification()
        } }
        return START_NOT_STICKY
    }

    fun command(action: String, source: String = "notification", seconds: Int? = null) {
        when (action) {
            SAVE -> save(source, seconds)
            PAUSE -> scope.launch { controls.withLock {
                if (g.state.value.status == CaptureStatus.RECORDING) stopCapture(CaptureStatus.PAUSED,
                    "Pozastaveno. Po pokračování začne nová historie; pauza nebude spojena do souvislého záznamu.", false)
                updateNotification()
            } }
            STOP -> {
                stopping = true
                scope.launch { controls.withLock {
                    stopCapture(CaptureStatus.IDLE, "Záznam zastaven. Dočasný buffer byl uvolněn.", true)
                    captureSession = false
                    if (g.state.value.pendingExports == 0) stopSelf() else updateNotification()
                } }
            }
        }
    }
    private suspend fun stopCapture(status: CaptureStatus, message: String?, discard: Boolean) {
        withContext(Dispatchers.IO) { capture.stop(); if (discard) g.store.close() }
        g.state.update { it.copy(status = status, message = message, availableSeconds = g.store.availableSeconds()) }
    }
    private fun save(source: String, seconds: Int? = null) {
        if (stopping) { g.message("Záznam se ukončuje."); return }
        if (g.state.value.status !in setOf(CaptureStatus.RECORDING, CaptureStatus.PAUSED, CaptureStatus.ERROR)) return
        val settings = g.settings.value
        if (!gate.accept(source, SystemClock.elapsedRealtime(), settings.doublePress)) {
            g.message("Pro uložení stiskněte stejné tlačítko ještě jednou do 0,9 s.")
            android.widget.Toast.makeText(this, "Stiskněte ještě jednou pro uložení", android.widget.Toast.LENGTH_SHORT).show(); return
        }
        if (g.state.value.pendingExports >= 3) { g.message("Fronta obsahuje 3 exporty. Vyčkejte na dokončení."); return }
        val snapshot = try { g.store.snapshot(seconds?.coerceIn(1,3600) ?: settings.saveDurationSec) } catch (e: Exception) { g.message(e.localizedMessage ?: "Není dostupný zvuk."); return }
        g.state.update { it.copy(pendingExports = it.pendingExports + 1, message = "Ukládám ${(snapshot.durationUs / 1_000_000)} s dostupné historie…") }
        scope.launch {
            val temp = File(g.recordings.staging, "${UUID.randomUUID()}.m4a.tmp")
            try {
                encoder.withLock {
                    val wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioReplay:export")
                    wake.acquire(30 * 60 * 1000L)
                    try {
                        val context = currentCoroutineContext()
                        withContext(Dispatchers.IO) { g.exporter.encode(snapshot, temp, settings.bitrate) { context.ensureActive() } }
                        g.mediaReady.await()
                        val saved = g.recordings.publish(temp, snapshot, settings.bitrate, settings.publicStorage)
                        g.state.update { it.copy(lastSaved = saved.name, message = "Uloženo: ${saved.name}") }
                        if (settings.vibration) vibrate()
                    } finally { if (wake.isHeld) wake.release() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { g.message("Uložení se nezdařilo: ${e.localizedMessage}. Uložit můžete znovu, dokud je úsek v bufferu.") }
            finally {
                temp.delete(); snapshot.close()
                g.state.update { it.copy(pendingExports = (it.pendingExports - 1).coerceAtLeast(0)) }
                if ((!captureSession || stopping) && g.state.value.pendingExports == 0) stopSelf() else if (foreground) updateNotification()
            }
        }
        updateNotification()
    }
    private fun edit(intent: Intent) {
        if (g.state.value.pendingExports >= 3) {
            g.message("Fronta obsahuje 3 úlohy. Vyčkejte na dokončení.")
            if (!captureSession && g.state.value.pendingExports == 0) stopSelf()
            return
        }
        val id = requireNotNull(intent.getStringExtra("recording"))
        val requested = AudioEdit(intent.getLongExtra("from",0),intent.getLongExtra("to",0),intent.getIntExtra("gain",0))
        val settings = g.settings.value
        g.state.update { it.copy(pendingExports = it.pendingExports + 1, message = "Úprava nahrávky je připravená ve frontě.") }
        scope.launch {
            val jobId = UUID.randomUUID().toString()
            val pcm = File(g.recordings.staging,"$jobId.pcm.tmp")
            val temp = File(g.recordings.staging,"$jobId.m4a.tmp")
            var snapshot: SegmentStore.Snapshot? = null
            try {
                encoder.withLock {
                    g.mediaReady.await()
                    val recording = requireNotNull(g.database.recordings().find(id)) { "Nahrávka už neexistuje." }
                    requested.validate(recording.durationUs)
                    editingJob = jobId
                    g.state.update { it.copy(editingName = recording.name, editProgress = 0f) }
                    val wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"AudioReplay:edit")
                    wake.acquire(30 * 60 * 1000L)
                    try {
                        val context = currentCoroutineContext()
                        val decoded = withContext(Dispatchers.IO) {
                            g.editor.decode(g.recordings.uri(recording),requested,pcm,
                                { p -> g.state.update { it.copy(editProgress = p * 0.55f) } }) { context.ensureActive() }
                        }
                        snapshot = decoded
                        withContext(Dispatchers.IO) { g.exporter.encode(decoded,temp,settings.bitrate,
                            { p -> g.state.update { it.copy(editProgress = 0.55f + p * 0.4f) } }) { context.ensureActive() } }
                        val saved = g.recordings.publish(temp,decoded,settings.bitrate,recording.publicStorage)
                        g.state.update { it.copy(lastSaved = saved.name, message = "Upravená kopie: ${saved.name}") }
                        if (settings.vibration) vibrate()
                    } finally { if(wake.isHeld) wake.release() }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { g.message("Úprava se nezdařila: ${e.localizedMessage}") }
            finally {
                snapshot?.close(); pcm.delete(); temp.delete()
                val clearProgress = editingJob == jobId
                if (clearProgress) editingJob = null
                g.state.update { it.copy(pendingExports = (it.pendingExports - 1).coerceAtLeast(0),
                    editProgress = if(clearProgress) null else it.editProgress,editingName = if(clearProgress) null else it.editingName) }
                if ((!captureSession || stopping) && g.state.value.pendingExports == 0) stopSelf() else if (foreground) updateNotification()
            }
        }
        updateNotification()
    }
    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        vibrator.vibrate(VibrationEffect.createOneShot(65, VibrationEffect.DEFAULT_AMPLITUDE))
    }
    private fun updateNotification() {
        if (!foreground) return
        try {
            val type = if (!captureSession) processingType() else microphoneType()
            startForeground(NOTIFICATION, notification(), type)
        } catch (e: Exception) { g.message("Oznámení: ${e.localizedMessage}") }
    }
    private fun notification(): Notification {
        val state = g.state.value
        val private = g.settings.value.privateNotification
        val title = when (state.status) {
            CaptureStatus.RECORDING -> "AudioReplay · Mikrofon nahrává"
            CaptureStatus.PAUSED -> "AudioReplay · Pozastaveno"
            CaptureStatus.STARTING -> "AudioReplay · Spouštění"
            CaptureStatus.ERROR -> "AudioReplay · Mikrofon neběží"
            else -> if (state.editingName != null) "AudioReplay · Upravuji nahrávku" else if (state.pendingExports > 0) "AudioReplay · Dokončuji export" else "AudioReplay · Zastaveno"
        }
        val builder = NotificationCompat.Builder(this, RecordingNotice.CHANNEL).setSmallIcon(R.drawable.ic_replay)
            .setContentTitle(title).setContentText(if(g.settings.value.notificationDetails) "Historie ${state.availableSeconds.toInt()} s · Úlohy ${state.pendingExports}" else "Klepnutím otevřete AudioReplay")
            .setContentIntent(openActivity(this)).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(if (private) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
        if (private) builder.setPublicVersion(NotificationCompat.Builder(this, RecordingNotice.CHANNEL).setSmallIcon(R.drawable.ic_replay).setContentTitle(title).build())
        if (!stopping && captureSession && g.settings.value.notificationActions) {
            builder.addAction(0, "Uložit ${Durations.label(g.settings.value.saveDurationSec)}", controlIntent(this, SAVE, "notification"))
            if (state.status == CaptureStatus.RECORDING) builder.addAction(0, "Pozastavit", controlIntent(this, PAUSE, "notification"))
            else builder.addAction(0, "Pokračovat", openActivity(this, resume = true))
        }
        if (captureSession && g.settings.value.notificationActions) builder.addAction(0, "Zastavit", controlIntent(this, STOP, "notification"))
        return builder.build()
    }
    private fun microphoneType(): Int = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
    private fun processingType(): Int = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onTimeout(startId: Int, fgsType: Int) { command(STOP); scope.launch { delay(1000); stopSelf() } }
    override fun onDestroy() {
        instance = null
        scope.cancel()
        runCatching { capture.stop(); g.store.close() }
        g.state.update { it.copy(status = if (it.status == CaptureStatus.ERROR) CaptureStatus.ERROR else CaptureStatus.IDLE,
            serviceRunning = false, availableSeconds = 0.0) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateControls(this)
        super.onDestroy()
    }
    companion object {
        @Volatile var instance: ReplayService? = null; private set
        const val START = "cz.audioreplay.START"
        const val SAVE = "cz.audioreplay.SAVE"
        const val PAUSE = "cz.audioreplay.PAUSE"
        const val STOP = "cz.audioreplay.STOP"
        const val EDIT = "cz.audioreplay.EDIT"
        private const val NOTIFICATION = 1
    }
}
