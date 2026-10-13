package com.warehousebot.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class HtmlTest {

    @Test
    void escapesSpecialCharacters() {
        assertEquals("Fujikura 70S &amp; сумка &lt;new&gt;", Html.esc("Fujikura 70S & сумка <new>"));
    }

    @Test
    void returnsSameInstanceWhenNothingToEscape() {
        String s = "Иванов И.И.";
        assertSame(s, Html.esc(s));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", Html.esc(null));
    }
}
