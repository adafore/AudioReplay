package cz.audioreplay.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.pow

object RecordingNames {
    fun next(day: LocalDate, used: Set<String>, lastSequence: Int = 0): Pair<String, Int> {
        val date = day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        var sequence = (lastSequence + 1).coerceAtLeast(1)
        while (true) {
            val name = date + if (sequence == 1) ".m4a" else " $sequence.m4a"
            if (name !in used) return name to sequence
            sequence++
        }
    }
    fun isLegacy(name: String): Boolean = name.matches(Regex("AudioReplay_[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}_[a-zA-Z0-9]{6}\\.m4a"))
}

enum class WidgetLayout { COMPACT, QUICK, WIDE, FULL }
object WidgetSizes {
    fun layout(widthDp: Int, heightDp: Int): WidgetLayout = when {
        widthDp >= 140 && heightDp >= 140 -> WidgetLayout.FULL
        widthDp >= 260 -> WidgetLayout.WIDE
        widthDp >= 160 -> WidgetLayout.QUICK
        else -> WidgetLayout.COMPACT
    }
}

data class AudioEdit(val startUs: Long, val endUs: Long, val gainDb: Int) {
    fun validate(durationUs: Long): AudioEdit {
        require(startUs >= 0 && endUs <= durationUs && endUs - startUs >= 100_000) { "Vyberte úsek alespoň 0,1 s uvnitř nahrávky." }
        require(gainDb in 0..18) { "Zesílení musí být 0–18 dB." }
        return this
    }
}
object PcmGain {
    fun apply(sample: Int, gainDb: Int): Int = (sample * 10.0.pow(gainDb / 20.0)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
}
