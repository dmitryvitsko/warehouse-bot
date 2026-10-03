import java.sql.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:warehouse.db";

    public static final long ADMIN_ID = 129265455L;

    private static final DecimalFormat QTY_FMT;
    private static final DecimalFormat PRICE_FMT;

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        QTY_FMT = new DecimalFormat("#.####", symbols);
        PRICE_FMT = new DecimalFormat("0.00##", symbols);
    }

    public static String fmtQty(double v) {
        if (Math.abs(v) < 1e-9) return "0";
        return QTY_FMT.format(v);
    }

    public static String fmtPrice(double v) {
        return PRICE_FMT.format(v);
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    public static void initDatabase() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS users (
                            id INTEGER PRIMARY KEY,
                            full_name TEXT NOT NULL,
                            role TEXT DEFAULT 'WORKER'
                        );
                    """);

            stmt.execute("""
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
                        );
                    """);

            try {
                stmt.execute("ALTER TABLE materials ADD COLUMN original_unit TEXT DEFAULT 'шт';");
                stmt.execute("ALTER TABLE materials ADD COLUMN work_unit TEXT DEFAULT 'шт';");
                stmt.execute("ALTER TABLE materials ADD COLUMN conv_factor REAL DEFAULT 1.0;");
            } catch (SQLException ignored) {}

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS employee_balances (
                            user_id INTEGER,
                            material_id INTEGER,
                            quantity REAL DEFAULT 0.0,
                            PRIMARY KEY (user_id, material_id)
                        );
                    """);

            stmt.execute("""
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
                        );
                    """);

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS return_requests (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            user_id INTEGER NOT NULL,
                            material_id INTEGER NOT NULL,
                            quantity REAL NOT NULL,
                            status TEXT DEFAULT 'PENDING',
                            created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                        );
                    """);

            try { stmt.execute("ALTER TABLE transactions ADD COLUMN phone_number TEXT;"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE transactions ADD COLUMN closing_code TEXT;"); } catch (SQLException ignored) {}

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS service_tariffs (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            service_name TEXT NOT NULL,
                            unit TEXT NOT NULL,
                            price_with_vat REAL NOT NULL,
                            is_single INTEGER DEFAULT 0
                        );
                    """);

            ResultSet rsTariffs = stmt.executeQuery("SELECT SUM(price_with_vat) FROM service_tariffs");
            if (!rsTariffs.next() || rsTariffs.getDouble(1) == 0) {
                stmt.execute("DELETE FROM service_tariffs;");
                String[] defaultServices = {
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
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO service_tariffs (service_name, unit, is_single, price_with_vat) VALUES (?, ?, ?, ?)")) {
                    for (String s : defaultServices) {
                        String[] parts = s.split(";");
                        ps.setString(1, parts[0]);
                        ps.setString(2, parts[1]);
                        ps.setInt(3, Integer.parseInt(parts[2]));
                        ps.setDouble(4, Double.parseDouble(parts[3]));
                        ps.executeUpdate();
                    }
                }
            }

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS user_excel_names (
                            user_id INTEGER PRIMARY KEY,
                            excel_name TEXT NOT NULL
                        );
                    """);

            stmt.execute("""
                        CREATE TABLE IF NOT EXISTS schedules (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            excel_name TEXT NOT NULL,
                            month_name TEXT,
                            year_val INTEGER,
                            day_num INTEGER NOT NULL,
                            start_time TEXT,
                            end_time TEXT,
                            status_code TEXT
                        );
                    """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS tools (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    inv_number TEXT,
                    status TEXT DEFAULT 'IN_STOCK',
                    assigned_to INTEGER,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                );
            """);

            try { stmt.execute("ALTER TABLE tools ADD COLUMN write_off_reason TEXT;"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE tools ADD COLUMN written_off_at DATETIME;"); } catch (SQLException ignored) {}

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS orsh_inspections (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    orsh_number TEXT NOT NULL,
                    address TEXT NOT NULL,
                    location TEXT NOT NULL,
                    status TEXT DEFAULT 'PENDING',
                    reason TEXT,
                    inspected_by TEXT,
                    inspected_at DATETIME
                );
            """);

            // ==========================================
            // НОВЫЕ ТАБЛИЦЫ ДЛЯ СВАРОЧНЫХ АППАРАТОВ
            // ==========================================
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS welders (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT UNIQUE,
                    status TEXT DEFAULT 'ON_BASE',
                    assigned_to INTEGER DEFAULT NULL,
                    assigned_time TIMESTAMP DEFAULT NULL
                );
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS welders_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    welder_id INTEGER,
                    user_id INTEGER,
                    action TEXT,
                    action_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    admin_id INTEGER DEFAULT NULL
                );
            """);

            // Первичная загрузка списка сварочников
            String[] initialWelders = {
                    "Swift Литвинко", "Switf Салома", "Fujikura Ильев",
                    "Fujikura новая №1", "Fujikura новая №2", "Sumitomo", "INNO"
            };
            try (PreparedStatement psWeld = conn.prepareStatement("INSERT OR IGNORE INTO welders (name) VALUES (?)")) {
                for (String wName : initialWelders) {
                    psWeld.setString(1, wName);
                    psWeld.executeUpdate();
                }
            }
            // ==========================================

            try {
                stmt.execute("UPDATE users SET full_name = (SELECT excel_name FROM user_excel_names WHERE user_excel_names.user_id = users.id) WHERE EXISTS (SELECT 1 FROM user_excel_names WHERE user_excel_names.user_id = users.id)");
            } catch (SQLException ignored) {}

            System.out.println("✅ База данных warehouse.db и все таблицы успешно готовы к работе!");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // =========================================================================
    // СУПЕР-МОДУЛЬ: КОНТРОЛЬ СВАРОЧНЫХ АППАРАТОВ
    // =========================================================================

    public static boolean takeWelder(int welderId, long userId, Long adminId) {
        String sqlUpdate = "UPDATE welders SET status = 'IN_USE', assigned_to = ?, assigned_time = datetime('now', 'localtime') WHERE id = ? AND status = 'ON_BASE'";
        String sqlHistory = "INSERT INTO welders_history (welder_id, user_id, action, admin_id) VALUES (?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement psUpdate = conn.prepareStatement(sqlUpdate);
             PreparedStatement psHist = conn.prepareStatement(sqlHistory)) {

            psUpdate.setLong(1, userId);
            psUpdate.setInt(2, welderId);
            int affected = psUpdate.executeUpdate();

            if (affected > 0) {
                psHist.setInt(1, welderId);
                psHist.setLong(2, userId);
                psHist.setString(3, adminId == null ? "TAKEN" : "FORCE_TAKEN");
                if (adminId != null) {
                    psHist.setLong(4, adminId);
                } else {
                    psHist.setNull(4, java.sql.Types.INTEGER);
                }
                psHist.executeUpdate();
                return true;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public static boolean returnWelder(int welderId, long userId, Long adminId) {
        String sqlUpdate = "UPDATE welders SET status = 'ON_BASE', assigned_to = NULL, assigned_time = NULL WHERE id = ?";
        String sqlHistory = "INSERT INTO welders_history (welder_id, user_id, action, admin_id) VALUES (?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement psUpdate = conn.prepareStatement(sqlUpdate);
             PreparedStatement psHist = conn.prepareStatement(sqlHistory)) {

            psUpdate.setInt(1, welderId);
            int affected = psUpdate.executeUpdate();

            if (affected > 0) {
                psHist.setInt(1, welderId);
                psHist.setLong(2, userId);
                psHist.setString(3, adminId == null ? "RETURNED" : "FORCE_RETURNED");
                if (adminId != null) {
                    psHist.setLong(4, adminId);
                } else {
                    psHist.setNull(4, java.sql.Types.INTEGER);
                }
                psHist.executeUpdate();
                return true;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public static List<String[]> getWeldersStatus() {
        List<String[]> list = new ArrayList<>();
        // Возвращает: id, name, status, user_name, assigned_time, user_id
        String sql = "SELECT w.id, w.name, w.status, u.full_name, w.assigned_time, w.assigned_to " +
                "FROM welders w LEFT JOIN users u ON w.assigned_to = u.id ORDER BY w.status DESC, w.name";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new String[]{
                        String.valueOf(rs.getInt("id")),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getString("full_name") != null ? rs.getString("full_name") : "",
                        rs.getString("assigned_time") != null ? rs.getString("assigned_time") : "",
                        rs.getString("assigned_to") != null ? rs.getString("assigned_to") : ""
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static String getWeldersHistoryText() {
        StringBuilder sb = new StringBuilder("📜 <b>Журнал движений сварочных аппаратов:</b>\n<i>(последние 40 операций)</i>\n\n");
        String sql = """
            SELECT datetime(h.action_time, 'localtime') as local_time, w.name, u.full_name, h.action, a.full_name as admin_name
            FROM welders_history h
            JOIN welders w ON h.welder_id = w.id
            JOIN users u ON h.user_id = u.id
            LEFT JOIN users a ON h.admin_id = a.id
            ORDER BY h.action_time DESC LIMIT 40
        """;
        boolean hasItems = false;
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                hasItems = true;
                String time = rs.getString("local_time");
                String welderName = rs.getString("name");
                String userName = rs.getString("full_name");
                String action = rs.getString("action");
                String adminName = rs.getString("admin_name");

                String actionRu = switch (action) {
                    case "TAKEN" -> "взял(а)";
                    case "RETURNED" -> "сдал(а) на базу";
                    case "FORCE_TAKEN" -> "выдано принудительно админом";
                    case "FORCE_RETURNED" -> "возвращено принудительно админом";
                    default -> action;
                };

                String adminSuffix = (adminName != null && !action.equals("TAKEN") && !action.equals("RETURNED")) ? " (" + adminName + ")" : "";

                String icon = (action.contains("TAKEN")) ? "🔴" : "🟢";
                sb.append(String.format("%s <i>%s</i>\n<b>%s</b> — %s <b>%s</b>%s\n\n",
                        icon, time, userName, actionRu, welderName, adminSuffix));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return hasItems ? sb.toString() : "📜 История пока пуста.";
    }

    public static String getWelderNameById(int id) {
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT name FROM welders WHERE id = ?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("name");
        } catch (SQLException e) { e.printStackTrace(); }
        return "Неизвестный аппарат";
    }

    // =========================================================================
    // ОСТАЛЬНОЙ СТАРЫЙ КОД (БЕЗ ИЗМЕНЕНИЙ)
    // =========================================================================

    public static String saveImportedMaterials(List<ExcelImporter.MaterialRow> rows) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("UPDATE materials SET warehouse_qty = 0, start_qty = 0, in_qty = 0, out_qty = 0, start_sum = 0, end_sum = 0;");
            }
            String sql = """
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
                            end_sum = excluded.end_sum
                    """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (ExcelImporter.MaterialRow r : rows) {
                    ps.setString(1, r.account);
                    ps.setString(2, r.code);
                    ps.setString(3, r.name);
                    ps.setString(4, r.originalUnit);
                    ps.setString(5, r.workUnit);
                    ps.setDouble(6, r.convFactor);
                    ps.setDouble(7, r.priceWithVat);
                    ps.setDouble(8, r.qty);
                    ps.setString(9, r.batchInfo != null ? r.batchInfo : "");
                    ps.setDouble(10, r.startQty);
                    ps.setDouble(11, r.startSum);
                    ps.setDouble(12, r.inQty);
                    ps.setDouble(13, r.outQty);
                    ps.setDouble(14, r.endSum);
                    ps.executeUpdate();
                }
            }
            conn.commit();
            return "✅ <b>Оборотная ведомость успешно загружена!</b>\nУмный конвертер единиц (км ➔ м, тыс.шт ➔ шт, гильзы уп ➔ шт) сработал корректно.";
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка базы данных при сохранении ведомости: " + e.getMessage();
        }
    }

    public static String getUserRole(long userId, String fullName) {
        try (Connection conn = getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT role FROM users WHERE id = ?");
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("role");
            } else {
                String defaultRole = (userId == ADMIN_ID) ? "ADMIN" : "PENDING";
                PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO users (id, full_name, role) VALUES (?, ?, ?)");
                insert.setLong(1, userId);
                insert.setString(2, fullName);
                insert.setString(3, defaultRole);
                insert.executeUpdate();
                return (userId == ADMIN_ID) ? "ADMIN" : "NEW_PENDING";
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "PENDING";
        }
    }

    public static String getActualUserName(long userId, String tgName) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT full_name FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("full_name");
        } catch (SQLException e) { e.printStackTrace(); }
        return tgName;
    }

    // Метод для получения полного имени пользователя по его ID
    public static String getUserFullName(long userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT full_name FROM users WHERE id = ?")) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("full_name");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "Сотрудник (ID: " + userId + ")";
    }

    public static String getServiceTariffsText() {
        StringBuilder sb = new StringBuilder("📋 <b>Утвержденные тарифы (с НДС):</b>\n");
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT service_name, unit, price_with_vat FROM service_tariffs ORDER BY id")) {
            int currentCategory = -1;
            int rowNum = 1;
            while (rs.next()) {
                int categoryGroup = getCategoryGroup(rowNum);
                if (categoryGroup != currentCategory) {
                    sb.append("\n").append(getCategoryHeader(categoryGroup)).append("\n");
                    currentCategory = categoryGroup;
                }
                double price = rs.getDouble("price_with_vat");
                String priceStr = (price == 0) ? "<i>(не задана)</i>" : String.format(Locale.US, "<b>%.2f руб.</b>", price);
                sb.append(String.format(" ▪ %s (<i>%s</i>) — %s\n", rs.getString("service_name"), rs.getString("unit"), priceStr));
                rowNum++;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return sb.toString();
    }

    private static int getCategoryGroup(int id) {
        if (id <= 2) return 1;
        if (id <= 6) return 2;
        if (id <= 9) return 3;
        if (id <= 13) return 4;
        if (id <= 15) return 5;
        if (id <= 17) return 6;
        return 7;
    }

    private static String getCategoryHeader(int group) {
        return switch (group) {
            case 1 -> "🛠 <b>ОБЩИЕ УСЛУГИ</b>";
            case 2 -> "🔌 <b>РОЗЕТКИ И ШНУРЫ</b>";
            case 3 -> "🔄 <b>ЗАМЕНА ПРОВОДКИ (ЦЕЛИКОМ)</b>";
            case 4 -> "✂ <b>ОКОНЕЧИВАНИЕ И СВАРКА</b>";
            case 5 -> "🕳 <b>ОТВЕРСТИЯ И КРЕПЛЕНИЕ</b>";
            case 6 -> "🗑 <b>ДЕМОНТАЖ</b>";
            case 7 -> "📏 <b>ПРОКЛАДКА КАБЕЛЯ И КОРОБОВ</b>";
            default -> "🔹 <b>ПРОЧИЕ УСЛУГИ</b>";
        };
    }

    public static boolean updateServicePrice(String name, double price) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE service_tariffs SET price_with_vat = ? WHERE service_name = ?")) {
            ps.setDouble(1, price);
            ps.setString(2, name.trim());
            int updated = ps.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static List<String[]> getAvailableMaterials() {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                         SELECT id, account_number, code, name, work_unit, price_with_vat, warehouse_qty, batch_info
                         FROM materials
                         WHERE warehouse_qty > 0
                         ORDER BY account_number, name
                     """)) {
            while (rs.next()) {
                double priceWithVat = rs.getDouble("price_with_vat");
                double priceNoVat = priceWithVat / 1.20;

                list.add(new String[]{
                        String.valueOf(rs.getInt("id")),
                        rs.getString("account_number"),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("work_unit"),
                        fmtPrice(priceNoVat),
                        fmtPrice(priceWithVat),
                        fmtQty(rs.getDouble("warehouse_qty")),
                        rs.getString("batch_info") != null ? rs.getString("batch_info") : ""
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static WriteOffSession createWriteOffSession(long userId, int materialId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                         SELECT m.id, m.code, m.name, m.work_unit, m.price_with_vat, b.quantity
                         FROM employee_balances b
                         JOIN materials m ON b.material_id = m.id
                         WHERE b.user_id = ? AND m.id = ? AND b.quantity > 0
                     """)) {
            ps.setLong(1, userId);
            ps.setInt(2, materialId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                WriteOffSession s = new WriteOffSession();
                s.materialId = rs.getInt("id");
                s.materialName = rs.getString("name") + " [" + rs.getString("code") + "]";
                s.unit = rs.getString("work_unit");
                s.priceWithVat = rs.getDouble("price_with_vat");
                s.maxAvailable = rs.getDouble("quantity");
                s.step = "WAIT_QTY";
                return s;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    public static String takeMaterialFromWarehouse(long userId, int materialId, double qtyToTake) {
        if (qtyToTake <= 0) return "❌ Количество должно быть больше нуля!";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            PreparedStatement psCheck = conn.prepareStatement(
                    "SELECT code, name, work_unit, warehouse_qty, price_with_vat FROM materials WHERE id = ?");
            psCheck.setInt(1, materialId);
            ResultSet rs = psCheck.executeQuery();

            if (!rs.next()) return "❌ Материал не найден.";
            String code = rs.getString("code");
            String name = rs.getString("name");
            String unit = rs.getString("work_unit");
            double available = rs.getDouble("warehouse_qty");
            double priceVat = rs.getDouble("price_with_vat");

            if (qtyToTake > available + 1e-9) {
                return String.format("❌ На складе недостаточно материала! Доступно всего: <b>%s %s</b>", fmtQty(available), unit);
            }

            PreparedStatement psUpdateWarehouse = conn.prepareStatement(
                    "UPDATE materials SET warehouse_qty = ROUND(warehouse_qty - ?, 6) WHERE id = ?");
            psUpdateWarehouse.setDouble(1, qtyToTake);
            psUpdateWarehouse.setInt(2, materialId);
            psUpdateWarehouse.executeUpdate();

            PreparedStatement psUpdateWorker = conn.prepareStatement("""
                        INSERT INTO employee_balances (user_id, material_id, quantity) VALUES (?, ?, ?)
                        ON CONFLICT(user_id, material_id) DO UPDATE SET quantity = ROUND(quantity + excluded.quantity, 6)
                    """);
            psUpdateWorker.setLong(1, userId);
            psUpdateWorker.setInt(2, materialId);
            psUpdateWorker.setDouble(3, qtyToTake);
            psUpdateWorker.executeUpdate();

            double totalSum = qtyToTake * priceVat;
            PreparedStatement psLog = conn.prepareStatement("""
                        INSERT INTO transactions (type, user_id, material_id, quantity, total_sum_with_vat)
                        VALUES ('ISSUE', ?, ?, ?, ?)
                    """);
            psLog.setLong(1, userId);
            psLog.setInt(2, materialId);
            psLog.setDouble(3, qtyToTake);
            psLog.setDouble(4, totalSum);
            psLog.executeUpdate();

            conn.commit();
            return String.format("""
                            ✅ <b>Вы взяли со склада в подотчет:</b>
                            • Наименование: <b>%s</b>
                            • Инв. номер: <code>%s</code>
                            • Количество: <b>%s %s</b>
                            • Сумма (с НДС): <b>%s руб.</b> <i>(по %s за 1 %s)</i>""",
                    name, code, fmtQty(qtyToTake), unit, fmtPrice(totalSum), fmtPrice(priceVat), unit);
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка базы данных при выдаче.";
        }
    }

    public static String getUserBalanceText(long userId) {
        StringBuilder sb = new StringBuilder("🧰 <b>Числится за вами (в подотчете):</b>\n\n");
        boolean hasItems = false;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                         SELECT m.code, m.name, m.work_unit, m.price_with_vat, b.quantity 
                         FROM employee_balances b
                         JOIN materials m ON b.material_id = m.id
                         WHERE b.user_id = ? AND b.quantity > 0.00001
                         ORDER BY m.name
                     """)) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                hasItems = true;
                double qty = rs.getDouble("quantity");
                double priceVat = rs.getDouble("price_with_vat");
                double totalSum = qty * priceVat;

                sb.append(String.format("🔹 <b>%s</b>\n   • Инв. №: <code>%s</code>\n   • В наличии: <b>%s %s</b>\n   • Сумма (с НДС): <b>%s руб.</b> <i>(по %s за 1 %s)</i>\n\n",
                        rs.getString("name"), rs.getString("code"), fmtQty(qty), rs.getString("work_unit"),
                        fmtPrice(totalSum), fmtPrice(priceVat), rs.getString("work_unit")));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return hasItems ? sb.toString() : "🧰 За вами сейчас не числится материалов. Возьмите нужные позиции в разделе «📦 Склад».";
    }

    public static String getAllWorkersBalancesText() {
        StringBuilder sb = new StringBuilder("📊 <b>Материалы на руках у сотрудников:</b>\n");
        boolean hasAny = false;
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                         SELECT u.full_name, m.code, m.name, m.work_unit, m.price_with_vat, b.quantity
                         FROM employee_balances b
                         JOIN users u ON b.user_id = u.id
                         JOIN materials m ON b.material_id = m.id
                         WHERE b.quantity > 0.00001
                         ORDER BY u.full_name, m.name
                     """)) {
            String currentWorker = "";
            while (rs.next()) {
                hasAny = true;
                String worker = rs.getString("full_name");
                if (!worker.equals(currentWorker)) {
                    sb.append("\n👤 <b>").append(worker).append(":</b>\n");
                    currentWorker = worker;
                }
                double qty = rs.getDouble("quantity");
                double priceVat = rs.getDouble("price_with_vat");
                double totalSum = qty * priceVat;

                sb.append(String.format("   • <b>%s</b> [<code>%s</code>] — <b>%s %s</b> (сумма: <b>%s руб.</b>)\n",
                        rs.getString("name"), rs.getString("code"), fmtQty(qty), rs.getString("work_unit"), fmtPrice(totalSum)));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return hasAny ? sb.toString() : "📊 Сейчас на руках у сотрудников нет ни одного материала.";
    }

    public static List<String[]> getUserMaterialsForWriteOff(long userId) {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                         SELECT m.id, m.code, m.name, m.work_unit, b.quantity
                         FROM employee_balances b
                         JOIN materials m ON b.material_id = m.id
                         WHERE b.user_id = ? AND b.quantity > 0.00001
                         ORDER BY m.name
                     """)) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new String[]{
                        String.valueOf(rs.getInt("id")), rs.getString("code"), rs.getString("name"),
                        rs.getString("work_unit"), fmtQty(rs.getDouble("quantity"))
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static String completeWriteOff(long userId, WriteOffSession s) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            PreparedStatement psCheck = conn.prepareStatement("SELECT quantity FROM employee_balances WHERE user_id = ? AND material_id = ?");
            psCheck.setLong(1, userId);
            psCheck.setInt(2, s.materialId);
            ResultSet rs = psCheck.executeQuery();

            if (!rs.next() || rs.getDouble("quantity") + 1e-9 < s.quantity) {
                return "❌ Ошибка: у вас на руках недостаточно этого материала.";
            }

            PreparedStatement psUpdate = conn.prepareStatement("UPDATE employee_balances SET quantity = ROUND(quantity - ?, 6) WHERE user_id = ? AND material_id = ?");
            psUpdate.setDouble(1, s.quantity);
            psUpdate.setLong(2, userId);
            psUpdate.setInt(3, s.materialId);
            psUpdate.executeUpdate();

            double totalSumVat = Math.round(s.quantity * s.priceWithVat * 100.0) / 100.0;
            PreparedStatement psLog = conn.prepareStatement("""
                        INSERT INTO transactions (
                            type, user_id, material_id, quantity, is_paid_receipt,
                            receipt_number, phone_number, contract_number, subscriber_address,
                            closing_code, write_off_reason, total_sum_with_vat
                        ) VALUES ('WRITE_OFF', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            psLog.setLong(1, userId);
            psLog.setInt(2, s.materialId);
            psLog.setDouble(3, s.quantity);
            psLog.setInt(4, s.isPaidReceipt ? 1 : 0);
            psLog.setString(5, s.receiptNumber);
            psLog.setString(6, s.phoneNumber);
            psLog.setString(7, s.contractNumber);
            psLog.setString(8, s.address);
            psLog.setString(9, s.closingCode);
            psLog.setString(10, s.reason);
            psLog.setDouble(11, totalSumVat);
            psLog.executeUpdate();

            conn.commit();

            if (s.isPaidReceipt) {
                return String.format("""
                                ✅ <b>Списание ПО КВИТАНЦИИ сохранено!</b>
                                
                                📦 Материал: <b>%s — %s %s</b>
                                🧾 Квитанция: <b>%s</b>
                                ☎️ Телефон заявки: <b>%s</b>
                                📄 Номер договора: <b>%s</b>
                                🏠 Адрес: <b>%s</b>
                                
                                💰 <b>Сумма материала для квитанции (с НДС): %.2f руб.</b>
                                <i>(%s %s × %s руб.)</i>""",
                        s.materialName, fmtQty(s.quantity), s.unit,
                        s.receiptNumber, s.phoneNumber, s.contractNumber, s.address,
                        totalSumVat, fmtQty(s.quantity), s.unit, fmtPrice(s.priceWithVat));
            } else {
                return String.format("""
                                ✅ <b>Техническое списание (БЕЗ квитанции) сохранено!</b>
                                
                                📦 Материал: <b>%s — %s %s</b>
                                🔢 Код закрытия: <b>%s</b>
                                ☎️ Телефон заявки: <b>%s</b>
                                📄 Номер договора: <b>%s</b>
                                🏠 Адрес: <b>%s</b>
                                🛠 Причина: <b>%s</b>""",
                        s.materialName, fmtQty(s.quantity), s.unit,
                        s.closingCode, s.phoneNumber, s.contractNumber, s.address, s.reason);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка базы данных при сохранении списания.";
        }
    }

    public static String checkCode212History(String phone, String contract) {
        if ((phone == null || phone.isEmpty() || phone.equals("-")) &&
                (contract == null || contract.isEmpty() || contract.equals("-"))) return "";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                         SELECT closing_code, write_off_date FROM write_offs 
                         WHERE (phone_number = ? OR contract_number = ?) 
                         AND closing_code != '227' 
                         AND write_off_date >= date('now', '-6 month') 
                         ORDER BY write_off_date DESC LIMIT 1
                     """)) {
            ps.setString(1, phone);
            ps.setString(2, contract);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String code = rs.getString("closing_code");
                String date = rs.getString("write_off_date");
                return "\n\n⚠️ <b>ОСТОРОЖНО:</b> По этому абоненту менее 6 мес. назад уже закрывалась заявка (код " + code + " от " + date + "). <b>Вы обязаны использовать код 227 (любой другой запрещен)!</b>";
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return "";
    }

    public static String getClosingCodesText() {
        return """
                🔢 <b>Шпаргалка по кодам закрытия заявок:</b>
                
                📍 <b>Участок ОРК ↔ ОРА (от коробки до розетки абонента):</b>
                • <b>Код 212</b> — Работы со списанием материалов (не чаще 6 мес!).
                • <b>Код 227</b> — Выправление волокна без списания материала (без ограничений).
                
                📍 <b>Работы в ОРШ:</b>
                • <b>Код 215</b> — Выправление положения пигтейла/волокна (без материалов).
                • <b>Код 226</b> — Замена пигтейла/адаптера (с материалами).
                
                📍 <b>Участок ОРШ ↔ ОРК:</b>
                • <b>Код 214</b> — Ремонт/замена райзера.
                • <b>Код 217</b> — Переход на запасной модуль (КДЗС).
                """;
    }

    public static class ReturnRequestInfo {
        public boolean success;
        public String messageForWorker;
        public String messageForAdmin;
        public int requestId;
    }

    public static ReturnRequestInfo createReturnRequest(long userId, int materialId, double qtyToReturn) {
        ReturnRequestInfo info = new ReturnRequestInfo();
        if (qtyToReturn <= 0) {
            info.success = false;
            info.messageForWorker = "❌ Количество для возврата должно быть больше нуля!";
            return info;
        }

        try (Connection conn = getConnection()) {
            PreparedStatement psCheck = conn.prepareStatement("""
                        SELECT b.quantity, m.code, m.name, m.work_unit, u.full_name
                        FROM employee_balances b
                        JOIN materials m ON b.material_id = m.id
                        JOIN users u ON b.user_id = u.id
                        WHERE b.user_id = ? AND b.material_id = ?
                    """);
            psCheck.setLong(1, userId);
            psCheck.setInt(2, materialId);
            ResultSet rs = psCheck.executeQuery();

            if (!rs.next()) {
                info.success = false;
                info.messageForWorker = "❌ Этот материал не числится у вас в подотчете.";
                return info;
            }

            double onHands = rs.getDouble("quantity");
            String code = rs.getString("code");
            String name = rs.getString("name");
            String unit = rs.getString("work_unit");
            String workerName = rs.getString("full_name");

            double pendingQty = 0.0;
            try (PreparedStatement psPend = conn.prepareStatement(
                    "SELECT COALESCE(SUM(quantity), 0) FROM return_requests WHERE user_id = ? AND material_id = ? AND status = 'PENDING'")) {
                psPend.setLong(1, userId);
                psPend.setInt(2, materialId);
                ResultSet rsP = psPend.executeQuery();
                if (rsP.next()) pendingQty = rsP.getDouble(1);
            }

            double availableForReturn = onHands - pendingQty;
            if (qtyToReturn > availableForReturn + 1e-9) {
                info.success = false;
                info.messageForWorker = String.format(
                        "❌ Нельзя вернуть больше, чем есть на руках!\nЧислится за вами: <b>%s %s</b> (в ожидании подтверждения МОЛ: <b>%s %s</b>).",
                        fmtQty(onHands), unit, fmtQty(pendingQty), unit);
                return info;
            }

            try (PreparedStatement psIns = conn.prepareStatement(
                    "INSERT INTO return_requests (user_id, material_id, quantity, status) VALUES (?, ?, ?, 'PENDING')", Statement.RETURN_GENERATED_KEYS)) {
                psIns.setLong(1, userId);
                psIns.setInt(2, materialId);
                psIns.setDouble(3, qtyToReturn);
                psIns.executeUpdate();
                ResultSet keys = psIns.getGeneratedKeys();
                if (keys.next()) info.requestId = keys.getInt(1);
            }

            info.success = true;
            info.messageForWorker = String.format("""
                            ⏳ <b>Заявка на возврат №%d отправлена МОЛ!</b>
                            
                            • Материал: <b>%s</b> [<code>%s</code>]
                            • Количество к возврату: <b>%s %s</b>
                            
                            <i>⚠️ Материал числится за вами до тех пор, пока МОЛ не подтвердит приемку на склад.</i>""",
                    info.requestId, name, code, fmtQty(qtyToReturn), unit);

            info.messageForAdmin = String.format("""
                            🔔 <b>Запрос на возврат материала на склад (№%d)</b>
                            
                            👤 Сотрудник: <b>%s</b>
                            📦 Материал: <b>%s</b>
                            🔢 Инв. №: <code>%s</code>
                            ↩️ Количество к возврату: <b>%s %s</b>
                            
                            Подтверждаете приемку этого материала обратно на склад?""",
                    info.requestId, workerName, name, code, fmtQty(qtyToReturn), unit);

            return info;

        } catch (SQLException e) {
            e.printStackTrace();
            info.success = false;
            info.messageForWorker = "❌ Ошибка базы данных при создании заявки на возврат.";
            return info;
        }
    }

    public static String[] approveReturnRequest(int requestId) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            PreparedStatement psReq = conn.prepareStatement("""
                        SELECT r.user_id, r.material_id, r.quantity, r.status,
                               m.code, m.name, m.work_unit, m.price_with_vat, u.full_name
                        FROM return_requests r
                        JOIN materials m ON r.material_id = m.id
                        JOIN users u ON r.user_id = u.id
                        WHERE r.id = ?
                    """);
            psReq.setInt(1, requestId);
            ResultSet rs = psReq.executeQuery();

            if (!rs.next()) return new String[]{"ERROR", "0", "❌ Заявка №" + requestId + " не найдена."};

            String status = rs.getString("status");
            if (!"PENDING".equals(status)) return new String[]{"ERROR", "0", "ℹ️ Эта заявка уже обработана."};

            long workerId = rs.getLong("user_id");
            int materialId = rs.getInt("material_id");
            double qty = rs.getDouble("quantity");
            String code = rs.getString("code");
            String name = rs.getString("name");
            String unit = rs.getString("work_unit");
            double priceVat = rs.getDouble("price_with_vat");
            String workerName = rs.getString("full_name");

            PreparedStatement psBal = conn.prepareStatement("SELECT quantity FROM employee_balances WHERE user_id = ? AND material_id = ?");
            psBal.setLong(1, workerId);
            psBal.setInt(2, materialId);
            ResultSet rsBal = psBal.executeQuery();

            if (!rsBal.next() || rsBal.getDouble("quantity") + 1e-9 < qty) {
                return new String[]{"ERROR", String.valueOf(workerId), "❌ Ошибка: у сотрудника " + workerName + " на руках уже меньше материала, чем указано в заявке."};
            }

            PreparedStatement psUpdWorker = conn.prepareStatement("UPDATE employee_balances SET quantity = ROUND(quantity - ?, 6) WHERE user_id = ? AND material_id = ?");
            psUpdWorker.setDouble(1, qty);
            psUpdWorker.setLong(2, workerId);
            psUpdWorker.setInt(3, materialId);
            psUpdWorker.executeUpdate();

            PreparedStatement psUpdWarehouse = conn.prepareStatement("UPDATE materials SET warehouse_qty = ROUND(warehouse_qty + ?, 6) WHERE id = ?");
            psUpdWarehouse.setDouble(1, qty);
            psUpdWarehouse.setInt(2, materialId);
            psUpdWarehouse.executeUpdate();

            PreparedStatement psUpdReq = conn.prepareStatement("UPDATE return_requests SET status = 'APPROVED' WHERE id = ?");
            psUpdReq.setInt(1, requestId);
            psUpdReq.executeUpdate();

            PreparedStatement psLog = conn.prepareStatement("INSERT INTO transactions (type, user_id, material_id, quantity, total_sum_with_vat, write_off_reason) VALUES ('RETURN', ?, ?, ?, ?, 'Возврат на склад (подтверждено МОЛ)')");
            psLog.setLong(1, workerId);
            psLog.setInt(2, materialId);
            psLog.setDouble(3, qty);
            psLog.setDouble(4, qty * priceVat);
            psLog.executeUpdate();

            conn.commit();

            String msgAdmin = String.format("✅ <b>Возврат №%d подтвержден!</b>\n• Сотрудник: <b>%s</b>\n• Материал: <b>%s</b> [<code>%s</code>]\n• Возвращено на склад: <b>%s %s</b>", requestId, workerName, name, code, fmtQty(qty), unit);
            String msgWorker = String.format("✅ <b>МОЛ подтвердил ваш возврат (заявка №%d)!</b>\n• Материал: <b>%s</b> [<code>%s</code>]\n• Списано на склад: <b>%s %s</b>", requestId, name, code, fmtQty(qty), unit);

            return new String[]{"OK", String.valueOf(workerId), msgAdmin, msgWorker};
        } catch (SQLException e) {
            e.printStackTrace();
            return new String[]{"ERROR", "0", "❌ Ошибка базы данных при подтверждении возврата."};
        }
    }

    public static String[] rejectReturnRequest(int requestId) {
        try (Connection conn = getConnection()) {
            PreparedStatement psReq = conn.prepareStatement("""
                        SELECT r.user_id, r.quantity, r.status, m.code, m.name, m.work_unit, u.full_name
                        FROM return_requests r
                        JOIN materials m ON r.material_id = m.id
                        JOIN users u ON r.user_id = u.id
                        WHERE r.id = ?
                    """);
            psReq.setInt(1, requestId);
            ResultSet rs = psReq.executeQuery();

            if (!rs.next()) return new String[]{"ERROR", "0", "❌ Заявка №" + requestId + " не найдена."};
            String status = rs.getString("status");
            if (!"PENDING".equals(status)) return new String[]{"ERROR", "0", "ℹ Эта заявка (№" + requestId + ") уже обработана."};

            long workerId = rs.getLong("user_id");
            double qty = rs.getDouble("quantity");
            String code = rs.getString("code");
            String name = rs.getString("name");
            String unit = rs.getString("work_unit");
            String workerName = rs.getString("full_name");

            PreparedStatement psUpd = conn.prepareStatement("UPDATE return_requests SET status = 'REJECTED' WHERE id = ?");
            psUpd.setInt(1, requestId);
            psUpd.executeUpdate();

            String msgAdmin = String.format("❌ <b>Вы отклонили заявку на возврат №%d.</b>\nМатериал <b>%s</b> (%s %s) остался в подотчете за сотрудником <b>%s</b>.", requestId, name, fmtQty(qty), unit, workerName);
            String msgWorker = String.format("❌ <b>МОЛ отклонил вашу заявку на возврат №%d.</b>\nМатериал <b>%s</b> [<code>%s</code>] в количестве <b>%s %s</b> остается в вашем подотчете.", requestId, name, code, fmtQty(qty), unit);

            return new String[]{"OK", String.valueOf(workerId), msgAdmin, msgWorker};
        } catch (SQLException e) {
            e.printStackTrace();
            return new String[]{"ERROR", "0", "❌ Ошибка БД при отклонении возврата."};
        }
    }

    public static List<Long> getAdminIds() {
        List<Long> admins = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM users WHERE role = 'ADMIN'")) {
            while (rs.next()) admins.add(rs.getLong("id"));
        } catch (SQLException e) { e.printStackTrace(); }
        return admins;
    }

    public static class ScheduleDay {
        public String excelName;
        public String monthName;
        public int yearVal;
        public int dayNum;
        public String startTime;
        public String endTime;
        public String statusCode;
    }

    public static String saveSchedule(List<ScheduleDay> days) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) { stmt.execute("DELETE FROM schedules"); }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO schedules (excel_name, month_name, year_val, day_num, start_time, end_time, status_code) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                for (ScheduleDay d : days) {
                    ps.setString(1, d.excelName);
                    ps.setString(2, d.monthName);
                    ps.setInt(3, d.yearVal);
                    ps.setInt(4, d.dayNum);
                    ps.setString(5, d.startTime != null ? d.startTime : "");
                    ps.setString(6, d.endTime != null ? d.endTime : "");
                    ps.setString(7, d.statusCode != null ? d.statusCode : "");
                    ps.executeUpdate();
                }
            }
            conn.commit();
            return "✅ <b>График работ успешно загружен и обновлен для всех сотрудников!</b>";
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка при сохранении графика в БД.";
        }
    }

    public static List<String> getAvailableExcelNames() {
        List<String> names = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             // ВАЖНОЕ ИЗМЕНЕНИЕ: Исключаем фамилии, которые уже кем-то заняты
             ResultSet rs = stmt.executeQuery(
                     "SELECT DISTINCT excel_name FROM schedules " +
                             "WHERE excel_name NOT IN (SELECT excel_name FROM user_excel_names) " +
                             "ORDER BY excel_name")) {
            while (rs.next()) names.add(rs.getString("excel_name"));
        } catch (SQLException e) { e.printStackTrace(); }
        return names;
    }

    public static void bindUserToExcelName(long userId, String excelName) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT INTO user_excel_names (user_id, excel_name) VALUES (?, ?) ON CONFLICT(user_id) DO UPDATE SET excel_name = excluded.excel_name")) {
            ps.setLong(1, userId);
            ps.setString(2, excelName);
            ps.executeUpdate();
            try (PreparedStatement psUpdateUser = conn.prepareStatement("UPDATE users SET full_name = ? WHERE id = ?")) {
                psUpdateUser.setString(1, excelName);
                psUpdateUser.setLong(2, userId);
                psUpdateUser.executeUpdate();
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public static String getUserExcelName(long userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT excel_name FROM user_excel_names WHERE user_id = ?")) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("excel_name");
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    private static int getMonthNumber(String monthName) {
        String m = monthName.toUpperCase();
        if (m.contains("ЯНВАР")) return 1; if (m.contains("ФЕВРАЛ")) return 2;
        if (m.contains("МАРТ")) return 3; if (m.contains("АПРЕЛ")) return 4;
        if (m.contains("МА")) return 5; if (m.contains("ИЮН")) return 6;
        if (m.contains("ИЮЛ")) return 7; if (m.contains("АВГУСТ")) return 8;
        if (m.contains("СЕНТЯБР")) return 9; if (m.contains("ОКТЯБР")) return 10;
        if (m.contains("НОЯБР")) return 11; if (m.contains("ДЕКАБР")) return 12;
        return java.time.LocalDate.now().getMonthValue();
    }

    private static String getDayOfWeekRu(int year, int month, int day) {
        try {
            java.time.DayOfWeek dow = java.time.LocalDate.of(year, month, day).getDayOfWeek();
            return switch (dow) {
                case MONDAY -> "Пн"; case TUESDAY -> "Вт"; case WEDNESDAY -> "Ср";
                case THURSDAY -> "Чт"; case FRIDAY -> "Пт"; case SATURDAY -> "Сб"; case SUNDAY -> "Вс";
            };
        } catch (Exception e) { return ""; }
    }

    public static String getFormattedSchedule(String excelName) {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT month_name, year_val, day_num, start_time, end_time, status_code FROM schedules WHERE excel_name = ? ORDER BY day_num ASC")) {
            ps.setString(1, excelName);
            ResultSet rs = ps.executeQuery();
            int weekNumber = 1;
            boolean hasData = false;

            while (rs.next()) {
                int dayNum = rs.getInt("day_num");
                int year = rs.getInt("year_val");
                String monthName = rs.getString("month_name");
                int monthNum = getMonthNumber(monthName);
                java.time.LocalDate date = java.time.LocalDate.of(year, monthNum, dayNum);
                String dayOfWeek = getDayOfWeekRu(year, monthNum, dayNum);

                if (!hasData) {
                    sb.append(String.format("🗓 <b>Ваш график на %s %d г.</b>\n👤 Сотрудник: <b>%s</b>\n\n➖ <b>Неделя %d</b> ➖➖➖➖➖➖\n", monthName, year, excelName, weekNumber));
                    hasData = true;
                } else if (date.getDayOfWeek() == java.time.DayOfWeek.MONDAY) {
                    weekNumber++;
                    sb.append(String.format("\n➖ <b>Неделя %d</b> ➖➖➖➖➖➖\n", weekNumber));
                }

                String start = rs.getString("start_time");
                String end = rs.getString("end_time");
                String statusCode = rs.getString("status_code");
                String timeRange = (start != null && !start.isEmpty() && end != null && !end.isEmpty()) ? start + " - " + end : "";

                String line;
                if ("В".equals(statusCode)) {
                    line = String.format("🏖 <u><b>%s, %02d</b></u> — Выходной", dayOfWeek, dayNum);
                } else if ("О".equals(statusCode)) {
                    line = String.format("🌴 <b>%s, %02d</b> — Отпуск", dayOfWeek, dayNum);
                } else if ("Д".equals(statusCode)) {
                    line = String.format("🚨 <u><b>%s, %02d</b></u> — ❗️ <b>Дежурство</b>", dayOfWeek, dayNum);
                } else {
                    String icon = "🟩";
                    if (start != null && (start.startsWith("11:") || start.startsWith("12:") || start.startsWith("13:") || start.startsWith("14:"))) icon = "🟧";

                    if (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY) {
                        line = String.format("🟥 <u><b>%s, %02d</b></u> — %s ❗️ <b>РАБОЧАЯ СУББОТА</b>", dayOfWeek, dayNum, timeRange);
                    } else if (date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                        line = String.format("🟥 <u><b>%s, %02d</b></u> — %s ❗️ <b>РАБОЧЕЕ ВОСКРЕСЕНЬЕ</b>", dayOfWeek, dayNum, timeRange);
                    } else {
                        line = String.format("%s <b>%s, %02d</b> — %s", icon, dayOfWeek, dayNum, timeRange);
                    }
                }
                sb.append(line).append("\n");
            }
            if (!hasData) return "ℹ️ График для сотрудника <b>" + excelName + "</b> не найден в базе. Попросите администратора загрузить файл.";
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка при чтении графика из базы данных.";
        }
        return sb.toString();
    }

    private static int getSeniorityRank(String excelName) {
        if (excelName == null) return 99;
        String lower = excelName.toLowerCase();
        if (lower.contains("прищепчик") || lower.contains("витько")) return 1;
        if (lower.contains("белевич")) return 2;
        if (lower.contains("шаметько")) return 3;
        if (lower.contains("максимов")) return 4;
        return 99;
    }

    public static String getShiftPartners(String excelName) {
        StringBuilder sb = new StringBuilder("🤝 <b>Ваши напарники по вторым сменам и субботам:</b>\n\n");
        try (Connection conn = getConnection()) {
            String userDaysSql = "SELECT day_num, year_val, month_name, start_time, end_time FROM schedules WHERE excel_name = ? AND status_code NOT IN ('В', 'О', 'Д') ORDER BY day_num ASC";
            boolean foundAny = false;

            try (PreparedStatement psUser = conn.prepareStatement(userDaysSql)) {
                psUser.setString(1, excelName);
                ResultSet rsUser = psUser.executeQuery();

                while (rsUser.next()) {
                    int dayNum = rsUser.getInt("day_num");
                    int year = rsUser.getInt("year_val");
                    String monthName = rsUser.getString("month_name");
                    String start = rsUser.getString("start_time");
                    String end = rsUser.getString("end_time");

                    int monthNum = getMonthNumber(monthName);
                    java.time.LocalDate date = java.time.LocalDate.of(year, monthNum, dayNum);
                    boolean isSaturday = date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY;
                    boolean isSecondShift = false;

                    if (end != null && (end.contains("21:00") || end.contains("21.00"))) isSecondShift = true;
                    else if (start != null && (start.startsWith("11:") || start.startsWith("12:") || start.startsWith("13:") || start.startsWith("14:"))) isSecondShift = true;

                    if (isSaturday || isSecondShift) {
                        foundAny = true;
                        String allWorkersSql = "SELECT excel_name, start_time, end_time FROM schedules WHERE day_num = ? AND month_name = ? AND year_val = ? AND status_code NOT IN ('В', 'О', 'Д')";
                        List<String> allWorkersThisShift = new ArrayList<>();

                        try (PreparedStatement psAll = conn.prepareStatement(allWorkersSql)) {
                            psAll.setInt(1, dayNum);
                            psAll.setString(2, monthName);
                            psAll.setInt(3, year);
                            ResultSet rsAll = psAll.executeQuery();
                            while (rsAll.next()) {
                                String pName = rsAll.getString("excel_name");
                                String pStart = rsAll.getString("start_time");
                                String pEnd = rsAll.getString("end_time");

                                boolean partnerIsSecondShift = false;
                                if (pEnd != null && (pEnd.contains("21:00") || pEnd.contains("21.00"))) partnerIsSecondShift = true;
                                else if (pStart != null && (pStart.startsWith("11:") || pStart.startsWith("12:") || pStart.startsWith("13:") || pStart.startsWith("14:"))) partnerIsSecondShift = true;

                                if (isSaturday || (isSecondShift && partnerIsSecondShift)) allWorkersThisShift.add(pName);
                            }
                        }

                        int minRank = 99;
                        for (String w : allWorkersThisShift) {
                            int r = getSeniorityRank(w);
                            if (r < minRank) minRank = r;
                        }

                        List<String> leaderLines = new ArrayList<>();
                        List<String> normalLines = new ArrayList<>();
                        boolean iAmSenior = false;

                        for (String w : allWorkersThisShift) {
                            if (w.equals(excelName)) {
                                if (getSeniorityRank(w) == minRank && minRank != 99) iAmSenior = true;
                                continue;
                            }
                            if (getSeniorityRank(w) == minRank && minRank != 99) leaderLines.add(" • <b>" + w + " (Старший смены)</b>");
                            else normalLines.add(" • " + w);
                        }

                        String dayOfWeekRu = getDayOfWeekRu(year, monthNum, dayNum);
                        String headerPrefix = isSaturday ? String.format("📅 <b>%02d.%02d (%s)</b>", dayNum, monthNum, dayOfWeekRu) : String.format("📅 <b>%02d.%02d (%s) — Вторая смена</b>", dayNum, monthNum, dayOfWeekRu);
                        if (iAmSenior) headerPrefix += "  👑 <i>(Вы старший смены!)</i>";

                        sb.append(headerPrefix).append("\n");
                        sb.append(isSaturday ? "С вами работают:\n" : "С вами во второй смене:\n");

                        if (leaderLines.isEmpty() && normalLines.isEmpty()) sb.append(" <i>(Никого не найдено)</i>\n");
                        else {
                            for (String line : leaderLines) sb.append(line).append("\n");
                            for (String line : normalLines) sb.append(line).append("\n");
                        }
                        sb.append("\n");
                    }
                }
            }
            if (!foundAny) return "ℹ️ В этом месяце у вас нет рабочих суббот или вторых смен.";
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка при поиске напарников.";
        }
        return sb.toString();
    }

    public static class ReceiptItem {
        public int dbId;
        public String name;
        public String unit;
        public double quantity;
        public double priceWithVatPerUnit;
        public boolean isMaterial;
        public boolean isSingle;
    }

    public static class ReceiptSession {
        public List<ReceiptItem> items = new ArrayList<>();
        public int waitingServiceId = -1;
        public int waitingMaterialId = -1;
    }

    public static List<String[]> getAllServicesForReceipt() {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id, service_name, unit, price_with_vat FROM service_tariffs ORDER BY id")) {
            while (rs.next()) list.add(new String[]{ String.valueOf(rs.getInt("id")), rs.getString("service_name"), rs.getString("unit"), String.valueOf(rs.getDouble("price_with_vat")) });
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static ReceiptItem getServiceById(int id) {
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT service_name, unit, price_with_vat, is_single FROM service_tariffs WHERE id = ?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ReceiptItem item = new ReceiptItem();
                item.dbId = id; item.name = rs.getString("service_name"); item.unit = rs.getString("unit");
                item.priceWithVatPerUnit = rs.getDouble("price_with_vat"); item.isMaterial = false; item.isSingle = rs.getInt("is_single") == 1;
                return item;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    public static ReceiptItem getMaterialFromBalanceById(long userId, int materialId) {
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT m.name, m.work_unit, m.price_with_vat FROM employee_balances b JOIN materials m ON b.material_id = m.id WHERE b.user_id = ? AND m.id = ? AND b.quantity > 0.00001")) {
            ps.setLong(1, userId); ps.setInt(2, materialId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ReceiptItem item = new ReceiptItem();
                item.dbId = materialId; item.name = rs.getString("name"); item.unit = rs.getString("work_unit");
                item.priceWithVatPerUnit = rs.getDouble("price_with_vat"); item.isMaterial = true;
                return item;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    private static String getShortUnit(String fullUnit) {
        String u = fullUnit.toLowerCase();
        if (u.contains("услуг") || u.contains("отверст") || u.contains("устройств")) return "шт";
        if (u.contains("метр")) return "м";
        return fullUnit;
    }

    public static String generateReceiptText(ReceiptSession session) {
        if (session.items.isEmpty()) return "🛒 Корзина квитанции пуста.";
        StringBuilder sb = new StringBuilder("🧾 <b>АКТ-КВИТАНЦИЯ (Расчет для заполнения)</b>\n\n");
        double totalServicesVat = 0, totalServicesSum = 0, totalMaterialsVat = 0, totalMaterialsSum = 0;

        sb.append("╔════ 🛠 <b>ВЫПОЛНЕННЫЕ РАБОТЫ</b> ════╗\n");
        boolean hasServices = false;
        for (ReceiptItem item : session.items) {
            if (!item.isMaterial) {
                hasServices = true;
                double sumWithVat = item.quantity * item.priceWithVatPerUnit;
                double vatSum = sumWithVat - (sumWithVat / 1.20);
                totalServicesSum += sumWithVat; totalServicesVat += vatSum;
                sb.append(String.format("🔹 %s\n ┝ %s %s  х  %.2f  =  <b>%.2f руб.</b>\n", item.name, fmtQty(item.quantity), getShortUnit(item.unit), item.priceWithVatPerUnit, sumWithVat));
            }
        }
        if (!hasServices) sb.append("<i>Услуги не добавлялись</i>\n");
        sb.append("╠═══════════════════════════════╣\n");
        sb.append(String.format("Итого по работам: <b>%.2f руб.</b>\n(В том числе НДС 20%%: %.2f руб.)\n\n", totalServicesSum, totalServicesVat));

        sb.append("╔══ 📦 <b>ИЗРАСХОДОВАННЫЕ МАТЕРИАЛЫ</b> ══╗\n");
        boolean hasMaterials = false;
        for (ReceiptItem item : session.items) {
            if (item.isMaterial) {
                hasMaterials = true;
                double sumWithVat = item.quantity * item.priceWithVatPerUnit;
                double vatSum = sumWithVat - (sumWithVat / 1.20);
                totalMaterialsSum += sumWithVat; totalMaterialsVat += vatSum;
                sb.append(String.format("🔹 %s\n ┝ %s %s  х  %.2f  =  <b>%.2f руб.</b>\n", item.name, fmtQty(item.quantity), item.unit, item.priceWithVatPerUnit, sumWithVat));
            }
        }
        if (!hasMaterials) sb.append("<i>Материалы не добавлялись</i>\n");
        sb.append("╠═══════════════════════════════╣\n");
        sb.append(String.format("Итого по материалам: <b>%.2f руб.</b>\n(В том числе НДС 20%%: %.2f руб.)\n\n", totalMaterialsSum, totalMaterialsVat));

        double finalSum = totalServicesSum + totalMaterialsSum;
        sb.append("═══════════════════════════════════\n");
        sb.append(String.format("💰 <b>ВСЕГО К ОПЛАТЕ: %.2f руб.</b>", finalSum));
        return sb.toString();
    }

    public static List<String[]> getPendingUsers() {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, full_name FROM users WHERE role = 'PENDING'")) {
            while (rs.next()) {
                list.add(new String[]{String.valueOf(rs.getLong("id")), rs.getString("full_name")});
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static List<String[]> getPendingReturnRequests() {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT r.id, u.full_name, m.name, m.code, r.quantity, m.work_unit FROM return_requests r JOIN users u ON r.user_id = u.id JOIN materials m ON r.material_id = m.id WHERE r.status = 'PENDING' ORDER BY r.created_at ASC")) {
            while (rs.next()) {
                list.add(new String[]{ String.valueOf(rs.getInt("id")), rs.getString("full_name"), rs.getString("name"), rs.getString("code"), String.valueOf(rs.getDouble("quantity")), rs.getString("work_unit") });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static List<Long> getUsersWithBalances() {
        List<Long> users = new ArrayList<>();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT DISTINCT user_id FROM employee_balances WHERE quantity > 0.00001")) {
            while (rs.next()) users.add(rs.getLong("user_id"));
        } catch (SQLException e) { e.printStackTrace(); }
        return users;
    }

    public static List<Long> getAllUserIds() {
        List<Long> users = new ArrayList<>();
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id FROM users")) {
            while (rs.next()) users.add(rs.getLong("id"));
        } catch (SQLException e) { e.printStackTrace(); }
        return users;
    }

    public static String getUsersListText() {
        StringBuilder sb = new StringBuilder("👥 <b>Список пользователей бота:</b>\n\n");
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT id, full_name, role FROM users")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String role = rs.getString("role");
                String status = switch (role) {
                    case "BANNED" -> "🚫 ЗАБЛОКИРОВАН"; case "ADMIN" -> "👑 АДМИН"; case "PENDING" -> "⏳ ОЖИДАЕТ ОДОБРЕНИЯ"; default -> "👷‍♂️ МАСТЕР";
                };
                sb.append(String.format("👤 <b>%s</b>\n   ID: <code>%d</code>\n   Связь: <a href=\"tg://user?id=%d\">Написать в ЛС</a>\n   Статус: %s\n", rs.getString("full_name"), id, id, status));
                if ("BANNED".equals(role) || "PENDING".equals(role)) sb.append(String.format("   Разблокировать/Одобрить: /unban_%d\n", id));
                else if (!"ADMIN".equals(role)) {
                    sb.append(String.format("   Блокировать: /ban_%d\n", id));
                    sb.append(String.format("   Сбросить ФИО: /unbind_%d\n", id));
                }
                sb.append("\n");
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return sb.toString();
    }

    public static String setBanStatus(long targetUserId, boolean ban) {
        String newRole = ban ? "BANNED" : "WORKER";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement("UPDATE users SET role = ? WHERE id = ? AND role != 'ADMIN'")) {
            ps.setString(1, newRole); ps.setLong(2, targetUserId);
            int updated = ps.executeUpdate();
            if (updated > 0) return ban ? "✅ Пользователь " + targetUserId + " заблокирован. Бот больше не будет ему отвечать." : "✅ Пользователь разблокирован.";
            return "❌ Пользователь не найден или это администратор (которого нельзя заблокировать).";
        } catch (SQLException e) { return "❌ Ошибка базы данных."; }
    }

    public static boolean unbindUser(long userId) {
        try (Connection conn = getConnection()) {
            // 1. Удаляем привязку к графику
            PreparedStatement ps1 = conn.prepareStatement("DELETE FROM user_excel_names WHERE user_id = ?");
            ps1.setLong(1, userId);
            int rows = ps1.executeUpdate();

            // 2. Откатываем имя в основной таблице до базового "Сотрудник",
            // чтобы путаница с "Белевичами" сразу исчезла
            if (rows > 0) {
                PreparedStatement ps2 = conn.prepareStatement("UPDATE users SET full_name = ? WHERE id = ?");
                ps2.setString(1, "Отвязанный Сотрудник " + userId);
                ps2.setLong(2, userId);
                ps2.executeUpdate();
                return true;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return false;
    }

    public static class ParsedTool {
        public String invNumber;
        public String name;
        public int quantity;
    }

    public static String saveImportedTools(List<ParsedTool> tools) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            String sql = "INSERT INTO tools (name, inv_number, status) VALUES (?, ?, 'IN_STOCK')";
            int addedCount = 0;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (ParsedTool t : tools) {
                    int qty = t.quantity > 0 ? t.quantity : 1;
                    for (int i = 1; i <= qty; i++) {
                        ps.setString(1, t.name);
                        String inv = (t.invNumber == null || t.invNumber.trim().isEmpty()) ? "Б/Н - " + i : (qty > 1 ? t.invNumber + " (" + i + ")" : t.invNumber);
                        ps.setString(2, inv);
                        ps.executeUpdate();
                        addedCount++;
                    }
                }
            }
            conn.commit();
            return "✅ <b>База инструмента успешно загружена!</b>\nДобавлено единиц на склад: <b>" + addedCount + "</b> шт.";
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка базы данных при сохранении инструмента: " + e.getMessage();
        }
    }

    public static String getUserToolsText(long userId) {
        StringBuilder sb = new StringBuilder("🪛 <b>Ваш закрепленный инструмент:</b>\n\n");
        boolean hasTools = false;
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement("SELECT name, inv_number FROM tools WHERE status = 'ASSIGNED' AND assigned_to = ? ORDER BY name")) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            int counter = 1;
            while (rs.next()) {
                hasTools = true;
                sb.append(String.format("%d. <b>%s</b> (Инв. №: <code>%s</code>)\n", counter++, rs.getString("name"), rs.getString("inv_number")));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return hasTools ? sb.toString() : "🪛 За вами пока не закреплен инструмент.";
    }

    public static String getToolsAuditText() {
        StringBuilder sb = new StringBuilder("📊 <b>Аудит инструмента:</b>\n\n");

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {

            ResultSet rsTotal = stmt.executeQuery(
                    "SELECT COUNT(*) as total, SUM(CASE WHEN status = 'IN_STOCK' THEN 1 ELSE 0 END) as avail FROM tools WHERE status != 'WRITTEN_OFF'"
            );
            if (rsTotal.next()) {
                int totalTools = rsTotal.getInt("total");
                int availTools = rsTotal.getInt("avail");
                sb.append("🏢 <b>Всего числится инструмента:</b> ").append(totalTools).append(" шт.\n");
                sb.append("📦 <b>Из них ждет на складе:</b> ").append(availTools).append(" шт.\n\n");
                sb.append("➖➖➖➖➖➖➖➖➖➖\n\n");
            }

            Map<String, int[]> statsMap = new java.util.LinkedHashMap<>();
            // 0: first_id, 1: t_count, 2: a_count, 3: h_count
            ResultSet rsStats = stmt.executeQuery(
                    "SELECT MIN(id) as first_id, name, COUNT(*) as t_count, " +
                            "SUM(CASE WHEN status = 'IN_STOCK' THEN 1 ELSE 0 END) as a_count, " +
                            "SUM(CASE WHEN status = 'ASSIGNED' THEN 1 ELSE 0 END) as h_count " +
                            "FROM tools WHERE status != 'WRITTEN_OFF' GROUP BY name ORDER BY name"
            );
            while (rsStats.next()) {
                statsMap.put(rsStats.getString("name"), new int[]{
                        rsStats.getInt("first_id"),
                        rsStats.getInt("t_count"),
                        rsStats.getInt("a_count"),
                        rsStats.getInt("h_count")
                });
            }

            String sql = """
                SELECT t.id as tool_id, t.name as tool_name, 
                       u.full_name as worker_name, 
                       t.inv_number
                FROM tools t
                JOIN users u ON t.assigned_to = u.id
                WHERE t.status = 'ASSIGNED'
                ORDER BY worker_name
            """;
            ResultSet rsAssigned = stmt.executeQuery(sql);

            Map<String, List<String>> workersMap = new java.util.HashMap<>();
            while (rsAssigned.next()) {
                String toolName = rsAssigned.getString("tool_name");
                String workerName = rsAssigned.getString("worker_name");
                String invNum = rsAssigned.getString("inv_number");
                int toolId = rsAssigned.getInt("tool_id");

                String displayLine = "   ✅ <b>" + workerName + "</b>";
                if (invNum != null && !invNum.isEmpty() && !invNum.equals("null")) {
                    displayLine += " (инв: " + invNum + ")";
                }
                displayLine += " 👉 /take_" + toolId;

                workersMap.computeIfAbsent(toolName, k -> new ArrayList<>()).add(displayLine);
            }

            if (statsMap.isEmpty()) {
                sb.append("<i>База инструмента пока пуста. Загрузите файл Excel.</i>");
            } else {
                for (Map.Entry<String, int[]> entry : statsMap.entrySet()) {
                    String toolName = entry.getKey();
                    int[] counts = entry.getValue();

                    sb.append("🔧 <b>").append(toolName).append("</b>");
                    if (counts[2] > 0) {
                        sb.append(" 👉 /give_").append(counts[0]);
                    }
                    sb.append("\n");

                    sb.append("   <i>Всего: ").append(counts[1])
                            .append(" | На складе: ").append(counts[2])
                            .append(" | На руках: ").append(counts[3]).append("</i>\n");

                    if (counts[3] > 0) {
                        List<String> workers = workersMap.getOrDefault(toolName, new ArrayList<>());
                        for (String line : workers) {
                            sb.append(line).append("\n");
                        }
                    }
                    sb.append("\n");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка при формировании аудита инструмента.";
        }

        return sb.toString();
    }

    public static String getToolNameById(int toolId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT name FROM tools WHERE id = ?")) {
            ps.setInt(1, toolId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("name");
        } catch (SQLException e) { e.printStackTrace(); }
        return "Неизвестный инструмент";
    }

    public static String[] assignAnyAvailableToolFast(int firstId, long userId) {
        try (Connection conn = getConnection()) {
            PreparedStatement psName = conn.prepareStatement("SELECT name FROM tools WHERE id = ?");
            psName.setInt(1, firstId);
            ResultSet rsName = psName.executeQuery();
            if (!rsName.next()) return new String[]{"ERROR", "Группа инструментов не найдена."};
            String toolName = rsName.getString("name");

            PreparedStatement psFind = conn.prepareStatement("SELECT id, inv_number FROM tools WHERE name = ? AND status = 'IN_STOCK' LIMIT 1");
            psFind.setString(1, toolName);
            ResultSet rsFind = psFind.executeQuery();
            if (!rsFind.next()) return new String[]{"ERROR", "❌ Нет в наличии: " + toolName};

            int toolId = rsFind.getInt("id");
            String invNum = rsFind.getString("inv_number");

            PreparedStatement psUser = conn.prepareStatement("SELECT full_name FROM users WHERE id = ?");
            psUser.setLong(1, userId);
            ResultSet rsUser = psUser.executeQuery();
            String userName = rsUser.next() ? rsUser.getString("full_name") : "Сотрудник";

            PreparedStatement psUpdate = conn.prepareStatement("UPDATE tools SET status = 'ASSIGNED', assigned_to = ? WHERE id = ?");
            psUpdate.setLong(1, userId);
            psUpdate.setInt(2, toolId);
            psUpdate.executeUpdate();

            String shortMsg = "✅ Выдано: " + userName;
            String userMsg = String.format("🔔 <b>Вам выдан новый инструмент!</b>\n\n🪛 <b>%s</b>\n🔢 Инв. №: <code>%s</code>", toolName, invNum);

            return new String[]{"OK", shortMsg, userMsg};

        } catch (SQLException e) {
            e.printStackTrace();
            return new String[]{"ERROR", "❌ Ошибка базы данных."};
        }
    }

    public static List<String[]> getAvailableToolGroups() {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT MIN(id) as first_id, name, COUNT(*) as cnt FROM tools WHERE status = 'IN_STOCK' GROUP BY name ORDER BY name"
             )) {
            while (rs.next()) {
                list.add(new String[]{
                        String.valueOf(rs.getInt("first_id")),
                        rs.getString("name"),
                        String.valueOf(rs.getInt("cnt"))
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static List<String[]> getUsersForToolAssignment() {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, full_name as d_name, role " +
                             "FROM users " +
                             "WHERE role != 'BANNED' AND role != 'PENDING' " +
                             "ORDER BY role DESC, d_name")) {
            while (rs.next()) {
                list.add(new String[]{
                        String.valueOf(rs.getLong("id")),
                        rs.getString("d_name"),
                        rs.getString("role")
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static String assignTool(int toolId, long userId) {
        try (Connection conn = getConnection()) {
            PreparedStatement psCheck = conn.prepareStatement("SELECT name, inv_number FROM tools WHERE id = ? AND status = 'IN_STOCK'");
            psCheck.setInt(1, toolId);
            ResultSet rs = psCheck.executeQuery();
            if (!rs.next()) return "❌ Этот инструмент уже выдан или списан. Попробуйте выбрать заново.";

            String name = rs.getString("name");
            String inv = rs.getString("inv_number");

            PreparedStatement psUser = conn.prepareStatement(
                    "SELECT full_name as d_name FROM users WHERE id = ?"
            );
            psUser.setLong(1, userId);
            ResultSet rsUser = psUser.executeQuery();
            String userName = rsUser.next() ? rsUser.getString("d_name") : "Неизвестный сотрудник";

            PreparedStatement psUpdate = conn.prepareStatement("UPDATE tools SET status = 'ASSIGNED', assigned_to = ? WHERE id = ?");
            psUpdate.setLong(1, userId);
            psUpdate.setInt(2, toolId);
            psUpdate.executeUpdate();

            return String.format("✅ <b>Успешно выдано!</b>\n\n🪛 Инструмент: <b>%s</b>\n🔢 Инв. №: <code>%s</code>\n👤 Выдано сотруднику: <b>%s</b>", name, inv, userName);
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка при выдаче инструмента.";
        }
    }

    public static String getToolNameAndInvById(int toolId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT name, inv_number FROM tools WHERE id = ?")) {
            ps.setInt(1, toolId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String name = rs.getString("name");
                String inv = rs.getString("inv_number");
                return name + " (Инв. №: " + inv + ")";
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return "Неизвестный инструмент";
    }

    public static List<String[]> getUsersWithAssignedTools() {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT DISTINCT u.id, u.full_name as d_name 
                     FROM tools t 
                     JOIN users u ON t.assigned_to = u.id 
                     WHERE t.status = 'ASSIGNED' 
                     ORDER BY d_name
                 """)) {
            while (rs.next()) {
                list.add(new String[]{
                        String.valueOf(rs.getLong("id")),
                        rs.getString("d_name")
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static List<String[]> getUserAssignedToolsForReturn(long userId) {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, name, inv_number FROM tools WHERE status = 'ASSIGNED' AND assigned_to = ? ORDER BY name")) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new String[]{
                        String.valueOf(rs.getInt("id")),
                        rs.getString("name"),
                        rs.getString("inv_number")
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static String returnToolToWarehouse(int toolId) {
        try (Connection conn = getConnection()) {
            PreparedStatement psCheck = conn.prepareStatement("SELECT name, inv_number FROM tools WHERE id = ?");
            psCheck.setInt(1, toolId);
            ResultSet rs = psCheck.executeQuery();
            if (!rs.next()) return "❌ Инструмент не найден.";

            String name = rs.getString("name");
            String inv = rs.getString("inv_number");

            PreparedStatement psUpdate = conn.prepareStatement("UPDATE tools SET status = 'IN_STOCK', assigned_to = NULL WHERE id = ?");
            psUpdate.setInt(1, toolId);
            psUpdate.executeUpdate();

            return String.format("✅ <b>Инструмент возвращен на склад!</b>\n\n🪛 <b>%s</b>\n🔢 Инв. №: <code>%s</code>", name, inv);
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка базы данных при возврате инструмента.";
        }
    }

    public static List<String[]> getToolsInStockByGroup(String firstIdStr) {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement psCheck = conn.prepareStatement("SELECT name FROM tools WHERE id = ?");
             PreparedStatement ps = conn.prepareStatement("SELECT id, name, inv_number FROM tools WHERE status = 'IN_STOCK' AND name = ? ORDER BY inv_number")) {
            psCheck.setInt(1, Integer.parseInt(firstIdStr));
            ResultSet rsCheck = psCheck.executeQuery();
            if (rsCheck.next()) {
                ps.setString(1, rsCheck.getString("name"));
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    list.add(new String[]{ String.valueOf(rs.getInt("id")), rs.getString("name"), rs.getString("inv_number") });
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static String writeOffTool(int toolId, String reason) {
        try (Connection conn = getConnection()) {
            PreparedStatement psCheck = conn.prepareStatement("SELECT name, inv_number FROM tools WHERE id = ?");
            psCheck.setInt(1, toolId);
            ResultSet rs = psCheck.executeQuery();
            if (!rs.next()) return "❌ Инструмент не найден.";

            String name = rs.getString("name");
            String inv = rs.getString("inv_number");

            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tools SET status = 'WRITTEN_OFF', assigned_to = NULL, write_off_reason = ?, written_off_at = datetime('now', 'localtime') WHERE id = ?");
            ps.setString(1, reason);
            ps.setInt(2, toolId);
            ps.executeUpdate();

            return String.format("✅ <b>Инструмент успешно СПИСАН!</b>\n\n🪛 <b>%s</b>\n🔢 Инв. №: <code>%s</code>\n📝 Причина: <i>%s</i>", name, inv, reason);
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка БД при списании.";
        }
    }

    public static String getWrittenOffToolsArchiveText() {
        StringBuilder sb = new StringBuilder("🗄 <b>Архив списанного инструмента:</b>\n\n");
        boolean hasItems = false;
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name, inv_number, write_off_reason, strftime('%d.%m.%Y', written_off_at) as wo_date " +
                             "FROM tools WHERE status = 'WRITTEN_OFF' ORDER BY name")) {

            int counter = 1;
            while (rs.next()) {
                hasItems = true;
                String reason = rs.getString("write_off_reason");
                if (reason == null || reason.isEmpty()) reason = "Не указана";

                String date = rs.getString("wo_date");
                if (date == null) date = "Дата неизвестна";

                sb.append(String.format("%d. <b>%s</b>\n   • Инв. №: <code>%s</code>\n   • Дата списания: <b>%s</b>\n   • Причина: <i>%s</i>\n\n",
                        counter++, rs.getString("name"), rs.getString("inv_number"), date, reason));
            }
        } catch (SQLException e) { e.printStackTrace(); }

        return hasItems ? sb.toString() : "🗄 В архиве списанного инструмента пока пусто.";
    }

    public static List<String[]> getWrittenOffToolsForRestore() {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, name, inv_number FROM tools WHERE status = 'WRITTEN_OFF' ORDER BY name")) {
            while (rs.next()) {
                list.add(new String[]{
                        String.valueOf(rs.getInt("id")),
                        rs.getString("name"),
                        rs.getString("inv_number")
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static String restoreToolToStock(int toolId) {
        try (Connection conn = getConnection()) {
            PreparedStatement psCheck = conn.prepareStatement("SELECT name, inv_number FROM tools WHERE id = ?");
            psCheck.setInt(1, toolId);
            ResultSet rs = psCheck.executeQuery();
            if (!rs.next()) return "❌ Инструмент не найден.";

            String name = rs.getString("name");
            String inv = rs.getString("inv_number");

            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tools SET status = 'IN_STOCK', write_off_reason = NULL, written_off_at = NULL WHERE id = ?");
            ps.setInt(1, toolId);
            ps.executeUpdate();

            return String.format("✅ <b>Инструмент успешно восстановлен из архива на склад!</b>\n\n🪛 <b>%s</b>\n🔢 Инв. №: <code>%s</code>\n📦 Теперь он снова доступен для выдачи.", name, inv);
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка базы данных при восстановлении.";
        }
    }

    public static class ParsedOrsh {
        public String number;
        public String address;
        public String location;
    }

    public static String saveImportedOrsh(List<ParsedOrsh> list) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM orsh_inspections");
            }

            String sql = "INSERT INTO orsh_inspections (orsh_number, address, location) VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (ParsedOrsh o : list) {
                    ps.setString(1, o.number);
                    ps.setString(2, o.address);
                    ps.setString(3, o.location);
                    ps.executeUpdate();
                }
            }
            conn.commit();
            return "✅ <b>План осмотра ОРШ успешно загружен!</b>\nДобавлено шкафов: <b>" + list.size() + "</b> шт.";
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка при сохранении плана ОРШ.";
        }
    }

    public static List<String[]> getPendingOrshList() {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, orsh_number, address FROM orsh_inspections WHERE status = 'PENDING' ORDER BY id")) {
            while (rs.next()) {
                list.add(new String[]{ rs.getString("id"), rs.getString("orsh_number"), rs.getString("address") });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public static String[] getOrshById(int id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT orsh_number, address, location FROM orsh_inspections WHERE id = ?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new String[]{ rs.getString("orsh_number"), rs.getString("address"), rs.getString("location") };
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    public static void markOrshCompleted(int id, String workerName, boolean isProblem, String reason) {
        String status = isProblem ? "PROBLEM" : "COMPLETED";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE orsh_inspections SET status = ?, reason = ?, inspected_by = ?, inspected_at = datetime('now', 'localtime') WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, reason);
            ps.setString(3, workerName);
            ps.setInt(4, id);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public static String getOrshStatistics() {
        StringBuilder sb = new StringBuilder("📊 <b>Статистика осмотра ОРШ:</b>\n\n");
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                    "SELECT status, COUNT(*) as cnt FROM orsh_inspections GROUP BY status");
            int total = 0, completed = 0, pending = 0, problem = 0;
            while (rs.next()) {
                int count = rs.getInt("cnt");
                total += count;
                switch (rs.getString("status")) {
                    case "COMPLETED" -> completed = count;
                    case "PENDING" -> pending = count;
                    case "PROBLEM" -> problem = count;
                }
            }
            sb.append(String.format("Всего в плане: <b>%d шт.</b>\n✅ Проверено: <b>%d шт.</b>\n⏳ Осталось: <b>%d шт.</b>\n⚠️ Проблемные: <b>%d шт.</b>\n\n", total, completed, pending, problem));

            if (problem > 0) {
                sb.append("🚨 <b>Проблемные ОРШ:</b>\n");
                ResultSet rp = stmt.executeQuery("SELECT orsh_number, reason, inspected_by FROM orsh_inspections WHERE status = 'PROBLEM'");
                while (rp.next()) {
                    sb.append(String.format("• <b>%s</b> — %s <i>(%s)</i>\n", rp.getString("orsh_number"), rp.getString("reason"), rp.getString("inspected_by")));
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return sb.toString();
    }
}