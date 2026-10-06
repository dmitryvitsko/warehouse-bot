import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
     * Число заявок в нужной группе. closed=false — "в работе" (closed=="0"),
     * closed=true — "закрытые" (closed=="1").
     */
    public static int countByStatus(JSONArray tasks, boolean closed) {
        return filterByStatus(tasks, closed).length();
    }

    /**
     * Форматирует одну группу заявок (в работе либо закрытые) в текст для Telegram.
     */
    public static String formatTasksText(JSONArray tasks, boolean closed) {
        if (tasks == null) {
            return "⚠️ Не удалось получить заявки. Возможно, сессия устарела — попробуйте ещё раз.";
        }

        JSONArray filtered = filterByStatus(tasks, closed);
        if (filtered.isEmpty()) {
            return closed ? "✅ Закрытых заявок нет." : "📋 Заявок в работе нет.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(closed ? "✅ <b>Закрытые заявки" : "📋 <b>Заявки в работе")
                .append(" (").append(filtered.length()).append("):</b>\n\n");

        for (int i = 0; i < filtered.length(); i++) {
            sb.append(formatTask(filtered.getJSONObject(i))).append("\n\n———\n\n");
        }
        return sb.toString();
    }

    /**
     * Отправляет отчёт по заявке (аналог кнопки "Отправить отчёт" в приложении).
     * type = "0" — абонентский участок (пока всегда используем это значение по умолчанию).
     * Возвращает true при успехе.
     */
    public static boolean performTask(String session, String taskId, String description) {
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
        return response != null && response.optBoolean("result", false);
    }

    /** Заявки нужной группы (closed=false — в работе, closed=true — закрытые). */
    public static JSONArray byStatus(JSONArray tasks, boolean closed) {
        return filterByStatus(tasks, closed);
    }

    /** Линейные данные АСТУП (кнопка "АСТУП" в приложении). */
    public static JSONObject getAstup(String session, String taskId) {
        JSONObject response = post(taskCallBody("getData", session, taskId));
        return response != null ? response.optJSONObject("result") : null;
    }

    /** Новый замер оптических параметров (кнопка "Замерить" в приложении). */
    public static JSONObject measureParams(String session, String taskId) {
        JSONObject response = post(taskCallBody("newSecondParams", session, taskId));
        return response != null ? response.optJSONObject("result") : null;
    }

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

    private static final int TABLE_WIDTH = 34;

    public static String formatAstup(JSONObject astup) {
        if (astup == null) return "⚠️ Не удалось получить данные АСТУП.";

        JSONObject info = astup.optJSONObject("info");
        String address = info != null ? info.optString("physical_addr", "") : "";
        JSONArray line = astup.optJSONArray("line");

        JSONObject ork = null;
        JSONObject other = null;
        if (line != null) {
            for (int i = 0; i < line.length(); i++) {
                JSONObject item = line.getJSONObject(i);
                if (ork == null && "ОРК".equals(item.optString("object_name"))) {
                    ork = item;
                } else if (other == null && ork != null) {
                    other = item; // первый узел после ОРК, независимо от названия (ОРШ/аб.кросс/ЛАЗ)
                }
            }
        }

        StringBuilder sb = new StringBuilder("<pre>");
        sb.append("АСТУП физ. адрес:\n").append(esc(address)).append("\n");
        sb.append(sep()).append("\n");
        sb.append(astupBlock(ork, false));
        sb.append(sep()).append("\n");
        sb.append(astupBlock(other, true));
        sb.append(sep());
        sb.append("</pre>");
        return sb.toString();
    }

    private static String astupBlock(JSONObject item, boolean showDevice) {
        if (item == null) return row("нет данных");
        StringBuilder sb = new StringBuilder();
        sb.append(row("Объект: " + esc(item.optString("object_name"))));
        sb.append(row("Номер: " + esc(item.optString("object_num"))));
        sb.append(row(""));
        if (showDevice) {
            String deviceName = item.optString("device_name", "");
            if (!deviceName.isEmpty()) sb.append(row("Устройство: " + esc(deviceName)));
            String deviceNum = item.optString("device_num", "");
            if (!deviceNum.isEmpty()) sb.append(row("Порт: " + esc(deviceNum)));
        }
        for (String addrLine : wrap(item.optString("address", ""), TABLE_WIDTH - 2)) {
            sb.append(row(esc(addrLine)));
        }
        return sb.toString();
    }

    public static String formatParams(JSONObject params) {
        if (params == null) return "⚠️ Не удалось выполнить замер.";

        String time = params.optString("date", "");
        JSONObject rxtx = params.optJSONObject("RxTx");
        String oltUp = "—", onuUp = "—", oltDown = "—", onuDown = "—";
        if (rxtx != null) {
            String[] upParts = rxtx.optString("up", "").split("/");
            String[] downParts = rxtx.optString("down", "").split("/");
            if (upParts.length == 2) { oltUp = upParts[0]; onuUp = upParts[1]; }
            if (downParts.length == 2) { oltDown = downParts[0]; onuDown = downParts[1]; }
        }

        JSONObject att = params.optJSONObject("attenuation");
        String attUp = (att != null && !att.isNull("up")) ? String.valueOf(att.opt("up")) : "—";
        String attDown = (att != null && !att.isNull("down")) ? String.valueOf(att.opt("down")) : "—";

        String voltage = params.optString("powerOptical", "—");
        String laser = params.optString("powerLaser", "—");
        String temp = params.optString("temperature", "—");

        StringBuilder sb = new StringBuilder("<pre>");
        sb.append("Измерено: Сегодня в ").append(esc(time)).append("\n");
        sb.append(sep()).append("\n");
        sb.append(row("OLT: up=" + oltUp + " / down=" + oltDown + " dB"));
        sb.append(row("ONU: up=" + onuUp + " / down=" + onuDown + " dB"));
        sb.append(row("Затухание: " + attUp + " / " + attDown + " dB"));
        sb.append(row("Напряжение: " + voltage + " В"));
        sb.append(row("Ток лазера: " + laser + " мА"));
        sb.append(row("Температура: " + temp + " °C"));
        sb.append(sep());
        sb.append("</pre>");
        return sb.toString();
    }

    private static String row(String text) {
        return "| " + text + "\n";
    }

    private static String sep() {
        return "-".repeat(TABLE_WIDTH);
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() + word.length() + 1 > width) {
                lines.add(current.toString());
                current = new StringBuilder();
            }
            if (current.length() > 0) current.append(" ");
            current.append(word);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    public static String formatTask(JSONObject t) {
        JSONObject damage = t.optJSONObject("damage");
        String damageCode = damage != null ? damage.optString("code", "") : "";
        String damageDesc = damage != null ? damage.optString("description", "") : "";

        JSONArray contactsArr = t.optJSONArray("contacts");
        String phones = "";
        if (contactsArr != null) {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < contactsArr.length(); i++) list.add(contactsArr.getString(i));
            phones = String.join(", ", list);
        }

        return "🏠 <b>" + esc(t.optString("address")) + "</b> · " + formatDate(t.optString("d_date")) + "\n" +
                "👤 " + esc(t.optString("fio")) + "\n" +
                "⚠️ " + esc(damageCode) + ": " + esc(damageDesc) + "\n\n" +
                esc(t.optString("description").trim()) + "\n\n" +
                "📞 " + esc(phones) + "\n" +
                "🕐 " + esc(t.optString("period")) + "   🆔 " + t.optString("id");
    }

    private static String formatDate(String dDate) {
        try {
            LocalDate date = LocalDate.parse(dDate);
            LocalDate today = LocalDate.now();
            if (date.isEqual(today)) return "Сегодня";
            if (date.isEqual(today.plusDays(1))) return "Завтра";
            return date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        } catch (Exception e) {
            return dDate;
        }
    }

    // Экранируем спецсимволы, чтобы Telegram не ругался на HTML-разметку
    // (в полях вроде fio встречаются кавычки и т.п.)
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // closed: "0" — в работе, "1" — закрыта (пока предположение, уточним если встретится другое значение)
    private static JSONArray filterByStatus(JSONArray tasks, boolean closed) {
        JSONArray result = new JSONArray();
        String want = closed ? "1" : "0";
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.getJSONObject(i);
            if (want.equals(t.optString("closed"))) result.put(t);
        }
        return result;
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
