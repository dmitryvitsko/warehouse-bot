import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    // Файл базы данных создастся автоматически в корне проекта
    private static final String DB_URL = "jdbc:sqlite:warehouse.db";

    // Ваш личный Telegram ID (Администратор / МОЛ)
    public static final long ADMIN_ID = 129265455L;

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL);
    }

    public static void initDatabase() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Таблица сотрудников
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id BIGINT PRIMARY KEY,
                    full_name TEXT NOT NULL,
                    role TEXT DEFAULT 'WORKER',
                    is_active INTEGER DEFAULT 1
                );
            """);

            // 2. Таблица материалов на складе МОЛ
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS materials (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code TEXT NOT NULL,
                    name TEXT NOT NULL,
                    short_name TEXT,
                    acc_unit TEXT NOT NULL,
                    work_unit TEXT NOT NULL,
                    conversion_factor REAL DEFAULT 1.0,
                    price_no_vat REAL NOT NULL,
                    price_with_vat REAL NOT NULL,
                    warehouse_qty REAL DEFAULT 0.0,
                    UNIQUE(code, price_no_vat)
                );
            """);

            // 3. Таблица материалов на руках у мастеров
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS employee_balances (
                    user_id BIGINT,
                    material_id INTEGER,
                    quantity REAL DEFAULT 0.0,
                    PRIMARY KEY (user_id, material_id)
                );
            """);

            // 4. Таблица истории операций и списаний (с квитанциями и без)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    type TEXT NOT NULL,
                    user_id BIGINT,
                    material_id INTEGER,
                    quantity REAL NOT NULL,
                    is_paid_receipt INTEGER DEFAULT 0,
                    receipt_number TEXT,
                    contract_number TEXT,
                    subscriber_address TEXT,
                    write_off_reason TEXT,
                    total_sum_with_vat REAL DEFAULT 0.0
                );
            """);

            try {
                stmt.execute("ALTER TABLE transactions ADD COLUMN phone_number TEXT;");
            } catch (SQLException ignored) {}

            try {
                stmt.execute("ALTER TABLE transactions ADD COLUMN closing_code TEXT;");
            } catch (SQLException ignored) {}

            // 5. Таблица-шпаргалка со стоимостью услуг для квитанций
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS service_tariffs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category TEXT NOT NULL,
                    service_name TEXT NOT NULL,
                    unit TEXT NOT NULL,
                    price_with_vat REAL NOT NULL
                );
            """);

            // Автоматически добавляем вас как Администратора (МОЛ)
            stmt.execute("""
                INSERT OR IGNORE INTO users (id, full_name, role) 
                VALUES (129265455, 'Дмитрий Викторович (МОЛ)', 'ADMIN');
            """);

            // Заполняем базовые тарифы услуг, если таблица еще пустая
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM service_tariffs");
            if (rs.next() && rs.getInt(1) == 0) {
                stmt.execute("""
                    INSERT INTO service_tariffs (category, service_name, unit, price_with_vat) VALUES
                    ('Вызов', 'Вызов специалиста к абоненту', '1 вызов', 10.80),
                    ('Медный кабель', 'Оконцевание кабеля UTP с одной стороны', '1 шт', 4.38);
                """);
            }

            // Заполняем тестовые материалы на склад (если склад еще пустой)
            ResultSet rsMat = stmt.executeQuery("SELECT COUNT(*) FROM materials");
            if (rsMat.next() && rsMat.getInt(1) == 0) {
                stmt.execute("""
                    INSERT INTO materials (code, name, short_name, acc_unit, work_unit, conversion_factor, price_no_vat, price_with_vat, warehouse_qty) VALUES
                    ('10001', 'Кабель UTP 4PR кат.5е внутренний', 'Кабель UTP 4 пары', 'км', 'м', 1000, 0.25, 0.30, 500),
                    ('10002', 'Коннектор RJ-45 (8P8C) кат.5е', 'Коннектор RJ-45', 'тыс. шт', 'шт', 1000, 0.15, 0.18, 200),
                    ('10003', 'Розетка абонентская RJ-45 1-порт', 'Розетка RJ-45', 'шт', 'шт', 1, 3.50, 4.20, 25);
                """);
            }

            System.out.println("✅ База данных warehouse.db и все таблицы успешно готовы к работе!");

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // Метод для проверки роли пользователя (ADMIN или WORKER)
    public static String getUserRole(long userId, String firstName) {
        try (Connection conn = getConnection()) {
            PreparedStatement ps = conn.prepareStatement("SELECT role FROM users WHERE id = ?");
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("role");
            } else {
                // Если написал новый мастер, автоматически регистрируем его как WORKER
                PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO users (id, full_name, role) VALUES (?, ?, 'WORKER')");
                insert.setLong(1, userId);
                insert.setString(2, firstName);
                insert.executeUpdate();
                return "WORKER";
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "WORKER";
        }
    }
    // Метод для получения текста шпаргалки по услугам
    public static String getServiceTariffsText() {
        StringBuilder sb = new StringBuilder("📋 <b>Шпаргалка по стоимости услуг (с НДС):</b>\n\n");
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT category, service_name, unit, price_with_vat FROM service_tariffs ORDER BY category, service_name")) {

            String currentCategory = "";
            while (rs.next()) {
                String category = rs.getString("category");
                String name = rs.getString("service_name");
                String unit = rs.getString("unit");
                double price = rs.getDouble("price_with_vat");

                // Группируем красиво по категориям
                if (!category.equals(currentCategory)) {
                    sb.append("\n🔹 <b>").append(category).append(":</b>\n");
                    currentCategory = category;
                }
                sb.append(String.format("• %s (%s) — <b>%.2f руб.</b>\n", name, unit, price));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка при загрузке тарифов.";
        }
        sb.append("\n<i>💡 Для добавления новой услуги (только МОЛ) отправьте сообщение в формате:\n+услуга Категория ; Название ; Ед.изм ; Цена</i>");
        return sb.toString();
    }

    // Метод для добавления новой услуги в шпаргалку
    public static boolean addServiceTariff(String category, String name, String unit, double price) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO service_tariffs (category, service_name, unit, price_with_vat) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, category.trim());
            ps.setString(2, name.trim());
            ps.setString(3, unit.trim());
            ps.setDouble(4, price);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    // Список материалов на складе (для отображения и создания кнопок)
    public static java.util.List<String[]> getAvailableMaterials() {
        java.util.List<String[]> list = new java.util.ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, short_name, work_unit, price_with_vat, warehouse_qty FROM materials WHERE warehouse_qty > 0 ORDER BY short_name")) {
            while (rs.next()) {
                list.add(new String[]{
                        String.valueOf(rs.getInt("id")),
                        rs.getString("short_name"),
                        rs.getString("work_unit"),
                        String.format("%.2f", rs.getDouble("price_with_vat")),
                        String.format("%.0f", rs.getDouble("warehouse_qty"))
                });
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // Перемещение материала со склада на руки мастеру (в одной транзакции)
    public static String takeMaterialFromWarehouse(long userId, int materialId, double qtyToTake) {
        if (qtyToTake <= 0) return "❌ Количество должно быть больше нуля!";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false); // Начинаем безопасную транзакцию

            PreparedStatement psCheck = conn.prepareStatement(
                    "SELECT short_name, work_unit, warehouse_qty, price_with_vat FROM materials WHERE id = ?");
            psCheck.setInt(1, materialId);
            ResultSet rs = psCheck.executeQuery();

            if (!rs.next()) return "❌ Материал не найден.";
            String name = rs.getString("short_name");
            String unit = rs.getString("work_unit");
            double available = rs.getDouble("warehouse_qty");
            double priceVat = rs.getDouble("price_with_vat");

            if (qtyToTake > available) {
                return String.format("❌ На складе недостаточно материала! Доступно всего: %.0f %s", available, unit);
            }

            // 1. Уменьшаем остаток на складе МОЛ
            PreparedStatement psUpdateWarehouse = conn.prepareStatement(
                    "UPDATE materials SET warehouse_qty = warehouse_qty - ? WHERE id = ?");
            psUpdateWarehouse.setDouble(1, qtyToTake);
            psUpdateWarehouse.setInt(2, materialId);
            psUpdateWarehouse.executeUpdate();

            // 2. Прибавляем материал на руки сотруднику
            PreparedStatement psUpdateWorker = conn.prepareStatement("""
                INSERT INTO employee_balances (user_id, material_id, quantity) VALUES (?, ?, ?)
                ON CONFLICT(user_id, material_id) DO UPDATE SET quantity = quantity + excluded.quantity
            """);
            psUpdateWorker.setLong(1, userId);
            psUpdateWorker.setInt(2, materialId);
            psUpdateWorker.setDouble(3, qtyToTake);
            psUpdateWorker.executeUpdate();

            // 3. Записываем в журнал операций (ISSUE)
            PreparedStatement psLog = conn.prepareStatement("""
                INSERT INTO transactions (type, user_id, material_id, quantity, total_sum_with_vat)
                VALUES ('ISSUE', ?, ?, ?, ?)
            """);
            psLog.setLong(1, userId);
            psLog.setInt(2, materialId);
            psLog.setDouble(3, qtyToTake);
            psLog.setDouble(4, qtyToTake * priceVat);
            psLog.executeUpdate();

            conn.commit(); // Подтверждаем все 3 действия одновременно
            return String.format("✅ Вы взяли со склада: <b>%s — %.0f %s</b> (по %.2f руб. с НДС).", name, qtyToTake, unit, priceVat);
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка базы данных при выдаче.";
        }
    }

    // Посмотреть, что числится за конкретным мастером ("Мой подотчет")
    public static String getUserBalanceText(long userId) {
        StringBuilder sb = new StringBuilder("🧰 <b>Числится за вами (в подотчете):</b>\n\n");
        boolean hasItems = false;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 SELECT m.short_name, m.work_unit, m.price_with_vat, b.quantity 
                 FROM employee_balances b
                 JOIN materials m ON b.material_id = m.id
                 WHERE b.user_id = ? AND b.quantity > 0
                 ORDER BY m.short_name
             """)) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                hasItems = true;
                String name = rs.getString("short_name");
                String unit = rs.getString("work_unit");
                double price = rs.getDouble("price_with_vat");
                double qty = rs.getDouble("quantity");
                sb.append(String.format("• <b>%s</b>: %.0f %s <i>(цена с НДС: %.2f руб/%s)</i>\n", name, qty, unit, price, unit));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return hasItems ? sb.toString() : "🧰 За вами сейчас не числится материалов. Возьмите нужные позиции в разделе «📦 Склад».";
    }

    // Сводка для МОЛ: у кого из сотрудников что сейчас на руках
    public static String getAllWorkersBalancesText() {
        StringBuilder sb = new StringBuilder("📊 <b>Материалы на руках у сотрудников:</b>\n");
        boolean hasAny = false;
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                 SELECT u.full_name, m.short_name, m.work_unit, m.price_with_vat, b.quantity
                 FROM employee_balances b
                 JOIN users u ON b.user_id = u.id
                 JOIN materials m ON b.material_id = m.id
                 WHERE b.quantity > 0
                 ORDER BY u.full_name, m.short_name
             """)) {
            String currentWorker = "";
            while (rs.next()) {
                hasAny = true;
                String worker = rs.getString("full_name");
                if (!worker.equals(currentWorker)) {
                    sb.append("\n👤 <b>").append(worker).append(":</b>\n");
                    currentWorker = worker;
                }
                sb.append(String.format("   • %s — <b>%.0f %s</b> (по %.2f руб.)\n",
                        rs.getString("short_name"),
                        rs.getDouble("quantity"),
                        rs.getString("work_unit"),
                        rs.getDouble("price_with_vat")));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return hasAny ? sb.toString() : "📊 Сейчас на руках у сотрудников нет ни одного материала.";
    }
    // Список материалов на руках у конкретного мастера (для кнопок списания)
    public static java.util.List<String[]> getUserMaterialsForWriteOff(long userId) {
        java.util.List<String[]> list = new java.util.ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 SELECT m.id, m.short_name, m.work_unit, m.price_with_vat, b.quantity
                 FROM employee_balances b
                 JOIN materials m ON b.material_id = m.id
                 WHERE b.user_id = ? AND b.quantity > 0
                 ORDER BY m.short_name
             """)) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                list.add(new String[]{
                        String.valueOf(rs.getInt("id")),
                        rs.getString("short_name"),
                        rs.getString("work_unit"),
                        String.valueOf(rs.getDouble("price_with_vat")),
                        String.valueOf(rs.getDouble("quantity"))
                });
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // Проведение списания в базе данных (с кодом закрытия заявки)
    public static String completeWriteOff(long userId, WriteOffSession s) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            PreparedStatement psCheck = conn.prepareStatement(
                    "SELECT quantity FROM employee_balances WHERE user_id = ? AND material_id = ?");
            psCheck.setLong(1, userId);
            psCheck.setInt(2, s.materialId);
            ResultSet rs = psCheck.executeQuery();

            if (!rs.next() || rs.getDouble("quantity") < s.quantity) {
                return "❌ Ошибка: у вас на руках недостаточно этого материала.";
            }

            // 1. Списываем с баланса мастера
            PreparedStatement psUpdate = conn.prepareStatement(
                    "UPDATE employee_balances SET quantity = quantity - ? WHERE user_id = ? AND material_id = ?");
            psUpdate.setDouble(1, s.quantity);
            psUpdate.setLong(2, userId);
            psUpdate.setInt(3, s.materialId);
            psUpdate.executeUpdate();

            // 2. Записываем в таблицу транзакций со всеми деталями (включая closing_code)
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
                        
                        📦 Материал: <b>%s — %.0f %s</b>
                        🧾 Квитанция: <b>%s</b>
                        ☎️ Телефон заявки: <b>%s</b>
                        📄 Номер договора: <b>%s</b>
                        🏠 Адрес: <b>%s</b>
                        
                        💰 <b>Сумма материала для квитанции (с НДС): %.2f руб.</b>
                        <i>(%.0f %s × %.2f руб.)</i>""",
                        s.materialName, s.quantity, s.unit,
                        s.receiptNumber, s.phoneNumber, s.contractNumber, s.address,
                        totalSumVat, s.quantity, s.unit, s.priceWithVat);
            } else {
                return String.format("""
                        ✅ <b>Техническое списание (БЕЗ квитанции) сохранено!</b>
                        
                        📦 Материал: <b>%s — %.0f %s</b>
                        🔢 Код закрытия: <b>%s</b>
                        ☎️ Телефон заявки: <b>%s</b>
                        📄 Номер договора: <b>%s</b>
                        🏠 Адрес: <b>%s</b>
                        🛠 Причина: <b>%s</b>""",
                        s.materialName, s.quantity, s.unit,
                        s.closingCode, s.phoneNumber, s.contractNumber, s.address, s.reason);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return "❌ Ошибка базы данных при сохранении списания.";
        }
    }
    // Проверка: не закрывали ли уже по этому телефону/договору заявку кодом 212 за последние 6 месяцев
    public static String checkCode212History(String phone, String contract) {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                 SELECT date(created_at, 'localtime') as dt, subscriber_address
                 FROM transactions
                 WHERE type = 'WRITE_OFF'
                   AND closing_code = '212'
                   AND created_at >= datetime('now', '-6 months')
                   AND (phone_number = ? OR (contract_number = ? AND contract_number != '-'))
                 ORDER BY created_at DESC LIMIT 1
             """)) {
            ps.setString(1, phone.trim());
            ps.setString(2, contract.trim());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return String.format("\n\n⚠️ <b>ВНИМАНИЕ! По этому абоненту уже был использован код 212 (%s, адрес: %s)!</b>\nНе прошло 6 месяцев — <b>код 212 использовать НЕЛЬЗЯ</b>, выбирайте код <b>227</b>!",
                        rs.getString("dt"), rs.getString("subscriber_address"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "";
    }

    // Текст шпаргалки по кодам закрытия заявок (без символа <, чтобы не ломать HTML)
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
}