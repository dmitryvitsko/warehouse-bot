package com.warehousebot.bot.feature.welders;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WelderMonitorTest {

    @Test
    void parsesExcelTimeFormats() {
        assertEquals(LocalTime.of(17, 30), WelderMonitor.parseTimeSafe("17:30"));
        assertEquals(LocalTime.of(17, 30), WelderMonitor.parseTimeSafe(" 17.30 "));
        assertEquals(LocalTime.of(9, 0), WelderMonitor.parseTimeSafe("9:00"));
        assertEquals(LocalTime.of(17, 30), WelderMonitor.parseTimeSafe("17:30:00"));
    }

    @Test
    void returnsNullForEmptyOrInvalid() {
        assertNull(WelderMonitor.parseTimeSafe(null));
        assertNull(WelderMonitor.parseTimeSafe("  "));
        assertNull(WelderMonitor.parseTimeSafe("В"));
        assertNull(WelderMonitor.parseTimeSafe("25:00"));
    }

    @Test
    void warnsOnlyWithinFifteenMinutesBeforeShiftEnd() {
        LocalTime end = LocalTime.of(17, 30);
        assertEquals(15, WelderMonitor.minutesLeft(LocalTime.of(17, 15), end));
        assertTrue(WelderMonitor.isWarningDue(WelderMonitor.minutesLeft(LocalTime.of(17, 15), end)));
        assertTrue(WelderMonitor.isWarningDue(WelderMonitor.minutesLeft(LocalTime.of(17, 30), end)));
        // неполная минута после конца смены усекается до 0 — как и раньше
        assertTrue(WelderMonitor.isWarningDue(WelderMonitor.minutesLeft(LocalTime.of(17, 30, 40), end)));
        assertFalse(WelderMonitor.isWarningDue(WelderMonitor.minutesLeft(LocalTime.of(17, 14), end)));
        assertFalse(WelderMonitor.isWarningDue(WelderMonitor.minutesLeft(LocalTime.of(17, 31), end)));
    }
}
