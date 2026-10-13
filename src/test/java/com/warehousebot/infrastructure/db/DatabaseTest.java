package com.warehousebot.infrastructure.db;

import com.warehousebot.testutil.TestDb;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    @Test
    void schemaInitializesOnEmptyDatabase() {
        // Регрессия: раньше индексы создавались до таблиц, и пустая база оставалась без таблиц.
        Database db = TestDb.create();
        assertEquals(7, db.queryOne("SELECT COUNT(*) FROM welders", rs -> rs.getInt(1)).orElseThrow());
        assertTrue(db.queryOne("SELECT COUNT(*) FROM service_tariffs", rs -> rs.getInt(1)).orElseThrow() > 0);
    }

    @Test
    void schemaInitializationIsIdempotent() {
        Database db = TestDb.create();
        SchemaInitializer.initialize(db);
        assertEquals(7, db.queryOne("SELECT COUNT(*) FROM welders", rs -> rs.getInt(1)).orElseThrow());
    }

    @Test
    void transactionRollsBackOnException() {
        Database db = TestDb.create();
        assertThrows(IllegalStateException.class, () -> db.inTransaction(() -> {
            db.update("INSERT INTO users (id, full_name, role) VALUES (1, 'A', 'WORKER')");
            throw new IllegalStateException("boom");
        }));
        assertEquals(0, db.queryOne("SELECT COUNT(*) FROM users", rs -> rs.getInt(1)).orElseThrow());
    }

    @Test
    void nestedTransactionsJoinOuter() {
        Database db = TestDb.create();
        db.inTransaction(() -> {
            db.update("INSERT INTO users (id, full_name, role) VALUES (1, 'A', 'WORKER')");
            db.inTransaction(() -> db.update("INSERT INTO users (id, full_name, role) VALUES (2, 'B', 'WORKER')"));
        });
        assertEquals(2, db.queryOne("SELECT COUNT(*) FROM users", rs -> rs.getInt(1)).orElseThrow());
    }

    @Test
    void insertReturnsGeneratedKey() {
        Database db = TestDb.create();
        long id = db.insert("INSERT INTO directory_contacts (category, contact_text, added_by) VALUES (1, 'x', 1)");
        assertTrue(id > 0);
    }
}
