package com.warehousebot.testutil;

import com.warehousebot.bot.core.Messenger;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Messenger, записывающий все вызовы вместо обращения к Telegram. */
public final class FakeMessenger implements Messenger {

    public record Call(String type, long chatId, Integer messageId, String text, Object markup) {
    }

    public final List<Call> calls = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger(100);

    @Override
    public synchronized Message send(long chatId, String html, ReplyKeyboard markup) {
        calls.add(new Call("send", chatId, null, html, markup));
        return message(chatId);
    }

    @Override
    public synchronized Message sendPinned(long chatId, String html, ReplyKeyboard markup) {
        calls.add(new Call("sendPinned", chatId, null, html, markup));
        return message(chatId);
    }

    @Override
    public synchronized void edit(long chatId, int messageId, String html, InlineKeyboardMarkup markup) {
        calls.add(new Call("edit", chatId, messageId, html, markup));
    }

    @Override
    public synchronized void sendLong(long chatId, String html, ReplyKeyboard markup) {
        calls.add(new Call("send", chatId, null, html, markup));
    }

    @Override
    public synchronized void delete(long chatId, int messageId) {
        calls.add(new Call("delete", chatId, messageId, null, null));
    }

    @Override
    public synchronized void answer(String callbackQueryId, String text, boolean showAlert) {
        calls.add(new Call("answer", 0, null, text, showAlert));
    }

    @Override
    public void sendDocument(long chatId, File file) {
        calls.add(new Call("document", chatId, null, file.getName(), null));
    }

    @Override
    public File download(String fileId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Chat getChat(long chatId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public synchronized void clearChatHistory(long chatId) {
        calls.add(new Call("clear", chatId, null, null, null));
    }

    public synchronized List<String> texts() {
        List<String> out = new ArrayList<>();
        for (Call c : calls) if (c.text() != null) out.add(c.text());
        return out;
    }

    public synchronized Call last() {
        return calls.get(calls.size() - 1);
    }

    private Message message(long chatId) {
        Message m = new Message();
        m.setMessageId(ids.incrementAndGet());
        Chat chat = new Chat();
        chat.setId(chatId);
        m.setChat(chat);
        return m;
    }
}
