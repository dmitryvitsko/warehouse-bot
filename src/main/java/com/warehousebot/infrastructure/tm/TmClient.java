package com.warehousebot.infrastructure.tm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Клиент API «Мобильная ТМ».
 * Один эндпоинт, метод передаётся в теле запроса (поле "method").
 *
 * <p>Ошибки сети и сервера не пробрасываются: методы возвращают {@code null}/{@code false},
 * как и прежде. В лог пишется причина (сервер недоступен / неверные данные), но никогда —
 * пароль или токен сессии.
 */
public final class TmClient {

    private static final Logger log = LoggerFactory.getLogger(TmClient.class);

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public TmClient(String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.trim();
        if (this.baseUrl == null) log.error("ТМ: в config.properties не задан параметр tm.api.url — интеграция с ТМ отключена");
    }

    /**
     * Логин в ТМ. Возвращает session-токен или {@code null}, если логин/пароль неверные
     * либо сервер недоступен.
     */
    public String login(String username, String password) {
        if (baseUrl == null) {
            log.error("ТМ: вход невозможен — не задан tm.api.url");
            return null;
        }
        JSONObject params = new JSONObject();
        params.put("session", "");

        // Логин сотрудника передаётся в оба поля (так требует сервер)
        params.put("task", username);
        params.put("username", username);

        params.put("password", password);
        params.put("version_name", "5.6.8");
        params.put("device", "WarehouseBot");
        params.put("OS", "13");

        JSONObject body = new JSONObject();
        body.put("method", "login");
        body.put("params", params);

        JSONObject response = post(body);
        if (response == null) {
            log.warn("ТМ: вход не выполнен — сервер недоступен или вернул ошибку");
            return null;
        }

        JSONObject result = response.optJSONObject("result");
        String session = result == null ? null : result.optString("session", null);
        if (session == null) log.info("ТМ: вход отклонён сервером (неверный логин или пароль)");
        return session;
    }

    /**
     * Список заявок текущей сессии. Возвращает {@code null}, если сессия протухла
     * (в этом случае нужно перелогиниться через {@link #login}).
     */
    public JSONArray getTasks(String session) {
        JSONObject params = new JSONObject();
        params.put("session", session);

        JSONObject body = new JSONObject();
        body.put("method", "Tasks.get");
        body.put("params", params);

        JSONObject response = post(body);
        if (response == null) return null;
        return response.optJSONArray("result");
    }

    /**
     * Отправляет отчёт по заявке (аналог кнопки «Отправить отчёт» в приложении).
     * type = "0" — абонентский участок. Возвращает {@code true} при успехе.
     */
    /**
     * Отправка отчёта.
     *
     * @return {@code true} — принят, {@code false} — сервер отказал (например, сессия истекла),
     * {@code null} — ответа нет (таймаут/сеть): отчёт мог дойти, повторять его нельзя
     */
    public Boolean performTask(String session, String taskId, String description) {
        JSONObject params = new JSONObject();
        params.put("session", session);
        params.put("task", taskId);
        params.put("offset", "0");
        params.put("limit", "5");
        params.put("type", "0");
        params.put("description", description);

        JSONObject body = new JSONObject();
        body.put("method", "Tasks.perform");
        body.put("params", params);

        JSONObject response = post(body);
        if (response == null) return null;
        return response.optBoolean("result", false);
    }

    /** Линейные данные АСТУП (кнопка «АСТУП» в приложении). */
    public JSONObject getAstup(String session, String taskId) {
        JSONObject response = post(taskCallBody("getData", session, taskId));
        return response != null ? response.optJSONObject("result") : null;
    }

    /** Новый замер оптических параметров (кнопка «Замерить» в приложении). */
    public JSONObject measureParams(String session, String taskId) {
        JSONObject response = post(taskCallBody("newSecondParams", session, taskId));
        return response != null ? response.optJSONObject("result") : null;
    }

    /** История выездов по заявке: ответ сервера целиком (с полями code и result). */
    public JSONObject getTaskHistory(String session, String taskId) {
        JSONObject params = new JSONObject();
        params.put("session", session);
        params.put("task", taskId);

        JSONObject body = new JSONObject();
        body.put("method", "History.task");
        body.put("params", params);
        return post(body);
    }

    // ------------------------------------------------------------------ разбор ответа

    /** Число заявок в группе: closed=false — «в работе», closed=true — «закрытые». */
    public static int countByStatus(JSONArray tasks, boolean closed) {
        return byStatus(tasks, closed).length();
    }

    /** Заявки нужной группы. Поле closed: "0" — в работе, "1" — закрыта. */
    public static JSONArray byStatus(JSONArray tasks, boolean closed) {
        JSONArray result = new JSONArray();
        String want = closed ? "1" : "0";
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.getJSONObject(i);
            if (want.equals(t.optString("closed"))) result.put(t);
        }
        return result;
    }

    // ------------------------------------------------------------------ HTTP

    private static JSONObject taskCallBody(String method, String session, String taskId) {
        JSONObject params = new JSONObject();
        params.put("session", session);
        params.put("task", taskId);
        params.put("offset", "0");
        params.put("limit", "5");
        params.put("type", "0");
        params.put("description", "");

        JSONObject body = new JSONObject();
        body.put("method", method);
        body.put("params", params);
        return body;
    }

    /** POST на эндпоинт ТМ; {@code null} при любой ошибке (тело запроса в лог не пишется — в нём пароль/сессия). */
    private JSONObject post(JSONObject body) {
        String method = body.optString("method");
        if (baseUrl == null) {
            log.error("ТМ: запрос {} не выполнен — не задан tm.api.url", method);
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("ТМ: сервер ответил HTTP {} на {}", response.statusCode(), method);
                return null;
            }
            return new JSONObject(response.body());
        } catch (JSONException e) {
            log.warn("ТМ: некорректный ответ сервера на {}: {}", method, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("ТМ: запрос {} прерван", method);
            return null;
        } catch (Exception e) {
            log.warn("ТМ: сервер недоступен ({}): {}", method, e.toString());
            return null;
        }
    }
}
