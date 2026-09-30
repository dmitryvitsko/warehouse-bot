import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        // 1. Запускаем и проверяем базу данных
        DatabaseManager.initDatabase();

        // 2. Запускаем бота
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new WarehouseBot());
            System.out.println("✅ Бот Склада успешно запущен и ждет сообщений!");
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}