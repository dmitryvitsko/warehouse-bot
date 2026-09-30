import java.sql.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:warehouse.db";

    // ВАШ TELEGRAM ID АДМИНИСТРАТОРА (МОЛ)
    public static final long ADMIN_ID = 576227060L;

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

            // 1. Таблица сотрудников
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY,
                    full_name TEXT NOT NULL,
                    role TEXT DEFAULT 'WORKER'
                );
            """);

            // 2. Таблица материалов на складе (с поддержкой конвертации единиц)
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

            // Безопасное добавление новых колонок для конвертера (если их еще нет)
            try {
                stmt.execute("ALTER TABLE materials ADD COLUMN original_unit TEXT DEFAULT 'шт';");
                stmt.execute("ALTER TABLE materials ADD COLUMN work_unit TEXT DEFAULT 'шт';");
                stmt.execute("ALTER TABLE materials ADD COLUMN conv_factor REAL DEFAULT 1.0;");
            } catch (SQLException ignored) {
                // Колонки уже добавлены, всё в порядке
            }

            // 3. Таблица подотчета мастеров
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS employee_balances (
                    user_id INTEGER,
                    material_id INTEGER,
                    quantity REAL DEFAULT 0.0,
                    PRIMARY KEY (user_id, material_id)
                );
            """);

            // 4. Журнал всех операций
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

            // 4.1. Таблица заявок на возврат материала на склад (ожидающих подтверждения МОЛ)
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

            // 5. Таблица тарифов (Приказ РУП «Белтелеком», вводятся с 10.11.2025 — физ. лица с НДС)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS service_tariffs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    service_name TEXT NOT NULL,
                    unit TEXT NOT NULL,
                    price_with_vat REAL NOT NULL,
                    is_single INTEGER DEFAULT 0
                );
            """);


            // Умная проверка: если таблица пустая ИЛИ все цены равны нулю, заполняем правильным прайсом
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

            // 6. Таблицы для графиков работы
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_excel_names (
                    user_id INTEGER PRIMARY KEY,
                    excel_name TEXT NOT NULL
                );
            """);

            // Заменили DROP TABLE на безопасное CREATE TABLE IF NOT EXISTS
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

            System.out.println("✅ База данных warehouse.db и все таблицы успешно готовы к работе!");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static String saveImportedMaterials(List<ExcelImporter.MaterialRow> rows) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {
                // Обнуляем витрину склада и старые данные отчетов перед загрузкой свежей ведомости
                stmt.execute("UPDATE materials SET warehouse_qty = 0, start_qty = 0, in_qty = 0, out_qty = 0, start_sum = 0, end_sum = 0;");
            }

            // Большой запрос, который записывает ВСЕ новые поля
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
                    ps.setDouble(8, r.qty); // Тут уже пересчитанное количество (метры, штуки)
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
        String defaultRole = (userId == ADMIN_ID) ? "ADMIN" : "WORKER";
        try (Connection conn = getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT role FROM users WHERE id = ?");
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("role");
            } else {
                PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO users (id, full_name, role) VALUES (?, ?, ?)");
                insert.setLong(1, userId);
                insert.setString(2, fullName);
                insert.setString(3, defaultRole);
                insert.executeUpdate();
                return defaultRole;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return defaultRole;
        }
    }

    public static String getServiceTariffsText() {
        StringBuilder sb = new StringBuilder("📋 <b>Утвержденные тарифы (с НДС):</b>\n");
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             // Нам больше не нужен ID из базы для сортировки категорий
             ResultSet rs = stmt.executeQuery("SELECT service_name, unit, price_with_vat FROM service_tariffs ORDER BY id")) {

            int currentCategory = -1;
            int rowNum = 1; // Заводим собственный независимый счетчик строк

            while (rs.next()) {
                int categoryGroup = getCategoryGroup(rowNum); // Группируем по счетчику

                if (categoryGroup != currentCategory) {
                    sb.append("\n").append(getCategoryHeader(categoryGroup)).append("\n");
                    currentCategory = categoryGroup;
                }

                double price = rs.getDouble("price_with_vat");
                String priceStr = (price == 0) ? "<i>(не задана)</i>" : String.format(Locale.US, "<b>%.2f руб.</b>", price);

                sb.append(String.format(" ▪️ %s (<i>%s</i>) — %s\n",
                        rs.getString("service_name"),
                        rs.getString("unit"),
                        priceStr));

                rowNum++; // Увеличиваем счетчик для следующей услуги
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return sb.toString();
    }

    // Помощник 1: Определяет номер группы по ID услуги (т.к. мы загружали их по порядку)
    private static int getCategoryGroup(int id) {
        if (id <= 2) return 1;
        if (id <= 6) return 2;
        if (id <= 9) return 3;
        if (id <= 13) return 4;
        if (id <= 15) return 5;
        if (id <= 17) return 6;
        return 7;
    }

    // Помощник 2: Возвращает красивый заголовок для группы
    private static String getCategoryHeader(int group) {
        return switch (group) {
            case 1 -> "🛠 <b>ОБЩИЕ УСЛУГИ</b>";
            case 2 -> "🔌 <b>РОЗЕТКИ И ШНУРЫ</b>";
            case 3 -> "🔄 <b>ЗАМЕНА ПРОВОДКИ (ЦЕЛИКОМ)</b>";
            case 4 -> "✂️ <b>ОКОНЕЧИВАНИЕ И СВАРКА</b>";
            case 5 -> "🕳 <b>ОТВЕРСТИЯ И КРЕПЛЕНИЕ</b>";
            case 6 -> "🗑 <b>ДЕМОНТАЖ</b>";
            case 7 -> "📏 <b>ПРОКЛАДКА КАБЕЛЯ И КОРОБОВ</b>";
            default -> "🔹 <b>ПРОЧИЕ УСЛУГИ</b>";
        };
    }

    // Теперь эта команда будет обновлять цену для уже существующих коротких названий
    public static boolean updateServicePrice(String name, double price) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE service_tariffs SET price_with_vat = ? WHERE service_name = ?")) {
            ps.setDouble(1, price);
            ps.setString(2, name.trim());
            int updated = ps.executeUpdate();
            return updated > 0; // Вернет true, если услуга найдена и обновлена
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    // Список материалов на складе (счетами 10.01 и 10.05) для отображения и кнопок
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
                double priceNoVat = priceWithVat / 1.20; // Вычисляем цену без НДС на лету

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
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // Получение одной позиции у мастера для начала диалога списания (безопасно для длинных названий)
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
        } catch (SQLException e) {
            e.printStackTrace();
        }
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
                        rs.getString("name"),
                        rs.getString("code"),
                        fmtQty(qty),
                        rs.getString("work_unit"),
                        fmtPrice(totalSum),
                        fmtPrice(priceVat),
                        rs.getString("work_unit")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
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
                        rs.getString("name"),
                        rs.getString("code"),
                        fmtQty(qty),
                        rs.getString("work_unit"),
                        fmtPrice(totalSum)));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
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
                        String.valueOf(rs.getInt("id")),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("work_unit"),
                        fmtQty(rs.getDouble("quantity"))
                });
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public static String completeWriteOff(long userId, WriteOffSession s) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            PreparedStatement psCheck = conn.prepareStatement(
                    "SELECT quantity FROM employee_balances WHERE user_id = ? AND material_id = ?");
            psCheck.setLong(1, userId);
            psCheck.setInt(2, s.materialId);
            ResultSet rs = psCheck.executeQuery();

            if (!rs.next() || rs.getDouble("quantity") + 1e-9 < s.quantity) {
                return "❌ Ошибка: у вас на руках недостаточно этого материала.";
            }

            PreparedStatement psUpdate = conn.prepareStatement(
                    "UPDATE employee_balances SET quantity = ROUND(quantity - ?, 6) WHERE user_id = ? AND material_id = ?");
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
                
                • <b>Код 212</b> — Работы на участке от ОРК до ОРА <b>со списанием материалов</b>: перемонтаж ВОК-1 на ОРК или на участке, замена ВОК-1 от ОРК до ОРА, монтаж короба с дюбелями.
                <i>❌ Не используется для работ внутри квартиры/офиса (там списание идет по квитанции).</i>
                ⚠️ <b>ОСТОРОЖНО (повторность 6 мес.):</b> Нельзя закрывать заявку кодом 212 повторно в течение 6 месяцев по одному абоненту! Если с прошлого кода 212 не прошло полгода — используйте код <b>227</b>.
                
                • <b>Код 227</b> — Выправление положения волокна, устранение загиба (увеличенного затухания) без списания материала.
                ✅ <b>Безопасный код:</b> НЕ является повторным! Можно смело использовать каждый раз (в том числе если менее 6 мес. назад уже был код 212).
                
                📍 <b>Работы в ОРШ (оптический распределительный шкаф):</b>
                
                • <b>Код 215</b> — Устранение повреждения в ОРШ <b>без материалов</b>: выправление положения оптического пигтейла или волокна в ОРШ, улучшившее сигнал.
                
                • <b>Код 226</b> — Устранение повреждения в ОРШ <b>с использованием материалов</b>: замена пигтейла, замена оптического адаптера в ОРШ и др.
                
                📍 <b>Участок ОРШ ↔ ОРК (райзер-кабель):</b>
                
                • <b>Код 214</b> — Ремонт или замена райзер-кабеля на участке ОРШ–ОРК, вставка участка райзер-кабеля с муфтами (любая работа с материалами по ремонту райзера).
                
                • <b>Код 217</b> — Переход на свободный запасной модуль и волокно в райзер-кабеле на участке ОРШ–ОРК (например, крыса повредила рабочий модуль, разварили свободный модуль на ОРК и ОРШ, из материалов — только гильзы КДЗС).
                """;
    }
    // Информация о созданной заявке на возврат для отправки уведомления МОЛ
    public static class ReturnRequestInfo {
        public boolean success;
        public String messageForWorker;
        public String messageForAdmin;
        public int requestId;
    }

    // 1. Создание заявки на возврат мастером (материал еще НЕ списывается)
    public static ReturnRequestInfo createReturnRequest(long userId, int materialId, double qtyToReturn) {
        ReturnRequestInfo info = new ReturnRequestInfo();
        if (qtyToReturn <= 0) {
            info.success = false;
            info.messageForWorker = "❌ Количество для возврата должно быть больше нуля!";
            return info;
        }

        try (Connection conn = getConnection()) {
            // Проверяем, сколько материала у сотрудника на руках
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

            // Проверяем, сколько уже висит в неподтвержденных заявках на возврат
            double pendingQty = 0.0;
            try (PreparedStatement psPend = conn.prepareStatement("""
                SELECT COALESCE(SUM(quantity), 0) FROM return_requests
                WHERE user_id = ? AND material_id = ? AND status = 'PENDING'
            """)) {
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

            // Создаем заявку со статусом PENDING
            try (PreparedStatement psIns = conn.prepareStatement("""
                INSERT INTO return_requests (user_id, material_id, quantity, status)
                VALUES (?, ?, ?, 'PENDING')
            """, Statement.RETURN_GENERATED_KEYS)) {
                psIns.setLong(1, userId);
                psIns.setInt(2, materialId);
                psIns.setDouble(3, qtyToReturn);
                psIns.executeUpdate();
                ResultSet keys = psIns.getGeneratedKeys();
                if (keys.next()) {
                    info.requestId = keys.getInt(1);
                }
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

    // 2. Подтверждение возврата Администратором (списание с мастера и возврат на склад)
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

            if (!rs.next()) {
                return new String[]{"ERROR", "0", "❌ Заявка №" + requestId + " не найдена."};
            }

            String status = rs.getString("status");
            if (!"PENDING".equals(status)) {
                return new String[]{"ERROR", "0", "ℹ️ Эта заявка (№" + requestId + ") уже была обработана ранее (статус: " + status + ")."};
            }

            long workerId = rs.getLong("user_id");
            int materialId = rs.getInt("material_id");
            double qty = rs.getDouble("quantity");
            String code = rs.getString("code");
            String name = rs.getString("name");
            String unit = rs.getString("work_unit");
            double priceVat = rs.getDouble("price_with_vat");
            String workerName = rs.getString("full_name");

            // Проверяем текущий баланс сотрудника
            PreparedStatement psBal = conn.prepareStatement(
                    "SELECT quantity FROM employee_balances WHERE user_id = ? AND material_id = ?");
            psBal.setLong(1, workerId);
            psBal.setInt(2, materialId);
            ResultSet rsBal = psBal.executeQuery();

            if (!rsBal.next() || rsBal.getDouble("quantity") + 1e-9 < qty) {
                return new String[]{"ERROR", String.valueOf(workerId),
                        "❌ Ошибка: у сотрудника " + workerName + " на руках уже меньше материала, чем указано в заявке (возможно, он успел его списать)."};
            }

            // 1. Списываем с подотчета сотрудника
            PreparedStatement psUpdWorker = conn.prepareStatement(
                    "UPDATE employee_balances SET quantity = ROUND(quantity - ?, 6) WHERE user_id = ? AND material_id = ?");
            psUpdWorker.setDouble(1, qty);
            psUpdWorker.setLong(2, workerId);
            psUpdWorker.setInt(3, materialId);
            psUpdWorker.executeUpdate();

            // 2. Возвращаем на склад МОЛ
            PreparedStatement psUpdWarehouse = conn.prepareStatement(
                    "UPDATE materials SET warehouse_qty = ROUND(warehouse_qty + ?, 6) WHERE id = ?");
            psUpdWarehouse.setDouble(1, qty);
            psUpdWarehouse.setInt(2, materialId);
            psUpdWarehouse.executeUpdate();

            // 3. Отмечаем заявку как выполненную
            PreparedStatement psUpdReq = conn.prepareStatement(
                    "UPDATE return_requests SET status = 'APPROVED' WHERE id = ?");
            psUpdReq.setInt(1, requestId);
            psUpdReq.executeUpdate();

            // 4. Записываем в журнал транзакций
            PreparedStatement psLog = conn.prepareStatement("""
                INSERT INTO transactions (type, user_id, material_id, quantity, total_sum_with_vat, write_off_reason)
                VALUES ('RETURN', ?, ?, ?, ?, 'Возврат на склад (подтверждено МОЛ)')
            """);
            psLog.setLong(1, workerId);
            psLog.setInt(2, materialId);
            psLog.setDouble(3, qty);
            psLog.setDouble(4, qty * priceVat);
            psLog.executeUpdate();

            conn.commit();

            String msgAdmin = String.format("""
                    ✅ <b>Возврат №%d подтвержден!</b>
                    • Сотрудник: <b>%s</b>
                    • Материал: <b>%s</b> [<code>%s</code>]
                    • Возвращено на склад: <b>%s %s</b>""",
                    requestId, workerName, name, code, fmtQty(qty), unit);

            String msgWorker = String.format("""
                    ✅ <b>МОЛ подтвердил ваш возврат (заявка №%d)!</b>
                    • Материал: <b>%s</b> [<code>%s</code>]
                    • Списано с вашего подотчета на склад: <b>%s %s</b>""",
                    requestId, name, code, fmtQty(qty), unit);

            return new String[]{"OK", String.valueOf(workerId), msgAdmin, msgWorker};

        } catch (SQLException e) {
            e.printStackTrace();
            return new String[]{"ERROR", "0", "❌ Ошибка базы данных при подтверждении возврата."};
        }
    }

    // 3. Отклонение заявки на возврат Администратором
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

            if (!rs.next()) {
                return new String[]{"ERROR", "0", "❌ Заявка №" + requestId + " не найдена."};
            }

            String status = rs.getString("status");
            if (!"PENDING".equals(status)) {
                return new String[]{"ERROR", "0", "ℹ️ Эта заявка (№" + requestId + ") уже была обработана ранее."};
            }

            long workerId = rs.getLong("user_id");
            double qty = rs.getDouble("quantity");
            String code = rs.getString("code");
            String name = rs.getString("name");
            String unit = rs.getString("work_unit");
            String workerName = rs.getString("full_name");

            PreparedStatement psUpd = conn.prepareStatement(
                    "UPDATE return_requests SET status = 'REJECTED' WHERE id = ?");
            psUpd.setInt(1, requestId);
            psUpd.executeUpdate();

            String msgAdmin = String.format("""
                    ❌ <b>Вы отклонили заявку на возврат №%d.</b>
                    Материал <b>%s</b> (%s %s) остался в подотчете за сотрудником <b>%s</b>.""",
                    requestId, name, fmtQty(qty), unit, workerName);

            String msgWorker = String.format("""
                    ❌ <b>МОЛ отклонил вашу заявку на возврат №%d.</b>
                    Материал <b>%s</b> [<code>%s</code>] в количестве <b>%s %s</b> остается в вашем подотчете.""",
                    requestId, name, code, fmtQty(qty), unit);

            return new String[]{"OK", String.valueOf(workerId), msgAdmin, msgWorker};

        } catch (SQLException e) {
            e.printStackTrace();
            return new String[]{"ERROR", "0", "❌ Ошибка базы данных при отклонении возврата."};
        }
    }
    // Получение списка всех администраторов (МОЛ) из базы данных для рассылки уведомлений
    public static List<Long> getAdminIds() {
        List<Long> admins = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM users WHERE role = 'ADMIN'")) {
            while (rs.next()) {
                admins.add(rs.getLong("id"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return admins;
    }

    // ==========================================
    // ЛОГИКА ГРАФИКОВ РАБОТ
    // ==========================================

    public static class ScheduleDay {
        public String excelName;
        public String monthName;
        public int yearVal;
        public int dayNum;
        public String startTime;
        public String endTime;
        public String statusCode; // 'В', 'О', 'Д'
    }

    // Сохранение загруженного графика в базу
    public static String saveSchedule(List<ScheduleDay> days) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM schedules"); // Очищаем старый месяц
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO schedules (excel_name, month_name, year_val, day_num, start_time, end_time, status_code) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
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
             ResultSet rs = stmt.executeQuery("SELECT DISTINCT excel_name FROM schedules ORDER BY excel_name")) {
            while (rs.next()) names.add(rs.getString("excel_name"));
        } catch (SQLException e) { e.printStackTrace(); }
        return names;
    }

    public static void bindUserToExcelName(long userId, String excelName) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO user_excel_names (user_id, excel_name) VALUES (?, ?) ON CONFLICT(user_id) DO UPDATE SET excel_name = excluded.excel_name")) {
            ps.setLong(1, userId);
            ps.setString(2, excelName);
            ps.executeUpdate();
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

    // Вспомогательный метод перевода месяца в число
    private static int getMonthNumber(String monthName) {
        String m = monthName.toUpperCase();
        if (m.contains("ЯНВАР")) return 1;
        if (m.contains("ФЕВРАЛ")) return 2;
        if (m.contains("МАРТ")) return 3;
        if (m.contains("АПРЕЛ")) return 4;
        if (m.contains("МА")) return 5;
        if (m.contains("ИЮН")) return 6;
        if (m.contains("ИЮЛ")) return 7;
        if (m.contains("АВГУСТ")) return 8;
        if (m.contains("СЕНТЯБР")) return 9;
        if (m.contains("ОКТЯБР")) return 10;
        if (m.contains("НОЯБР")) return 11;
        if (m.contains("ДЕКАБР")) return 12;
        return java.time.LocalDate.now().getMonthValue();
    }

    // Вспомогательный метод вычисления дня недели
    private static String getDayOfWeekRu(int year, int month, int day) {
        try {
            java.time.DayOfWeek dow = java.time.LocalDate.of(year, month, day).getDayOfWeek();
            return switch (dow) {
                case MONDAY -> "Пн";
                case TUESDAY -> "Вт";
                case WEDNESDAY -> "Ср";
                case THURSDAY -> "Чт";
                case FRIDAY -> "Пт";
                case SATURDAY -> "Сб";
                case SUNDAY -> "Вс";
            };
        } catch (Exception e) {
            return "";
        }
    }

    // Сформировать красивый график для сотрудника с днями недели
    public static String getFormattedSchedule(String excelName) {
        String month = "текущий месяц";
        int year = java.time.LocalDate.now().getYear();
        int monthNum = java.time.LocalDate.now().getMonthValue();

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT month_name, year_val FROM schedules WHERE excel_name = ? LIMIT 1")) {
            ps.setString(1, excelName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                month = rs.getString("month_name");
                year = rs.getInt("year_val");
                monthNum = getMonthNumber(month);
            }
        } catch (SQLException e) {}

        StringBuilder sb = new StringBuilder("🗓 <b>Ваш график на ").append(month.toUpperCase()).append(" ").append(year).append(" г. (").append(excelName).append("):</b>\n\n");
        boolean found = false;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT day_num, start_time, end_time, status_code FROM schedules WHERE excel_name = ? ORDER BY day_num")) {
            ps.setString(1, excelName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                found = true;
                int day = rs.getInt("day_num");
                String status = rs.getString("status_code");

                String dow = getDayOfWeekRu(year, monthNum, day);
                // Делаем выходные (Сб, Вс) жирными, чтобы они выделялись
                if (dow.equals("Сб") || dow.equals("Вс")) dow = "<u>" + dow + "</u>";

                String dayPrefix = String.format("<b>%s, %02d число</b>", dow, day);

                if ("В".equals(status)) {
                    sb.append("🏖 ").append(dayPrefix).append(" — Выходной\n");
                } else if ("О".equals(status)) {
                    sb.append("🌴 ").append(dayPrefix).append(" — Отпуск\n");
                } else if ("Д".equals(status)) {
                    sb.append("🚨 ").append(dayPrefix).append(" — Дежурство\n");
                } else {
                    String start = rs.getString("start_time");
                    String end = rs.getString("end_time");
                    String emo = "🟦";
                    if (start.startsWith("08")) emo = "🟩"; // Утро
                    else if (start.startsWith("12") || start.startsWith("13")) emo = "🟧"; // Вторая смена

                    sb.append(emo).append(" ").append(dayPrefix).append(" — ").append(start).append(" - ").append(end).append("\n");
                }
            }
        } catch (SQLException e) { e.printStackTrace(); }

        if (!found) return "ℹ️ График для <b>" + excelName + "</b> пока не загружен. Обратитесь к МОЛ.";
        return sb.toString();
    }
    // ==========================================
    // ЛОГИКА КАЛЬКУЛЯТОРА КВИТАНЦИЙ
    // ==========================================

    public static class ReceiptItem {
        public int dbId;
        public String name;
        public String unit;
        public double quantity;
        public double priceWithVatPerUnit;
        public boolean isMaterial;
        public boolean isSingle; // <-- Добавили признак разовой услуги
    }

    public static class ReceiptSession {
        public List<ReceiptItem> items = new ArrayList<>();
        public int waitingServiceId = -1;
        public int waitingMaterialId = -1;
    }

    // Получить список всех услуг для кнопок
    public static List<String[]> getAllServicesForReceipt() {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, service_name, unit, price_with_vat FROM service_tariffs ORDER BY id")) {
            while (rs.next()) {
                list.add(new String[]{
                        String.valueOf(rs.getInt("id")),
                        rs.getString("service_name"),
                        rs.getString("unit"),
                        String.valueOf(rs.getDouble("price_with_vat"))
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    // Получить конкретную услугу по ID
    public static ReceiptItem getServiceById(int id) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT service_name, unit, price_with_vat, is_single FROM service_tariffs WHERE id = ?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ReceiptItem item = new ReceiptItem();
                item.dbId = id;
                item.name = rs.getString("service_name");
                item.unit = rs.getString("unit");
                item.priceWithVatPerUnit = rs.getDouble("price_with_vat");
                item.isMaterial = false;
                item.isSingle = rs.getInt("is_single") == 1; // <-- Считываем из БД
                return item;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    // Получить конкретный материал из подотчета мастера по ID
    public static ReceiptItem getMaterialFromBalanceById(long userId, int materialId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 SELECT m.name, m.work_unit, m.price_with_vat 
                 FROM employee_balances b
                 JOIN materials m ON b.material_id = m.id
                 WHERE b.user_id = ? AND m.id = ? AND b.quantity > 0.00001
             """)) {
            ps.setLong(1, userId);
            ps.setInt(2, materialId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ReceiptItem item = new ReceiptItem();
                item.dbId = materialId;
                item.name = rs.getString("name");
                item.unit = rs.getString("work_unit");
                item.priceWithVatPerUnit = rs.getDouble("price_with_vat");
                item.isMaterial = true;
                return item;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    // Метод-помощник для превращения бюрократических единиц в нормальные (шт, м)
    private static String getShortUnit(String fullUnit) {
        String u = fullUnit.toLowerCase();
        if (u.contains("услуг") || u.contains("отверст") || u.contains("устройств")) return "шт";
        if (u.contains("метр")) return "м";
        return fullUnit;
    }

    // Генерация красивого текста квитанции в виде таблицы
    public static String generateReceiptText(ReceiptSession session) {
        if (session.items.isEmpty()) return "🛒 Корзина квитанции пуста.";

        StringBuilder sb = new StringBuilder("🧾 <b>АКТ-КВИТАНЦИЯ (Расчет для заполнения)</b>\n\n");

        double totalServicesVat = 0;
        double totalServicesSum = 0;
        double totalMaterialsVat = 0;
        double totalMaterialsSum = 0;

        // ================= БЛОК 1: УСЛУГИ =================
        sb.append("╔════ 🛠 <b>ВЫПОЛНЕННЫЕ РАБОТЫ</b> ════╗\n");
        boolean hasServices = false;
        for (ReceiptItem item : session.items) {
            if (!item.isMaterial) {
                hasServices = true;
                double sumWithVat = item.quantity * item.priceWithVatPerUnit;
                double vatSum = sumWithVat - (sumWithVat / 1.20);

                totalServicesSum += sumWithVat;
                totalServicesVat += vatSum;

                String displayUnit = getShortUnit(item.unit);

                sb.append(String.format("🔹 %s\n", item.name));
                sb.append(String.format(" ┝ %s %s  х  %.2f  =  <b>%.2f руб.</b>\n",
                        fmtQty(item.quantity), displayUnit, item.priceWithVatPerUnit, sumWithVat));
            }
        }
        if (!hasServices) sb.append("<i>Услуги не добавлялись</i>\n");

        sb.append("╠═══════════════════════════════╣\n");
        sb.append(String.format("Итого по работам: <b>%.2f руб.</b>\n", totalServicesSum));
        sb.append(String.format("(В том числе НДС 20%%: %.2f руб.)\n\n", totalServicesVat));

        // ================= БЛОК 2: МАТЕРИАЛЫ =================
        sb.append("╔══ 📦 <b>ИЗРАСХОДОВАННЫЕ МАТЕРИАЛЫ</b> ══╗\n");
        boolean hasMaterials = false;
        for (ReceiptItem item : session.items) {
            if (item.isMaterial) {
                hasMaterials = true;
                double sumWithVat = item.quantity * item.priceWithVatPerUnit;
                double vatSum = sumWithVat - (sumWithVat / 1.20);

                totalMaterialsSum += sumWithVat;
                totalMaterialsVat += vatSum;

                sb.append(String.format("🔹 %s\n", item.name));
                sb.append(String.format(" ┝ %s %s  х  %.2f  =  <b>%.2f руб.</b>\n",
                        fmtQty(item.quantity), item.unit, item.priceWithVatPerUnit, sumWithVat));
            }
        }
        if (!hasMaterials) sb.append("<i>Материалы не добавлялись</i>\n");

        sb.append("╠═══════════════════════════════╣\n");
        sb.append(String.format("Итого по материалам: <b>%.2f руб.</b>\n", totalMaterialsSum));
        sb.append(String.format("(В том числе НДС 20%%: %.2f руб.)\n\n", totalMaterialsVat));

        // ================= ИТОГО =================
        double finalSum = totalServicesSum + totalMaterialsSum;
        sb.append("═══════════════════════════════════\n");
        sb.append(String.format("💰 <b>ВСЕГО К ОПЛАТЕ: %.2f руб.</b>", finalSum));

        return sb.toString();
    }
}
