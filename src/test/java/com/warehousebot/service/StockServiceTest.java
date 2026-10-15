package com.warehousebot.service;

import com.warehousebot.domain.WriteOffItem;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.repository.BalanceRepository;
import com.warehousebot.repository.MaterialRepository;
import com.warehousebot.repository.ReturnRequestRepository;
import com.warehousebot.repository.TransactionRepository;
import com.warehousebot.testutil.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Движение материалов: склад → подотчёт → списание/возврат, включая параллельные запросы. */
class StockServiceTest {

    private static final long WORKER = 2;
    private static final int MATERIAL = 1;

    private Database db;
    private StockService stock;
    private ReturnService returns;

    @BeforeEach
    void setUp() {
        db = TestDb.create();
        db.update("INSERT INTO users (id, full_name, role) VALUES (?, 'Иванов', 'WORKER')", WORKER);
        db.update("INSERT INTO materials (id, account_number, code, name, original_unit, work_unit, conv_factor, price_with_vat, warehouse_qty) " +
                "VALUES (?, '10.01', 'K-1', 'Кабель', 'км', 'м', 1000, 2.4, 10)", MATERIAL);
        MaterialRepository materials = new MaterialRepository(db);
        BalanceRepository balances = new BalanceRepository(db);
        TransactionRepository transactions = new TransactionRepository(db);
        stock = new StockService(db, materials, balances, transactions);
        returns = new ReturnService(db, new ReturnRequestRepository(db), balances, materials, transactions);
    }

    @Test
    void takeMovesStockToBalanceAndLogsIssue() {
        assertTrue(stock.take(WORKER, MATERIAL, 4).ok());
        assertEquals(6.0, warehouseQty());
        assertEquals(4.0, balance());
        assertEquals(1, count("SELECT COUNT(*) FROM transactions WHERE type = 'ISSUE'"));
    }

    @Test
    void cannotTakeMoreThanInStock() {
        StockService.TakeResult r = stock.take(WORKER, MATERIAL, 11);
        assertFalse(r.ok());
        assertTrue(r.text().startsWith("❌ На складе недостаточно материала!"));
        assertEquals(10.0, warehouseQty());
    }

    @Test
    void rejectsNonPositiveAndNaN() {
        assertFalse(stock.take(WORKER, MATERIAL, 0).ok());
        assertFalse(stock.take(WORKER, MATERIAL, Double.NaN).ok());
        assertEquals(10.0, warehouseQty());
    }

    @Test
    void concurrentTakesNeverOversell() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) tasks.add(() -> stock.take(WORKER, MATERIAL, 1).ok());
        int ok = 0;
        for (Future<Boolean> f : pool.invokeAll(tasks)) if (f.get()) ok++;
        pool.shutdown();

        assertEquals(10, ok);
        assertEquals(0.0, warehouseQty(), 1e-9);
        assertEquals(10.0, balance(), 1e-9);
    }

    @Test
    void writeOffDecreasesBalanceAndChecksQuantity() {
        stock.take(WORKER, MATERIAL, 4);

        String ok = stock.writeOff(WORKER, item(3));
        assertTrue(ok.startsWith("✅ <b>Техническое списание"));
        assertEquals(1.0, balance(), 1e-9);

        assertEquals("❌ Ошибка: у вас на руках недостаточно этого материала.", stock.writeOff(WORKER, item(2)));
        assertEquals(1.0, balance(), 1e-9);
        assertEquals(1, count("SELECT COUNT(*) FROM transactions WHERE type = 'WRITE_OFF'"));
    }

    @Test
    void returnIsAppliedOnlyOnce() {
        stock.take(WORKER, MATERIAL, 4);
        ReturnService.Created created = returns.create(WORKER, MATERIAL, 4);
        assertTrue(created.success());

        assertTrue(returns.approve(created.requestId()).ok());
        assertFalse(returns.approve(created.requestId()).ok());
        assertFalse(returns.reject(created.requestId()).ok());

        assertEquals(10.0, warehouseQty(), 1e-9);
        assertEquals(0.0, balance(), 1e-9);
    }

    private static WriteOffItem item(double qty) {
        return new WriteOffItem(MATERIAL, "Кабель [K-1]", "м", 2.4, qty, false, null,
                "+375291112233", "-", "ул. Ленина, 1", "227", "Выправление волокна");
    }

    private double warehouseQty() {
        return db.queryOne("SELECT warehouse_qty FROM materials WHERE id = ?", rs -> rs.getDouble(1), MATERIAL).orElseThrow();
    }

    private double balance() {
        return db.queryOne("SELECT quantity FROM employee_balances WHERE user_id = ? AND material_id = ?",
                rs -> rs.getDouble(1), WORKER, MATERIAL).orElse(0.0);
    }

    private int count(String sql) {
        return db.queryOne(sql, rs -> rs.getInt(1)).orElseThrow();
    }
}
