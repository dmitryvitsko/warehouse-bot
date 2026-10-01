import java.sql.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DatabaseManager {
    private static final String DB_URL = "jdbc:sqlite:warehouse.db";

    // ВАШ TELEGRAM ID АДМИНИСТРАТОРА (МОЛ)
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

            try {
                stmt.execute("ALTER TABLE transactions ADD COLUMN phone_number TEXT;");
            } catch (SQLException ignored) {
            }
            try {
                stmt.execute("ALTER TABLE transactions ADD COLUMN closing_code TEXT;");
            } catch (SQLException ignored) {
            }

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
            // 7. Таблица инструмента
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

            // Добавляем колонки для причины и даты списания (если их еще нет)
            try {
                stmt.execute("ALTER TABLE tools ADD COLUMN write_off_reason TEXT;");
            } catch (SQLException ignored) {}
            try {
                stmt.execute("ALTER TABLE tools ADD COLUMN written_off_at DATETIME;");
            } catch (SQLException ignored) {}

            // 8. Таблица планового осмотра ОРШ
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
        try (Connection conn = getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT role FROM users WHERE id = ?");
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("role");
            } else {
                // Если это вы (ADMIN_ID), то даем админа, остальным — режим ожидания PENDING
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
        } catch (SQLException e) {
            e.printStackTrace();
        }
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
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return names;
    }

    public static void bindUserToExcelName(long userId, String excelName) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO user_excel_names (user_id, excel_name) VALUES (?, ?) ON CONFLICT(user_id) DO UPDATE SET excel_name = excluded.excel_name")) {
            ps.setLong(1, userId);
            ps.setString(2, excelName);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static String getUserExcelName(long userId) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT excel_name FROM user_excel_names WHERE user_id = ?")) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("excel_name");
        } catch (SQLException e) {
            e.printStackTrace();
        }
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

    public static String getFormattedSchedule(String excelName) {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                         SELECT month_name, year_val, day_num, start_time, end_time, status_code 
                         FROM schedules 
                         WHERE excel_name = ? 
                         ORDER BY day_num ASC
                     """)) {
            ps.setString(1, excelName);
            ResultSet rs = ps.executeQuery();

            int weekNumber = 1;
            int counter = 0;
            boolean hasData = false;

            while (rs.next()) {
                if (!hasData) {
                    // Пишем заголовок только один раз
                    String month = rs.getString("month_name");
                    int year = rs.getInt("year_val");
                    sb.append(String.format("🗓 <b>Ваш график на %s %d г.</b>\n", month, year));
                    sb.append(String.format("👤 Сотрудник: <b>%s</b>\n\n", excelName));
                    sb.append(String.format("➖ <b>Неделя %d</b> ➖➖➖➖➖➖\n", weekNumber));
                    hasData = true;
                }

                int dayNum = rs.getInt("day_num");
                int year = rs.getInt("year_val");
                String monthName = rs.getString("month_name");
                int monthNum = getMonthNumber(monthName);
                String dayOfWeek = getDayOfWeekRu(year, monthNum, dayNum);

                String start = rs.getString("start_time");
                String end = rs.getString("end_time");
                String statusCode = rs.getString("status_code");

                // Формируем диапазон времени
                String timeRange = "";
                if (start != null && !start.isEmpty() && end != null && !end.isEmpty()) {
                    timeRange = start + " - " + end;
                }

                // Каждые 7 дней делаем разделитель новой недели
                if (counter > 0 && counter % 7 == 0) {
                    weekNumber++;
                    sb.append(String.format("\n➖ <b>Неделя %d</b> ➖➖➖➖➖➖\n", weekNumber));
                }

                String line;
                if ("В".equals(statusCode)) {
                    line = String.format("🏖 <u><b>%s, %02d</b></u> — Выходной", dayOfWeek, dayNum);
                } else if ("Д".equals(statusCode)) {
                    line = String.format("🚨 <b>%s, %02d</b> — Дежурство", dayOfWeek, dayNum);
                } else if ("О".equals(statusCode)) {
                    line = String.format("🌴 <b>%s, %02d</b> — Отпуск", dayOfWeek, dayNum);
                } else {
                    String icon = "🟩"; // По умолчанию утренняя смена
                    // Если смена начинается с 11, 12, 13 или 14 часов — это вторая смена
                    if (start != null && (start.startsWith("11:") || start.startsWith("12:") ||
                            start.startsWith("13:") || start.startsWith("14:"))) {
                        icon = "🟧";
                    }
                    line = String.format("%s <b>%s, %02d</b> — %s", icon, dayOfWeek, dayNum, timeRange);
                }

                sb.append(line).append("\n");
                counter++;
            }

            if (!hasData) {
                return "ℹ️ График для сотрудника <b>" + excelName + "</b> не найден в базе. Попросите администратора загрузить файл.";
            }

        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка при чтении графика из базы данных.";
        }
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
        } catch (SQLException e) {
            e.printStackTrace();
        }
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
        } catch (SQLException e) {
            e.printStackTrace();
        }
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
        } catch (SQLException e) {
            e.printStackTrace();
        }
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
    // Получить список ID всех пользователей, у которых есть материалы на руках
    public static List<Long> getUsersWithBalances() {
        List<Long> users = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT DISTINCT user_id FROM employee_balances WHERE quantity > 0.00001"
             )) {
            while (rs.next()) {
                users.add(rs.getLong("user_id"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return users;
    }
    // Получить список ID всех зарегистрированных пользователей бота (для рассылки)
    public static List<Long> getAllUserIds() {
        List<Long> users = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id FROM users")) {
            while (rs.next()) {
                users.add(rs.getLong("id"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return users;
    }
    // Вывод списка всех пользователей с командами для блокировки
    public static String getUsersListText() {
        StringBuilder sb = new StringBuilder("👥 <b>Список пользователей бота:</b>\n\n");
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, full_name, role FROM users")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String role = rs.getString("role");

                String status = switch (role) {
                    case "BANNED" -> "🚫 ЗАБЛОКИРОВАН";
                    case "ADMIN" -> "👑 АДМИН";
                    case "PENDING" -> "⏳ ОЖИДАЕТ ОДОБРЕНИЯ";
                    default -> "👷‍♂️ МАСТЕР";
                };

                // Добавлена строка "Связь: Написать в ЛС" с глубокой ссылкой Telegram
                sb.append(String.format("👤 <b>%s</b>\n   ID: <code>%d</code>\n   Связь: <a href=\"tg://user?id=%d\">Написать в ЛС</a>\n   Статус: %s\n",
                        rs.getString("full_name"), id, id, status));

                // СНАЧАЛА проверяем на бан и ожидание
                if ("BANNED".equals(role) || "PENDING".equals(role)) {
                    sb.append(String.format("   Разблокировать/Одобрить: /unban_%d\n", id));
                }
                // А ЗАТЕМ уже выводим кнопку блокировки для всех обычных мастеров (не админов)
                else if (!"ADMIN".equals(role)) {
                    sb.append(String.format("   Блокировать: /ban_%d\n", id));
                }
                sb.append("\n");
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return sb.toString();
    }

    // Блокировка или разблокировка пользователя
    public static String setBanStatus(long targetUserId, boolean ban) {
        String newRole = ban ? "BANNED" : "WORKER";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET role = ? WHERE id = ? AND role != 'ADMIN'")) {
            ps.setString(1, newRole);
            ps.setLong(2, targetUserId);
            int updated = ps.executeUpdate();
            if (updated > 0) return ban ? "✅ Пользователь " + targetUserId + " заблокирован. Бот больше не будет ему отвечать." : "✅ Пользователь разблокирован.";
            return "❌ Пользователь не найден или это администратор (которого нельзя заблокировать).";
        } catch (SQLException e) { return "❌ Ошибка базы данных."; }
    }
    // Класс для временного хранения данных из Excel
    public static class ParsedTool {
        public String invNumber;
        public String name;
        public int quantity;
    }

    // Сохранение инструмента из Excel
    public static String saveImportedTools(List<ParsedTool> tools) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            // Если вы загружаете базу первый раз, можем просто добавлять.
            String sql = "INSERT INTO tools (name, inv_number, status) VALUES (?, ?, 'IN_STOCK')";

            int addedCount = 0;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {

                for (ParsedTool t : tools) {
                    int qty = t.quantity > 0 ? t.quantity : 1; // Защита от нулевого количества
                    for (int i = 1; i <= qty; i++) {
                        ps.setString(1, t.name);

                        // Логика номеров: если нет номера, пишем "Б/Н - 1", "Б/Н - 2"
                        // Если номер есть, но количество > 1, пишем "112233 (1)", "112233 (2)"
                        String inv = (t.invNumber == null || t.invNumber.trim().isEmpty())
                                ? "Б/Н - " + i
                                : (qty > 1 ? t.invNumber + " (" + i + ")" : t.invNumber);

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
    // ==========================================
    // ЛОГИКА УЧЕТА ИНСТРУМЕНТА
    // ==========================================

    // 1. Посмотреть свой инструмент (для мастера)
    public static String getUserToolsText(long userId) {
        StringBuilder sb = new StringBuilder("🪛 <b>Ваш закрепленный инструмент:</b>\n\n");
        boolean hasTools = false;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name, inv_number FROM tools WHERE status = 'ASSIGNED' AND assigned_to = ? ORDER BY name")) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            int counter = 1;
            while (rs.next()) {
                hasTools = true;
                sb.append(String.format("%d. <b>%s</b> (Инв. №: <code>%s</code>)\n",
                        counter++, rs.getString("name"), rs.getString("inv_number")));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return hasTools ? sb.toString() : "🪛 За вами пока не закреплен инструмент.";
    }

    // 2. Аудит инструмента (для админа)
    public static String getToolsAuditText() {
        StringBuilder sb = new StringBuilder("📊 <b>Аудит инструмента:</b>\n\n");
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // На складе
            ResultSet rsStock = stmt.executeQuery("SELECT COUNT(*) FROM tools WHERE status = 'IN_STOCK'");
            int inStock = rsStock.next() ? rsStock.getInt(1) : 0;
            sb.append("📦 На складе (доступно к выдаче): <b>").append(inStock).append(" шт.</b>\n\n");

            // На руках
            ResultSet rsAssigned = stmt.executeQuery("""
                SELECT u.full_name, COUNT(t.id) as cnt 
                FROM tools t 
                JOIN users u ON t.assigned_to = u.id 
                WHERE t.status = 'ASSIGNED' 
                GROUP BY u.id ORDER BY u.full_name
            """);
            boolean hasAssigned = false;
            sb.append("👥 <b>На руках у сотрудников:</b>\n");
            while (rsAssigned.next()) {
                hasAssigned = true;
                sb.append(String.format(" • %s: <b>%d шт.</b>\n", rsAssigned.getString("full_name"), rsAssigned.getInt("cnt")));
            }
            if (!hasAssigned) sb.append(" <i>(Никому ничего не выдано)</i>\n");

        } catch (SQLException e) { e.printStackTrace(); }
        return sb.toString();
    }
    // Получить сгруппированный список свободного инструмента
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

    // Получить список сотрудников для выдачи
    public static List<String[]> getUsersForToolAssignment() {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, full_name, role FROM users WHERE role != 'BANNED' AND role != 'PENDING' ORDER BY full_name")) {
            while (rs.next()) {
                list.add(new String[]{
                        String.valueOf(rs.getLong("id")),
                        rs.getString("full_name"),
                        rs.getString("role")
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    // Процесс выдачи (закрепления)
    public static String assignTool(int toolId, long userId) {
        try (Connection conn = getConnection()) {
            // Проверяем статус и берем данные конкретной единицы (по ID первой свободной в группе)
            PreparedStatement psCheck = conn.prepareStatement("SELECT name, inv_number FROM tools WHERE id = ? AND status = 'IN_STOCK'");
            psCheck.setInt(1, toolId);
            ResultSet rs = psCheck.executeQuery();
            if (!rs.next()) return "❌ Этот инструмент уже выдан или списан. Попробуйте выбрать заново.";

            String name = rs.getString("name");
            String inv = rs.getString("inv_number");

            // Ищем имя сотрудника
            PreparedStatement psUser = conn.prepareStatement("SELECT full_name FROM users WHERE id = ?");
            psUser.setLong(1, userId);
            ResultSet rsUser = psUser.executeQuery();
            String userName = rsUser.next() ? rsUser.getString("full_name") : "Неизвестный сотрудник";

            // Выдаем
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
    // Получить полное название и инвентарный номер инструмента по его ID
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
    // Получить список сотрудников, у которых есть инструмент на руках
    public static List<String[]> getUsersWithAssignedTools() {
        List<String[]> list = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT DISTINCT u.id, u.full_name 
                     FROM tools t 
                     JOIN users u ON t.assigned_to = u.id 
                     WHERE t.status = 'ASSIGNED' 
                     ORDER BY u.full_name
                 """)) {
            while (rs.next()) {
                list.add(new String[]{
                        String.valueOf(rs.getLong("id")),
                        rs.getString("full_name")
                });
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    // Получить список инструмента конкретного сотрудника для возврата
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

    // Оформление возврата инструмента на склад
    public static String returnToolToWarehouse(int toolId) {
        try (Connection conn = getConnection()) {
            // Узнаем, что именно возвращаем, чтобы красиво написать в ответе
            PreparedStatement psCheck = conn.prepareStatement("SELECT name, inv_number FROM tools WHERE id = ?");
            psCheck.setInt(1, toolId);
            ResultSet rs = psCheck.executeQuery();
            if (!rs.next()) return "❌ Инструмент не найден.";

            String name = rs.getString("name");
            String inv = rs.getString("inv_number");

            // Переводим статус обратно на склад
            PreparedStatement psUpdate = conn.prepareStatement("UPDATE tools SET status = 'IN_STOCK', assigned_to = NULL WHERE id = ?");
            psUpdate.setInt(1, toolId);
            psUpdate.executeUpdate();

            return String.format("✅ <b>Инструмент успешно возвращен на склад!</b>\n\n🪛 <b>%s</b>\n🔢 Инв. №: <code>%s</code>", name, inv);
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка базы данных при возврате инструмента.";
        }
    }
    // Получить конкретные единицы инструмента со склада для списания
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

    // Окончательное списание инструмента с указанием причины
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
    // Получить архив списанного инструмента
    public static String getWrittenOffToolsArchiveText() {
        StringBuilder sb = new StringBuilder("🗄 <b>Архив списанного инструмента:</b>\n\n");
        boolean hasItems = false;
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             // Достаем дату в формате ДД.ММ.ГГГГ с помощью функции strftime
             ResultSet rs = stmt.executeQuery(
                     "SELECT name, inv_number, write_off_reason, strftime('%d.%m.%Y', written_off_at) as wo_date " +
                             "FROM tools WHERE status = 'WRITTEN_OFF' ORDER BY name")) {

            int counter = 1;
            while (rs.next()) {
                hasItems = true;
                String reason = rs.getString("write_off_reason");
                if (reason == null || reason.isEmpty()) reason = "Не указана";

                String date = rs.getString("wo_date");
                if (date == null) date = "Дата неизвестна"; // Для того инструмента, что списали до этого обновления

                sb.append(String.format("%d. <b>%s</b>\n   • Инв. №: <code>%s</code>\n   • Дата списания: <b>%s</b>\n   • Причина: <i>%s</i>\n\n",
                        counter++, rs.getString("name"), rs.getString("inv_number"), date, reason));
            }
        } catch (SQLException e) { e.printStackTrace(); }

        return hasItems ? sb.toString() : "🗄 В архиве списанного инструмента пока пусто.";
    }
    // Получить список списанного инструмента для восстановления (в виде кнопок)
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

    // Восстановить инструмент на склад
    public static String restoreToolToStock(int toolId) {
        try (Connection conn = getConnection()) {
            PreparedStatement psCheck = conn.prepareStatement("SELECT name, inv_number FROM tools WHERE id = ?");
            psCheck.setInt(1, toolId);
            ResultSet rs = psCheck.executeQuery();
            if (!rs.next()) return "❌ Инструмент не найден.";

            String name = rs.getString("name");
            String inv = rs.getString("inv_number");

            // Меняем статус на IN_STOCK и затираем причину списания
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
    // ==========================================
    // ЛОГИКА ПЛАНОВОГО ОСМОТРА ОРШ
    // ==========================================

    public static class ParsedOrsh {
        public String number;
        public String address;
        public String location;
    }

    // Сохранение нового плана осмотра (старый удаляется)
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

    // Получить список шкафов, которые еще не проверены
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

    // Получить данные конкретного шкафа
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

    // Отметить шкаф как проверенный или проблемный
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

    // Сводка для админа
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
