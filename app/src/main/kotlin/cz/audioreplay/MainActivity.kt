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
import cz.audioreplay.ui.ReplayApp

class MainActivity : ComponentActivity() {
    private var resumeRequested = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasPermissions()) startCapture() else graph.message("Povolte mikrofon. Záznam se nespustil.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        resumeRequested = intent.getBooleanExtra("resume", false)
        intent.removeExtra("resume")
        setContent { ReplayApp(graph, ::startCapture) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        resumeRequested = intent.getBooleanExtra("resume", false); intent.removeExtra("resume")
    }
    override fun onPostResume() {
        super.onPostResume()
        if (resumeRequested) { resumeRequested = false; window.decorView.post { startCapture() } }
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
}
