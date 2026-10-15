package com.warehousebot.service;

import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.repository.ScheduleRepository;
import com.warehousebot.repository.SickLeaveRepository;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.testutil.TestDb;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SickLeaveServiceTest {

    private final Database db = TestDb.create();
    private final ScheduleRepository schedules = new ScheduleRepository(db);
    private final SickLeaveService service = new SickLeaveService(db, schedules, new SickLeaveRepository(db), new UserRepository(db));

    @Test
    void shortSickLeaveUpdatesScheduleImmediately() {
        SickLeaveService.Report r = service.report(1, "Иванов И.И.", LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 18));
        assertFalse(r.needsApproval());
        assertEquals(10, r.days());
        assertEquals(10, schedules.findMonth("Иванов И.И.", 2026, 11).stream().filter(d -> "Б".equals(d.statusCode())).count());
    }

    @Test
    void longSickLeaveIsDecidedOnlyOnce() {
        SickLeaveService.Report r = service.report(1, "Иванов И.И.", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 11));
        assertTrue(r.needsApproval());
        assertTrue(schedules.findMonth("Иванов И.И.", 2026, 11).isEmpty());

        assertTrue(service.decide(r.requestId(), true).isPresent());
        assertEquals(11, schedules.findMonth("Иванов И.И.", 2026, 11).size());
        assertTrue(service.decide(r.requestId(), false).isEmpty());
        assertTrue(service.decide(9999, true).isEmpty());
    }
}
