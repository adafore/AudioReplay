package cz.audioreplay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import cz.audioreplay.service.ReplayService
import cz.audioreplay.service.ScheduleAlarms
import android.net.Uri
import android.provider.Settings
import cz.audioreplay.ui.ReplayApp

class MainActivity : ComponentActivity() {
    private var resumeRequested = false
    private var awaitingExactAlarm = false
    private var permissionForPlan = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val plan = permissionForPlan; permissionForPlan = false
        if (hasPermissions()) { if (plan) armSchedule() else startCapture() } else graph.message("Povolte mikrofon. Záznam se nespustil.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        resumeRequested = intent.getBooleanExtra("resume", false)
        awaitingExactAlarm = savedInstanceState?.getBoolean("awaitingExactAlarm") ?: false
        permissionForPlan = savedInstanceState?.getBoolean("permissionForPlan") ?: false
        intent.removeExtra("resume")
        setContent { ReplayApp(graph, ::startCapture, ::armSchedule) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        resumeRequested = intent.getBooleanExtra("resume", false); intent.removeExtra("resume")
    }
    override fun onPostResume() {
        super.onPostResume()
        if (resumeRequested) { resumeRequested = false; window.decorView.post { startCapture() } }
        if (awaitingExactAlarm) {
            awaitingExactAlarm = false
            if (ScheduleAlarms.canSchedule(this)) window.decorView.post { armSchedule() }
            else graph.message("Plán není aktivní. Pro spuštění v přesný čas povolte budíky a připomenutí.")
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("awaitingExactAlarm",awaitingExactAlarm); outState.putBoolean("permissionForPlan",permissionForPlan)
        super.onSaveInstanceState(outState)
    }
    private fun hasPermissions(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun startCapture() {
        if (!hasPermissions()) {
            permissions.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        runCatching { ContextCompat.startForegroundService(this, Intent(this, ReplayService::class.java).setAction(ReplayService.START)) }
            .onFailure { graph.message("Spuštění selhalo: ${it.localizedMessage}") }
    }
    private fun armSchedule() {
        if (!ScheduleAlarms.canSchedule(this) && Build.VERSION.SDK_INT >= 31) {
            awaitingExactAlarm = true
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:$packageName"))) }
                .onFailure { awaitingExactAlarm = false; graph.message("Povolte přesné budíky pro AudioReplay v nastavení Androidu.") }
            return
        }
        if (!hasPermissions()) {
            permissionForPlan = true
            permissions.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        runCatching { ContextCompat.startForegroundService(this,Intent(this,ReplayService::class.java).setAction(ReplayService.ARM_SCHEDULE)) }
            .onFailure { graph.message("Plán nelze aktivovat: ${it.localizedMessage}") }
    }
}
