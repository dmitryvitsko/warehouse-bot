package com.warehousebot.repository;

import com.warehousebot.domain.Material;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.db.RowMapper;

import java.util.List;
import java.util.Optional;

/** Таблица materials — склад МОЛ (остатки и цены из оборотной ведомости). */
public final class MaterialRepository {

    private static final RowMapper<Material> MAPPER = rs -> new Material(rs.getInt("id"), rs.getString("account_number"),
            rs.getString("code"), rs.getString("name"), rs.getString("work_unit"), rs.getDouble("price_with_vat"),
            rs.getDouble("warehouse_qty"), rs.getString("batch_info"));

    private static final String COLUMNS = "id, account_number, code, name, work_unit, price_with_vat, warehouse_qty, batch_info";

    /** Строка остатков склада для отчёта (количество в рабочих единицах). */
    public record StockRow(String code, String name, double quantity, String workUnit, double priceWithVat,
                           double convFactor, String originalUnit) {
    }

    /** Позиция оборотной ведомости, подготовленная к сохранению. */
    public record ImportRow(String account, String code, String name, String originalUnit, String workUnit,
                            double convFactor, double priceWithVat, double warehouseQty, String batchInfo,
                            double startQty, double startSum, double inQty, double outQty, double endSum) {
    }

    private final Database db;

    public MaterialRepository(Database db) {
        this.db = db;
    }

    /** Материалы с ненулевым остатком на складе: по счёту, затем по названию. */
    public List<Material> findAvailable() {
        return db.query("SELECT " + COLUMNS + " FROM materials WHERE warehouse_qty > 0 ORDER BY account_number, name", MAPPER);
    }

    public Optional<Material> findById(int id) {
        return db.queryOne("SELECT " + COLUMNS + " FROM materials WHERE id = ?", MAPPER, id);
    }

    /**
     * Уменьшает остаток склада, только если его хватает (допуск 1e-9, как и раньше).
     * Возвращает {@code false}, если остатка уже недостаточно.
     */
    public boolean decreaseStock(int id, double qty) {
        return db.update("UPDATE materials SET warehouse_qty = ROUND(warehouse_qty - ?, 6) WHERE id = ? AND warehouse_qty + 1e-9 >= ?",
                qty, id, qty) > 0;
    }

    public void increaseStock(int id, double qty) {
        db.update("UPDATE materials SET warehouse_qty = ROUND(warehouse_qty + ?, 6) WHERE id = ?", qty, id);
    }

    /** Полная очистка склада. */
    public void deleteAll() {
        db.execute("DELETE FROM materials");
    }

    /** Перед загрузкой новой ведомости обнуляет остатки и обороты всех позиций. */
    public void resetBeforeImport() {
        db.execute("UPDATE materials SET warehouse_qty = 0, start_qty = 0, in_qty = 0, out_qty = 0, start_sum = 0, end_sum = 0;");
    }

    /** Добавляет новые позиции и обновляет существующие (по инвентарному номеру). */
    public void upsert(List<ImportRow> rows) {
        List<Object[]> params = rows.stream().map(r -> new Object[]{
                r.account(), r.code(), r.name(), r.originalUnit(), r.workUnit(), r.convFactor(), r.priceWithVat(),
                r.warehouseQty(), r.batchInfo(), r.startQty(), r.startSum(), r.inQty(), r.outQty(), r.endSum()}).toList();
        db.batch("""
                INSERT INTO materials (
                    account_number, code, name, original_unit, work_unit,
                    conv_factor, price_with_vat, warehouse_qty, batch_info,
                    start_qty, start_sum, in_qty, out_qty, end_sum
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(code) DO UPDATE SET
                    account_number = excluded.account_number,
                    name = excluded.name,
                    original_unit = excluded.original_unit,
                    work_unit = excluded.work_unit,
                    conv_factor = excluded.conv_factor,
                    price_with_vat = excluded.price_with_vat,
                    warehouse_qty = excluded.warehouse_qty,
                    batch_info = excluded.batch_info,
                    start_qty = excluded.start_qty,
                    start_sum = excluded.start_sum,
                    in_qty = excluded.in_qty,
                    out_qty = excluded.out_qty,
                    end_sum = excluded.end_sum""", params);
    }

    /** Остатки склада МОЛ для отчёта. */
    public List<StockRow> stockForReport() {
        return db.query("SELECT code, name, warehouse_qty, work_unit, price_with_vat, conv_factor, original_unit FROM materials WHERE warehouse_qty > 0",
                rs -> new StockRow(rs.getString("code"), rs.getString("name"), rs.getDouble("warehouse_qty"),
                        rs.getString("work_unit"), rs.getDouble("price_with_vat"), rs.getDouble("conv_factor"),
                        rs.getString("original_unit")));
    }
}
