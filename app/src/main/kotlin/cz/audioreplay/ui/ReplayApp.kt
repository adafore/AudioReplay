package cz.audioreplay.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.audioreplay.*
import cz.audioreplay.controls.updateControls
import cz.audioreplay.core.*
import cz.audioreplay.data.Recording
import cz.audioreplay.service.ReplayService
import cz.audioreplay.service.RecordingNotice
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

@Composable fun ReplayApp(g: AppGraph, start: () -> Unit) {
    val settings by g.settings.collectAsStateWithLifecycle()
    val state by g.state.collectAsStateWithLifecycle()
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(primary = Color(0xFFA4D6BD), primaryContainer = Color(0xFF244C3E), onPrimaryContainer = Color(0xFFD4ECDD),
        secondary = Color(0xFFBACBC0), secondaryContainer = Color(0xFF314A3A), background = Color(0xFF101714), surface = Color(0xFF141D18))
        else lightColorScheme(primary = Color(0xFF265E52), primaryContainer = Color(0xFFD3E9DC), onPrimaryContainer = Color(0xFF102F25),
            secondary = Color(0xFF53675B), secondaryContainer = Color(0xFFDDEADF), background = Color(0xFFF6F8F3), surface = Color(0xFFF6F8F3))
    MaterialTheme(colorScheme = colors) {
        var page by remember { mutableIntStateOf(0) }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it) } }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            if (settings.onboardingDone) NavigationBar {
                listOf("Záznam", "Historie", "Nastavení").forEachIndexed { index, title ->
                    NavigationBarItem(selected = page == index, onClick = { page = index }, label = { Text(title) },
                        icon = { Icon(when(index) { 0 -> Icons.Default.Mic; 1 -> Icons.AutoMirrored.Filled.List; else -> Icons.Default.Settings }, title) })
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (!settings.onboardingDone) Onboarding(g)
                else when (page) {
                    0 -> Dashboard(g, state, settings, start)
                    1 -> History(g)
                    else -> Preferences(g, settings, state)
                }
            }
        }
    }
}

@Composable private fun Heading(kicker: String, title: String, description: String? = null) {
    Text(kicker.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    if (description != null) { Spacer(Modifier.height(8.dp)); Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Spacer(Modifier.height(24.dp))
}
@Composable private fun Onboarding(g: AppGraph) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.GraphicEq, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Heading("AudioReplay", "Důležité už zaznělo?", "Uložte zpětně poslední okamžiky. Záznam spouštíte a zastavujete vy.")
        InfoCard("Mikrofon pod vaší kontrolou", "Nahrávání spouštíte vy a Android zobrazuje indikátor mikrofonu. Oznámení s ovládáním si můžete nastavit nebo vypnout.")
        Spacer(Modifier.height(12.dp))
        InfoCard("Zvuk zůstává v telefonu", "Průběžný buffer se maže rotací. Trvale zůstanou jen úseky, které uložíte. Aplikace nepoužívá cloud.")
        Spacer(Modifier.height(12.dp))
        InfoCard("Ovládání ze zamčené obrazovky", "Po spuštění zamkněte telefon a ověřte akce oznámení. Android a HyperOS mohou vyžadovat povolení obsahu oznámení na zamykací obrazovce.")
        Spacer(Modifier.height(16.dp))
        Text("Respektujte soukromí ostatních. Mikrofon je pro záznam nutný; zobrazení oznámení je vaše volba.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { scope.launch { g.settingsRepository.update { it.copy(onboardingDone = true) } } }, modifier = Modifier.fillMaxWidth()) { Text("Rozumím, otevřít AudioReplay") }
    }
}
@Composable private fun InfoCard(title: String, text: String) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(6.dp)); Text(text, style = MaterialTheme.typography.bodyMedium) }
    }
}
@Composable private fun Dashboard(g: AppGraph, state: ReplayState, settings: ReplaySettings, start: () -> Unit) {
    val active = state.status == CaptureStatus.RECORDING
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Heading("AudioReplay", "Zachyťte okamžik.", "Průběžná paměť pro zvuk kolem vás.")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (active) Icons.Default.Mic else Icons.Default.MicOff, null)
                    Spacer(Modifier.width(8.dp))
                    Text(when(state.status) { CaptureStatus.RECORDING -> "Mikrofon nahrává"; CaptureStatus.STARTING -> "Spouštění mikrofonu…"; CaptureStatus.PAUSED -> "Pozastaveno · mikrofon vypnutý"; CaptureStatus.ERROR -> "Mikrofon neběží · chyba"; else -> "Připraveno ke spuštění" }, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(28.dp))
                Text(clock(state.availableSeconds.toInt()), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Medium)
                Text("skutečně dostupná historie / ${Durations.label(settings.bufferDurationSec)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(progress = { (state.availableSeconds / settings.bufferDurationSec).toFloat().coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                Button(onClick = { ReplayService.instance?.command(ReplayService.SAVE, "app") }, enabled = state.availableSeconds > 0 && state.serviceRunning,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("Uložit posledních ${Durations.label(settings.saveDurationSec)}")
                }
                if (state.availableSeconds in 0.001..<settings.saveDurationSec.toDouble()) { Spacer(Modifier.height(8.dp)); Text("Nyní se uloží jen ${state.availableSeconds.toInt()} s dostupného zvuku.", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { if (active) ReplayService.instance?.command(ReplayService.PAUSE) else start() },
                enabled = state.status != CaptureStatus.STARTING, modifier = Modifier.weight(1f)) {
                Icon(if (active) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                Text(if (active) "Pozastavit" else if (state.status == CaptureStatus.PAUSED) "Pokračovat" else "Spustit")
            }
            OutlinedButton(onClick = { ReplayService.instance?.command(ReplayService.STOP) }, enabled = state.serviceRunning, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Stop, null); Text("Zastavit")
            }
        }
        Spacer(Modifier.height(24.dp))
        InfoCard("Ukládání pokračuje souběžně", "${state.pendingExports} exportů ve frontě · přibližně ${size(Durations.estimatedBytes(settings.saveDurationSec, settings.bitrate))} na úsek. Během exportu můžete dál nahrávat.")
        if (state.lastSaved != null) { Spacer(Modifier.height(12.dp)); InfoCard("Poslední uložení", state.lastSaved) }
        if (state.status == CaptureStatus.PAUSED) { Spacer(Modifier.height(12.dp)); Text("Po pokračování začne nová historie. Pauzu do uloženého zvuku nespojujeme.") }
        state.message?.let { Spacer(Modifier.height(16.dp)); Text(it, color = if (state.status == CaptureStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable private fun DurationEditor(title: String, current: Int, max: Int, presets: List<Int>, commit: (Int) -> Unit) {
    var minutes by remember { mutableStateOf(true) }
    var draft by remember { mutableStateOf(durationField(current, true)) }
    var dirty by remember { mutableStateOf(false) }
    var slider by remember(current, max) { mutableFloatStateOf(current.toFloat().coerceIn(10f, max.toFloat())) }
    LaunchedEffect(current, minutes) { if (!dirty) draft = durationField(current, minutes) }
    val parsed = Durations.parse(draft, minutes, max)
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text("Nastaveno: ${Durations.label(current)}", style = MaterialTheme.typography.bodySmall)
            Slider(value = slider, onValueChange = { slider = it }, valueRange = 10f..max.toFloat(), onValueChangeFinished = {
                val value = slider.roundToInt().coerceIn(10, max); dirty = false; draft = durationField(value, minutes); commit(value)
            })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = draft, onValueChange = { draft = it; dirty = true }, label = { Text("Délka") },
                    modifier = Modifier.weight(1f), singleLine = true, isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                FilterChip(selected = !minutes, onClick = { val value = parsed; minutes = false; if (value != null) draft = durationField(value, false) }, label = { Text("s") })
                FilterChip(selected = minutes, onClick = { val value = parsed; minutes = true; if (value != null) draft = durationField(value, true) }, label = { Text("min") })
            }
            if (parsed == null) Text("Zadejte 10–$max sekund.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else if (dirty) Text("Po zaokrouhlení: ${Durations.label(parsed)}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { parsed?.let { dirty = false; commit(it) } }, enabled = parsed != null && parsed != current) { Text("Použít hodnotu") }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.filter { it <= max }.forEach { value -> AssistChip(onClick = { dirty = false; draft = durationField(value, minutes); commit(value) }, label = { Text(Durations.label(value)) }) }
            }
        }
    }
}
private fun durationField(seconds: Int, minutes: Boolean): String = if (!minutes) seconds.toString()
    else java.math.BigDecimal(seconds).divide(60.toBigDecimal(), 4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

@Composable private fun Preferences(g: AppGraph, settings: ReplaySettings, state: ReplayState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun update(change: (ReplaySettings) -> ReplaySettings) { scope.launch {
        runCatching { g.settingsRepository.update(change) }.onFailure { g.message("Nastavení se nepodařilo uložit: ${it.localizedMessage}") }
        updateControls(context)
    } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Heading("Vaše volby", "Nastavení")
        DurationEditor("Délka průběžného bufferu", settings.bufferDurationSec, 3600, listOf(300,600,1200,1800,3600)) { value ->
            if (value < settings.saveDurationSec) g.message("Ukládaná délka byla zkrácena na ${Durations.label(value)}, aby nepřesáhla buffer.")
            update { it.withBuffer(value) }
        }
        DurationEditor("Délka ukládaného úseku", settings.saveDurationSec, settings.bufferDurationSec, listOf(60,150,300,600,1200,1800)) { value -> update { it.copy(saveDurationSec = value) } }
        Text("Zvětšení bufferu přidá prostor pro budoucí zvuk. Již dostupná historie se tím neprodlouží.", style = MaterialTheme.typography.bodySmall)
        SectionTitle("Kvalita a soubory")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(32000,64000,128000).forEach { bitrate -> FilterChip(settings.bitrate == bitrate, { update { it.copy(bitrate = bitrate) } }, label = { Text("${bitrate / 1000} kb/s") }) }
        }
        Text("AAC-LC · mono · přednostně 24 kHz. Změna kvality se použije pro další uložení. Odhad: ${size(Durations.estimatedBytes(settings.saveDurationSec, settings.bitrate))} / úsek.", style = MaterialTheme.typography.bodySmall)
        Toggle("Veřejná složka", "Recordings/AudioReplay (Android 10–11: Music/AudioReplay). Jinak soukromé úložiště aplikace.", settings.publicStorage) { value -> update { it.copy(publicStorage = value) } }
        Text("Volné místo: ${size(g.app.filesDir.usableSpace)}. Soukromé nahrávky se při odinstalování aplikace smažou.", style = MaterialTheme.typography.bodySmall)
        SectionTitle("Ovládání")
        Text("Akce Uložit, Pozastavit a Zastavit jsou v trvalém oznámení.")
        Toggle("Dlaždice rychlého nastavení", "Přidejte AudioReplay v úpravě systémových dlaždic.", settings.tileEnabled) { value -> update { it.copy(tileEnabled = value) } }
        Toggle("Widget domovské obrazovky", "Velikost změníte podržením na ploše. Malý ukládá předvolený čas; větší přidá 1/5 min a Spustit/Zastavit.", settings.widgetEnabled) { value -> update { it.copy(widgetEnabled = value) } }
        Toggle("Dvojí stisk pro uložení", "Druhý stisk stejného ovládacího prvku do 0,9 sekundy.", settings.doublePress) { value -> update { it.copy(doublePress = value) } }
        Toggle("Vibrace po uložení", "Až po úspěšném zveřejnění celého souboru.", settings.vibration) { value -> update { it.copy(vibration = value) } }
        SectionTitle("Oznámení nahrávání")
        NotificationControls(g)
        Toggle("Podrobnosti v oznámení", "Dostupná historie a počet ukládání.",settings.notificationDetails) { value -> update { it.copy(notificationDetails = value) } }
        Toggle("Tlačítka v oznámení", "Uložit, Pozastavit a Zastavit.",settings.notificationActions) { value -> update { it.copy(notificationActions = value) } }
        Toggle("Soukromé oznámení", "Na zamčené obrazovce může systém skrýt obsah i akce.", settings.privateNotification) { value -> update { it.copy(privateNotification = value) } }
        SectionTitle("Telefon a spolehlivost")
        InfoCard("Test zamykací obrazovky", "Spusťte záznam, počkejte alespoň 10 s, zamkněte telefon a klepněte v oznámení na Uložit. Potom v Historii ověřte přehrávání a délku. Na HyperOS ověřte i 45 minut se zhasnutým displejem.")
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Nastavení úspory baterie") }
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Oprávnění a informace o aplikaci") }
        Text("HyperOS může záznam ukončit podle nastavení baterie. Mikrofon se po ukončení procesu sám nespustí. Dočasnou historii po restartu zahodíme.", style = MaterialTheme.typography.bodySmall)
        Toggle("Zobrazit diagnostiku", "Stav služby a orientační velikost PCM. Spotřebu baterie neměříme.", settings.diagnostics) { value -> update { it.copy(diagnostics = value) } }
        if (settings.diagnostics) InfoCard("Diagnostika", "Služba: ${if (state.serviceRunning) "běží" else "vypnutá"}\nMikrofon: ${state.status}\nVzorkování: ${state.sampleRate} Hz\nHistorie: ${state.availableSeconds.toInt()} s\nPCM na disku: ${size(state.cacheBytes)}\nFronta exportů: ${state.pendingExports}\nOdhad plného bufferu při 24 kHz: ${size(settings.bufferDurationSec.toLong() * 48000)}")
        Spacer(Modifier.height(16.dp))
    }
}
@Composable private fun NotificationControls(g: AppGraph) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(RecordingNotice.visible(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_RESUME) visible = RecordingNotice.visible(context) }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    Toggle("Zobrazovat oznámení", "Zapnutí a vypnutí potvrdíte v přepínači Androidu. Nahrávání funguje i při skrytém oznámení.",visible) {
        runCatching { RecordingNotice.settings(context) }.onFailure { g.message("Nastavení oznámení nelze otevřít.") }
    }
    OutlinedButton(onClick = { runCatching { RecordingNotice.settings(context) }.onFailure { g.message("Nastavení oznámení nelze otevřít.") } }) { Text("Zobrazení a zvuk oznámení") }
}
@Composable private fun SectionTitle(text: String) { Spacer(Modifier.height(8.dp)); Text(text, style = MaterialTheme.typography.titleLarge) }
@Composable private fun Toggle(title: String, description: String, value: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, fontWeight = FontWeight.Medium); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked = value, onCheckedChange = changed)
    }
}

@Composable private fun History(g: AppGraph) {
    val recordings by g.recordings.flow.collectAsStateWithLifecycle(initialValue = emptyList())
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    var rename by remember { mutableStateOf<Recording?>(null) }
    var remove by remember { mutableStateOf<Recording?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Recording?>(null) }
    var folderDialog by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val state by g.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember { MediaPlayer() }
    var playing by remember { mutableStateOf<Recording?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var length by remember { mutableIntStateOf(0) }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) { runCatching { player.pause() }; isPlaying = false } }
        lifecycle.addObserver(observer)
        player.setOnCompletionListener { isPlaying = false; position = length }
        player.setOnErrorListener { _, _, _ -> g.message("Soubor se nepodařilo přehrát."); isPlaying = false; playing = null; true }
        onDispose { lifecycle.removeObserver(observer); player.release() }
    }
    LaunchedEffect(isPlaying) { while (isPlaying) { position = runCatching { player.currentPosition }.getOrDefault(0); delay(300) } }
    fun play(recording: Recording) {
        runCatching {
            if (playing?.id == recording.id) {
                if (isPlaying) player.pause() else player.start()
                isPlaying = !isPlaying
            } else {
                player.reset(); playing = recording; isPlaying = false; position = 0
                player.setDataSource(context, g.recordings.uri(recording))
                player.setOnPreparedListener { length = it.duration; it.start(); isPlaying = true }
                player.prepareAsync()
            }
        }.onFailure { g.message("Přehrávání: ${it.localizedMessage}"); playing = null; isPlaying = false }
    }
    val filtered = recordings.filter { it.name.contains(search, ignoreCase = true) }.let { rows -> when(sort) { 1 -> rows.sortedBy { it.name.lowercase() }; 2 -> rows.sortedByDescending { it.durationUs }; else -> rows } }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Heading("Uložené okamžiky", "Historie") }
            IconButton(onClick = { if(recordings.any { !it.publicStorage }) folderDialog = true else openRecordingsFolder(context,g) }) { Icon(Icons.Default.FolderOpen,"Otevřít složku nahrávek") }
        }
        if (state.editProgress != null) {
            Text("Upravuji: ${state.editingName?.removeSuffix(".m4a") ?: "nahrávku"}")
            LinearProgressIndicator(progress = { state.editProgress ?: 0f },modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Hledat nahrávku") }, leadingIcon = { Icon(Icons.Default.Search, null) })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Nejnovější", "Název", "Nejdelší").forEachIndexed { i, s -> FilterChip(sort == i, { sort = i }, label = { Text(s) }) }
        }
        playing?.let { current ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(current.name, maxLines = 2, fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { play(current) }) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pozastavit přehrávání" else "Přehrát") }
                        Text("${clock(position/1000)} / ${clock(length/1000)}")
                        Slider(position.toFloat().coerceAtMost(length.toFloat()), { value -> position = value.toInt(); runCatching { player.seekTo(position) } }, valueRange = 0f..length.coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (filtered.isEmpty()) {
            Spacer(Modifier.height(32.dp)); InfoCard("Zatím tu je ticho", if (search.isEmpty()) "Spusťte záznam a uložte první úsek. Najdete ho zde." else "Žádná nahrávka neodpovídá hledání.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(filtered, key = { it.id }) { recording ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(recording.name.removeSuffix(".m4a"), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text("${clock((recording.durationUs/1_000_000).toInt())} · ${size(recording.sizeBytes)} · ${SimpleDateFormat("d. M. yyyy HH:mm", Locale.getDefault()).format(Date(recording.createdMs))}", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton(onClick = { play(recording) }) { Icon(Icons.Default.PlayArrow, "Přehrát nahrávku") }
                            IconButton(onClick = { rename = recording; renameText = recording.name.removeSuffix(".m4a"); renameError = null }) { Icon(Icons.Default.Edit, "Přejmenovat") }
                            IconButton(onClick = { editing = recording },enabled = state.pendingExports < 3) { Icon(Icons.Default.ContentCut,"Oříznout a zesílit") }
                            IconButton(onClick = {
                                runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("audio/mp4")
                                    .putExtra(Intent.EXTRA_STREAM, g.recordings.uri(recording)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Sdílet nahrávku")) }.onFailure { g.message("Sdílení selhalo: ${it.localizedMessage}") }
                            }) { Icon(Icons.Default.Share, "Sdílet") }
                            IconButton(onClick = { remove = recording }) { Icon(Icons.Default.Delete, "Smazat") }
                        }
                    }
                }
            }
        }
    }
    editing?.let { recording -> AudioEditDialog(recording,onDismiss = { editing = null }) { edit ->
        runCatching {
            ContextCompat.startForegroundService(context,Intent(context,ReplayService::class.java).setAction(ReplayService.EDIT)
                .putExtra("recording",recording.id).putExtra("from",edit.startUs).putExtra("to",edit.endUs).putExtra("gain",edit.gainDb))
            editing = null
        }.onFailure { g.message("Úpravu nelze spustit: ${it.localizedMessage}") }
    } }
    if(folderDialog) AlertDialog(onDismissRequest = { if(!moving) folderDialog = false },title = { Text("Složka nahrávek") },
        text = { Text(if(moving) "Přesouvám nahrávky…" else "Soukromé nahrávky jsou v úložišti aplikace. Pro otevření ve správci souborů je zpřístupníme ve složce ${g.recordings.directoryLabel()}. Tuto složku použijeme i pro další ukládání.") },
        confirmButton = { TextButton(enabled = !moving,onClick = { moving = true; scope.launch {
            runCatching {
                g.mediaReady.await(); g.recordings.makePrivateRecordingsPublic()
                g.settingsRepository.update { it.copy(publicStorage = true) }
                folderDialog = false; openRecordingsFolder(context,g)
            }.onFailure { g.message("Zpřístupnění se nezdařilo: ${it.localizedMessage}") }
            moving = false
        } }) { Text("Zpřístupnit a otevřít") } },
        dismissButton = { TextButton(enabled = !moving,onClick = { folderDialog = false; openRecordingsFolder(context,g) }) { Text("Jen veřejná složka") } })
    rename?.let { recording -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("Přejmenovat nahrávku") }, text = {
        Column { OutlinedTextField(renameText, { renameText = it; renameError = null }, label = { Text("Název bez .m4a") }, isError = renameError != null); renameError?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
    }, confirmButton = { TextButton(onClick = { scope.launch {
        runCatching { g.recordings.rename(recording, renameText) }.onSuccess { rename = null }.onFailure { renameError = it.localizedMessage }
    } }) { Text("Přejmenovat") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("Zrušit") } }) }
    remove?.let { recording -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Smazat nahrávku?") }, text = { Text(recording.name) },
        confirmButton = { TextButton(onClick = { scope.launch {
            if (playing?.id == recording.id) { player.reset(); playing = null; isPlaying = false }
            runCatching { g.recordings.delete(recording) }.onFailure { g.message("Mazání: ${it.localizedMessage}") }; remove = null
        } }) { Text("Smazat") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("Zrušit") } }) }
}
private fun clock(seconds: Int): String = "%d:%02d".format(Locale.ROOT, seconds/60, seconds%60)
private fun size(bytes: Long): String = if (bytes < 1024*1024) "%.0f kB".format(Locale.getDefault(), bytes/1024.0) else "%.1f MB".format(Locale.getDefault(), bytes/(1024.0*1024))
