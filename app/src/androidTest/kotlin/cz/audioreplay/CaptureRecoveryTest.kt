package cz.audioreplay

import android.Manifest
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.audioreplay.audio.*
import cz.audioreplay.service.ReplayService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class CaptureRecoveryTest {
    private val app = ApplicationProvider.getApplicationContext<ReplayApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String, timeout: Long = 15000, predicate: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (!predicate() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(25)
        assertTrue("$message: ${app.graph.state.value}",predicate())
    }
    private inner class Driver : CaptureDriver {
        @Volatile var busy: String? = null
        val starts = AtomicInteger()
        val listeners = CopyOnWriteArrayList<CaptureListener>()
        val rates = CopyOnWriteArrayList<Int?>()
        override fun blockedReason() = busy
        override fun start(resumeRate: Int?, listener: CaptureListener): Int {
            starts.incrementAndGet(); rates.add(resumeRate); listeners.add(listener)
            if (resumeRate == null) app.graph.store.beginSession(24000,0) else app.graph.store.resumeSession(resumeRate)
            app.graph.store.append(ByteArray(48000) { 7 })
            listener.onAvailability(true)
            return 24000
        }
        override fun stop() { app.graph.store.finishWriting() }
        fun silence() { app.graph.store.finishWriting(); listeners.last().onAvailability(false) }
        fun fail(recoverable: Boolean = true) { stop(); listeners.last().onFailure(CaptureFailure("Test: vstup přerušen",recoverable)) }
    }
    private fun fixture(block: (Driver,ActivityScenario<MainActivity>) -> Unit) = runBlocking {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName,Manifest.permission.RECORD_AUDIO)
        app.graph.settingsReady.await(); app.graph.mediaReady.await()
        val previous = app.graph.settings.value
        val factory = app.graph.captureFactory
        val originalIds = app.graph.database.recordings().all().map { it.id }.toSet()
        app.graph.settingsRepository.update { it.copy(onboardingDone = true,doublePress = false,vibration = false) }
        val driver = Driver()
        app.graph.captureFactory = { driver }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario -> block(driver,scenario) }
        } finally {
            instrumentation.runOnMainSync { ReplayService.instance?.command(ReplayService.STOP) }
            await("Service cleanup failed") { ReplayService.instance == null }
            app.graph.captureFactory = factory
            app.graph.settingsRepository.update { previous }
            app.graph.database.recordings().all().filter { it.id !in originalIds }.forEach { app.graph.recordings.delete(it) }
        }
    }
    private fun start(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { ContextCompat.startForegroundService(it,Intent(it,ReplayService::class.java).setAction(ReplayService.START)) }
    }

    @Test fun repeatedDriverFailuresWaitForFreeInputAndAppendToSameHistory() = fixture { driver,scenario ->
        start(scenario)
        await("Initial capture failed") { app.graph.state.value.status == CaptureStatus.RECORDING }
        val session = app.graph.store.snapshot(60).use { it.sessionId }
        val frames = app.graph.store.availableFrames()
        driver.busy = "Test: mikrofon používá jiná aplikace"
        driver.fail()
        await("Missing waiting state") { app.graph.state.value.status == CaptureStatus.WAITING }
        Thread.sleep(2300)
        assertEquals(1,driver.starts.get())
        assertEquals(frames,app.graph.store.availableFrames())
        val before = app.graph.state.value.lastSaved
        instrumentation.runOnMainSync { ReplayService.instance!!.command(ReplayService.SAVE,"test") }
        await("Cannot save history while waiting",30000) { app.graph.state.value.lastSaved != before && app.graph.state.value.pendingExports == 0 }
        driver.busy = null
        await("No automatic recovery") { driver.starts.get() == 2 && app.graph.state.value.status == CaptureStatus.RECORDING }
        assertEquals(24000,driver.rates.last())
        assertEquals(frames*2,app.graph.store.availableFrames())
        assertEquals(session,app.graph.store.snapshot(60).use { it.sessionId })
        driver.fail()
        await("Second recovery failed") { driver.starts.get() == 3 && app.graph.state.value.status == CaptureStatus.RECORDING }
        assertEquals(2,app.graph.state.value.interruptionCount)
        assertEquals(frames*3,app.graph.store.availableFrames())
    }

    @Test fun manualPauseCancelsRecoveryAndStaleCallbacksCannotReviveRecording() = fixture { driver,scenario ->
        start(scenario)
        await("Initial capture failed") { app.graph.state.value.status == CaptureStatus.RECORDING }
        driver.silence()
        await("Missing waiting state") { app.graph.state.value.status == CaptureStatus.WAITING }
        val oldListener = driver.listeners.last()
        instrumentation.runOnMainSync { ReplayService.instance!!.command(ReplayService.PAUSE) }
        oldListener.onAvailability(true)
        await("Pause failed") { app.graph.state.value.status == CaptureStatus.PAUSED }
        Thread.sleep(2300)
        assertEquals(1,driver.starts.get())
        start(scenario)
        await("Manual resume failed") { driver.starts.get() == 2 && app.graph.state.value.status == CaptureStatus.RECORDING }
        oldListener.onFailure(CaptureFailure("Stale failure",false))
        Thread.sleep(200)
        assertEquals(CaptureStatus.RECORDING,app.graph.state.value.status)
        assertEquals(null,driver.rates.last())
        assertEquals(24000L,app.graph.store.availableFrames())
    }

    @Test fun stopDuringInitialBusyWaitPreventsAllLaterRetries() = fixture { driver,scenario ->
        driver.busy = "Test: vstup obsazen"
        start(scenario)
        await("Missing initial waiting state") { app.graph.state.value.status == CaptureStatus.WAITING }
        assertEquals(0,driver.starts.get())
        instrumentation.runOnMainSync { ReplayService.instance!!.command(ReplayService.STOP) }
        await("Stop failed") { ReplayService.instance == null }
        driver.busy = null
        Thread.sleep(2300)
        assertEquals(0,driver.starts.get())
        assertEquals(CaptureStatus.IDLE,app.graph.state.value.status)
    }

    @Test fun permanentStorageOrPermissionFailureDoesNotRetry() = fixture { driver,scenario ->
        start(scenario)
        await("Initial capture failed") { app.graph.state.value.status == CaptureStatus.RECORDING }
        driver.fail(recoverable = false)
        await("Permanent error was not surfaced") { app.graph.state.value.status == CaptureStatus.ERROR }
        Thread.sleep(2300)
        assertEquals(1,driver.starts.get())
        assertTrue(app.graph.store.availableFrames() > 0)
    }
}
