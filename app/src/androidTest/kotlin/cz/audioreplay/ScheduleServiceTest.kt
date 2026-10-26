package cz.audioreplay

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.audioreplay.core.*
import cz.audioreplay.data.ScheduleRepository
import cz.audioreplay.service.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class ScheduleServiceTest {
    private val app = ApplicationProvider.getApplicationContext<ReplayApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun await(message: String,timeout: Long = 20000,predicate: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime()+timeout
        while (!predicate() && android.os.SystemClock.elapsedRealtime()<deadline) Thread.sleep(25)
        assertTrue("$message: ${app.graph.state.value}",predicate())
    }
    private fun authorize() {
        val ui = instrumentation.uiAutomation
        ui.grantRuntimePermission(app.packageName,Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT>=33) ui.grantRuntimePermission(app.packageName,Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT>=31) ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("appops set ${app.packageName} SCHEDULE_EXACT_ALARM allow")).use { it.readBytes() }
        assertTrue(ScheduleAlarms.canSchedule(app))
    }
    private fun oneTime(id: String,start: ZonedDateTime,end: ZonedDateTime) = ScheduleRule(id,start.toLocalTime(),end.toLocalTime(),start.toLocalDate())
    private fun fixture(block: suspend () -> Unit) = runBlocking {
        authorize(); app.graph.settingsReady.await(); app.graph.scheduleReady.await(); app.graph.mediaReady.await()
        val settings = app.graph.settings.value
        val plan = app.graph.schedules.value
        val originalIds = app.graph.database.recordings().all().map { it.id }.toSet()
        app.graph.scheduleRepository.update { ScheduleConfig() }
        app.graph.settingsRepository.update { it.copy(onboardingDone = true,doublePress = false,vibration = false) }
        try { block() } finally {
            instrumentation.runOnMainSync { ReplayService.instance?.command(ReplayService.STOP) }
            await("Service cleanup failed") { ReplayService.instance == null }
            app.graph.scheduleRepository.update { plan }
            app.graph.settingsRepository.update { settings }
            ScheduleAlarms.clearNotice(app)
            app.graph.database.recordings().all().filter { it.id !in originalIds }.forEach { app.graph.recordings.delete(it) }
        }
    }

    @Test fun twoBackgroundIntervalsStartStopAndKeepBufferForManualSave() = fixture {
        val now = ZonedDateTime.now().withNano(0)
        val rules = listOf(oneTime("first",now.plusSeconds(8),now.plusSeconds(14)),oneTime("second",now.plusSeconds(20),now.plusSeconds(26)))
        app.graph.scheduleRepository.update { ScheduleConfig(false,rules) }
        await("Rules not loaded") { app.graph.schedules.value.rules.size == 2 }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { ContextCompat.startForegroundService(it,Intent(it,ReplayService::class.java).setAction(ReplayService.ARM_SCHEDULE)) }
            await("Plan not armed") { app.graph.state.value.planArmed && app.graph.state.value.status == CaptureStatus.PLANNED }
            assertEquals(0L,app.graph.store.availableFrames())
            scenario.moveToState(Lifecycle.State.CREATED)
            await("First microphone interval did not start in background") { app.graph.state.value.status == CaptureStatus.RECORDING && app.graph.store.availableSeconds()>=1 }
            val firstSession = app.graph.store.snapshot(60).use { it.sessionId }
            await("First interval did not stop") { app.graph.state.value.status == CaptureStatus.PLANNED && app.graph.store.availableSeconds()>=1 }
            val frames = app.graph.store.availableFrames()
            Thread.sleep(500); assertEquals(frames,app.graph.store.availableFrames())
            val last = app.graph.state.value.lastSaved
            instrumentation.runOnMainSync { ReplayService.instance!!.command(ReplayService.SAVE,"test:planned") }
            await("Retained buffer could not be saved") { app.graph.state.value.lastSaved != last && app.graph.state.value.pendingExports == 0 }
            await("Second interval did not start") { app.graph.state.value.status == CaptureStatus.RECORDING && app.graph.store.availableSeconds()>=0.2 }
            assertNotEquals(firstSession,app.graph.store.snapshot(60).use { it.sessionId })
            await("Second interval did not stop") { app.graph.state.value.status == CaptureStatus.PLANNED }
            assertTrue(app.graph.store.availableFrames()>0)
            assertTrue(app.graph.state.value.planArmed)
            instrumentation.runOnMainSync { ReplayService.instance!!.command(ReplayService.PAUSE) }
            await("Manual pause did not disarm plan") { !app.graph.state.value.planArmed && !app.graph.schedules.value.enabled && app.graph.state.value.status == CaptureStatus.PAUSED }
        }
    }

    @Test fun stopCancelsFutureScheduledStart() = fixture {
        val now = ZonedDateTime.now().withNano(0)
        app.graph.scheduleRepository.update { ScheduleConfig(false,listOf(oneTime("cancel",now.plusSeconds(6),now.plusSeconds(12)))) }
        await("Rule not loaded") { app.graph.schedules.value.rules.size == 1 }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { ContextCompat.startForegroundService(it,Intent(it,ReplayService::class.java).setAction(ReplayService.ARM_SCHEDULE)) }
            await("Plan not armed") { app.graph.state.value.planArmed }
            scenario.onActivity { ReplayService.instance!!.command(ReplayService.STOP) }
            await("Stop failed") { ReplayService.instance == null && !app.graph.schedules.value.enabled }
            Thread.sleep(7000)
            assertNull(ReplayService.instance)
            assertEquals(0L,app.graph.store.availableFrames())
        }
    }

    @Test fun absentServiceGetsActivationNotificationInsteadOfBackgroundMicrophone() = fixture {
        val now = ZonedDateTime.now().withNano(0)
        app.graph.scheduleRepository.update { ScheduleConfig(true,listOf(oneTime("reminder",now.minusSeconds(5),now.plusSeconds(30)))) }
        await("Rule not loaded") { app.graph.schedules.value.enabled }
        app.sendBroadcast(Intent(app,ScheduleReceiver::class.java).setAction("cz.audioreplay.SCHEDULE_BOUNDARY"))
        await("Activation reminder missing") { app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 2 } }
        assertNull(ReplayService.instance)
        assertNotEquals(CaptureStatus.RECORDING,app.graph.state.value.status)
    }

    @Test fun weeklyAndOneTimeRulesPersistAndCanBeEditedWithoutChangingOthers() = fixture {
        val first = ScheduleRule("weekly",LocalTime.of(7,0),LocalTime.of(12,0),days = setOf(DayOfWeek.FRIDAY))
        val second = first.copy(id = "once",date = LocalDate.now().plusDays(1),days = emptySet())
        app.graph.scheduleRepository.update { ScheduleConfig(false,listOf(first,second)) }
        val reopened = ScheduleRepository(app)
        assertEquals(listOf(first,second),reopened.flow.first().rules)
        reopened.put(first.copy(end = LocalTime.of(13,0)))
        val edited = reopened.flow.first().rules
        assertEquals(second,edited.first { it.id == "once" })
        assertEquals(LocalTime.of(13,0),edited.first { it.id == "weekly" }.end)
        reopened.remove("weekly")
        assertEquals(listOf(second),reopened.flow.first().rules)
    }
}
