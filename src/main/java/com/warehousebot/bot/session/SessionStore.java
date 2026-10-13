package com.warehousebot.bot.session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Сессии всех чатов. Живут в памяти, как и прежде (после перезапуска диалоги начинаются заново). */
public final class SessionStore {

    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();

    public UserSession get(long chatId) {
        return sessions.computeIfAbsent(chatId, UserSession::new);
    }

    /** Сессия другого чата, если она уже существует (не создаёт новую). */
    public Optional<UserSession> peek(long chatId) {
        return Optional.ofNullable(sessions.get(chatId));
    }
}
