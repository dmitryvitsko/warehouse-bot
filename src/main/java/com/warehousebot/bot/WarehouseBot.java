package com.warehousebot.bot;

import com.warehousebot.bot.core.ChatExecutor;
import com.warehousebot.bot.core.UpdateDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Адаптер TelegramBots: принимает апдейты long polling и передаёт их в {@link UpdateDispatcher}
 * через {@link ChatExecutor} (параллельно по чатам, последовательно внутри чата).
 */
public final class WarehouseBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(WarehouseBot.class);

    private final String username;
    private volatile UpdateDispatcher dispatcher;
    private volatile ChatExecutor executor;

    public WarehouseBot(String token, String username) {
        super(token);
        this.username = username;
    }

    /** Подключает обработку; до вызова апдейты не принимаются (бот ещё не зарегистрирован). */
    public void attach(UpdateDispatcher dispatcher, ChatExecutor executor) {
        this.dispatcher = dispatcher;
        this.executor = executor;
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public void onUpdateReceived(Update update) {
        Long chatId = UpdateDispatcher.chatIdOf(update);
        if (chatId == null) return;
        executor.submit(chatId, () -> {
            try {
                dispatcher.dispatch(update);
            } catch (Exception e) {
                log.error("❌ Ошибка обработки апдейта", e);
            }
        });
    }
}
