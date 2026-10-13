package com.warehousebot.bot.core;

import com.warehousebot.bot.session.UserSession;
import com.warehousebot.domain.Role;

/** Текстовое сообщение пользователя (ввод в диалоге, команда или кнопка главного меню). */
public final class TextContext {

    private final long chatId;
    private final String text;
    private final String firstName;
    private final Role role;
    private final UserSession session;

    public TextContext(long chatId, String text, String firstName, Role role, UserSession session) {
        this.chatId = chatId;
        this.text = text;
        this.firstName = firstName;
        this.role = role;
        this.session = session;
    }

    public long chatId() { return chatId; }
    public String text() { return text; }
    public String trimmed() { return text.trim(); }
    public String firstName() { return firstName; }
    public Role role() { return role; }
    public boolean isAdmin() { return role.isAdmin(); }
    public UserSession session() { return session; }
}
