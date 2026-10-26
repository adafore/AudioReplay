package cz.audioreplay.ui

import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.audioreplay.*
import cz.audioreplay.core.*
import cz.audioreplay.service.ReplayService
import cz.audioreplay.service.ScheduleAlarms
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("d. M. yyyy")
private val dayLabels = listOf("Po","Út","St","Čt","Pá","So","Ne")
private fun ScheduleRule.description(): String = date?.format(dateFormat) ?: days.sortedBy { it.value }.joinToString(", ") { dayLabels[it.value-1] }

@Composable fun SchedulePane(g: AppGraph,activate: () -> Unit) {
    val config by g.schedules.collectAsStateWithLifecycle()
    val state by g.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduleRule?>(null) }
    var removing by remember { mutableStateOf<ScheduleRule?>(null) }
    var exact by remember { mutableStateOf(ScheduleAlarms.canSchedule(context)) }
    var notifications by remember { mutableStateOf(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _,event -> if (event == Lifecycle.Event.ON_RESUME) {
            exact = ScheduleAlarms.canSchedule(context)
            notifications = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("KDY NAHRÁVAT",style = MaterialTheme.typography.labelMedium,color = MaterialTheme.colorScheme.primary)
        Text("Plánování",style = MaterialTheme.typography.headlineLarge,fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (state.planArmed) "Plán je aktivní" else "Plán není aktivní",style = MaterialTheme.typography.titleMedium)
                Text(if (state.planArmed) "Mezi intervaly je mikrofon vypnutý. Služba čeká na další čas." else "Po nastavení intervalů aktivujte plán v otevřené aplikaci. Potom může pracovat i se zhasnutou obrazovkou.")
                state.nextPlanChangeMs?.let { millis ->
                    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d. M. HH:mm",Locale.forLanguageTag("cs")))
                    Text(if (state.status == CaptureStatus.PLANNED) "Další spuštění: $time" else "Plánované vypnutí: $time")
                }
                if (config.enabled && !state.planArmed) Text("Po ukončení služby nebo restartu telefonu je nutná nová aktivace.",color = MaterialTheme.colorScheme.error)
                if (!exact) Text("Při aktivaci Android požádá o povolení přesných budíků a připomenutí.",style = MaterialTheme.typography.bodySmall)
                Button(onClick = { if (state.planArmed) ReplayService.instance?.command(ReplayService.PAUSE) else activate() },
                    enabled = state.planArmed || config.rules.any { it.enabled }) {
                    Icon(if (state.planArmed) Icons.Default.Pause else Icons.Default.PlayArrow,null)
                    Spacer(Modifier.width(8.dp)); Text(if (state.planArmed) "Vypnout plán" else "Aktivovat plán")
                }
            }
        }
        Text("Konec intervalu vypne mikrofon a ponechá buffer pro ruční uložení. Další spuštění začne novou historii. Nejde o automatické uložení celého intervalu.",style = MaterialTheme.typography.bodySmall)
        Text("Ruční Pozastavit nebo Zastavit vypne také plán. Překrývající se intervaly se spojí.",style = MaterialTheme.typography.bodySmall)
        if (!notifications) {
            Text("Povolte oznámení pro připomenutí aktivace, pokud Android službu ukončí.",style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)) }) { Text("Nastavení oznámení") }
        }
        OutlinedButton(onClick = { adding = true },enabled = config.rules.size < 64,modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Add,null); Spacer(Modifier.width(8.dp)); Text("Přidat interval")
        }
        if (config.rules.isEmpty()) Text("Například přidejte dva páteční intervaly: 7:00–12:00 a 12:30–22:00.")
        config.rules.forEach { rule ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.description(),fontWeight = FontWeight.SemiBold)
                            Text("${rule.start.format(timeFormat)}–${rule.end.format(timeFormat)}" + if (rule.end < rule.start) " · do dalšího dne" else "")
                            rule.date?.let { date -> if (rule.occurrence(date,ZoneId.systemDefault()).end <= Instant.now()) Text("Dokončeno",style = MaterialTheme.typography.bodySmall) }
                        }
                        Switch(rule.enabled,{ enabled -> scope.launch { runCatching { g.scheduleRepository.put(rule.copy(enabled = enabled)) }.onFailure { g.message(it.localizedMessage ?: "Plán nelze změnit.") } } })
                        IconButton(onClick = { editing = rule }) { Icon(Icons.Default.Edit,"Upravit interval") }
                        IconButton(onClick = { removing = rule }) { Icon(Icons.Default.Delete,"Smazat interval") }
                    }
                }
            }
        }
    }
    if (adding || editing != null) ScheduleDialog(editing,onDismiss = { adding = false; editing = null }) { rule ->
        scope.launch { runCatching { g.scheduleRepository.put(rule) }
            .onSuccess { adding = false; editing = null }.onFailure { g.message(it.localizedMessage ?: "Interval nelze uložit.") } }
    }
    removing?.let { rule -> AlertDialog(onDismissRequest = { removing = null },title = { Text("Smazat interval?") },
        text = { Text("${rule.description()} ${rule.start.format(timeFormat)}–${rule.end.format(timeFormat)}") },
        confirmButton = { TextButton(onClick = { scope.launch {
            runCatching { g.scheduleRepository.remove(rule.id) }.onFailure { g.message(it.localizedMessage ?: "Interval nelze smazat.") }; removing = null
        } }) { Text("Smazat") } },dismissButton = { TextButton(onClick = { removing = null }) { Text("Zrušit") } }) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ScheduleDialog(original: ScheduleRule?,onDismiss: () -> Unit,onSave: (ScheduleRule) -> Unit) {
    val context = LocalContext.current
    val id = remember { original?.id ?: UUID.randomUUID().toString() }
    var weekly by remember { mutableStateOf(original?.date == null) }
    var date by remember { mutableStateOf(original?.date ?: LocalDate.now()) }
    var days by remember { mutableStateOf(original?.days ?: setOf(DayOfWeek.FRIDAY)) }
    var start by remember { mutableStateOf((original?.start ?: LocalTime.of(7,0)).format(timeFormat)) }
    var end by remember { mutableStateOf((original?.end ?: LocalTime.of(12,0)).format(timeFormat)) }
    fun parse(text: String): LocalTime? = runCatching { LocalTime.parse(text.trim(),timeFormat) }.getOrNull()
    val first = parse(start); val last = parse(end)
    val rule = if (first != null && last != null) ScheduleRule(id,first,last,if (weekly) null else date,if (weekly) days else emptySet(),original?.enabled ?: true) else null
    val error = when {
        first == null || last == null -> "Čas zadejte jako 07:00."
        first == last -> "Konec musí být jiný než začátek."
        weekly && days.isEmpty() -> "Vyberte alespoň jeden den."
        !weekly && rule!!.occurrence(date,ZoneId.systemDefault()).end <= Instant.now() -> "Vyberte interval v budoucnosti."
        else -> null
    }
    fun picker(text: String,set: (String) -> Unit) {
        val time = parse(text) ?: LocalTime.of(7,0)
        TimePickerDialog(context,{ _,hour,minute -> set(LocalTime.of(hour,minute).format(timeFormat)) },time.hour,time.minute,true).show()
    }
    AlertDialog(onDismissRequest = onDismiss,title = { Text(if (original == null) "Nový interval" else "Upravit interval") },text = {
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!weekly,{ weekly = false },label = { Text("Jednorázově") })
                FilterChip(weekly,{ weekly = true },label = { Text("Každý týden") })
            }
            if (weekly) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DayOfWeek.entries.forEach { day -> FilterChip(day in days,{ days = if (day in days) days-day else days+day },label = { Text(dayLabels[day.value-1]) }) }
            } else OutlinedButton(onClick = { DatePickerDialog(context,{ _,year,month,day -> date = LocalDate.of(year,month+1,day) },date.year,date.monthValue-1,date.dayOfMonth).show() }) {
                Icon(Icons.Default.CalendarMonth,null); Spacer(Modifier.width(8.dp)); Text(date.format(dateFormat))
            }
            OutlinedTextField(start,{ start = it },label = { Text("Začátek · HH:mm") },singleLine = true,
                trailingIcon = { IconButton(onClick = { picker(start) { start = it } }) { Icon(Icons.Default.Schedule,"Vybrat čas začátku") } })
            OutlinedTextField(end,{ end = it },label = { Text("Konec · HH:mm") },singleLine = true,
                trailingIcon = { IconButton(onClick = { picker(end) { end = it } }) { Icon(Icons.Default.Schedule,"Vybrat čas konce") } })
            if (first != null && last != null && last < first) Text("Konec je následující den.",style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it,color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall) }
            Text("Časy se řídí místním časem telefonu. Další interval přidáte samostatně.",style = MaterialTheme.typography.bodySmall)
        }
    },confirmButton = { TextButton(onClick = { rule?.let(onSave) },enabled = error == null) { Text("Uložit interval") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } })
}
