package com.warehousebot.repository;

import com.warehousebot.infrastructure.db.Database;

import java.util.Optional;

/** Таблица tm_accounts: логин/пароль сотрудника в системе «Мобильная ТМ» и текущий токен сессии. */
public final class TmAccountRepository {

    /** Учётные данные ТМ. */
    public record Credentials(String username, String password) {
        @Override
        public String toString() {
            return "Credentials[username=" + username + "]"; // пароль в логи не попадает
        }
    }

    private final Database db;

    public TmAccountRepository(Database db) {
        this.db = db;
    }

    public void saveCredentials(long chatId, String username, String password) {
        db.update("INSERT INTO tm_accounts (chat_id, username, password) VALUES (?, ?, ?) " +
                "ON CONFLICT(chat_id) DO UPDATE SET username = excluded.username, password = excluded.password",
                chatId, username, password);
    }

    public Optional<Credentials> findCredentials(long chatId) {
        return db.queryOne("SELECT username, password FROM tm_accounts WHERE chat_id = ?",
                rs -> new Credentials(rs.getString("username"), rs.getString("password")), chatId);
    }

    public void saveSession(long chatId, String session) {
        db.update("UPDATE tm_accounts SET session = ? WHERE chat_id = ?", session, chatId);
    }

    /** Токен сессии; пусто, если аккаунта нет или сессия не сохранена. */
    public Optional<String> findSession(long chatId) {
        return db.queryOne("SELECT session FROM tm_accounts WHERE chat_id = ?", rs -> rs.getString("session"), chatId);
    }
}
