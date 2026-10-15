package com.warehousebot.repository;

import com.warehousebot.domain.OrshCabinet;
import com.warehousebot.domain.OrshProblem;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.db.RowMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Таблица orsh_inspections — план планового осмотра шкафов ОРШ. */
public final class OrshRepository {

    private static final RowMapper<OrshCabinet> CABINET = rs -> new OrshCabinet(rs.getInt("id"),
            rs.getString("orsh_number"), rs.getString("address"), rs.getString("location"));

    private final Database db;

    public OrshRepository(Database db) {
        this.db = db;
    }

    /** Полностью заменяет план новым списком (одной транзакцией). */
    public void replacePlan(List<OrshCabinet> cabinets) {
        List<Object[]> rows = new ArrayList<>(cabinets.size());
        for (OrshCabinet c : cabinets) rows.add(new Object[]{c.number(), c.address(), c.location()});
        db.inTransaction(() -> {
            db.execute("DELETE FROM orsh_inspections");
            if (!rows.isEmpty()) {
                db.batch("INSERT INTO orsh_inspections (orsh_number, address, location) VALUES (?, ?, ?)", rows);
            }
        });
    }

    /** Шкафы, которые ещё не осмотрены, в порядке плана. */
    public List<OrshCabinet> findPending() {
        return db.query("SELECT id, orsh_number, address, location FROM orsh_inspections WHERE status = 'PENDING' ORDER BY id", CABINET);
    }

    public Optional<OrshCabinet> findById(int id) {
        return db.queryOne("SELECT id, orsh_number, address, location FROM orsh_inspections WHERE id = ?", CABINET, id);
    }

    /** Отмечает осмотр: COMPLETED (фото отправлено) или PROBLEM (с причиной). */
    public void markInspected(int id, String workerName, boolean problem, String reason) {
        db.update("UPDATE orsh_inspections SET status = ?, reason = ?, inspected_by = ?, inspected_at = datetime('now', 'localtime') WHERE id = ?",
                problem ? "PROBLEM" : "COMPLETED", reason, workerName, id);
    }

    /** Количество шкафов по статусам. */
    public Map<String, Integer> countByStatus() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        db.query("SELECT status, COUNT(*) AS cnt FROM orsh_inspections GROUP BY status",
                        rs -> Map.entry(String.valueOf(rs.getString("status")), rs.getInt("cnt")))
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    public List<OrshProblem> findProblems() {
        return db.query("SELECT orsh_number, reason, inspected_by FROM orsh_inspections WHERE status = 'PROBLEM'",
                rs -> new OrshProblem(rs.getString("orsh_number"), rs.getString("reason"), rs.getString("inspected_by")));
    }
}
