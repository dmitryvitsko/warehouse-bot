package com.warehousebot.bot.core;

import com.warehousebot.bot.core.Routes.CallbackStage;
import com.warehousebot.bot.session.SessionStore;
import com.warehousebot.domain.Role;
import com.warehousebot.testutil.FakeMessenger;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoutesTest {

    private final List<String> hits = new ArrayList<>();

    private CallbackContext ctx(String data, Role role) {
        return new CallbackContext(1, 10, data, "Иван", role, "q", new SessionStore().get(1), new FakeMessenger());
    }

    private void run(Routes r, CallbackStage stage, CallbackContext ctx) throws Exception {
        Handlers.CallbackHandler h = r.findCallback(stage, ctx);
        if (h != null) h.handle(ctx);
    }

    @Test
    void exactAndPrefixRoutes() throws Exception {
        Routes r = new Routes();
        r.onCallback("W_MAIN_MENU").handle(c -> hits.add("exact"));
        r.onCallbackPrefix("W_TAKE:").handle(c -> hits.add("take " + c.intPart(1)));

        run(r, CallbackStage.AFTER_BIND_CHECK, ctx("W_MAIN_MENU", Role.WORKER));
        run(r, CallbackStage.AFTER_BIND_CHECK, ctx("W_TAKE:7", Role.WORKER));

        assertEquals(List.of("exact", "take 7"), hits);
    }

    @Test
    void adminGuardFallsThroughForWorkers() {
        Routes r = new Routes();
        r.onCallbackPrefix("T_RET_T:").beforeBindCheck().adminOnly().handle(c -> hits.add("admin"));

        assertNull(r.findCallback(CallbackStage.BEFORE_BIND_CHECK, ctx("T_RET_T:1", Role.WORKER)));
        assertEquals(1, r.findCallback(CallbackStage.BEFORE_BIND_CHECK, ctx("T_RET_T:1", Role.ADMIN)) != null ? 1 : 0);
    }

    @Test
    void stagesAreSeparate() {
        Routes r = new Routes();
        r.onCallback("SCHED_MAIN").beforeBindCheck().handle(c -> hits.add("x"));
        assertNull(r.findCallback(CallbackStage.AFTER_BIND_CHECK, ctx("SCHED_MAIN", Role.WORKER)));
    }

    @Test
    void duplicateCommandIsRejected() {
        Routes r = new Routes();
        r.onCommand("/start", c -> { });
        assertThrows(IllegalStateException.class, () -> r.onCommand("/start", c -> { }));
    }

    @Test
    void payloadKeepsColonsInData() {
        CallbackContext c = ctx("ED_SCH_S:08:30-17:30", Role.ADMIN);
        assertEquals("08:30-17:30", c.payload("ED_SCH_S:"));
        assertEquals("08", c.part(1));
    }
}
