package com.warehousebot.bot.core;

import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;

/**
 * Всё общение с Telegram API. Ошибки отправки логируются и не роняют обработку апдейта —
 * так же, как это было в прежней версии.
 *
 * <p>Все тексты отправляются с parse_mode=HTML.
 */
public interface Messenger {

    /** Отправляет сообщение; возвращает его или {@code null}, если Telegram отказал. */
    Message send(long chatId, String html, ReplyKeyboard markup);

    default Message send(long chatId, String html) {
        return send(chatId, html, null);
    }

    /**
     * Отправляет сообщение, которое не удаляется при «очистке чата» (переходе в главное меню).
     * Для запросов на подтверждение и предупреждений, которые должны дождаться реакции пользователя.
     */
    Message sendPinned(long chatId, String html, ReplyKeyboard markup);

    void edit(long chatId, int messageId, String html, InlineKeyboardMarkup markup);

    /** Новое сообщение, если {@code messageId == null}, иначе — редактирование существующего. */
    default void sendOrEdit(long chatId, Integer messageId, String html, InlineKeyboardMarkup markup) {
        if (messageId == null) send(chatId, html, markup);
        else edit(chatId, messageId, html, markup);
    }

    /**
     * Длинный текст режется на части по ~3900 символов по границе абзаца;
     * клавиатура прикрепляется к последней части.
     */
    void sendLong(long chatId, String html, ReplyKeyboard markup);

    void delete(long chatId, int messageId);

    void answer(String callbackQueryId, String text, boolean showAlert);

    void sendDocument(long chatId, File file) throws TelegramApiException;

    File download(String fileId) throws TelegramApiException;

    Chat getChat(long chatId) throws TelegramApiException;

    /** Удаляет все сообщения бота, отправленные в чат с момента прошлой очистки. */
    void clearChatHistory(long chatId);
}
