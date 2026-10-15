package com.warehousebot.bot.feature.schedule;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateInputParserTest {

    private static final LocalDate NOV = LocalDate.of(2026, 11, 5);
    private static final LocalDate DEC = LocalDate.of(2026, 12, 20);

    @Test
    void singleDateFormats() {
        assertEquals(List.of(LocalDate.of(2026, 11, 15)), DateInputParser.parse("15.11", NOV).orElseThrow());
        assertEquals(List.of(LocalDate.of(2026, 11, 15)), DateInputParser.parse("15.11.26", NOV).orElseThrow());
        assertEquals(List.of(LocalDate.of(2027, 11, 15)), DateInputParser.parse("15.11.2027", NOV).orElseThrow());
    }

    @Test
    void januaryWithoutYearInDecemberIsNextYear() {
        assertEquals(List.of(LocalDate.of(2027, 1, 3)), DateInputParser.parse("03.01", DEC).orElseThrow());
    }

    @Test
    void decemberWithoutYearInJanuaryIsPreviousYear() {
        LocalDate jan = LocalDate.of(2027, 1, 4);
        List<LocalDate> dates = DateInputParser.parse("29.12-03.01", jan).orElseThrow();
        assertEquals(LocalDate.of(2026, 12, 29), dates.get(0));
        assertEquals(LocalDate.of(2027, 1, 3), dates.get(dates.size() - 1));
        assertEquals(6, dates.size());
    }

    @Test
    void combinedItemsAreSortedAndDistinct() {
        List<LocalDate> dates = DateInputParser.parse("20.11, 14.11-16.11, 15.11", NOV).orElseThrow();
        assertEquals(List.of(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 20)), dates);
    }

    @Test
    void decemberToJanuaryRangeWrapsYear() {
        List<LocalDate> dates = DateInputParser.parse("30.12-02.01", NOV).orElseThrow();
        assertEquals(4, dates.size());
        assertEquals(LocalDate.of(2026, 12, 30), dates.get(0));
        assertEquals(LocalDate.of(2027, 1, 2), dates.get(3));
    }

    @Test
    void reversedRangeIsAnError() {
        assertTrue(DateInputParser.parse("22.10-15.10", NOV).isEmpty());
        assertTrue(DateInputParser.parse("10.11, 22.10-15.10", NOV).isEmpty());
    }

    @Test
    void garbageIsAnError() {
        for (String bad : new String[]{"", " , ", "завтра", "15", "32.11", "15.13", "15.11-", "aa.bb"}) {
            Optional<List<LocalDate>> r = DateInputParser.parse(bad, NOV);
            assertTrue(r.isEmpty(), bad);
        }
    }
}
