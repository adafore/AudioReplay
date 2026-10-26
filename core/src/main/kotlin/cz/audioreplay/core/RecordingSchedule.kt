package cz.audioreplay.core

import java.time.*

data class ScheduleRule(
    val id: String,
    val start: LocalTime,
    val end: LocalTime,
    val date: LocalDate? = null,
    val days: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
) {
    fun validate(): ScheduleRule {
        require(id.isNotBlank() && id.length <= 100) { "Neplatný identifikátor intervalu." }
        require((date != null && days.isEmpty()) || (date == null && days.isNotEmpty())) { "Vyberte datum nebo dny v týdnu." }
        require(start != end) { "Začátek a konec musí být různé časy." }
        return this
    }
    fun occurrence(day: LocalDate, zone: ZoneId): ScheduleInterval {
        val lastDay = if (end < start) day.plusDays(1) else day
        return ScheduleInterval(day.atTime(start).atZone(zone).toInstant(),lastDay.atTime(end).atZone(zone).toInstant())
    }
}
data class ScheduleInterval(val start: Instant,val end: Instant)
data class ScheduleConfig(val enabled: Boolean = false,val rules: List<ScheduleRule> = emptyList()) {
    fun validate(): ScheduleConfig {
        require(rules.size <= 64 && rules.map { it.id }.distinct().size == rules.size) { "Plán může obsahovat nejvýše 64 různých intervalů." }
        rules.forEach { it.validate() }
        return this
    }
}
data class ScheduleDecision(val record: Boolean,val nextChange: Instant?)

object ScheduleClock {
    /** Local calendar times, including overnight ranges and DST. Overlapping/touching
     * intervals form one window so a rule boundary cannot reset an active buffer. */
    fun evaluate(config: ScheduleConfig, now: Instant, zone: ZoneId): ScheduleDecision {
        config.validate()
        if (!config.enabled) return ScheduleDecision(false,null)
        val today = now.atZone(zone).toLocalDate()
        val intervals = config.rules.filter { it.enabled }.flatMap { rule ->
            val dates = rule.date?.let { listOf(it) } ?: ( -1L..8L ).map { today.plusDays(it) }.filter { it.dayOfWeek in rule.days }
            dates.map { rule.occurrence(it,zone) }.filter { it.end > it.start && it.end > now }
        }.sortedBy { it.start }
        var until = intervals.filter { it.start <= now }.maxOfOrNull { it.end }
        if (until != null) {
            for (interval in intervals) if (interval.start <= until && interval.end > until) until = interval.end
            return ScheduleDecision(true,until)
        }
        return ScheduleDecision(false,intervals.firstOrNull { it.start > now }?.start)
    }
}
