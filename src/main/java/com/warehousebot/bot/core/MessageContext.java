package com.warehousebot.bot.core;

import com.warehousebot.bot.session.UserSession;
import com.warehousebot.domain.Role;
import org.telegram.telegrambots.meta.api.objects.Message;

/** Сообщение с вложением: документ, фото или контакт. */
public final class MessageContext {

    private final long chatId;
    private final String firstName;
    private final Role role;
    private final UserSession session;
    private final Message message;

    public MessageContext(long chatId, String firstName, Role role, UserSession session, Message message) {
        this.chatId = chatId;
        this.firstName = firstName;
        this.role = role;
        this.session = session;
        this.message = message;
    }

    public long chatId() { return chatId; }
    public String firstName() { return firstName; }
    public Role role() { return role; }
    public boolean isAdmin() { return role.isAdmin(); }
    public UserSession session() { return session; }
    public Message message() { return message; }
}
