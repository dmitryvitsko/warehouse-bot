package com.warehousebot.bot.feature.welders;

import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.domain.ActiveUser;
import com.warehousebot.domain.Welder;
import com.warehousebot.domain.WelderHistoryEntry;
import com.warehousebot.service.WelderService;
import com.warehousebot.util.Html;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;

/** Тексты и клавиатуры раздела «⚡️ Сварочные аппараты». */
final class WeldersView {

    /** Готовый экран: текст + inline-клавиатура. */
    record Screen(String text, InlineKeyboardMarkup markup) {
    }

    static final String TRANSFER_CANCELLED = "ℹ️ Коллега отменил передачу сварочного аппарата.";

    private WeldersView() {
    }

    // ------------------------------------------------------------------ главная панель

    /**
     * Панель сварочников: кто что взял, кнопки свободных аппаратов, для владельца — «вернуть»/«передать».
     *
     * @param statusMsg строка результата действия (уже экранированная) или {@code null}
     */
    static Screen mainMenu(List<Welder> welders, long chatId, boolean admin, String statusMsg) {
        StringBuilder sb = new StringBuilder();
        if (statusMsg != null && !statusMsg.isEmpty()) {
            sb.append("✅ <i>").append(statusMsg).append("</i>\n\n");
        }

        Keyboards.Rows rows = Keyboards.rows();
        boolean hasBusy = false;
        Welder mine = null;
        for (Welder w : welders) {
            if (!w.inUse()) continue;
            if (!hasBusy) {
                sb.append("🔴 <b>В работе у коллег:</b>\n");
                hasBusy = true;
            }
            sb.append("▫ ").append(Html.esc(w.name())).append(" 👉 у <b>").append(Html.esc(w.holderName())).append("</b>\n");
            if (w.heldBy(chatId)) mine = w;
        }

        if (mine != null) {
            sb.insert(statusMsg != null ? sb.indexOf("\n\n") + 2 : 0,
                    "⚠️ <b>На вас сейчас числится: " + Html.esc(mine.name()) + "</b>\n\n");
            rows.add(btn("↩️ ВЕРНУТЬ НА БАЗУ", "W_RET:" + mine.id()),
                    btn("🔄 Передать коллеге", "W_TRANS_START:" + mine.id()));
            sb.append("\n👇 <b>Управление и свободные аппараты:</b>");
        } else {
            sb.append("\n👇 <b>Выберите свобод аппарат, который берете с базы:</b>");
        }

        for (Welder w : welders) {
            if (w.onBase()) rows.add(btn("⚡️ " + w.name(), "W_TAKE:" + w.id()));
        }
        rows.add(btn("📜 История логов", "W_HISTORY"));
        if (admin) {
            rows.add(btn("➕ Выдать принудительно", "W_FORCE_TAKE_M"), btn("⚠️ Вернуть принудительно", "W_FORCE_RET_M"));
        }
        rows.add(btn("❌ Закрыть панель", "W_CLOSE"));
        return new Screen(sb.toString(), rows.build());
    }

    // ------------------------------------------------------------------ передача коллеге

    static Screen transferUsers(String welderName, int welderId, List<ActiveUser> users, long chatId) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (ActiveUser u : users) {
            if (u.id() == chatId) continue; // самому себе не передаём
            buttons.add(btn("👤 " + u.fullName(), "W_TRANS_SEL:" + welderId + ":" + u.id()));
        }
        InlineKeyboardMarkup markup = Keyboards.rows()
                .addAll(Keyboards.grid(buttons, 2))
                .add(btn("🔙 Назад", "W_MAIN_MENU"))
                .build();
        return new Screen("🔄 <b>Кому передаем " + Html.esc(welderName) + "?</b>\nВыберите сотрудника из списка:", markup);
    }

    /** Запрос получателю с кнопками «Принять»/«Отказаться». */
    static Screen transferRequest(String senderFirstName, String welderName, int welderId, long senderId) {
        InlineKeyboardMarkup markup = Keyboards.of(row(
                btn("✅ Принять", "W_TRANS_ACC:" + welderId + ":" + senderId),
                btn("❌ Отказаться", "W_TRANS_REJ:" + welderId + ":" + senderId)));
        return new Screen("🚨 <b>ПЕРЕДАЧА ОБОРУДОВАНИЯ!</b>\n\nКоллега <b>" + Html.esc(senderFirstName)
                + "</b> передает вам сварочный аппарат <b>" + Html.esc(welderName) + "</b>.\n\n👇 Подтвердите получение:", markup);
    }

    /** Окно передающего, пока получатель не ответил. */
    static Screen transferWaiting(String welderName, int welderId, String receiverName) {
        return new Screen("⏳ <b>Ожидание подтверждения...</b>\n\nВы передаете аппарат <b>" + Html.esc(welderName)
                + "</b> сотруднику <b>" + Html.esc(receiverName) + "</b>.\nЗапрос отправлен, ожидаем решения...",
                Keyboards.single("🚫 Отменить передачу", "W_TRANS_CANCEL:" + welderId));
    }

    static String receiverLocked() {
        return "⚠️ <b>ДОСТУП ЗАБЛОКИРОВАН!</b>\nКоллега передает вам сварочный аппарат.\n\n👇 Пожалуйста, найдите сообщение ниже с кнопками <b>«Принять»</b> / <b>«Отказаться»</b> и сделайте выбор.";
    }

    // ------------------------------------------------------------------ конец смены

    static Screen endOfShiftWarning(int welderId, String welderName, long minutesLeft) {
        String text = "🚨 <b>ДЕЙСТВИЕ ПРИОСТАНОВЛЕНО! Отчет по оборудованию</b> 🚨\n\n" +
                "Ваша рабочая смена заканчивается через <b>" + minutesLeft + " минут</b>. " +
                "На вас числится сварочный аппарат: <b>" + Html.esc(welderName) + "</b>.\n\n" +
                "Чтобы аппараты не терялись, бот временно заблокировал ваше основное меню. Пожалуйста, отчитайтесь за прибор:\n\n" +
                "👇 <i>Выберите один из вариантов ниже:</i>";
        InlineKeyboardMarkup markup = Keyboards.of(
                row(btn("↩️ Я сдал прибор на базу", "W_RET:" + welderId)),
                row(btn("🌙 Оставляю себе на завтра", "W_KEEP_TOMORROW")));
        return new Screen(text, markup);
    }

    static String returnLocked(String welderName) {
        return "⚠️ <b>ДОСТУП ЗАБЛОКИРОВАН!</b>\n\nУ вас висит необработанный запрос по возврату сварочного аппарата: <b>"
                + Html.esc(welderName) + "</b>.\n\nПоднимитесь чуть выше в истории чата и нажмите одну из кнопок под сообщением-напоминанием!";
    }

    // ------------------------------------------------------------------ журнал

    static Screen history(List<WelderHistoryEntry> newestFirst) {
        InlineKeyboardMarkup markup = Keyboards.single("🔙 Назад к списку", "W_MAIN_MENU");
        if (newestFirst.isEmpty()) return new Screen("📜 Журнал сварочных аппаратов пока пуст.", markup);

        List<String> lines = new ArrayList<>();
        for (WelderHistoryEntry e : newestFirst) lines.add(historyLine(e));
        Collections.reverse(lines); // старые сверху, новые снизу

        StringBuilder sb = new StringBuilder("📜 <b>История движения аппаратов (30 записей):</b>\n\n");
        for (String l : lines) sb.append(l).append("\n\n");
        return new Screen(sb.toString().trim(), markup);
    }

    static String historyLine(WelderHistoryEntry e) {
        String time = formatTime(e.localTime());
        String welder = Html.esc(e.welderName());
        String user = Html.esc(e.userName());
        String action = e.action();
        if (action.equals(WelderService.FORCE_TAKEN) || action.equals(WelderService.FORCE_RETURNED)) {
            String admin = e.adminName() != null ? Html.esc(e.adminName()) : "Админ";
            if (action.equals(WelderService.FORCE_TAKEN)) {
                return String.format("🔴 `[%s]` <b>%s</b> выдал <i>%s</i> ➔ <b>%s</b>", time, admin, welder, user);
            }
            return String.format("🟢 `[%s]` <b>%s</b> списал <i>%s</i> у <b>%s</b>", time, admin, welder, user);
        }
        if (action.equals(WelderService.TAKEN)) {
            return String.format("🔴 `[%s]` <b>%s</b> взял <i>%s</i>", time, user, welder);
        }
        return String.format("🟢 `[%s]` <b>%s</b> вернул <i>%s</i>", time, user, welder);
    }

    /** «2026-10-05 14:20:00» → «05.10 14:20»; иначе строка как есть. */
    static String formatTime(String time) {
        try {
            if (time != null && time.length() >= 16) {
                String[] dp = time.substring(0, 10).split("-");
                return dp[2] + "." + dp[1] + " " + time.substring(11, 16);
            }
        } catch (Exception ignored) {
            // нестандартный формат — показываем как есть
        }
        return time;
    }

    // ------------------------------------------------------------------ администратор

    static Screen forceAssignWelders(List<Welder> welders) {
        Keyboards.Rows rows = Keyboards.rows();
        for (Welder w : welders) {
            if (w.onBase()) rows.add(btn("⚡️ " + w.name(), "W_F_SEL:" + w.id()));
        }
        rows.add(btn("🔙 Назад", "W_MAIN_MENU"));
        return new Screen("➕ <b>Принудительная выдача (Шаг 1)</b>\nВыберите свободный сварочник:", rows.build());
    }

    static Screen forceAssignUsers(String welderName, int welderId, List<ActiveUser> users) {
        Keyboards.Rows rows = Keyboards.rows();
        for (ActiveUser u : users) rows.add(btn("👤 " + u.fullName(), "W_F_ASS:" + welderId + ":" + u.id()));
        rows.add(btn("🔙 Назад", "W_FORCE_TAKE_M"));
        return new Screen("➕ Принудительная выдача: <b>" + Html.esc(welderName)
                + "</b>\n\n👤 <b>Шаг 2: Выберите сотрудника</b>, на которого нужно повесить аппарат:", rows.build());
    }

    static Screen forceReturnWelders(List<Welder> welders) {
        Keyboards.Rows rows = Keyboards.rows();
        for (Welder w : welders) {
            if (w.inUse()) rows.add(btn("⚠️ Списать: " + w.name() + " (у " + w.holderName() + ")", "W_F_RET:" + w.id()));
        }
        rows.add(btn("🔙 Назад", "W_MAIN_MENU"));
        return new Screen("⚠️ <b>Принудительный возврат на базу</b>\nВыберите аппарат, который хотите списать с сотрудника:", rows.build());
    }
}
