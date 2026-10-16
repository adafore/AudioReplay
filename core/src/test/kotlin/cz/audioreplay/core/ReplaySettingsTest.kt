package cz.audioreplay.core

import org.junit.Assert.*
import org.junit.Test

class ReplaySettingsTest {
    @Test fun defaultsKeepIndependentLengths() { val s = ReplaySettings(); assertEquals(1800, s.bufferDurationSec); assertEquals(300, s.saveDurationSec) }
    @Test fun changeSaveToTwentyMinutesWithoutChangingBuffer() { val s = ReplaySettings().copy(saveDurationSec = 1200).normalized(); assertEquals(1800, s.bufferDurationSec); assertEquals(1200, s.saveDurationSec) }
    @Test fun shrinkBufferClampsSave() { assertEquals(60, ReplaySettings().withBuffer(60).saveDurationSec) }
    @Test fun invalidPersistedSettingsAreClamped() { val s = ReplaySettings(-1,9000,123).normalized(); assertEquals(10,s.bufferDurationSec); assertEquals(10,s.saveDurationSec); assertEquals(64000,s.bitrate) }
    @Test fun unitConversion() { assertEquals(90, Durations.parse("1,5", true, 3600)); assertEquals(20,Durations.parse("20",false,3600)); assertEquals(10, Durations.parse("0.1667",true,10)) }
    @Test fun invalidAndUnfinishedInputsAreRejected() { listOf("", "-1", "4", "abc", "1..2", "999999999999999999999", "1e100000000").forEach { assertNull(Durations.parse(it,false,3600)) } }
    @Test fun durationsRespectCurrentBuffer() { assertNull(Durations.parse("20",true,300)); assertEquals(300,Durations.parse("5",true,300)) }
    @Test fun byteEstimateUsesLongArithmetic() { assertEquals(57_600_000L,Durations.estimatedBytes(3600,128000)) }
    @Test fun confirmationIsIndependentForEachInput() { val gate = PressGate(); assertFalse(gate.accept("tile",1000,true)); assertFalse(gate.accept("widget",1100,true)); assertTrue(gate.accept("tile",1200,true)); assertFalse(gate.accept("tile",1300,true)) }
    @Test fun confirmationExpiresAndUsesMonotonicOrder() { val gate = PressGate(); assertFalse(gate.accept("a",1000,true)); assertFalse(gate.accept("a",2000,true)); assertTrue(gate.accept("a",2100,true)); assertFalse(gate.accept("a",100,true)); assertFalse(gate.accept("a",50,true)) }
    @Test fun confirmationCanBeDisabled() { assertTrue(PressGate().accept("app",0,false)) }
}
