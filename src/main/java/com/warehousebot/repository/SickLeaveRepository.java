package com.warehousebot.repository;

import com.warehousebot.domain.SickLeaveRequest;
import com.warehousebot.infrastructure.db.Database;

import java.util.Optional;

/** Таблица sick_leave_requests — больничные, ожидающие подтверждения администратора. */
public final class SickLeaveRepository {

    private final Database db;

    public SickLeaveRepository(Database db) {
        this.db = db;
    }

    /** Создаёт запрос со статусом PENDING и возвращает его id. */
    public long create(long chatId, String excelName, String startDate, String endDate) {
        return db.insert("INSERT INTO sick_leave_requests (chat_id, excel_name, start_date, end_date) VALUES (?, ?, ?, ?)",
                chatId, excelName, startDate, endDate);
    }

    public Optional<SickLeaveRequest> find(long id) {
        return db.queryOne("SELECT id, chat_id, excel_name, start_date, end_date, status FROM sick_leave_requests WHERE id = ?",
                rs -> new SickLeaveRequest(rs.getLong("id"), rs.getLong("chat_id"), rs.getString("excel_name"),
                        rs.getString("start_date"), rs.getString("end_date"), rs.getString("status")), id);
    }

    /** Меняет статус, только если запрос ещё не обработан. {@code false} — его уже обработал кто-то другой. */
    public boolean resolveIfPending(long id, String status) {
        return db.update("UPDATE sick_leave_requests SET status = ? WHERE id = ? AND status = 'PENDING'", status, id) > 0;
    }
}
