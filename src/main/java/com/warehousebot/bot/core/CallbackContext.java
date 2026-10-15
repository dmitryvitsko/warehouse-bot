package com.warehousebot.bot.core;

import com.warehousebot.bot.session.UserSession;
import com.warehousebot.domain.Role;

/** Нажатие inline-кнопки. */
public final class CallbackContext {

    private final long chatId;
    private final int messageId;
    private final String data;
    private final String firstName;
    private final Role role;
    private final String callbackQueryId;
    private final UserSession session;
    private final Messenger messenger;
    private String[] parts;
    private boolean answered;

    public CallbackContext(long chatId, int messageId, String data, String firstName, Role role,
                           String callbackQueryId, UserSession session, Messenger messenger) {
        this.chatId = chatId;
        this.messageId = messageId;
        this.data = data;
        this.firstName = firstName;
        this.role = role;
        this.callbackQueryId = callbackQueryId;
        this.session = session;
        this.messenger = messenger;
    }

    public long chatId() { return chatId; }
    public int messageId() { return messageId; }
    public String data() { return data; }
    public String firstName() { return firstName; }
    public Role role() { return role; }
    public boolean isAdmin() { return role.isAdmin(); }
    public UserSession session() { return session; }

    /** Всё, что идёт после префикса (для данных, которые могут содержать «:»). */
    public String payload(String prefix) {
        return data.substring(prefix.length());
    }

    /** i-я часть callback data, разделённой «:». */
    public String part(int index) {
        if (parts == null) parts = data.split(":");
        return parts[index];
    }

    public int partCount() {
        if (parts == null) parts = data.split(":");
        return parts.length;
    }

    public int intPart(int index) {
        return Integer.parseInt(part(index));
    }

    public long longPart(int index) {
        return Long.parseLong(part(index));
    }

    /** Всплывающая подсказка у кнопки. */
    public void answer(String text, boolean showAlert) {
        answered = true;
        messenger.answer(callbackQueryId, text, showAlert);
    }

    public boolean isAnswered() {
        return answered;
    }

    /** Убирает «часики» на кнопке без текста. */
    public void answer() {
        answer(null, false);
    }
}
