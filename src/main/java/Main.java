import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        // 1. Запускаем и проверяем базу данных (и создаем новые таблицы)
        DatabaseManager.initDatabase();

        // 2. Инициализируем бота
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            WarehouseBot bot = new WarehouseBot();
            botsApi.registerBot(bot);
            System.out.println("✅ Бот Склада успешно запущен и ждет сообщений!");

            // 3. Запуск фонового Бота-надзирателя
            startWelderMonitor(bot);

        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    /**
     * Фоновый процесс, который каждые 5 минут проверяет, не пора ли сдавать сварочник.
     */
    private static void startWelderMonitor(WarehouseBot bot) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                LocalDate today = LocalDate.now();
                LocalTime now = LocalTime.now();

                try (Connection conn = DatabaseManager.getConnection()) {
                    // Ищем все аппараты, которые сейчас "в работе"
                    String sql = "SELECT w.id, w.name, w.assigned_to, u.full_name " +
                            "FROM welders w JOIN users u ON w.assigned_to = u.id " +
                            "WHERE w.status = 'IN_USE'";

                    try (PreparedStatement psWelders = conn.prepareStatement(sql);
                         ResultSet rsWelders = psWelders.executeQuery()) {

                        while (rsWelders.next()) {
                            int welderId = rsWelders.getInt("id");
                            String welderName = rsWelders.getString("name");
                            long userId = rsWelders.getLong("assigned_to");
                            String excelName = rsWelders.getString("full_name");

                            // Если пользователь УЖЕ заблокирован ботом-надзирателем, пропускаем его
                            if (bot.getForceWelderReturnIds().containsKey(userId)) {
                                continue;
                            }

                            // Узнаем окончание смены для этого человека на СЕГОДНЯ из актуальной таблицы schedules_v2
                            String schedSql = "SELECT end_time FROM schedules_v2 WHERE excel_name = ? AND year = ? AND month = ? AND day = ?";
                            try (PreparedStatement psSched = conn.prepareStatement(schedSql)) {
                                psSched.setString(1, excelName);
                                psSched.setInt(2, today.getYear());
                                psSched.setInt(3, today.getMonthValue());
                                psSched.setInt(4, today.getDayOfMonth());

                                try (ResultSet rsSched = psSched.executeQuery()) {
                                    // Если на сегодня есть запись в графике
                                    if (rsSched.next()) {
                                        String endStr = rsSched.getString("end_time");
                                        LocalTime shiftEnd = parseTimeSafe(endStr);

                                        if (shiftEnd != null) {
                                            // Вычисляем, сколько минут осталось до конца смены
                                            long minutesLeft = ChronoUnit.MINUTES.between(now, shiftEnd);

                                            // Если до конца смены осталось от 0 до 15 минут
                                            if (minutesLeft >= 0 && minutesLeft <= 15) {

                                                // 1. НАКЛАДЫВАЕМ ЖЕСТКУЮ БЛОКИРОВКУ
                                                bot.getForceWelderReturnIds().put(userId, welderId);

                                                // 2. ОТПРАВЛЯЕМ СООБЩЕНИЕ С КНОПКАМИ
                                                sendWarning(bot, userId, welderId, welderName, minutesLeft);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Молча ловим ошибку, чтобы таймер не остановился
                e.printStackTrace();
            }
        }, 0, 5, TimeUnit.MINUTES); // Запускать каждые 5 минут
    }

    /**
     * Формирование и отправка ультиматума мастеру
     */
    private static void sendWarning(WarehouseBot bot, long userId, int welderId, String welderName, long minutesLeft) {
        String text = "🚨 <b>ДЕЙСТВИЕ ПРИОСТАНОВЛЕНО! Отчет по оборудованию</b> 🚨\n\n" +
                "Ваша рабочая смена заканчивается через <b>" + minutesLeft + " минут</b>. " +
                "На вас числится сварочный аппарат: <b>" + welderName + "</b>.\n\n" +
                "Чтобы аппараты не терялись, бот временно заблокировал ваше основное меню. Пожалуйста, отчитайтесь за прибор:\n\n" +
                "👇 <i>Выберите один из вариантов ниже:</i>";

        InlineKeyboardButton btnReturn = new InlineKeyboardButton("↩️ Я сдал прибор на базу");
        btnReturn.setCallbackData("W_RET:" + welderId);

        InlineKeyboardButton btnKeep = new InlineKeyboardButton("🌙 Оставляю себе на завтра");
        btnKeep.setCallbackData("W_KEEP_TOMORROW");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(btnReturn),
                List.of(btnKeep)
        ));

        SendMessage msg = new SendMessage(String.valueOf(userId), text);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);

        try {
            bot.execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    /**
     * Безопасный парсинг времени из Excel (переваривает "17:30", "17.30", "9:00" и т.д.)
     */
    private static LocalTime parseTimeSafe(String t) {
        if (t == null || t.trim().isEmpty()) return null;
        try {
            t = t.trim().replace(".", ":");
            if (t.length() == 4) t = "0" + t; // Превращаем "9:30" в "09:30"
            if (t.length() > 5) t = t.substring(0, 5); // Обрезаем секунды, если они есть
            return LocalTime.parse(t);
        } catch (Exception e) {
            return null;
        }
    }
}