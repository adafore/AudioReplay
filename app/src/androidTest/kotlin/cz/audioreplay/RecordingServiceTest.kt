package cz.audioreplay

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cz.audioreplay.service.ReplayService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import cz.audioreplay.core.SegmentStore

@RunWith(AndroidJUnit4::class)
class RecordingServiceTest {
    private val app = ApplicationProvider.getApplicationContext<ReplayApplication>()
    private fun await(message: String, timeout: Long = 15000, predicate: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + timeout
        while (!predicate() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue(message,predicate())
    }
    @Test fun realMicrophoneSavePauseResumeAndStop() = runBlocking {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(app.packageName,Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(app.packageName,Manifest.permission.POST_NOTIFICATIONS)
        app.graph.settingsReady.await()
        val previous = app.graph.settings.value
        app.graph.settingsRepository.update { it.copy(bufferDurationSec = 30,saveDurationSec = 10,doublePress = false,vibration = false,onboardingDone = true) }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { ContextCompat.startForegroundService(it,Intent(it,ReplayService::class.java).setAction(ReplayService.START)) }
                await("Microphone did not enter RECORDING: ${app.graph.state.value}") { app.graph.state.value.status == CaptureStatus.RECORDING }
                await("No PCM history from microphone") { app.graph.state.value.availableSeconds >= 2 }
                val before = app.graph.state.value.lastSaved
                app.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 1 }.notification.actions[0].actionIntent.send()
                await("Export did not complete: ${app.graph.state.value}",30000) { app.graph.state.value.lastSaved != before }
                assertEquals(CaptureStatus.RECORDING,app.graph.state.value.status)
                app.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 1 }.notification.actions[1].actionIntent.send()
                await("Pause failed") { app.graph.state.value.status == CaptureStatus.PAUSED }
                val pausedFrames = app.graph.store.availableFrames(); Thread.sleep(500); assertEquals(pausedFrames,app.graph.store.availableFrames())
                scenario.onActivity { ContextCompat.startForegroundService(it,Intent(it,ReplayService::class.java).setAction(ReplayService.START)) }
                await("Resume failed") { app.graph.state.value.status == CaptureStatus.RECORDING }
                assertTrue(app.graph.store.availableSeconds() < 2)
                scenario.onActivity { ReplayService.instance!!.command(ReplayService.STOP) }
                await("Service did not stop") { ReplayService.instance == null }
                assertEquals(0.0,app.graph.state.value.availableSeconds,0.0)
                app.graph.database.recordings().all().filter { it.name == app.graph.state.value.lastSaved }.forEach { app.graph.recordings.delete(it) }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ReplayService.instance?.command(ReplayService.STOP) }
            app.graph.settingsRepository.update { previous }
        }
    }
    @Test fun standaloneEditServicePublishesCopyWithoutStartingMicrophone() = runBlocking {
        app.graph.mediaReady.await()
        val originalIds = app.graph.database.recordings().all().map { it.id }.toSet()
        val dir = File(app.cacheDir,"edit-service-${UUID.randomUUID()}").also { it.mkdirs() }
        try {
            val source = SegmentStore(File(dir,"pcm"),10).use { store ->
                store.beginSession(24000,0); store.append(ByteArray(24000*2*3))
                store.snapshot(3).use { snapshot ->
                    val temp=File(dir,"original.tmp"); app.graph.exporter.encode(snapshot,temp,64000)
                    app.graph.recordings.publish(temp,snapshot,64000,false)
                }
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val before=app.graph.state.value.lastSaved
                scenario.onActivity { ContextCompat.startForegroundService(it,Intent(it,ReplayService::class.java).setAction(ReplayService.EDIT)
                    .putExtra("recording",source.id).putExtra("from",500000L).putExtra("to",2000000L).putExtra("gain",6)) }
                await("Edit service did not publish a copy: ${app.graph.state.value}",30000) { app.graph.state.value.lastSaved != before }
                await("Edit-only service did not stop") { ReplayService.instance == null }
                assertEquals(CaptureStatus.IDLE,app.graph.state.value.status)
                assertEquals(0.0,app.graph.store.availableSeconds(),0.0)
                assertEquals(2,app.graph.database.recordings().all().count { it.id !in originalIds })
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { ReplayService.instance?.command(ReplayService.STOP) }
            app.graph.database.recordings().all().filter { it.id !in originalIds }.forEach { app.graph.recordings.delete(it) }
            dir.deleteRecursively()
        }
    }
}
