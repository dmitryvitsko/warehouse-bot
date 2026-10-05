import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Клиент для API "Мобильная ТМ" (mtm.beltelecom.by).
 * Один эндпоинт /7/index.php, метод передаётся в теле запроса (поле "method").
 */
public class TmClient {

    private static final String BASE_URL = "https://mtm.beltelecom.by/7/index.php";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Логин в ТМ. Возвращает session-токен или null, если логин/пароль неверные
     * либо сервер недоступен.
     */
    public static String login(String username, String password) {
        JSONObject params = new JSONObject();
        params.put("session", "");
        params.put("task", "h-rep-2265");
        params.put("username", username);
        params.put("password", password);
        params.put("version_name", "5.6.8");
        params.put("device", "WarehouseBot");
        params.put("OS", "13");

        JSONObject body = new JSONObject();
        body.put("method", "login");
        body.put("params", params);

        JSONObject response = post(body);
        if (response == null) return null;

        JSONObject result = response.optJSONObject("result");
        if (result == null) return null;

        return result.optString("session", null);
    }

    /**
     * Список заявок текущей сессии. Возвращает null, если сессия протухла
     * (в этом случае нужно перелогиниться через login()).
     */
    public static JSONArray getTasks(String session) {
        JSONObject params = new JSONObject();
        params.put("session", session);

        JSONObject body = new JSONObject();
        body.put("method", "Tasks.get");
        body.put("params", params);

        JSONObject response = post(body);
        if (response == null) return null;

        // code == 1 — успех. Другие коды (пока не знаем какие) будем уточнять по мере встречи.
        return response.optJSONArray("result");
    }

    /**
     * Форматирует список заявок в текст для Telegram.
     * Поля выводятся как есть (generic), т.к. пока неизвестна точная структура
     * одной заявки — подставим точные подписи (адрес/абонент/статус и т.п.),
     * когда появится первая реальная заявка в ответе сервера.
     */
    public static String formatTasksText(JSONArray tasks) {
        if (tasks == null) {
            return "⚠️ Не удалось получить заявки. Возможно, сессия устарела — попробуйте ещё раз.";
        }
        if (tasks.isEmpty()) {
            return "✅ Активных заявок сейчас нет.";
        }

        StringBuilder sb = new StringBuilder("🛠 <b>Мои заявки:</b>\n\n");
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            sb.append("— ").append(task.toString()).append("\n\n");
        }
        return sb.toString();
    }

    private static JSONObject post(JSONObject body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;

            return new JSONObject(response.body());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
