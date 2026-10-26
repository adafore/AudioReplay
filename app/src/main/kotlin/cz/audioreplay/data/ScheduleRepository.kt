package cz.audioreplay.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import cz.audioreplay.core.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.*

private val Context.scheduleDataStore by preferencesDataStore("schedule")
class ScheduleRepository(private val context: Context) {
    private val key = stringPreferencesKey("plan")
    internal fun decode(text: String?): ScheduleConfig {
        if (text == null) return ScheduleConfig()
        val json = JSONObject(text)
        val rows = json.getJSONArray("rules")
        val rules = (0 until rows.length()).map { index ->
            val r = rows.getJSONObject(index)
            val dayNumbers = r.getJSONArray("days")
            ScheduleRule(r.getString("id"),LocalTime.parse(r.getString("start")),LocalTime.parse(r.getString("end")),
                if (r.isNull("date")) null else LocalDate.parse(r.getString("date")),
                (0 until dayNumbers.length()).map { DayOfWeek.of(dayNumbers.getInt(it)) }.toSet(),r.getBoolean("enabled"))
        }
        return ScheduleConfig(json.getBoolean("enabled"),rules).validate()
    }
    private fun encode(config: ScheduleConfig): String = JSONObject().put("enabled",config.enabled).put("rules",JSONArray().apply {
        config.rules.forEach { r -> put(JSONObject().put("id",r.id).put("start",r.start.toString()).put("end",r.end.toString())
            .put("date",r.date?.toString() ?: JSONObject.NULL).put("days",JSONArray(r.days.sortedBy { it.value }.map { it.value })).put("enabled",r.enabled)) }
    }).toString()
    val flow = context.scheduleDataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { runCatching { decode(it[key]) }.getOrDefault(ScheduleConfig()) }
    suspend fun update(transform: (ScheduleConfig) -> ScheduleConfig) {
        context.scheduleDataStore.edit { prefs ->
            val updated = transform(runCatching { decode(prefs[key]) }.getOrDefault(ScheduleConfig())).validate()
            prefs[key] = encode(updated.copy(enabled = updated.enabled && updated.rules.any { it.enabled }))
        }
    }
    suspend fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }
    suspend fun put(rule: ScheduleRule) = update { config -> config.copy(rules = config.rules.filterNot { it.id == rule.id } + rule.validate()) }
    suspend fun remove(id: String) = update { config -> config.copy(rules = config.rules.filterNot { it.id == id }) }
}
