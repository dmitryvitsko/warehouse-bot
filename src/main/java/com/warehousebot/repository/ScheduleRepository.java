package com.warehousebot.repository;

import com.warehousebot.domain.ShiftDay;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.db.RowMapper;

import java.util.ArrayList;
import java.util.List;

/** Таблица schedules_v2 — график работ по дням (одна строка на сотрудника и день). */
public final class ScheduleRepository {

    private static final String UPSERT = "INSERT OR REPLACE INTO schedules_v2 (excel_name, year, month, day, status_code, start_time, end_time) VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final RowMapper<ShiftDay> MAPPER = rs -> new ShiftDay(rs.getString("excel_name"), rs.getInt("day"),
            rs.getString("status_code"), rs.getString("start_time"), rs.getString("end_time"));

    private final Database db;

    public ScheduleRepository(Database db) {
        this.db = db;
    }

    /** График одного сотрудника на месяц, по дням. */
    public List<ShiftDay> findMonth(String excelName, int year, int month) {
        return db.query("SELECT excel_name, day, status_code, start_time, end_time FROM schedules_v2 WHERE excel_name = ? AND year = ? AND month = ? ORDER BY day ASC",
                MAPPER, excelName, year, month);
    }

    /**
     * График всех сотрудников на месяц: по дням, внутри дня — в порядке вставки (rowid),
     * как раньше возвращал запрос «кто работает в этот день» без ORDER BY.
     */
    public List<ShiftDay> findMonthAll(int year, int month) {
        return db.query("SELECT excel_name, day, status_code, start_time, end_time FROM schedules_v2 WHERE year = ? AND month = ? ORDER BY day, rowid",
                MAPPER, year, month);
    }

    /** Все сотрудники на конкретный день, по фамилии. */
    public List<ShiftDay> findDay(int year, int month, int day) {
        return db.query("SELECT excel_name, day, status_code, start_time, end_time FROM schedules_v2 WHERE year = ? AND month = ? AND day = ? ORDER BY excel_name",
                MAPPER, year, month, day);
    }

    public boolean existsInMonth(String excelName, int year, int month) {
        return db.queryOne("SELECT count(*) FROM schedules_v2 WHERE excel_name = ? AND year = ? AND month = ?",
                rs -> rs.getInt(1) > 0, excelName, year, month).orElse(false);
    }

    /** Ставит (или заменяет) смену/статус на один день. */
    public void upsert(String excelName, int year, int month, int day, String statusCode, String startTime, String endTime) {
        db.update(UPSERT, excelName, year, month, day, statusCode, startTime, endTime);
    }

    /** Пакетная запись дней месяца с заменой существующих. */
    public void upsertAll(List<ShiftDay> days, int year, int month) {
        List<Object[]> rows = new ArrayList<>(days.size());
        for (ShiftDay d : days) {
            rows.add(new Object[]{d.excelName(), year, month, d.day(), d.statusCode(), d.startTime(), d.endTime()});
        }
        db.batch(UPSERT, rows);
    }

    /** Пакетная вставка новых дней (без замены — для нового сотрудника). */
    public void insertAll(List<ShiftDay> days, int year, int month) {
        List<Object[]> rows = new ArrayList<>(days.size());
        for (ShiftDay d : days) {
            rows.add(new Object[]{d.excelName(), year, month, d.day(), d.statusCode(), d.startTime(), d.endTime()});
        }
        db.batch("INSERT INTO schedules_v2 (excel_name, year, month, day, status_code, start_time, end_time) VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
    }
}
