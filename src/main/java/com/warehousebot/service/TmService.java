package com.warehousebot.service;

import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.infrastructure.tm.TmClient;
import com.warehousebot.repository.TmAccountRepository;
import com.warehousebot.repository.TmAccountRepository.Credentials;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Function;

/**
 * Работа с сессией ТМ: запрос выполняется с сохранённым токеном, а если он протух —
 * бот перелогинивается по сохранённым логину/паролю и повторяет запрос один раз.
 */
public final class TmService {

    private static final Logger log = LoggerFactory.getLogger(TmService.class);

    private final TmAccountRepository accounts;
    private final TmClient client;

    public TmService(TmAccountRepository accounts, TmClient client) {
        this.accounts = accounts;
        this.client = client;
    }

    /** Заявки сотрудника; {@code null}, если войти в ТМ не удалось (нет данных входа или сервер недоступен). */
    public JSONArray fetchTasks(long chatId) {
        return withRetry(chatId, client::getTasks);
    }

    /** Произвольный запрос к ТМ с повторным входом при отказе. */
    public JSONObject callWithRetry(long chatId, Function<String, JSONObject> call) {
        return withRetry(chatId, call);
    }

    /** Линейные данные АСТУП по заявке; {@code null} при ошибке. */
    public JSONObject astup(long chatId, String taskId) {
        return callWithRetry(chatId, s -> client.getAstup(s, taskId));
    }

    /** Новый замер оптических параметров; {@code null} при ошибке. */
    public JSONObject measureParams(long chatId, String taskId) {
        return callWithRetry(chatId, s -> client.measureParams(s, taskId));
    }

    /** История выездов (ответ сервера целиком); {@code null} при ошибке. */
    public JSONObject history(long chatId, String taskId) {
        return callWithRetry(chatId, s -> client.getTaskHistory(s, taskId));
    }

    /**
     * Отправка отчёта по заявке. Повторный вход и повторная отправка — только если сервер явно отказал;
     * при отсутствии ответа отчёт мог уже дойти, поэтому повтор не делается (иначе возможен дубль).
     */
    public boolean performTask(long chatId, String taskId, String description) {
        Optional<String> session = findSession(chatId);
        if (session.isPresent()) {
            Boolean result = client.performTask(session.get(), taskId, description);
            if (result == null) return false;
            if (result) return true;
        }
        String fresh = relogin(chatId);
        return fresh != null && Boolean.TRUE.equals(client.performTask(fresh, taskId, description));
    }

    /**
     * Вход по логину/паролю, введённым сотрудником. При успехе данные и сессия сохраняются.
     *
     * @return токен сессии или {@code null}, если вход не удался
     */
    public String login(long chatId, String username, String password) {
        String session = client.login(username, password);
        if (session == null) return null;
        try {
            accounts.saveCredentials(chatId, username, password);
        } catch (DataAccessException e) {
            log.error("Не удалось сохранить данные входа ТМ для {}", chatId, e);
        }
        saveSession(chatId, session);
        return session;
    }

    /** Заявки по только что полученной сессии (без повторного входа). */
    public JSONArray tasks(String session) {
        return client.getTasks(session);
    }

    private <T> T withRetry(long chatId, Function<String, T> call) {
        Optional<String> session = findSession(chatId);
        T result = session.map(call).orElse(null);
        if (result != null) return result;

        String fresh = relogin(chatId);
        return fresh == null ? null : call.apply(fresh);
    }

    /** Новый вход по сохранённым данным; новая сессия сохраняется. */
    private String relogin(long chatId) {
        Optional<Credentials> creds = findCredentials(chatId);
        if (creds.isEmpty()) return null;
        String fresh = client.login(creds.get().username(), creds.get().password());
        if (fresh != null) saveSession(chatId, fresh);
        return fresh;
    }

    // Сбой базы не должен обрывать работу с ТМ (как и раньше): без сохранённой сессии просто входим заново.

    private Optional<String> findSession(long chatId) {
        try {
            return accounts.findSession(chatId);
        } catch (DataAccessException e) {
            log.error("Не удалось прочитать сессию ТМ для {}", chatId, e);
            return Optional.empty();
        }
    }

    private Optional<Credentials> findCredentials(long chatId) {
        try {
            return accounts.findCredentials(chatId);
        } catch (DataAccessException e) {
            log.error("Не удалось прочитать данные входа ТМ для {}", chatId, e);
            return Optional.empty();
        }
    }

    private void saveSession(long chatId, String session) {
        try {
            accounts.saveSession(chatId, session);
        } catch (DataAccessException e) {
            log.error("Не удалось сохранить сессию ТМ для {}", chatId, e);
        }
    }
}
