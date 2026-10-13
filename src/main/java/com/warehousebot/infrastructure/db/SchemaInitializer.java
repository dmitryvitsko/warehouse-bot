package com.warehousebot.infrastructure.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Создание и миграция схемы warehouse.db.
 *
 * <p>Схема полностью совместима с прежней версией: те же таблицы и колонки,
 * миграции только добавляют колонки и индексы. Существующая база открывается без изменений данных.
 */
public final class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private static final String[] DEFAULT_SERVICES = {
            "Вызов мастера;за услугу;1;10.80",
            "Восстановление доступа в интернет;за услугу;1;15.60",
            "Установка/замена медной розетки;за услугу;1;11.58",
            "Установка/замена ОРА;за услугу;1;19.20",
            "Перенос ОРА;за услугу;1;32.76",
            "Замена шнура на удлиненный;за услугу;1;10.20",
            "Замена проводки ТРП (целиком);за услугу;1;0.00",
            "Замена кабеля ЮТП (целиком);за услугу;1;11.22",
            "Замена оптической проводки (целиком);за услугу;1;23.94",
            "Обжатие ЮТП (с 1 стороны);за услугу;0;4.32",
            "Установка фаст-коннектора (оптика);за услугу;0;10.20",
            "Сварка оптики (пигтейл);за услугу;0;15.72",
            "Мех. соединение оптики (пигтейл);за услугу;0;11.64",
            "Пробивка отверстия в стене;за отверстие;0;9.96",
            "Крепление оборудования к стене;за устройство;0;3.90",
            "Демонтаж медной проводки;за 1 метр;0;0.60",
            "Демонтаж оптики;за 1 метр;0;1.32",
            "Монтаж кабель-канала (короба);за 1 метр;0;3.12",
            "Прокладка ТРП открыто;за 1 метр;0;0.00",
            "Прокладка ЮТП по стене скобами;за 1 метр;0;4.98",
            "Прокладка ЮТП по дер. плинтусу;за 1 метр;0;3.72",
            "Прокладка ЮТП в кабель-канал;за 1 метр;0;4.20",
            "Прокладка ЮТП в пласт. плинтус;за 1 метр;0;3.78",
            "Прокладка ЮТП в трубу/металлорукав;за 1 метр;0;4.38",
            "Прокладка оптики в кабель-канал;за 1 метр;0;4.74",
            "Прокладка оптики в пласт. плинтус;за 1 метр;0;4.32",
            "Прокладка оптики в трубу/металлорукав;за 1 метр;0;5.22"
    };

    private static final String[] INITIAL_WELDERS = {
            "Swift Литвинко", "Switf Салома", "Fujikura Ильев",
            "Fujikura новая №1", "Fujikura новая №2", "Sumitomo", "INNO"
    };

    private SchemaInitializer() {
    }

    public static void initialize(Database db) {
        db.withConnection(conn -> {
            try (Statement st = conn.createStatement()) {
                createTables(st);
                migrateColumns(st);
                createIndexes(st);
                seedTariffs(conn, st);
                seedWelders(conn);
                syncUserNames(st);
            }
            return null;
        });
        log.info("✅ База данных warehouse.db и все таблицы успешно готовы к работе!");
    }

    private static void createTables(Statement st) throws SQLException {
        st.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY,
                    full_name TEXT NOT NULL,
                    role TEXT DEFAULT 'WORKER',
                    tm_access INTEGER DEFAULT 0
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS tm_accounts (
                    chat_id INTEGER PRIMARY KEY,
                    username TEXT NOT NULL,
                    password TEXT NOT NULL,
                    session TEXT
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS sick_leave_requests (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id INTEGER NOT NULL,
                    excel_name TEXT NOT NULL,
                    start_date TEXT NOT NULL,
                    end_date TEXT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING'
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS directory_contacts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category INTEGER,
                    contact_text TEXT,
                    added_by INTEGER
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS materials (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    account_number TEXT NOT NULL,
                    code TEXT UNIQUE NOT NULL,
                    name TEXT NOT NULL,
                    original_unit TEXT NOT NULL,
                    work_unit TEXT NOT NULL,
                    conv_factor REAL DEFAULT 1.0,
                    price_with_vat REAL NOT NULL,
                    warehouse_qty REAL NOT NULL,
                    batch_info TEXT,
                    start_qty REAL DEFAULT 0.0,
                    start_sum REAL DEFAULT 0.0,
                    in_qty REAL DEFAULT 0.0,
                    out_qty REAL DEFAULT 0.0,
                    end_sum REAL DEFAULT 0.0
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS employee_balances (
                    user_id INTEGER,
                    material_id INTEGER,
                    quantity REAL DEFAULT 0.0,
                    PRIMARY KEY (user_id, material_id)
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type TEXT NOT NULL,
                    user_id INTEGER,
                    material_id INTEGER,
                    quantity REAL NOT NULL,
                    is_paid_receipt INTEGER DEFAULT 0,
                    receipt_number TEXT,
                    phone_number TEXT,
                    contract_number TEXT,
                    subscriber_address TEXT,
                    closing_code TEXT,
                    write_off_reason TEXT,
                    total_sum_with_vat REAL DEFAULT 0.0,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS return_requests (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_id INTEGER NOT NULL,
                    material_id INTEGER NOT NULL,
                    quantity REAL NOT NULL,
                    status TEXT DEFAULT 'PENDING',
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS service_tariffs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    service_name TEXT NOT NULL,
                    unit TEXT NOT NULL,
                    price_with_vat REAL NOT NULL,
                    is_single INTEGER DEFAULT 0
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS user_excel_names (
                    user_id INTEGER PRIMARY KEY,
                    excel_name TEXT NOT NULL
                )""");
        // Устаревшая таблица графика: оставлена ради совместимости со старыми базами.
        st.execute("""
                CREATE TABLE IF NOT EXISTS schedules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    excel_name TEXT NOT NULL,
                    month_name TEXT,
                    year_val INTEGER,
                    day_num INTEGER NOT NULL,
                    start_time TEXT,
                    end_time TEXT,
                    status_code TEXT
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS schedules_v2 (
                    excel_name TEXT,
                    year INTEGER,
                    month INTEGER,
                    day INTEGER,
                    status_code TEXT,
                    start_time TEXT,
                    end_time TEXT,
                    UNIQUE(excel_name, year, month, day)
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS tools (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    inv_number TEXT,
                    status TEXT DEFAULT 'IN_STOCK',
                    assigned_to INTEGER,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS orsh_inspections (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    orsh_number TEXT NOT NULL,
                    address TEXT NOT NULL,
                    location TEXT NOT NULL,
                    status TEXT DEFAULT 'PENDING',
                    reason TEXT,
                    inspected_by TEXT,
                    inspected_at DATETIME
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS welders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT UNIQUE,
                    status TEXT DEFAULT 'ON_BASE',
                    assigned_to INTEGER DEFAULT NULL,
                    assigned_time TIMESTAMP DEFAULT NULL
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS welders_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    welder_id INTEGER,
                    user_id INTEGER,
                    action TEXT,
                    action_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    admin_id INTEGER DEFAULT NULL
                )""");
    }

    /** Колонки, добавленные в старые базы. Каждая миграция независима: падение одной не мешает остальным. */
    private static void migrateColumns(Statement st) {
        addColumnIfMissing(st, "ALTER TABLE users ADD COLUMN tm_access INTEGER DEFAULT 0");
        addColumnIfMissing(st, "ALTER TABLE materials ADD COLUMN original_unit TEXT DEFAULT 'шт'");
        addColumnIfMissing(st, "ALTER TABLE materials ADD COLUMN work_unit TEXT DEFAULT 'шт'");
        addColumnIfMissing(st, "ALTER TABLE materials ADD COLUMN conv_factor REAL DEFAULT 1.0");
        addColumnIfMissing(st, "ALTER TABLE transactions ADD COLUMN phone_number TEXT");
        addColumnIfMissing(st, "ALTER TABLE transactions ADD COLUMN closing_code TEXT");
        addColumnIfMissing(st, "ALTER TABLE tools ADD COLUMN write_off_reason TEXT");
        addColumnIfMissing(st, "ALTER TABLE tools ADD COLUMN written_off_at DATETIME");
    }

    private static void addColumnIfMissing(Statement st, String ddl) {
        try {
            st.execute(ddl);
        } catch (SQLException ignored) {
            // колонка уже существует
        }
    }

    /** Индексы создаются после таблиц (в старой версии порядок был обратным и ломал пустую базу). */
    private static void createIndexes(Statement st) throws SQLException {
        st.execute("CREATE INDEX IF NOT EXISTS idx_sched_v2_name ON schedules_v2(excel_name)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_sched_v2_ymd ON schedules_v2(year, month, day)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_tools_assigned ON tools(assigned_to)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_tools_status_name ON tools(status, name)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_tools_inv ON tools(inv_number)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_tx_type_created ON transactions(type, created_at)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_tx_phone ON transactions(phone_number)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_tx_contract ON transactions(contract_number)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_ret_status ON return_requests(status)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_ret_user_mat ON return_requests(user_id, material_id, status)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_users_role ON users(role)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_uen_excel_name ON user_excel_names(excel_name)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_orsh_status ON orsh_inspections(status)");
        st.execute("CREATE INDEX IF NOT EXISTS idx_welders_status ON welders(status)");
    }

    private static void seedTariffs(Connection conn, Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("SELECT SUM(price_with_vat) FROM service_tariffs")) {
            if (rs.next() && rs.getDouble(1) != 0) return;
        }
        st.execute("DELETE FROM service_tariffs");
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO service_tariffs (service_name, unit, is_single, price_with_vat) VALUES (?, ?, ?, ?)")) {
            for (String s : DEFAULT_SERVICES) {
                String[] p = s.split(";");
                ps.setString(1, p[0]);
                ps.setString(2, p[1]);
                ps.setInt(3, Integer.parseInt(p[2]));
                ps.setDouble(4, Double.parseDouble(p[3]));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void seedWelders(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR IGNORE INTO welders (name) VALUES (?)")) {
            for (String name : INITIAL_WELDERS) {
                ps.setString(1, name);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void syncUserNames(Statement st) {
        try {
            st.execute("UPDATE users SET full_name = (SELECT excel_name FROM user_excel_names WHERE user_excel_names.user_id = users.id) " +
                    "WHERE EXISTS (SELECT 1 FROM user_excel_names WHERE user_excel_names.user_id = users.id)");
        } catch (SQLException e) {
            log.warn("Синхронизация имён пользователей не удалась", e);
        }
    }
}
