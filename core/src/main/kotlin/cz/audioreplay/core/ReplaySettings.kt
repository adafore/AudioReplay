package cz.audioreplay.core

data class ReplaySettings(
    val bufferDurationSec: Int = 1800,
    val saveDurationSec: Int = 300,
    val bitrate: Int = 64000,
    val publicStorage: Boolean = false,
    val vibration: Boolean = true,
    val doublePress: Boolean = false,
    val privateNotification: Boolean = false,
    val tileEnabled: Boolean = true,
    val widgetEnabled: Boolean = true,
    val diagnostics: Boolean = false,
    val onboardingDone: Boolean = false,
    val notificationDetails: Boolean = true,
    val notificationActions: Boolean = true,
) {
    fun normalized(): ReplaySettings = copy(
        bufferDurationSec = bufferDurationSec.coerceIn(10, 3600),
        saveDurationSec = saveDurationSec.coerceIn(10, bufferDurationSec.coerceIn(10, 3600)),
        bitrate = bitrate.takeIf { it in setOf(32000, 64000, 128000) } ?: 64000,
    )
    fun withBuffer(seconds: Int): ReplaySettings = copy(bufferDurationSec = seconds).normalized()
}

object Durations {
    fun parse(text: String, minutes: Boolean, max: Int): Int? {
        val input = text.trim()
        if (!input.matches(Regex("[0-9]{1,6}([.,][0-9]{0,6})?"))) return null
        val value = input.replace(',', '.').toBigDecimalOrNull() ?: return null
        val seconds = value.multiply(if (minutes) 60.toBigDecimal() else 1.toBigDecimal())
        return try { seconds.setScale(0, java.math.RoundingMode.HALF_UP).intValueExact().takeIf { it in 10..max } } catch (_: ArithmeticException) { null }
    }
    fun label(seconds: Int): String = when {
        seconds % 60 == 0 -> "${seconds / 60} min"
        seconds >= 60 && seconds % 30 == 0 -> "${seconds / 60},5 min"
        else -> "$seconds s"
    }
    fun estimatedBytes(seconds: Int, bitrate: Int): Long = seconds.toLong() * bitrate / 8
}

/** Every input has an independent confirmation window, based on monotonic time. */
class PressGate(private val windowMs: Long = 900) {
    private val last = mutableMapOf<String, Long>()
    @Synchronized fun accept(source: String, nowMs: Long, confirmation: Boolean): Boolean {
        if (!confirmation) return true
        val previous = last.remove(source)
        return if (previous != null && nowMs - previous in 0..windowMs) true
        else { last[source] = nowMs; false }
    }
}
