package cz.audioreplay.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduleClockTest {
    private val zone = ZoneId.of("Europe/Prague")
    private val friday = LocalDate.of(2026,10,30)
    private fun instant(day: LocalDate,time: String) = day.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun rule(id: String,start: String,end: String,days: Set<DayOfWeek> = setOf(DayOfWeek.FRIDAY)) = ScheduleRule(id,LocalTime.parse(start),LocalTime.parse(end),days = days)
    private val morning get() = rule("am","07:00","12:00")
    private val afternoon get() = rule("pm","12:30","22:00")
    private fun decision(time: String,rules: List<ScheduleRule> = listOf(morning,afternoon)) = ScheduleClock.evaluate(ScheduleConfig(true,rules),instant(friday,time),zone)

    @Test fun disabledPlanHasNoWakeup() {
        assertEquals(ScheduleDecision(false,null),ScheduleClock.evaluate(ScheduleConfig(false,listOf(morning)),instant(friday,"08:00"),zone))
    }
    @Test fun fridayStartsAtSeven() {
        assertEquals(ScheduleDecision(false,instant(friday,"07:00")),decision("06:59"))
        assertEquals(ScheduleDecision(true,instant(friday,"12:00")),decision("07:00"))
    }
    @Test fun lunchGapStopsAndHasNextStart() { assertEquals(ScheduleDecision(false,instant(friday,"12:30")),decision("12:00")) }
    @Test fun afternoonEndsAtTwentyTwo() { assertEquals(ScheduleDecision(true,instant(friday,"22:00")),decision("12:30")) }
    @Test fun weeklyRepeatUsesNextFriday() { assertEquals(ScheduleDecision(false,instant(friday.plusWeeks(1),"07:00")),decision("22:00")) }
    @Test fun multipleWeekdaysChooseNearestLocalDay() {
        val r = rule("days","07:00","08:00",setOf(DayOfWeek.MONDAY,DayOfWeek.WEDNESDAY))
        assertEquals(instant(friday.plusDays(3),"07:00"),ScheduleClock.evaluate(ScheduleConfig(true,listOf(r)),instant(friday,"09:00"),zone).nextChange)
    }
    @Test fun oneTimeDateRunsOnlyOnce() {
        val r = morning.copy(date = friday,days = emptySet())
        assertTrue(decision("08:00",listOf(r)).record)
        assertEquals(ScheduleDecision(false,null),decision("12:00",listOf(r)))
    }
    @Test fun overnightExtendsIntoNextDay() {
        val r = rule("night","22:00","02:00")
        assertEquals(ScheduleDecision(true,instant(friday.plusDays(1),"02:00")),ScheduleClock.evaluate(ScheduleConfig(true,listOf(r)),instant(friday.plusDays(1),"01:00"),zone))
    }
    @Test fun overlappingRulesFormOneContinuousWindow() {
        val r = rule("overlap","11:00","14:00")
        assertEquals(ScheduleDecision(true,instant(friday,"14:00")),decision("08:00",listOf(morning,r)))
    }
    @Test fun touchingRulesDoNotStopAtTheirBoundary() {
        val r = rule("next","12:00","14:00")
        assertEquals(ScheduleDecision(true,instant(friday,"14:00")),decision("11:59",listOf(morning,r)))
    }
    @Test fun disabledIntervalsAreIgnored() {
        assertEquals(instant(friday,"12:30"),decision("08:00",listOf(morning.copy(enabled = false),afternoon)).nextChange)
    }
    @Test fun invalidAndDuplicateRulesAreRejected() {
        listOf(morning.copy(end = morning.start),morning.copy(days = emptySet()),morning.copy(date = friday)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { ScheduleConfig(true,listOf(morning,morning)).validate() }
    }
    @Test fun springClockGapMovesNonexistentTimeForward() {
        val date = LocalDate.of(2026,3,29)
        val r = ScheduleRule("spring",LocalTime.of(2,30),LocalTime.of(4,30),date)
        assertEquals(date.atTime(3,30).atZone(zone).toInstant(),ScheduleClock.evaluate(ScheduleConfig(true,listOf(r)),instant(date,"01:00"),zone).nextChange)
    }
    @Test fun autumnRepeatedHourUsesFirstOccurrenceAndRealEnd() {
        val date = LocalDate.of(2026,10,25)
        val r = ScheduleRule("autumn",LocalTime.of(2,30),LocalTime.of(3,30),date)
        val interval = r.occurrence(date,zone)
        assertEquals(Duration.ofHours(2),Duration.between(interval.start,interval.end))
        assertTrue(ScheduleClock.evaluate(ScheduleConfig(true,listOf(r)),interval.start.plusSeconds(3600),zone).record)
    }
    @Test fun timezoneChangeKeepsLocalClockTime() {
        val r = morning.copy(date = friday,days = emptySet())
        val config = ScheduleConfig(true,listOf(r))
        val utc = ZoneId.of("UTC")
        val before = instant(friday,"01:00")
        assertEquals(Instant.parse("2026-10-30T07:00:00Z"),ScheduleClock.evaluate(config,before,utc).nextChange)
        assertEquals(Instant.parse("2026-10-30T06:00:00Z"),ScheduleClock.evaluate(config,before,zone).nextChange)
    }
    @Test fun sundayNightCanRunOnMonday() {
        val r = rule("week","23:00","01:00",setOf(DayOfWeek.SUNDAY))
        val monday = LocalDate.of(2026,11,2)
        assertEquals(ScheduleDecision(true,instant(monday,"01:00")),ScheduleClock.evaluate(ScheduleConfig(true,listOf(r)),instant(monday,"00:30"),zone))
    }
}
