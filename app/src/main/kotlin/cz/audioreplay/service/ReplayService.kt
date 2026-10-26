package cz.audioreplay.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import cz.audioreplay.*
import cz.audioreplay.audio.CaptureDriver
import cz.audioreplay.audio.CaptureListener
import cz.audioreplay.audio.CaptureFailure
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import cz.audioreplay.controls.*
import cz.audioreplay.core.Durations
import cz.audioreplay.core.PressGate
import cz.audioreplay.core.AudioEdit
import cz.audioreplay.core.SegmentStore
import cz.audioreplay.core.PcmGain
import cz.audioreplay.core.ScheduleClock
import cz.audioreplay.core.ScheduleConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.time.Instant
import java.time.ZoneId

class ReplayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controls = Mutex()
    private val encoder = Mutex()
    private val gate = PressGate()
    private lateinit var capture: CaptureDriver
    private val g get() = graph
    private var stopping = false
    private var foreground = false
    private var captureSession = false
    private var editingJob: String? = null
    private var captureWanted = false
    private var captureOpen = false
    private var sessionRate: Int? = null
    private var captureGeneration = 0L
    private var recoveryJob: Job? = null
    private var planArmed = false

    override fun onCreate() {
        super.onCreate(); instance = this
        g.state.update { it.copy(serviceRunning = true) }
        capture = g.captureFactory()
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
            g.schedules.collect { config -> if (planArmed) controls.withLock {
                if (config.enabled) evaluatePlan(config) else {
                    planArmed = false; g.state.update { it.copy(planArmed = false,nextPlanChangeMs = null) }
                    stopCapture(CaptureStatus.PAUSED,"Plán vypnutý. Buffer zůstává pro ruční uložení.",false)
                }
            } }
        }
        scope.launch {
            while (isActive) {
                if (planArmed) controls.withLock { if (planArmed) evaluatePlan(g.schedules.value) }
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
        if (intent == null || intent.action !in setOf(START,ARM_SCHEDULE)) { if (!foreground) stopSelf(); return START_NOT_STICKY }
        try {
            captureSession = true
            startForeground(NOTIFICATION, notification(), microphoneType())
            foreground = true
        } catch (e: Exception) {
            g.state.update { it.copy(status = CaptureStatus.ERROR, message = "Android nepovolil spuštění mikrofonu: ${e.localizedMessage}") }
            stopSelf(); return START_NOT_STICKY
        }
        scope.launch { controls.withLock {
            stopping = false
            g.settingsReady.await()
            if (intent.getStringExtra("source") == "widget" && !g.settings.value.widgetEnabled) {
                if (g.state.value.status == CaptureStatus.IDLE && g.state.value.pendingExports == 0) stopSelf()
                return@withLock
            }
            if (intent.action == ARM_SCHEDULE) {
                try {
                    g.scheduleReady.await()
                    check(g.schedules.value.rules.any { it.enabled }) { "Nejprve přidejte alespoň jeden zapnutý interval." }
                    check(ScheduleAlarms.canSchedule(this@ReplayService)) { "Povolte v Androidu přesné budíky a připomenutí." }
                    planArmed = true
                    g.state.update { it.copy(planArmed = true) }
                    g.scheduleRepository.setEnabled(true)
                    evaluatePlan(g.schedules.value.copy(enabled = true))
                    ScheduleAlarms.clearNotice(this@ReplayService)
                } catch (e: Exception) {
                    disarmPlan()
                    g.message("Plán nelze aktivovat: ${e.localizedMessage}")
                    if (!captureWanted && g.store.availableFrames() == 0L) stopSelf()
                }
            } else {
                disarmPlan().join()
                if (!captureWanted) beginCaptureRequest()
            }
            updateNotification()
        } }
        return START_NOT_STICKY
    }

    private fun beginCaptureRequest() {
        sessionRate = null; captureWanted = true
        g.state.update { it.copy(status = CaptureStatus.STARTING,message = null,interruptionCount = 0) }
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            while (isActive && captureWanted) {
                controls.withLock { if (captureWanted && !captureOpen) tryCapture() }
                delay(2000)
            }
        }
    }
    private fun disarmPlan(): Job {
        planArmed = false
        g.state.update { it.copy(planArmed = false,nextPlanChangeMs = null) }
        return g.scope.launch { g.scheduleRepository.setEnabled(false) }
    }
    fun onScheduledAlarm() { scope.launch { controls.withLock { if (planArmed) evaluatePlan(g.schedules.value) } } }
    private suspend fun evaluatePlan(config: ScheduleConfig) {
        val decision = ScheduleClock.evaluate(config,Instant.now(),ZoneId.systemDefault())
        ScheduleAlarms.refresh(this,config)
        g.state.update { it.copy(nextPlanChangeMs = decision.nextChange?.toEpochMilli()) }
        if (decision.record) {
            if (!captureWanted) beginCaptureRequest()
        } else if (captureWanted || captureOpen) {
            stopCapture(CaptureStatus.PLANNED,"Interval skončil. Mikrofon je vypnutý; buffer zůstává pro ruční uložení do dalšího spuštění.",false)
        } else if (g.state.value.status != CaptureStatus.PLANNED) {
            g.state.update { it.copy(status = CaptureStatus.PLANNED,message = "Plán je aktivní. Mikrofon je do dalšího intervalu vypnutý.") }
        }
    }

    /** Runs only in the already user-started microphone foreground service. */
    private suspend fun tryCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            captureFailed(CaptureFailure("Povolte mikrofon v nastavení aplikace a záznam znovu spusťte.", false))
            return
        }
        val generation = ++captureGeneration
        try {
            val reason = withContext(Dispatchers.IO) { capture.stop(); capture.blockedReason() }
            if (!captureWanted) return
            if (reason != null) { waiting(reason); return }
            val rate = withContext(Dispatchers.IO) {
                capture.start(sessionRate, object : CaptureListener {
                    override fun onAvailability(available: Boolean) { scope.launch { controls.withLock {
                        if (!captureWanted || generation != captureGeneration || !captureOpen) return@withLock
                        if (available) g.state.update { it.copy(status = CaptureStatus.RECORDING,
                            message = if (it.interruptionCount > 0) "Mikrofon je opět dostupný. Pokračuji v dosavadní historii; zvuk během přerušení chybí." else null) }
                        else waiting("Mikrofon dočasně používá jiná aplikace nebo je vypnutý. Čekám a automaticky navážu.")
                        updateNotification()
                    } } }
                    override fun onFailure(failure: CaptureFailure) { scope.launch { controls.withLock {
                        if (!captureWanted || generation != captureGeneration) return@withLock
                        captureFailed(failure)
                    } } }
                })
            }
            sessionRate = rate
            captureOpen = true
            g.state.update { it.copy(status = CaptureStatus.WAITING, sampleRate = rate, availableSeconds = g.store.availableSeconds()) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (captureWanted) captureFailed(CaptureFailure(e.localizedMessage ?: "Mikrofon zatím není dostupný.",
                e !is SecurityException && e !is java.io.IOException && e !is IllegalArgumentException))
        }
        updateNotification()
    }
    private fun waiting(message: String) {
        g.state.update { it.copy(status = CaptureStatus.WAITING, message = message,
            interruptionCount = it.interruptionCount + if (it.status == CaptureStatus.RECORDING) 1 else 0) }
    }
    private fun captureFailed(failure: CaptureFailure) {
        captureOpen = false
        captureGeneration++ // Late callbacks from the failed stream cannot revive it.
        if (failure.recoverable) waiting(failure.message + " Automaticky zkusím pokračovat po uvolnění mikrofonu.")
        else {
            captureWanted = false
            if (planArmed) disarmPlan()
            g.state.update { it.copy(status = CaptureStatus.ERROR, message = failure.message) }
        }
        updateNotification()
    }

    fun command(action: String, source: String = "notification", seconds: Int? = null) {
        when (action) {
            SAVE -> save(source, seconds)
            PAUSE -> { captureWanted = false; val disarmed = disarmPlan(); scope.launch { controls.withLock {
                disarmed.join()
                if (g.state.value.status in setOf(CaptureStatus.RECORDING, CaptureStatus.WAITING, CaptureStatus.PLANNED, CaptureStatus.STARTING)) stopCapture(CaptureStatus.PAUSED,
                    "Pozastaveno. Po pokračování začne nová historie; pauza nebude spojena do souvislého záznamu.", false)
                updateNotification()
            } } }
            STOP -> {
                stopping = true; captureWanted = false; val disarmed = disarmPlan()
                scope.launch { controls.withLock {
                    disarmed.join()
                    stopCapture(CaptureStatus.IDLE, "Záznam zastaven. Dočasný buffer byl uvolněn.", true)
                    captureSession = false
                    if (g.state.value.pendingExports == 0) stopSelf() else updateNotification()
                } }
            }
        }
    }
    private suspend fun stopCapture(status: CaptureStatus, message: String?, discard: Boolean) {
        captureWanted = false
        recoveryJob?.cancel(); recoveryJob = null
        captureGeneration++
        withContext(Dispatchers.IO) { capture.stop(); if (discard) g.store.close() }
        captureOpen = false
        g.state.update { it.copy(status = status, message = message, availableSeconds = g.store.availableSeconds()) }
    }
    private fun save(source: String, seconds: Int? = null) {
        if (stopping) { g.message("Záznam se ukončuje."); return }
        if (g.state.value.status !in setOf(CaptureStatus.RECORDING, CaptureStatus.WAITING, CaptureStatus.PLANNED, CaptureStatus.PAUSED, CaptureStatus.ERROR)) return
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
                        withContext(Dispatchers.IO) { g.exporter.encode(snapshot, temp, settings.bitrate, gainDb = PcmGain.DEFAULT_DB,preventClipping = true) { context.ensureActive() } }
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
                            onProgress = { p -> g.state.update { it.copy(editProgress = 0.55f + p * 0.4f) } }) { context.ensureActive() } }
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
            CaptureStatus.WAITING -> "AudioReplay · Čekám na mikrofon, automaticky navážu"
            CaptureStatus.PLANNED -> "AudioReplay · Plán aktivní, mikrofon vypnutý"
            CaptureStatus.ERROR -> "AudioReplay · Mikrofon neběží"
            else -> if (state.editingName != null) "AudioReplay · Upravuji nahrávku" else if (state.pendingExports > 0) "AudioReplay · Dokončuji export" else "AudioReplay · Zastaveno"
        }
        val builder = NotificationCompat.Builder(this, RecordingNotice.CHANNEL).setSmallIcon(R.drawable.ic_replay)
            .setContentTitle(title).setContentText(if(!g.settings.value.notificationDetails) "Klepnutím otevřete AudioReplay" else if (state.status == CaptureStatus.PLANNED)
                state.nextPlanChangeMs?.let { "Další spuštění: ${java.text.SimpleDateFormat("EEE d. M. HH:mm",java.util.Locale.getDefault()).format(java.util.Date(it))}" } ?: "Plán dokončen · buffer lze ručně uložit"
                else "Historie ${state.availableSeconds.toInt()} s · Úlohy ${state.pendingExports}")
            .setContentIntent(openActivity(this)).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(if (private) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
        if (private) builder.setPublicVersion(NotificationCompat.Builder(this, RecordingNotice.CHANNEL).setSmallIcon(R.drawable.ic_replay).setContentTitle(title).build())
        if (!stopping && captureSession && g.settings.value.notificationActions) {
            builder.addAction(0, "Uložit ${Durations.label(g.settings.value.saveDurationSec)}", controlIntent(this, SAVE, "notification"))
            if (planArmed) builder.addAction(0, "Vypnout plán",controlIntent(this,PAUSE,"notification"))
            else if (state.status in setOf(CaptureStatus.RECORDING, CaptureStatus.WAITING, CaptureStatus.STARTING)) builder.addAction(0, "Pozastavit", controlIntent(this, PAUSE, "notification"))
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
        captureWanted = false; captureGeneration++
        instance = null
        scope.cancel()
        runCatching { capture.stop(); g.store.close() }
        g.state.update { it.copy(status = if (it.status == CaptureStatus.ERROR) CaptureStatus.ERROR else CaptureStatus.IDLE,
            serviceRunning = false, availableSeconds = 0.0,planArmed = false,nextPlanChangeMs = null) }
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
        const val ARM_SCHEDULE = "cz.audioreplay.ARM_SCHEDULE"
        private const val NOTIFICATION = 1
    }
}
