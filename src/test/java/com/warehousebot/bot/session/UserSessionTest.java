package com.warehousebot.bot.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSessionTest {

    private static final SessionKey<Integer> DIALOG = SessionKey.dialog("dialog");
    private static final SessionKey<String> KEEP = SessionKey.persistent("keep");
    private static final SessionKey<int[]> CUSTOM = SessionKey.custom("custom", v -> {
        v[0] = -1;
        return v;
    });

    @Test
    void resetAppliesKeyPolicies() {
        UserSession s = new SessionStore().get(1L);
        s.put(DIALOG, 5);
        s.put(KEEP, "x");
        s.put(CUSTOM, new int[]{7});

        s.resetDialogs();

        assertFalse(s.has(DIALOG));
        assertEquals("x", s.get(KEEP));
        assertEquals(-1, s.get(CUSTOM)[0]);
    }

    @Test
    void putNullRemoves() {
        UserSession s = new SessionStore().get(1L);
        s.put(DIALOG, 1);
        s.put(DIALOG, null);
        assertFalse(s.has(DIALOG));
    }

    @Test
    void storeReturnsSameSessionPerChat() {
        SessionStore store = new SessionStore();
        assertTrue(store.get(1L) == store.get(1L));
        assertTrue(store.peek(2L).isEmpty());
    }
}
