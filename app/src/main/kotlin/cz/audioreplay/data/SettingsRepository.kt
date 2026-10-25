package cz.audioreplay.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import cz.audioreplay.core.ReplaySettings
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore by preferencesDataStore("settings")
class SettingsRepository(private val context: Context) {
    private fun int(key: String) = intPreferencesKey(key)
    private fun bool(key: String) = booleanPreferencesKey(key)
    private fun decode(p: Preferences): ReplaySettings = ReplaySettings(
            bufferDurationSec = p[int("buffer")] ?: 1800, saveDurationSec = p[int("save")] ?: 300,
            bitrate = p[int("bitrate")] ?: 64000, publicStorage = p[bool("public")] ?: false,
            vibration = p[bool("vibration")] ?: true, doublePress = p[bool("double")] ?: false,
            privateNotification = p[bool("private")] ?: false, tileEnabled = p[bool("tile")] ?: true,
            widgetEnabled = p[bool("widget")] ?: true,
            diagnostics = p[bool("diagnostics")] ?: false, onboardingDone = p[bool("onboarding")] ?: false,
            notificationDetails = p[bool("notification_details")] ?: true,
            notificationActions = p[bool("notification_actions")] ?: true,
            recordingGainDb = p[int("recording_gain")] ?: 0,
        ).normalized()
    val flow = context.settingsDataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map(::decode)
    suspend fun update(transform: (ReplaySettings) -> ReplaySettings) {
        context.settingsDataStore.edit { p ->
            val current = decode(p)
            val s = transform(current).normalized()
            p[int("buffer")] = s.bufferDurationSec; p[int("save")] = s.saveDurationSec; p[int("bitrate")] = s.bitrate
            p[bool("public")] = s.publicStorage; p[bool("vibration")] = s.vibration; p[bool("double")] = s.doublePress
            p[bool("private")] = s.privateNotification; p[bool("tile")] = s.tileEnabled; p[bool("widget")] = s.widgetEnabled
            p.remove(bool("hid")); p[bool("diagnostics")] = s.diagnostics; p[bool("onboarding")] = s.onboardingDone
            p[bool("notification_details")] = s.notificationDetails; p[bool("notification_actions")] = s.notificationActions
            p[int("recording_gain")] = s.recordingGainDb
        }
    }
}
