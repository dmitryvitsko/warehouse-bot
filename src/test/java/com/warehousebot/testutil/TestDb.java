package com.warehousebot.testutil;

import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.db.SchemaInitializer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Временная база SQLite в файле (WAL требует файловую БД), удаляется JVM при выходе. */
public final class TestDb {

    private TestDb() {
    }

    public static Database create() {
        try {
            Path dir = Files.createTempDirectory("warehouse-test");
            dir.toFile().deleteOnExit();
            Database db = new Database("jdbc:sqlite:" + dir.resolve("test.db"), 2);
            SchemaInitializer.initialize(db);
            return db;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
