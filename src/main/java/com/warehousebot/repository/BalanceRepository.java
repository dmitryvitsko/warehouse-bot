package com.warehousebot.repository;

import com.warehousebot.domain.HeldMaterial;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.db.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Таблица employee_balances — материалы в подотчёте у сотрудников. */
public final class BalanceRepository {

    private static final RowMapper<HeldMaterial> HELD = rs -> new HeldMaterial(rs.getInt("id"), rs.getString("code"),
            rs.getString("name"), rs.getString("work_unit"), rs.getDouble("quantity"), rs.getDouble("price_with_vat"));

    private static final String HELD_SELECT = """
            SELECT m.id, m.code, m.name, m.work_unit, m.price_with_vat, b.quantity
            FROM employee_balances b
            JOIN materials m ON b.material_id = m.id
            """;

    /** Материал на руках у конкретного сотрудника (с его именем из users). */
    public record Holding(String workerName, String code, String name, String unit, double quantity, double priceWithVat) {
    }

    /** Строка подотчёта для отчёта (с коэффициентом пересчёта в бухгалтерские единицы). */
    public record ReportRow(String workerName, String code, String name, double quantity, String workUnit,
                            double priceWithVat, double convFactor, String originalUnit) {
    }

    private final Database db;

    public BalanceRepository(Database db) {
        this.db = db;
    }

    /** Подотчёт сотрудника (количество > 0.00001), по названию. */
    public List<HeldMaterial> findHeld(long userId) {
        return db.query(HELD_SELECT + "WHERE b.user_id = ? AND b.quantity > 0.00001 ORDER BY m.name", HELD, userId);
    }

    /** Материал из подотчёта, если его количество больше 0.00001. */
    public Optional<HeldMaterial> findHeld(long userId, int materialId) {
        return db.queryOne(HELD_SELECT + "WHERE b.user_id = ? AND m.id = ? AND b.quantity > 0.00001", HELD, userId, materialId);
    }

    /** Материал из подотчёта с любым положительным количеством (выбор позиции для списания). */
    public Optional<HeldMaterial> findPositive(long userId, int materialId) {
        return db.queryOne(HELD_SELECT + "WHERE b.user_id = ? AND m.id = ? AND b.quantity > 0", HELD, userId, materialId);
    }

    /** Запись подотчёта с любым количеством вместе с именем сотрудника. */
    public Optional<Holding> findHolding(long userId, int materialId) {
        return db.queryOne("""
                SELECT b.quantity, m.code, m.name, m.work_unit, m.price_with_vat, u.full_name
                FROM employee_balances b
                JOIN materials m ON b.material_id = m.id
                JOIN users u ON b.user_id = u.id
                WHERE b.user_id = ? AND b.material_id = ?""", BalanceRepository::holding, userId, materialId);
    }

    public Optional<Double> quantity(long userId, int materialId) {
        return db.queryOne("SELECT quantity FROM employee_balances WHERE user_id = ? AND material_id = ?",
                rs -> rs.getDouble("quantity"), userId, materialId);
    }

    /** Добавляет материал в подотчёт. */
    public void increase(long userId, int materialId, double qty) {
        db.update("""
                INSERT INTO employee_balances (user_id, material_id, quantity) VALUES (?, ?, ?)
                ON CONFLICT(user_id, material_id) DO UPDATE SET quantity = ROUND(quantity + excluded.quantity, 6)""",
                userId, materialId, qty);
    }

    /**
     * Уменьшает подотчёт, только если материала хватает (допуск 1e-9, как и раньше).
     * Возвращает {@code false}, если на руках уже меньше.
     */
    public boolean decrease(long userId, int materialId, double qty) {
        return db.update("UPDATE employee_balances SET quantity = ROUND(quantity - ?, 6) WHERE user_id = ? AND material_id = ? AND quantity + 1e-9 >= ?",
                qty, userId, materialId, qty) > 0;
    }

    /** Все материалы на руках у сотрудников: по имени сотрудника, затем по названию. */
    public List<Holding> findAllHoldings() {
        return db.query("""
                SELECT u.full_name, m.code, m.name, m.work_unit, m.price_with_vat, b.quantity
                FROM employee_balances b
                JOIN users u ON b.user_id = u.id
                JOIN materials m ON b.material_id = m.id
                WHERE b.quantity > 0.00001
                ORDER BY u.full_name, m.name""", BalanceRepository::holding);
    }

    /** Все материалы на руках — в естественном порядке таблицы (сверка при загрузке ведомости). */
    public List<Holding> findAllHoldingsUnordered() {
        return db.query("""
                SELECT u.full_name, m.code, m.name, m.work_unit, m.price_with_vat, b.quantity
                FROM employee_balances b
                JOIN materials m ON b.material_id = m.id
                JOIN users u ON b.user_id = u.id
                WHERE b.quantity > 0.00001""", BalanceRepository::holding);
    }

    /** Сотрудники, за которыми числятся материалы. */
    public List<Long> usersWithBalances() {
        return db.query("SELECT DISTINCT user_id FROM employee_balances WHERE quantity > 0.00001", rs -> rs.getLong("user_id"));
    }

    /** Подотчёт всех сотрудников для отчёта. */
    public List<ReportRow> balancesForReport() {
        return db.query("""
                SELECT u.full_name, m.code, m.name, b.quantity, m.work_unit, m.price_with_vat, m.conv_factor, m.original_unit
                FROM employee_balances b
                JOIN users u ON b.user_id = u.id
                JOIN materials m ON b.material_id = m.id
                WHERE b.quantity > 0
                ORDER BY u.full_name, m.name""",
                rs -> new ReportRow(rs.getString("full_name"), rs.getString("code"), rs.getString("name"),
                        rs.getDouble("quantity"), rs.getString("work_unit"), rs.getDouble("price_with_vat"),
                        rs.getDouble("conv_factor"), rs.getString("original_unit")));
    }

    private static Holding holding(ResultSet rs) throws SQLException {
        return new Holding(rs.getString("full_name"), rs.getString("code"), rs.getString("name"),
                rs.getString("work_unit"), rs.getDouble("quantity"), rs.getDouble("price_with_vat"));
    }
}
