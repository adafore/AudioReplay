package cz.audioreplay.controls

import android.app.PendingIntent
import android.Manifest
import android.appwidget.*
import android.content.*
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import android.util.SizeF
import androidx.core.content.ContextCompat
import android.service.quicksettings.*
import android.widget.RemoteViews
import cz.audioreplay.*
import cz.audioreplay.core.Durations
import cz.audioreplay.core.WidgetLayout
import cz.audioreplay.core.WidgetSizes
import cz.audioreplay.service.ReplayService

fun openActivity(context: Context, resume: Boolean = false): PendingIntent = PendingIntent.getActivity(context, if (resume) 11 else 10,
    Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP; if (resume) putExtra("resume", true) },
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
fun controlIntent(context: Context, action: String, source: String, seconds: Int? = null): PendingIntent = PendingIntent.getBroadcast(context,
    "$action:$source:$seconds".hashCode(), Intent(context, ControlReceiver::class.java).setAction(action).putExtra("source", source).apply { seconds?.let { putExtra("seconds", it) } },
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
fun widgetStartIntent(context: Context): PendingIntent {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return openActivity(context, true)
    return PendingIntent.getForegroundService(context,81,Intent(context,ReplayService::class.java).setAction(ReplayService.START).putExtra("source","widget"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val source = intent.getStringExtra("source") ?: "notification"
        if (source.startsWith("widget") && !context.graph.settings.value.widgetEnabled) return
        if (ReplayService.instance == null && intent.action == ReplayService.SAVE) android.widget.Toast.makeText(context,"Nejprve spusťte nahrávání.",android.widget.Toast.LENGTH_SHORT).show()
        ReplayService.instance?.command(intent.action ?: return, source, intent.getIntExtra("seconds",0).takeIf { it in 1..3600 })
    }
}
fun updateControls(context: Context) {
    ReplayWidget.refresh(context)
    TileService.requestListeningState(context, ComponentName(context, ReplayTile::class.java))
}
class ReplayTile : TileService() {
    override fun onStartListening() {
        val active = graph.state.value.status in setOf(CaptureStatus.RECORDING, CaptureStatus.WAITING)
        qsTile?.apply {
            state = if (!graph.settings.value.tileEnabled) Tile.STATE_UNAVAILABLE else if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "AudioReplay"
            subtitle = if (graph.state.value.status == CaptureStatus.WAITING) "Čekám na mikrofon" else if (active) "Uložit ${Durations.label(graph.settings.value.saveDurationSec)}" else "Otevřít aplikaci"
            updateTile()
        }
    }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (!graph.settings.value.tileEnabled) return
        if (ReplayService.instance != null && graph.state.value.availableSeconds > 0) ReplayService.instance?.command(ReplayService.SAVE, "tile")
        else if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(openActivity(this))
        else @Suppress("DEPRECATION") startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
class ReplayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { refresh(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) { refresh(context) }
    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, ReplayWidget::class.java)).forEach { id ->
                val views = if (Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(
                    SizeF(48f,48f) to createViews(context,WidgetLayout.COMPACT), SizeF(160f,48f) to createViews(context,WidgetLayout.QUICK),
                    SizeF(260f,48f) to createViews(context,WidgetLayout.WIDE), SizeF(140f,140f) to createViews(context,WidgetLayout.FULL),
                )) else {
                    val options = manager.getAppWidgetOptions(id)
                    createViews(context,WidgetSizes.layout(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,48),options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,48)))
                }
                manager.updateAppWidget(id,views)
            }
        }
        fun createViews(context: Context, layout: WidgetLayout): RemoteViews {
            val g = context.graph
            val enabled = g.settings.value.widgetEnabled
            val active = g.state.value.status == CaptureStatus.RECORDING
            val resource = when(layout) { WidgetLayout.COMPACT -> R.layout.widget_compact; WidgetLayout.QUICK -> R.layout.widget_quick; WidgetLayout.WIDE -> R.layout.widget_wide; WidgetLayout.FULL -> R.layout.widget_full }
            return RemoteViews(context.packageName,resource).apply {
                if (layout == WidgetLayout.COMPACT || layout == WidgetLayout.FULL) {
                    val label = Durations.label(g.settings.value.saveDurationSec)
                    setTextViewText(R.id.widget_save,if(layout == WidgetLayout.COMPACT) label else "Uložit $label")
                    setContentDescription(R.id.widget_save,"Uložit posledních $label")
                    setOnClickPendingIntent(R.id.widget_save,if(enabled) controlIntent(context,ReplayService.SAVE,"widget:preset") else openActivity(context))
                }
                if (layout != WidgetLayout.COMPACT) {
                    setOnClickPendingIntent(R.id.widget_minute,if(enabled) controlIntent(context,ReplayService.SAVE,"widget:60",60) else openActivity(context))
                    setOnClickPendingIntent(R.id.widget_five,if(enabled) controlIntent(context,ReplayService.SAVE,"widget:300",300) else openActivity(context))
                    setContentDescription(R.id.widget_minute,"Uložit poslední 1 minutu")
                    setContentDescription(R.id.widget_five,"Uložit posledních 5 minut")
                }
                if (layout == WidgetLayout.WIDE || layout == WidgetLayout.FULL) {
                    setOnClickPendingIntent(R.id.widget_start,if(enabled) widgetStartIntent(context) else openActivity(context))
                    setOnClickPendingIntent(R.id.widget_stop,controlIntent(context,ReplayService.STOP,"widget:stop"))
                    setBoolean(R.id.widget_start,"setEnabled",enabled && g.state.value.status !in setOf(CaptureStatus.RECORDING, CaptureStatus.WAITING, CaptureStatus.STARTING))
                    setBoolean(R.id.widget_stop,"setEnabled",enabled && g.state.value.serviceRunning)
                    setTextViewText(R.id.widget_start,if(layout == WidgetLayout.WIDE) "▶" else if(g.state.value.status == CaptureStatus.PAUSED) "Pokračovat" else "Spustit")
                    setContentDescription(R.id.widget_start,"Spustit nahrávání")
                    setContentDescription(R.id.widget_stop,"Zastavit nahrávání")
                }
                if (layout == WidgetLayout.FULL) {
                    setTextViewText(R.id.widget_status,"AudioReplay · " + if(!enabled) "Vypnutý widget" else if(active) "Nahrává" else if(g.state.value.status == CaptureStatus.WAITING) "Čekám na mikrofon" else if(g.state.value.status == CaptureStatus.PAUSED) "Pozastaveno" else "Připraveno")
                    setOnClickPendingIntent(R.id.widget_status,openActivity(context))
                }
            }
        }
    }
}
