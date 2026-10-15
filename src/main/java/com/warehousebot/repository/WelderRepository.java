package com.warehousebot.repository;

import com.warehousebot.domain.Welder;
import com.warehousebot.domain.WelderAssignment;
import com.warehousebot.domain.WelderHistoryEntry;
import com.warehousebot.infrastructure.db.Database;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Таблицы welders и welders_history (сварочные аппараты и журнал их движения). */
public final class WelderRepository {

    private final Database db;

    public WelderRepository(Database db) {
        this.db = db;
    }

    /** Все аппараты: сначала на базе (ON_BASE), затем в работе (ORDER BY status DESC), по названию. */
    public List<Welder> findAll() {
        return db.query("SELECT w.id, w.name, w.status, u.full_name, w.assigned_time, w.assigned_to " +
                        "FROM welders w LEFT JOIN users u ON w.assigned_to = u.id ORDER BY w.status DESC, w.name",
                rs -> {
                    long assigned = rs.getLong("assigned_to");
                    Long assignedTo = rs.wasNull() ? null : assigned;
                    String holder = rs.getString("full_name");
                    String time = rs.getString("assigned_time");
                    return new Welder(rs.getInt("id"), rs.getString("name"), rs.getString("status"),
                            holder != null ? holder : "", time != null ? time : "", assignedTo);
                });
    }

    public Optional<String> findName(int welderId) {
        return db.queryOne("SELECT name FROM welders WHERE id = ?", rs -> rs.getString("name"), welderId);
    }

    /** Сотрудник, за которым сейчас числится аппарат (только для статуса IN_USE). */
    public Optional<Long> findHolder(int welderId) {
        return db.queryOne("SELECT assigned_to FROM welders WHERE id = ? AND status = 'IN_USE' AND assigned_to IS NOT NULL",
                rs -> rs.getLong("assigned_to"), welderId);
    }

    /** Выдаёт аппарат, только если он на базе. Возвращает число изменённых строк. */
    public int assign(int welderId, long userId) {
        return db.update("UPDATE welders SET status = 'IN_USE', assigned_to = ?, assigned_time = datetime('now', 'localtime') " +
                "WHERE id = ? AND status = 'ON_BASE'", userId, welderId);
    }

    /** Возвращает аппарат на базу, только если он в работе. */
    public int release(int welderId) {
        return db.update("UPDATE welders SET status = 'ON_BASE', assigned_to = NULL, assigned_time = NULL " +
                "WHERE id = ? AND status = 'IN_USE'", welderId);
    }

    /** Возвращает аппарат на базу, только если он в работе именно у {@code userId}. */
    public int releaseFrom(int welderId, long userId) {
        return db.update("UPDATE welders SET status = 'ON_BASE', assigned_to = NULL, assigned_time = NULL " +
                "WHERE id = ? AND status = 'IN_USE' AND assigned_to = ?", welderId, userId);
    }

    /** Перевешивает аппарат с {@code fromUserId} на {@code toUserId}, только если он сейчас у {@code fromUserId}. */
    public int reassign(int welderId, long fromUserId, long toUserId) {
        return db.update("UPDATE welders SET assigned_to = ?, assigned_time = datetime('now', 'localtime') " +
                "WHERE id = ? AND status = 'IN_USE' AND assigned_to = ?", toUserId, welderId, fromUserId);
    }

    public void addHistory(int welderId, long userId, String action, Long adminId) {
        db.update("INSERT INTO welders_history (welder_id, user_id, action, admin_id) VALUES (?, ?, ?, ?)",
                welderId, userId, action, adminId);
    }

    /** Последние записи журнала, самые новые первыми. */
    public List<WelderHistoryEntry> lastHistory(int limit) {
        return db.query("""
                        SELECT datetime(h.action_time, 'localtime') as local_time, w.name, u.full_name, h.action, a.full_name as admin_name
                        FROM welders_history h
                        JOIN welders w ON h.welder_id = w.id
                        JOIN users u ON h.user_id = u.id
                        LEFT JOIN users a ON h.admin_id = a.id
                        ORDER BY h.action_time DESC LIMIT ?
                        """,
                rs -> new WelderHistoryEntry(rs.getString("local_time"), rs.getString("name"), rs.getString("full_name"),
                        rs.getString("action"), rs.getString("admin_name")),
                limit);
    }

    /** Аппараты в работе вместе с именами сотрудников (для бота-надзирателя). */
    public List<WelderAssignment> findInUse() {
        return db.query("SELECT w.id, w.name, w.assigned_to, u.full_name " +
                        "FROM welders w JOIN users u ON w.assigned_to = u.id WHERE w.status = 'IN_USE'",
                rs -> new WelderAssignment(rs.getInt("id"), rs.getString("name"), rs.getLong("assigned_to"),
                        rs.getString("full_name")));
    }

    /** Время окончания смены сотрудника в указанный день из графика schedules_v2 (как записано в Excel). */
    public Optional<String> findShiftEnd(String excelName, LocalDate day) {
        return db.queryOne("SELECT end_time FROM schedules_v2 WHERE excel_name = ? AND year = ? AND month = ? AND day = ?",
                rs -> rs.getString("end_time"), excelName, day.getYear(), day.getMonthValue(), day.getDayOfMonth());
    }
}
