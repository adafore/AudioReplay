package cz.audioreplay

import android.app.Application
import androidx.room.Room
import cz.audioreplay.audio.AacExporter
import cz.audioreplay.audio.AudioEditor
import cz.audioreplay.audio.AudioCapture
import cz.audioreplay.audio.CaptureDriver
import cz.audioreplay.service.RecordingNotice
import cz.audioreplay.core.ReplaySettings
import cz.audioreplay.core.SegmentStore
import cz.audioreplay.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

enum class CaptureStatus { IDLE, STARTING, RECORDING, WAITING, PAUSED, ERROR }
data class ReplayState(
    val status: CaptureStatus = CaptureStatus.IDLE,
    val availableSeconds: Double = 0.0,
    val sampleRate: Int = 0,
    val pendingExports: Int = 0,
    val lastSaved: String? = null,
    val message: String? = null,
    val cacheBytes: Long = 0,
    val serviceRunning: Boolean = false,
    val editProgress: Float? = null,
    val editingName: String? = null,
    val interruptionCount: Int = 0,
)
class ReplayApplication : Application() {
    lateinit var graph: AppGraph; private set
    override fun onCreate() { super.onCreate(); graph = AppGraph(this) }
}
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state = MutableStateFlow(ReplayState())
    val settingsRepository = SettingsRepository(app)
    val settings = settingsRepository.flow.stateIn(scope, SharingStarted.Eagerly, ReplaySettings())
    val settingsReady = CompletableDeferred<Unit>()
    val mediaReady = CompletableDeferred<Unit>()
    val store = SegmentStore(File(app.cacheDir, "pcm").also { SegmentStore.discardStale(it) })
    val database = Room.databaseBuilder(app, RecordingDatabase::class.java, "recordings.db").build()
    val recordings = RecordingsRepository(app, database.recordings())
    val exporter = AacExporter()
    val editor = AudioEditor(app)
    internal var captureFactory: () -> CaptureDriver = { AudioCapture(app, store) }
    init {
        RecordingNotice.ensure(app)
        scope.launch { settingsRepository.flow.first(); settingsReady.complete(Unit) }
        scope.launch(Dispatchers.IO) { runCatching { recordings.recover(); recordings.compactLegacyNames() }.onFailure { error ->
            state.update { it.copy(message = "Obnova historie: ${error.localizedMessage}") }
        }; mediaReady.complete(Unit) }
    }
    fun message(message: String) { state.update { it.copy(message = message) } }
}
val android.content.Context.graph: AppGraph get() = (applicationContext as ReplayApplication).graph
