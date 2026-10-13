package com.warehousebot.infrastructure.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Доступ к SQLite: небольшой пул постоянных соединений и транзакции.
 *
 * <p>Раньше на каждый запрос открывалось новое соединение (открытие файла + PRAGMA).
 * Теперь соединения переиспользуются, а PRAGMA применяются один раз при создании.
 *
 * <p>Транзакции стартуют в режиме {@code BEGIN IMMEDIATE}: блокировка на запись берётся сразу,
 * поэтому «проверить остаток → списать» внутри {@link #inTransaction} атомарно даже
 * при параллельной обработке апдейтов от разных пользователей.
 *
 * <p>Транзакция привязана к потоку: репозитории, вызванные внутри {@link #inTransaction},
 * автоматически работают в той же транзакции.
 */
public final class Database implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Database.class);
    private static final long BORROW_TIMEOUT_SECONDS = 30;

    private final String url;
    private final BlockingQueue<Connection> pool;
    private final List<Connection> all = new ArrayList<>();
    private final ThreadLocal<Connection> txConnection = new ThreadLocal<>();

    public Database(String url, int poolSize) {
        this.url = url;
        this.pool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            Connection c = open();
            all.add(c);
            pool.add(c);
        }
    }

    private Connection open() {
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
        cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        cfg.setBusyTimeout(10_000);
        cfg.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        try {
            return cfg.createConnection(url);
        } catch (SQLException e) {
            throw new DataAccessException("Не удалось открыть базу " + url, e);
        }
    }

    // ------------------------------------------------------------------ connections & transactions

    public <T> T withConnection(SqlFunction<Connection, T> work) {
        Connection bound = txConnection.get();
        if (bound != null) return call(work, bound);

        Connection conn = borrow();
        try {
            return call(work, conn);
        } finally {
            pool.offer(conn);
        }
    }

    /** Выполняет работу в одной транзакции. Вложенные вызовы присоединяются к внешней транзакции. */
    public <T> T inTransaction(SqlSupplier<T> work) {
        if (txConnection.get() != null) return callSupplier(work);

        Connection conn = borrow();
        txConnection.set(conn);
        boolean committed = false;
        try {
            conn.setAutoCommit(false);
            T result = callSupplier(work);
            // setAutoCommit(true) выполняет только COMMIT. Обычный commit() в sqlite-jdbc сразу открывает
            // следующую BEGIN IMMEDIATE, и её таймаут выглядел бы как ошибка уже сохранённой операции.
            conn.setAutoCommit(true);
            committed = true;
            return result;
        } catch (SQLException e) {
            throw new DataAccessException("Ошибка транзакции", e);
        } finally {
            txConnection.remove();
            if (!committed) rollbackQuietly(conn);
            pool.offer(conn);
        }
    }

    public void inTransaction(SqlRunnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }

    // ------------------------------------------------------------------ query helpers

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    while (rs.next()) out.add(mapper.map(rs));
                    return out;
                }
            }
        });
    }

    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.<T>empty();
                }
            }
        });
    }

    public int update(String sql, Object... params) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                bind(ps, params);
                return ps.executeUpdate();
            }
        });
    }

    /** INSERT, возвращающий сгенерированный ROWID. */
    public long insert(String sql, Object... params) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, params);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    return keys.next() ? keys.getLong(1) : -1L;
                }
            }
        });
    }

    public int[] batch(String sql, List<Object[]> rows) {
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Object[] row : rows) {
                    bind(ps, row);
                    ps.addBatch();
                }
                return ps.executeBatch();
            }
        });
    }

    public void execute(String sql) {
        withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute(sql);
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ internals

    private static void bind(PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private Connection borrow() {
        try {
            Connection c = pool.poll(BORROW_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (c == null) throw new DataAccessException("Пул соединений исчерпан", null);
            return c;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataAccessException("Прервано ожидание соединения", e);
        }
    }

    private static <T> T call(SqlFunction<Connection, T> work, Connection conn) {
        try {
            return work.apply(conn);
        } catch (SQLException e) {
            throw new DataAccessException(e.getMessage(), e);
        }
    }

    private static <T> T callSupplier(SqlSupplier<T> work) {
        try {
            return work.get();
        } catch (SQLException e) {
            throw new DataAccessException(e.getMessage(), e);
        }
    }

    private static void rollbackQuietly(Connection conn) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            log.warn("Откат транзакции не удался", e);
        }
        try {
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("Не удалось вернуть autocommit", e);
        }
    }

    @Override
    public void close() {
        for (Connection c : all) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // закрываемся при остановке — ошибки не важны
            }
        }
    }

    @FunctionalInterface
    public interface SqlFunction<A, R> {
        R apply(A arg) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlSupplier<R> {
        R get() throws SQLException;
    }

    @FunctionalInterface
    public interface SqlRunnable {
        void run() throws SQLException;
    }
}
