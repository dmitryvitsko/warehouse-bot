package com.warehousebot.bot.session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Всё состояние диалогов одного чата в одном объекте
 * (вместо двух десятков отдельных Map в старом WarehouseBot).
 *
 * <p>Апдейты одного чата обрабатываются строго последовательно, поэтому сессия
 * не требует дополнительной синхронизации; ConcurrentHashMap нужен лишь для безопасной публикации.
 */
public final class UserSession {

    private final long chatId;
    private final Map<SessionKey<?>, Object> values = new ConcurrentHashMap<>();

    UserSession(long chatId) {
        this.chatId = chatId;
    }

    public long chatId() {
        return chatId;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(SessionKey<T> key) {
        return (T) values.get(key);
    }

    public <T> Optional<T> find(SessionKey<T> key) {
        return Optional.ofNullable(get(key));
    }

    public boolean has(SessionKey<?> key) {
        return values.containsKey(key);
    }

    public <T> void put(SessionKey<T> key, T value) {
        if (value == null) values.remove(key);
        else values.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T remove(SessionKey<T> key) {
        return (T) values.remove(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrCreate(SessionKey<T> key, Supplier<T> factory) {
        return (T) values.computeIfAbsent(key, k -> factory.get());
    }

    /** Сбрасывает все незавершённые диалоги согласно политикам ключей. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void resetDialogs() {
        for (Map.Entry<SessionKey<?>, Object> e : values.entrySet()) {
            SessionKey key = e.getKey();
            Object next = key.reset(e.getValue());
            if (next == null) values.remove(key);
            else values.put(key, next);
        }
    }
}
