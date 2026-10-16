package cz.audioreplay.ui

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.audioreplay.AppGraph
import cz.audioreplay.core.AudioEdit
import cz.audioreplay.data.Recording
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

fun openRecordingsFolder(context: Context, g: AppGraph) {
    val uri = g.recordings.directoryUri()
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,DocumentsContract.Document.MIME_TYPE_DIR).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        .recoverCatching { context.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI,uri)) }
        .onFailure { g.message("Otevřete ve správci souborů složku ${g.recordings.directoryLabel()}.") }
}

@Composable fun AudioEditDialog(recording: Recording,onDismiss: () -> Unit,onSave: (AudioEdit) -> Unit) {
    val seconds = recording.durationUs / 1_000_000.0
    var from by remember(recording.id) { mutableStateOf("0") }
    var to by remember(recording.id) { mutableStateOf(String.format(Locale.ROOT,"%.3f",seconds)) }
    var range by remember(recording.id) { mutableStateOf(0f..seconds.toFloat()) }
    var gain by remember { mutableIntStateOf(0) }
    fun number(text: String): Double? = text.trim().replace(',','.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..seconds+0.0005 }
    val start = number(from); val end = number(to)
    val request = if(start != null && end != null) AudioEdit((start * 1_000_000).roundToLong(),(end * 1_000_000).roundToLong().coerceAtMost(recording.durationUs),gain) else null
    val valid = request != null && runCatching { request.validate(recording.durationUs) }.isSuccess
    AlertDialog(onDismissRequest = onDismiss,title = { Text("Oříznout a zesílit") },text = {
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(recording.name.removeSuffix(".m4a"))
            Text("Vyberte začátek a konec. Výsledek se uloží jako nová nahrávka.",style = MaterialTheme.typography.bodySmall)
            RangeSlider(value = range,onValueChange = { selected ->
                range = selected; from = String.format(Locale.ROOT,"%.3f",selected.start); to = String.format(Locale.ROOT,"%.3f",selected.endInclusive)
            },valueRange = 0f..seconds.toFloat().coerceAtLeast(0.1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(from,{ from = it; number(it)?.let { value -> if(value < (number(to) ?: 0.0)) range = value.toFloat()..range.endInclusive } },
                    label = { Text("Od (s)") },singleLine = true,modifier = Modifier.weight(1f),keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),isError = !valid)
                OutlinedTextField(to,{ to = it; number(it)?.let { value -> if(value > (number(from) ?: seconds)) range = range.start..value.toFloat() } },
                    label = { Text("Do (s)") },singleLine = true,modifier = Modifier.weight(1f),keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),isError = !valid)
            }
            if(!valid) Text("Vyberte alespoň 0,1 s uvnitř nahrávky.",color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall)
            Text("Zesílení: +$gain dB",style = MaterialTheme.typography.titleSmall)
            Slider(gain.toFloat(),{ gain = it.roundToInt() },valueRange = 0f..18f,steps = 17)
            Text("0 dB zachová hlasitost. Vyšší zesílení může zvuk zkreslit.",style = MaterialTheme.typography.bodySmall)
        }
    },confirmButton = { TextButton(onClick = { request?.let(onSave) },enabled = valid) { Text("Uložit kopii") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } })
}
