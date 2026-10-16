package com.warehousebot.repository;

import com.warehousebot.domain.Tool;
import com.warehousebot.domain.ToolGroup;
import com.warehousebot.domain.ToolHolder;
import com.warehousebot.domain.ToolPosition;
import com.warehousebot.domain.UserRef;
import com.warehousebot.domain.WrittenOffTool;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.db.RowMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Таблица tools: учёт инструмента (склад → сотрудник → архив списанного). */
public final class ToolRepository {

    private static final RowMapper<Tool> TOOL =
            rs -> new Tool(rs.getInt("id"), rs.getString("name"), rs.getString("inv_number"));

    private static final RowMapper<ToolPosition> POSITION = rs -> new ToolPosition(
            new Tool(rs.getInt("id"), rs.getString("name"), rs.getString("inv_number")),
            rs.getString("status"), rs.getString("worker_name"));

    private static final String ACTIVE_TOOLS = """
            SELECT t.id, t.name, t.status, u.full_name AS worker_name, t.inv_number
            FROM tools t
            LEFT JOIN users u ON t.assigned_to = u.id
            WHERE t.status != 'WRITTEN_OFF'
            """;

    private final Database db;

    public ToolRepository(Database db) {
        this.db = db;
    }

    // ------------------------------------------------------------------ чтение

    public Optional<Tool> findById(int toolId) {
        return db.queryOne("SELECT id, name, inv_number FROM tools WHERE id = ?", TOOL, toolId);
    }

    /** Весь несписанный инструмент по названию и инв. номеру (аудит). */
    public List<ToolPosition> activeByName() {
        return db.query(ACTIVE_TOOLS + "ORDER BY t.name, t.inv_number", POSITION);
    }

    /** Весь несписанный инструмент: сначала выданный, затем складской (поиск). */
    public List<ToolPosition> activeByStatus() {
        return db.query(ACTIVE_TOOLS + "ORDER BY t.status DESC, t.name", POSITION);
    }

    public List<Tool> assignedTo(long userId) {
        return db.query("SELECT id, name, inv_number FROM tools WHERE status = 'ASSIGNED' AND assigned_to = ? ORDER BY name",
                TOOL, userId);
    }

    /** Группы свободного инструмента на складе (по названию). */
    public List<ToolGroup> inStockGroups() {
        return db.query("SELECT MIN(id) AS first_id, name, COUNT(*) AS cnt FROM tools WHERE status = 'IN_STOCK' GROUP BY name ORDER BY name",
                rs -> new ToolGroup(rs.getInt("first_id"), rs.getString("name"), rs.getInt("cnt")));
    }

    public List<Tool> inStockByName(String name) {
        return db.query("SELECT id, name, inv_number FROM tools WHERE status = 'IN_STOCK' AND name = ? ORDER BY inv_number",
                TOOL, name);
    }

    public Optional<Tool> firstInStockByName(String name) {
        return db.queryOne("SELECT id, name, inv_number FROM tools WHERE name = ? AND status = 'IN_STOCK' LIMIT 1",
                TOOL, name);
    }

    /** Сотрудники, за которыми закреплён инструмент. */
    public List<UserRef> holders() {
        return db.query("""
                SELECT DISTINCT u.id, u.full_name AS d_name
                FROM tools t
                JOIN users u ON t.assigned_to = u.id
                WHERE t.status = 'ASSIGNED'
                ORDER BY d_name""", rs -> new UserRef(rs.getLong("id"), rs.getString("d_name")));
    }

    /** Сотрудники с количеством закреплённого инструмента. */
    public List<ToolHolder> holdersWithCounts() {
        return db.query("SELECT u.id, u.full_name, COUNT(t.id) AS t_count " +
                        "FROM tools t JOIN users u ON t.assigned_to = u.id " +
                        "WHERE t.status = 'ASSIGNED' " +
                        "GROUP BY u.id, u.full_name " +
                        "ORDER BY u.full_name",
                rs -> new ToolHolder(rs.getLong("id"), rs.getString("full_name"), rs.getInt("t_count")));
    }

    public List<Tool> writtenOff() {
        return db.query("SELECT id, name, inv_number FROM tools WHERE status = 'WRITTEN_OFF' ORDER BY name", TOOL);
    }

    public List<WrittenOffTool> writtenOffArchive() {
        return db.query("SELECT name, inv_number, write_off_reason, strftime('%d.%m.%Y', written_off_at) AS wo_date " +
                        "FROM tools WHERE status = 'WRITTEN_OFF' ORDER BY name",
                rs -> new WrittenOffTool(rs.getString("name"), rs.getString("inv_number"),
                        rs.getString("write_off_reason"), rs.getString("wo_date")));
    }

    /**
     * Имя сотрудника для текстов выдачи. Пустое, если сотрудника нет;
     * {@code "null"}, если имя в базе не заполнено (так прежняя версия подставляла его в текст).
     */
    public Optional<String> holderName(long userId) {
        return db.queryOne("SELECT full_name FROM users WHERE id = ?", rs -> String.valueOf(rs.getString("full_name")), userId);
    }

    /** Все непустые инвентарные номера (для импорта без запроса на каждую строку). */
    public Set<String> allInvNumbers() {
        return new HashSet<>(db.query("SELECT inv_number FROM tools WHERE inv_number IS NOT NULL", rs -> rs.getString("inv_number")));
    }

    // ------------------------------------------------------------------ смена статуса (с проверкой текущего)

    /** Выдаёт инструмент, только если он на складе. Возвращает число изменённых строк. */
    public int assign(int toolId, long userId) {
        return db.update("UPDATE tools SET status = 'ASSIGNED', assigned_to = ? WHERE id = ? AND status = 'IN_STOCK'", userId, toolId);
    }

    /** Возвращает на склад, только если инструмент выдан. */
    public int returnToStock(int toolId) {
        return db.update("UPDATE tools SET status = 'IN_STOCK', assigned_to = NULL WHERE id = ? AND status = 'ASSIGNED'", toolId);
    }

    /** Возвращает на склад весь инструмент сотрудника; число возвращённых единиц. */
    public int returnAllOf(long userId) {
        return db.update("UPDATE tools SET status = 'IN_STOCK', assigned_to = NULL WHERE assigned_to = ? AND status = 'ASSIGNED'", userId);
    }

    /** Списывает в архив (со склада или с сотрудника), если ещё не списан. */
    public int writeOff(int toolId, String reason) {
        return db.update("UPDATE tools SET status = 'WRITTEN_OFF', assigned_to = NULL, write_off_reason = ?, " +
                "written_off_at = datetime('now', 'localtime') WHERE id = ? AND status != 'WRITTEN_OFF'", reason, toolId);
    }

    /** Восстанавливает из архива на склад, только если инструмент списан. */
    public int restore(int toolId) {
        return db.update("UPDATE tools SET status = 'IN_STOCK', write_off_reason = NULL, written_off_at = NULL " +
                "WHERE id = ? AND status = 'WRITTEN_OFF'", toolId);
    }

    // ------------------------------------------------------------------ импорт

    /** Новые единицы на склад: строки {name, inv_number}. */
    public void insertAll(List<Object[]> nameAndInv) {
        if (!nameAndInv.isEmpty()) db.batch("INSERT INTO tools (name, inv_number, status) VALUES (?, ?, 'IN_STOCK')", nameAndInv);
    }

    /** Обновление названий существующих единиц: строки {name, inv_number}. Статус не трогается. */
    public void renameAll(List<Object[]> nameAndInv) {
        if (!nameAndInv.isEmpty()) db.batch("UPDATE tools SET name = ? WHERE inv_number = ?", nameAndInv);
    }

    /** Текущий статус единицы инструмента (IN_STOCK / ASSIGNED / WRITTEN_OFF). */
    public java.util.Optional<String> findStatus(int toolId) {
        return db.queryOne("SELECT status FROM tools WHERE id = ?", rs -> rs.getString("status"), toolId);
    }

    /** Полностью удаляет инструмент из базы (при передаче по накладной в другое подразделение). */
    public int deleteById(int toolId) {
        return db.update("DELETE FROM tools WHERE id = ?", toolId);
    }
}
