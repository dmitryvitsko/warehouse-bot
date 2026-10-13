package com.warehousebot.repository;

import com.warehousebot.domain.WriteOffItem;
import com.warehousebot.infrastructure.db.Database;

import java.util.List;
import java.util.Optional;

/** Таблица transactions — журнал выдач (ISSUE), возвратов (RETURN) и списаний (WRITE_OFF). */
public final class TransactionRepository {

    /** Последнее техническое закрытие по абоненту: код закрытия и дата. */
    public record LastClosing(String closingCode, String date) {
    }

    /** Списание по квитанции — строка отчёта. */
    public record PaidRow(String dateTime, String workerName, String code, String name, double quantity, String unit,
                          double totalWithVat, String receiptNumber, String phone, String contract, String address) {
    }

    /** Техническое списание — строка отчёта. */
    public record FreeRow(String dateTime, String workerName, String code, String name, double quantity, String unit,
                          String closingCode, String phone, String contract, String address, String reason) {
    }

    /** Свод списаний по материалу (количества в рабочих единицах). */
    public record SummaryRow(String code, String name, String workUnit, String originalUnit, double convFactor,
                             double paidQty, double freeQty, double totalQty) {
    }

    private final Database db;

    public TransactionRepository(Database db) {
        this.db = db;
    }

    public void logIssue(long userId, int materialId, double qty, double totalWithVat) {
        db.update("INSERT INTO transactions (type, user_id, material_id, quantity, total_sum_with_vat) VALUES ('ISSUE', ?, ?, ?, ?)",
                userId, materialId, qty, totalWithVat);
    }

    public void logReturn(long userId, int materialId, double qty, double totalWithVat) {
        db.update("INSERT INTO transactions (type, user_id, material_id, quantity, total_sum_with_vat, write_off_reason) VALUES ('RETURN', ?, ?, ?, ?, 'Возврат на склад (подтверждено МОЛ)')",
                userId, materialId, qty, totalWithVat);
    }

    public void logWriteOff(long userId, WriteOffItem item, double totalWithVat) {
        db.update("""
                INSERT INTO transactions (
                    type, user_id, material_id, quantity, is_paid_receipt,
                    receipt_number, phone_number, contract_number, subscriber_address,
                    closing_code, write_off_reason, total_sum_with_vat
                ) VALUES ('WRITE_OFF', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                userId, item.materialId(), item.quantity(), item.paidReceipt() ? 1 : 0,
                item.receiptNumber(), item.phoneNumber(), item.contractNumber(), item.address(),
                item.closingCode(), item.reason(), totalWithVat);
    }

    /**
     * Последнее техническое списание (кроме кода 227) за полгода по тому же телефону или договору.
     * Пустые значения и «-» не сравниваются.
     */
    public Optional<LastClosing> findRecentClosing(String phone, String contract) {
        return db.queryOne("""
                SELECT closing_code, date(created_at, 'localtime') AS d FROM transactions
                WHERE type = 'WRITE_OFF' AND is_paid_receipt = 0
                  AND closing_code != '227'
                  AND created_at >= datetime('now', '-6 month')
                  AND ((phone_number = ? AND phone_number NOT IN ('', '-'))
                    OR (contract_number = ? AND contract_number NOT IN ('', '-')))
                ORDER BY created_at DESC LIMIT 1""",
                rs -> new LastClosing(rs.getString("closing_code"), rs.getString("d")), phone, contract);
    }

    // ------------------------------------------------------------------ отчёт

    public List<PaidRow> paidWriteOffs() {
        return db.query("""
                SELECT datetime(t.created_at, 'localtime') as dt, u.full_name, m.code, m.name,
                       t.quantity, m.work_unit, t.total_sum_with_vat,
                       t.receipt_number, t.phone_number, t.contract_number, t.subscriber_address
                FROM transactions t
                JOIN users u ON t.user_id = u.id
                JOIN materials m ON t.material_id = m.id
                WHERE t.type = 'WRITE_OFF' AND t.is_paid_receipt = 1
                ORDER BY t.created_at DESC""",
                rs -> new PaidRow(rs.getString("dt"), rs.getString("full_name"), rs.getString("code"), rs.getString("name"),
                        rs.getDouble("quantity"), rs.getString("work_unit"), rs.getDouble("total_sum_with_vat"),
                        rs.getString("receipt_number"), rs.getString("phone_number"), rs.getString("contract_number"),
                        rs.getString("subscriber_address")));
    }

    public List<FreeRow> freeWriteOffs() {
        return db.query("""
                SELECT datetime(t.created_at, 'localtime') as dt, u.full_name, m.code, m.name,
                       t.quantity, m.work_unit, t.closing_code, t.phone_number, t.contract_number, t.subscriber_address, t.write_off_reason
                FROM transactions t
                JOIN users u ON t.user_id = u.id
                JOIN materials m ON t.material_id = m.id
                WHERE t.type = 'WRITE_OFF' AND t.is_paid_receipt = 0
                ORDER BY t.created_at DESC""",
                rs -> new FreeRow(rs.getString("dt"), rs.getString("full_name"), rs.getString("code"), rs.getString("name"),
                        rs.getDouble("quantity"), rs.getString("work_unit"), rs.getString("closing_code"),
                        rs.getString("phone_number"), rs.getString("contract_number"), rs.getString("subscriber_address"),
                        rs.getString("write_off_reason")));
    }

    public List<SummaryRow> writeOffSummary() {
        return db.query("""
                SELECT m.code, m.name, m.work_unit, m.original_unit, m.conv_factor,
                       SUM(CASE WHEN t.is_paid_receipt = 1 THEN t.quantity ELSE 0 END) as paid_qty,
                       SUM(CASE WHEN t.is_paid_receipt = 0 THEN t.quantity ELSE 0 END) as free_qty,
                       SUM(t.quantity) as total_work_qty
                FROM transactions t
                JOIN materials m ON t.material_id = m.id
                WHERE t.type = 'WRITE_OFF'
                GROUP BY m.id
                ORDER BY m.name""",
                rs -> new SummaryRow(rs.getString("code"), rs.getString("name"), rs.getString("work_unit"),
                        rs.getString("original_unit"), rs.getDouble("conv_factor"), rs.getDouble("paid_qty"),
                        rs.getDouble("free_qty"), rs.getDouble("total_work_qty")));
    }
}
