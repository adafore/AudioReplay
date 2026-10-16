package cz.audioreplay.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class UpdateFeaturesTest {
    private val day = LocalDate.of(2026,10,16)
    @Test fun firstDailyNameIsOnlyDate() { assertEquals("16.10.2026.m4a",RecordingNames.next(day,emptySet()).first) }
    @Test fun dailyDuplicatesAreNumbered() { assertEquals("16.10.2026 3.m4a",RecordingNames.next(day,setOf("16.10.2026.m4a","16.10.2026 2.m4a")).first) }
    @Test fun deletedNamesDoNotResetStoredSequence() { assertEquals("16.10.2026 8.m4a",RecordingNames.next(day,emptySet(),7).first) }
    @Test fun customNamesAreNotLegacy() { assertFalse(RecordingNames.isLegacy("Schuzka.m4a")); assertFalse(RecordingNames.isLegacy("16.10.2026.m4a")); assertTrue(RecordingNames.isLegacy("AudioReplay_2026-10-15_12-34-56_abc123.m4a")) }
    @Test fun labelsIncludeHalfMinutePreset() { assertEquals("2,5 min",Durations.label(150)); assertEquals("10 min",Durations.label(600)) }
    @Test fun compactAndQuickWidgetsOnlySave() { assertEquals(WidgetLayout.COMPACT,WidgetSizes.layout(65,65)); assertEquals(WidgetLayout.QUICK,WidgetSizes.layout(180,65)) }
    @Test fun largerWidgetsIncludeStartAndStop() { assertEquals(WidgetLayout.WIDE,WidgetSizes.layout(280,65)); assertEquals(WidgetLayout.FULL,WidgetSizes.layout(150,150)) }
    @Test fun validEditKeepsSubsecondPrecision() { val edit = AudioEdit(125000,925000,6); assertEquals(edit,edit.validate(1000000)) }
    @Test fun invalidEditsAreRejected() { listOf(AudioEdit(-1,1000000,0),AudioEdit(0,2000000,0),AudioEdit(900000,950000,0),AudioEdit(0,1000000,30)).forEach { edit -> assertThrows(IllegalArgumentException::class.java) { edit.validate(1000000) } } }
    @Test fun gainSaturatesInsteadOfWrapping() { assertEquals(1000,PcmGain.apply(1000,0)); assertTrue(PcmGain.apply(1000,6) in 1990..2000); assertEquals(32767,PcmGain.apply(30000,18)); assertEquals(-32768,PcmGain.apply(-30000,18)) }
}
