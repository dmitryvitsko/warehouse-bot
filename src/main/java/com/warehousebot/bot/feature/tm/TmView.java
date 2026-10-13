package com.warehousebot.bot.feature.tm;

import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.domain.HeldMaterial;
import com.warehousebot.infrastructure.tm.TmClient;
import com.warehousebot.util.Formats;
import com.warehousebot.util.Html;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;

/** Тексты и клавиатуры раздела «🛠 Мои заявки (ТМ)». Всё, что пришло от сервера ТМ, экранируется. */
final class TmView {

    private static final int TABLE_WIDTH = 34;
    private static final String CARD_SEPARATOR = "➖➖➖➖➖➖➖➖➖➖\n";
    static final String UNDER_REVIEW_MARK = "ОТЧЁТ НА РАССМОТРЕНИИ";
    static final String REJECTED_MARK = "ВОЗВРАТ НА ДОРАБОТКУ";

    private TmView() {
    }

    // ------------------------------------------------------------------ меню и карточки

    static final String TASKS_MENU = "🛠 <b>Мои заявки ТМ</b>\nВыберите раздел:";

    static InlineKeyboardMarkup tasksMenuKeyboard(JSONArray tasks) {
        return Keyboards.of(
                row(btn("📋 В работе (" + TmClient.countByStatus(tasks, false) + ")", "TM_INPROGRESS")),
                row(btn("✅ Закрытые (" + TmClient.countByStatus(tasks, true) + ")", "TM_CLOSED")),
                row(btn("❌ Закрыть панель", Keyboards.GENERIC_CLOSE)));
    }

    /** Подпись кнопки отчёта по статусу заявки (1 — на рассмотрении, 2 — возврат на доработку). */
    static String reportButtonText(int taskStatus) {
        return switch (taskStatus) {
            case 1 -> "⏳ Отчёт отправлен (Ожидает)";
            case 2 -> "⚠️ ДОРАБОТАТЬ (Отправить заново)";
            default -> "📝 Отправить отчёт";
        };
    }

    /** Количество в отчёте больше, чем числится на руках у сотрудника. */
    static String notEnoughOnHands(double available, String unit) {
        return "❌ У вас на руках только <b>" + com.warehousebot.util.Formats.qty(Math.max(available, 0)) + " " + Html.esc(unit)
                + "</b>. Введите количество не больше доступного:";
    }

    static String underReviewPrefix() {
        return "💠 <b>" + UNDER_REVIEW_MARK + "</b> 💠\n<i>Ожидает проверки диспетчером...</i>\n" + CARD_SEPARATOR;
    }

    static String rejectedPrefix(JSONObject task) {
        String reason = task.optString("task_note", "Причина не указана");
        if (reason.isEmpty() || reason.equals("null")) reason = "Причина не указана диспетчером";
        return "❌ <b>" + REJECTED_MARK + "!</b>\n💬 <i>Причина: " + Html.esc(reason) + "</i>\n" + CARD_SEPARATOR;
    }

    /** Карточка заявки «в работе» с плашкой статуса. */
    static String cardText(JSONObject task) {
        String card = formatTask(task);
        return switch (task.optInt("task_status", 0)) {
            case 1 -> underReviewPrefix() + card;
            case 2 -> rejectedPrefix(task) + card;
            default -> card;
        };
    }

    static InlineKeyboardMarkup cardKeyboard(String reportButtonText, String taskId) {
        return Keyboards.of(
                row(btn(reportButtonText, "TM_REPORT:" + taskId)),
                row(btn("📍 АСТУП", "TM_ASTUP:" + taskId), btn("📊 Параметры", "TM_PARAMS:" + taskId)),
                row(btn("📜 История", "TM_HIST:" + taskId)));
    }

    static String refreshText(int taskCount) {
        return "📋 Актуально заявок в работе: <b>" + taskCount + "</b>\n" +
                "👇 <i>Нажмите кнопку ниже, чтобы загрузить свежие данные:</i>";
    }

    static InlineKeyboardMarkup refreshKeyboard(int taskCount) {
        return Keyboards.of(
                row(btn("🔄 Обновить (Заявок: " + taskCount + ")", "TM_REFRESH")),
                row(btn("❌ Закрыть панель", "TM_CLOSE")));
    }

    static InlineKeyboardMarkup backToTaskKeyboard(String taskId) {
        return Keyboards.single("⬅ Назад к заявке", "TM_BACK:" + taskId);
    }

    // ------------------------------------------------------------------ форматирование данных ТМ

    /** Одна группа заявок (в работе либо закрытые) текстом. */
    static String formatTasksText(JSONArray tasks, boolean closed) {
        if (tasks == null) {
            return "⚠️ Не удалось получить заявки. Возможно, сессия устарела — попробуйте ещё раз.";
        }
        JSONArray filtered = TmClient.byStatus(tasks, closed);
        if (filtered.isEmpty()) {
            return closed ? "✅ Закрытых заявок нет." : "📋 Заявок в работе нет.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(closed ? "✅ <b>Закрытые заявки" : "📋 <b>Заявки в работе")
                .append(" (").append(filtered.length()).append("):</b>\n\n");
        for (int i = 0; i < filtered.length(); i++) {
            sb.append(formatTask(filtered.getJSONObject(i))).append("\n\n————————————————————\n\n");
        }
        return sb.toString();
    }

    static String formatTask(JSONObject t) {
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

        return "🏠 <b>" + Html.esc(t.optString("address")) + "</b> · " + Html.esc(formatDate(t.optString("d_date"))) + "\n" +
                "👤 " + Html.esc(t.optString("fio")) + "\n" +
                "⚠️ " + Html.esc(damageCode) + ": " + Html.esc(damageDesc) + "\n\n" +
                Html.esc(t.optString("description").trim()) + "\n\n" +
                "📞 " + Html.esc(phones) + "\n" +
                "🕐 " + Html.esc(t.optString("period")) + "   🆔 " + Html.esc(t.optString("id"));
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

    static String formatAstup(JSONObject astup) {
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

        return "<pre>" +
                "АСТУП физ. адрес:\n" + Html.esc(address) + "\n" +
                sep() + "\n" +
                astupBlock(ork, false) +
                sep() + "\n" +
                astupBlock(other, true) +
                sep() +
                "</pre>";
    }

    private static String astupBlock(JSONObject item, boolean showDevice) {
        if (item == null) return tableRow("нет данных");
        StringBuilder sb = new StringBuilder();
        sb.append(tableRow("Объект: " + Html.esc(item.optString("object_name"))));
        sb.append(tableRow("Номер: " + Html.esc(item.optString("object_num"))));
        sb.append(tableRow(""));
        if (showDevice) {
            String deviceName = item.optString("device_name", "");
            if (!deviceName.isEmpty()) sb.append(tableRow("Устройство: " + Html.esc(deviceName)));
            String deviceNum = item.optString("device_num", "");
            if (!deviceNum.isEmpty()) sb.append(tableRow("Порт: " + Html.esc(deviceNum)));
        }
        for (String addrLine : wrap(item.optString("address", ""), TABLE_WIDTH - 2)) {
            sb.append(tableRow(Html.esc(addrLine)));
        }
        return sb.toString();
    }

    static String formatParams(JSONObject params) {
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

        return "<pre>" +
                "Измерено: Сегодня в " + Html.esc(time) + "\n" +
                sep() + "\n" +
                tableRow("OLT: up=" + Html.esc(oltUp) + " / down=" + Html.esc(oltDown) + " dB") +
                tableRow("ONU: up=" + Html.esc(onuUp) + " / down=" + Html.esc(onuDown) + " dB") +
                tableRow("Затухание: " + Html.esc(attUp) + " / " + Html.esc(attDown) + " dB") +
                tableRow("Напряжение: " + Html.esc(voltage) + " В") +
                tableRow("Ток лазера: " + Html.esc(laser) + " мА") +
                tableRow("Температура: " + Html.esc(temp) + " °C") +
                sep() +
                "</pre>";
    }

    static String formatHistory(JSONObject historyObj, String taskId) {
        String header = "📜 <b>История выездов (Заявка #" + Html.esc(taskId) + "):</b>\n\n";
        if (historyObj == null || historyObj.optInt("code") != 1) {
            return header + "<i>❌ Не удалось получить историю или она пуста.</i>";
        }
        JSONArray history = historyObj.optJSONArray("result");
        if (history == null || history.isEmpty()) {
            return header + "<i>История пуста.</i>";
        }

        StringBuilder sb = new StringBuilder(header);
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.getJSONObject(i);
            String date = item.optString("date", "Неизвестно");
            String worker = item.optString("worker", "Неизвестный мастер");
            String note = item.optString("note", "").trim();
            String closeNote = item.optString("close_note", "").trim();
            if (note.isEmpty()) note = "Нет данных";
            if (closeNote.isEmpty()) closeNote = "Нет данных";

            sb.append("🗓 <b>").append(Html.esc(date)).append("</b>  |  👤 <i>").append(Html.esc(worker)).append("</i>\n");
            sb.append("🔴 <b>Заявка:</b>\n");
            sb.append("<blockquote>").append(Html.esc(note)).append("</blockquote>\n");
            sb.append("🟣 <b>Отчет:</b>\n");
            sb.append("<blockquote>").append(Html.esc(closeNote)).append("</blockquote>");
            if (i < history.length() - 1) sb.append("\n➖➖➖➖➖➖➖➖➖➖➖➖\n");
        }
        return sb.toString();
    }

    private static String tableRow(String text) {
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

    // ------------------------------------------------------------------ мастер отчёта

    static final String CANCEL_REPORT = "TM_REP_CANCEL";

    static String reportStartText(String taskId) {
        return "🛠 <b>Отчет по заявке #" + Html.esc(taskId) + "</b>\n\n<b>Шаг 1:</b> Укажите тип заявки:";
    }

    static InlineKeyboardMarkup reportTypeKeyboard() {
        return Keyboards.of(
                row(btn("🟢 ПОН", "TM_REP_TYPE:PON")),
                row(btn("🔵 ШПД", "TM_REP_TYPE:SHPD")),
                row(btn("💼 Выполнил служебное поручение", "TM_REP_TYPE:FAST_TRACK")),
                row(btn("❌ Отменить", CANCEL_REPORT)));
    }

    static final String FAST_TRACK_TEXT = "Выполнил служебное поручение";
    static final String FAST_CONFIRM = "💼 Отправить: <b>«Выполнил служебное поручение»</b>.\nВы уверены?";

    static InlineKeyboardMarkup fastConfirmKeyboard(String taskId) {
        return Keyboards.of(
                row(btn("✅ Да, отправить", "TM_REP_FAST_CONF:YES")),
                row(btn("🔙 Назад к выбору", "TM_REPORT:" + taskId), btn("❌ Отменить", CANCEL_REPORT)));
    }

    static String fastSentText(String taskId) {
        return "✅ Отчёт #" + Html.esc(taskId) + " отправлен:\n<i>" + FAST_TRACK_TEXT + "</i>";
    }

    static final String PON_CODES = "🟢 <b>Заявка ПОН (Шаг 2)</b>\nВыберите код закрытия:\n\n<b>102</b> — Квитанция\n<b>109</b> — Перемонтаж в ОРА\n<b>111</b> — Перемонтаж ВОК-1 в ОРА\n<b>113</b> — Повреждения на абон. участке\n<b>212</b> — Перемонтаж ВОК-1 в ОРК\n<b>214</b> — Замена райзера\n<b>215</b> — Выправил пигтейл в ОРШ\n<b>217</b> — Запасное волокно\n<b>226</b> — Замена пигтейла в ОРШ\n<b>227</b> — Выправил кабель ВОК-1";

    static InlineKeyboardMarkup ponCodesKeyboard(String taskId) {
        return Keyboards.of(
                row(btn("102", "TM_REP_CODE:102"), btn("109", "TM_REP_CODE:109"), btn("111", "TM_REP_CODE:111")),
                row(btn("113", "TM_REP_CODE:113"), btn("212", "TM_REP_CODE:212"), btn("214", "TM_REP_CODE:214")),
                row(btn("215", "TM_REP_CODE:215"), btn("217", "TM_REP_CODE:217"), btn("226", "TM_REP_CODE:226")),
                row(btn("227", "TM_REP_CODE:227")),
                row(btn("🔙 Назад", "TM_REPORT:" + taskId), btn("❌ Отменить", CANCEL_REPORT)));
    }

    static final String SHPD_RECEIPT_QUESTION = "🔵 <b>Заявка ШПД (Шаг 2)</b>\n\nВыписана квитанция?";

    static InlineKeyboardMarkup shpdReceiptKeyboard(String taskId) {
        return Keyboards.of(
                row(btn("🧾 Да", "TM_REP_HAS_REC:YES"), btn("🚫 Нет", "TM_REP_HAS_REC:NO")),
                row(btn("🔙 Назад", "TM_REPORT:" + taskId), btn("❌ Отменить", CANCEL_REPORT)));
    }

    static final String RECEIPT_NUMBER_PON = "🧾 <b>Код 102 (Квитанция)</b>\n\nВведите номер выписанной квитанции:";
    static final String RECEIPT_NUMBER_SHPD = "🧾 <b>Заявка ШПД</b>\n\nВведите номер выписанной квитанции:";
    static final String ENTER_QTY = "✍️ <b>Введите количество</b> списываемого материала (например: 1 или 15.5):";
    static final String BAD_QTY = "❌ Введите корректное число больше нуля (например: 5 или 0.5):";
    static final String ENTER_REPORT_TEXT = "📝 <b>Шаг 4: Текст отчета</b>\n\nНапишите текст отчета, который увидит диспетчер:";
    static final String EDIT_REPORT_TEXT = "📝 <b>Изменение текста</b>\n\nНапишите новый текст отчета для диспетчера:";
    static final String RETAIL_QUESTION = "🛍 <b>Шаг 5: Допродажи (Ритейл)</b>\n\nПредлагались ли абоненту товары в рассрочку?";

    static InlineKeyboardMarkup editTextKeyboard() {
        return Keyboards.single("🔙 Назад в превью", "TM_REP_PREVIEW");
    }

    static InlineKeyboardMarkup retailKeyboard() {
        return Keyboards.of(
                row(btn("Ритейл +", "TM_REP_RET:PLUS"), btn("Ритейл -", "TM_REP_RET:MINUS")),
                row(btn("Не указывать", "TM_REP_RET:NONE")),
                row(btn("🔙 Назад к тексту", "TM_REP_MAT_DONE"), btn("❌ Отменить", CANCEL_REPORT)));
    }

    /** Клавиатура по умолчанию для шагов, где ждём текст. */
    static InlineKeyboardMarkup cancelReportKeyboard() {
        return Keyboards.single("❌ Отменить", CANCEL_REPORT);
    }

    private static void appendUsed(StringBuilder sb, List<TmReportSession.UsedMaterial> used) {
        for (TmReportSession.UsedMaterial m : used) {
            sb.append("▪ ").append(Html.esc(m.name())).append(" — ").append(Formats.qty(m.quantity()))
                    .append(" ").append(Html.esc(m.unit())).append("\n");
        }
    }

    static String materialSelectionText(TmReportSession s) {
        StringBuilder sb = new StringBuilder("📦 <b>Списание материалов (Шаг 3)</b>\n\n");
        if (!s.usedMaterials.isEmpty()) {
            sb.append("✅ <b>Уже добавлено к списанию:</b>\n");
            appendUsed(sb, s.usedMaterials);
            sb.append("\n");
        }
        sb.append("👇 Выберите материал из вашего подотчета ИЛИ нажмите кнопку завершения:");
        return sb.toString();
    }

    static InlineKeyboardMarkup materialSelectionKeyboard(TmReportSession s, List<HeldMaterial> held) {
        Keyboards.Rows rows = Keyboards.rows();
        for (HeldMaterial m : held) {
            rows.add(btn("📦 " + Formats.cut(m.name(), 25) + " (" + Formats.qty(m.quantity()) + " " + m.unit() + ")",
                    "TM_REP_MAT:" + m.materialId()));
        }
        boolean empty = s.usedMaterials.isEmpty();
        if (s.editing) {
            rows.add(btn(empty ? "🚫 Без материалов (Вернуться в превью)" : "✅ Сохранить и вернуться в превью", "TM_REP_MAT_DONE"));
            rows.add(btn("🔙 Назад в превью", "TM_REP_PREVIEW"));
        } else {
            rows.add(btn(empty ? "🚫 Материалы не использовались" : "✅ Готово (Перейти к тексту)", "TM_REP_MAT_DONE"));
            rows.add(btn("🔙 Назад в начало", "TM_REPORT:" + s.taskId), btn("❌ Отменить", CANCEL_REPORT));
        }
        return rows.build();
    }

    static String previewText(TmReportSession s) {
        StringBuilder mat = new StringBuilder();
        if (s.usedMaterials.isEmpty()) mat.append("🚫 <i>Материалы не списываются</i>");
        else appendUsed(mat, s.usedMaterials);

        return "📄 <b>Проверьте отчет перед отправкой:</b>\n\n" +
                "🌐 <b>Уйдет в ТМ:</b>\n<code>" + Html.esc(s.tmText()) + "</code>\n\n" +
                "📦 <b>Спишется со склада:</b>\n" + mat;
    }

    static InlineKeyboardMarkup previewKeyboard(String taskId) {
        return Keyboards.of(
                row(btn("🚀 Отправить отчет в ТМ", "TM_REP_SEND")),
                row(btn("✏️ Изменить текст", "TM_REP_EDIT_TXT"), btn("📦 Изменить материалы", "TM_REP_EDIT_MAT")),
                row(btn("🔄 Начать заново", "TM_REPORT:" + taskId), btn("❌ Отменить", CANCEL_REPORT)));
    }

    static String sendResultText(String taskId, String tmText, boolean sentToTm, List<String> failedWriteOffs) {
        String text = sentToTm
                ? "✅ <b>Отчет по заявке #" + Html.esc(taskId) + " успешно отправлен в ТМ!</b>\n\nТекст отчета:\n<code>" + Html.esc(tmText) + "</code>"
                : "⚠️ Материалы списаны, но <b>не удалось отправить отчет в ТМ</b> (проверьте подключение или статус заявки).";
        if (failedWriteOffs.isEmpty()) return text;
        return text + "\n\n" + String.join("\n", failedWriteOffs);
    }

    static InlineKeyboardMarkup backToTasksKeyboard() {
        return Keyboards.single("📋 Вернуться к заявкам", "TM_INPROGRESS");
    }
}
