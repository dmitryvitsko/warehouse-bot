package com.warehousebot.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatsTest {

    @Test
    void qtyMatchesLegacyFormat() {
        assertEquals("0", Formats.qty(0.0000000001));
        assertEquals("5", Formats.qty(5.0));
        assertEquals("0.05", Formats.qty(0.05));
        assertEquals("1.2346", Formats.qty(1.23456));
    }

    @Test
    void priceMatchesLegacyFormat() {
        assertEquals("10.80", Formats.price(10.8));
        assertEquals("0.1235", Formats.price(0.12346));
    }

    @Test
    void cutAddsEllipsis() {
        assertEquals("abc…", Formats.cut("abcdef", 3));
        assertEquals("abc", Formats.cut("abc", 3));
    }

    @Test
    void parsesCommaDecimal() {
        assertEquals(0.5, Formats.parseUserNumber(" 0,5 "));
    }
}
