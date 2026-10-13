package com.warehousebot.repository;

import com.warehousebot.domain.PendingReturn;
import com.warehousebot.domain.ReturnRequest;
import com.warehousebot.infrastructure.db.Database;

import java.util.List;
import java.util.Optional;

/** Таблица return_requests — заявки мастеров на возврат материалов на склад. */
public final class ReturnRequestRepository {

    private final Database db;

    public ReturnRequestRepository(Database db) {
        this.db = db;
    }

    public List<PendingReturn> findPending() {
        return db.query("SELECT r.id, u.full_name, m.name, m.code, r.quantity, m.work_unit " +
                        "FROM return_requests r JOIN users u ON r.user_id = u.id JOIN materials m ON r.material_id = m.id " +
                        "WHERE r.status = 'PENDING' ORDER BY r.created_at ASC",
                rs -> new PendingReturn(rs.getInt("id"), rs.getString("full_name"), rs.getString("name"),
                        rs.getString("code"), rs.getDouble("quantity"), rs.getString("work_unit")));
    }

    /** Удаляет все зависшие заявки (бывшая команда /fix). */
    public int deleteAllPending() {
        return db.update("DELETE FROM return_requests WHERE status = 'PENDING'");
    }

    /** Сколько этого материала сотрудник уже заявил к возврату (заявки ещё не рассмотрены). */
    public double pendingQuantity(long userId, int materialId) {
        return db.queryOne("SELECT COALESCE(SUM(quantity), 0) FROM return_requests WHERE user_id = ? AND material_id = ? AND status = 'PENDING'",
                rs -> rs.getDouble(1), userId, materialId).orElse(0.0);
    }

    /** Создаёт заявку со статусом PENDING и возвращает её номер. */
    public int insertPending(long userId, int materialId, double qty) {
        return (int) db.insert("INSERT INTO return_requests (user_id, material_id, quantity, status) VALUES (?, ?, ?, 'PENDING')",
                userId, materialId, qty);
    }

    /** Заявка вместе с материалом и сотрудником. */
    public Optional<ReturnRequest> findById(int id) {
        return db.queryOne("""
                SELECT r.id, r.user_id, r.material_id, r.quantity, r.status,
                       m.code, m.name, m.work_unit, m.price_with_vat, u.full_name
                FROM return_requests r
                JOIN materials m ON r.material_id = m.id
                JOIN users u ON r.user_id = u.id
                WHERE r.id = ?""",
                rs -> new ReturnRequest(rs.getInt("id"), rs.getLong("user_id"), rs.getInt("material_id"),
                        rs.getDouble("quantity"), rs.getString("status"), rs.getString("code"), rs.getString("name"),
                        rs.getString("work_unit"), rs.getDouble("price_with_vat"), rs.getString("full_name")), id);
    }

    /**
     * Переводит заявку из PENDING в {@code status}.
     * Возвращает {@code false}, если заявку уже обработал кто-то другой.
     */
    public boolean resolvePending(int id, String status) {
        return db.update("UPDATE return_requests SET status = ? WHERE id = ? AND status = 'PENDING'", status, id) > 0;
    }
}
