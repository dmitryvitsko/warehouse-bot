package com.warehousebot.bot.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.DefaultAbsSender;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Реализация {@link Messenger} поверх TelegramBots.
 *
 * <p>Сохраняет прежнее поведение «умного пылесоса»: каждое отправленное ботом сообщение запоминается,
 * а при нажатии кнопки главного меню удаляется. Приветствие с ролью («якорь») не удаляется,
 * но заменяется новым при следующем /start.
 */
public final class TelegramMessenger implements Messenger {

    private static final Logger log = LoggerFactory.getLogger(TelegramMessenger.class);
    private static final int MAX_LEN = 3900;
    private static final String ANCHOR_MARKER = "Ваша роль в системе";
    private static final String HTML = "HTML";

    private final DefaultAbsSender sender;
    private final Map<Long, List<Integer>> sentMessages = new ConcurrentHashMap<>();
    private final Map<Long, Integer> anchors = new ConcurrentHashMap<>();

    public TelegramMessenger(DefaultAbsSender sender) {
        this.sender = sender;
    }

    @Override
    public Message send(long chatId, String html, ReplyKeyboard markup) {
        Message sent = doSend(chatId, html, markup);
        if (sent != null) track(html, sent);
        return sent;
    }

    @Override
    public Message sendPinned(long chatId, String html, ReplyKeyboard markup) {
        return doSend(chatId, html, markup);
    }

    private Message doSend(long chatId, String html, ReplyKeyboard markup) {
        SendMessage msg = new SendMessage(String.valueOf(chatId), html);
        msg.setParseMode(HTML);
        if (markup != null) msg.setReplyMarkup(markup);
        try {
            return sender.execute(msg);
        } catch (TelegramApiException e) {
            log.warn("Telegram: {}", e.getMessage());
            return null;
        }
    }

    private void track(String html, Message sent) {
        long chatId = sent.getChatId();
        if (html != null && html.contains(ANCHOR_MARKER)) {
            Integer old = anchors.put(chatId, sent.getMessageId());
            if (old != null) rawDelete(chatId, old);
        } else {
            // compute атомарен относительно remove в clearChatHistory: id не попадёт в уже очищенный список.
            sentMessages.compute(chatId, (k, ids) -> {
                List<Integer> list = ids == null ? new CopyOnWriteArrayList<>() : ids;
                list.add(sent.getMessageId());
                return list;
            });
        }
    }

    @Override
    public void edit(long chatId, int messageId, String html, InlineKeyboardMarkup markup) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText(html);
        edit.setParseMode(HTML);
        edit.setReplyMarkup(markup);
        try {
            sender.execute(edit);
        } catch (TelegramApiException e) {
            log.debug("Telegram edit: {}", e.getMessage());
        }
    }

    @Override
    public void sendLong(long chatId, String html, ReplyKeyboard markup) {
        if (html.length() <= MAX_LEN) {
            send(chatId, html, markup);
            return;
        }
        String remaining = html;
        while (remaining.length() > MAX_LEN) {
            int split = remaining.lastIndexOf("\n\n", MAX_LEN);
            if (split == -1) split = MAX_LEN;
            send(chatId, remaining.substring(0, split), null);
            remaining = remaining.substring(split).trim();
        }
        if (!remaining.isEmpty()) send(chatId, remaining, markup);
    }

    @Override
    public void delete(long chatId, int messageId) {
        rawDelete(chatId, messageId);
    }

    private void rawDelete(long chatId, int messageId) {
        try {
            sender.execute(new DeleteMessage(String.valueOf(chatId), messageId));
        } catch (TelegramApiException e) {
            // сообщение уже удалено или недоступно
        }
    }

    @Override
    public void answer(String callbackQueryId, String text, boolean showAlert) {
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQueryId);
        if (text != null) answer.setText(text);
        answer.setShowAlert(showAlert);
        try {
            sender.execute(answer);
        } catch (TelegramApiException e) {
            log.debug("Telegram answer: {}", e.getMessage());
        }
    }

    @Override
    public void sendDocument(long chatId, File file) throws TelegramApiException {
        SendDocument doc = new SendDocument();
        doc.setChatId(String.valueOf(chatId));
        doc.setDocument(new InputFile(file));
        sender.execute(doc);
    }

    @Override
    public File download(String fileId) throws TelegramApiException {
        org.telegram.telegrambots.meta.api.objects.File tgFile = sender.execute(new GetFile(fileId));
        return sender.downloadFile(tgFile);
    }

    @Override
    public Chat getChat(long chatId) throws TelegramApiException {
        return sender.execute(new GetChat(String.valueOf(chatId)));
    }

    @Override
    public void clearChatHistory(long chatId) {
        List<Integer> ids = sentMessages.remove(chatId);
        if (ids == null) return;
        for (Integer id : ids) rawDelete(chatId, id);
    }
}
