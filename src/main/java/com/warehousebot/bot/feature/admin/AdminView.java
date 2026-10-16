package com.warehousebot.bot.feature.admin;

import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.domain.UserAccount;
import com.warehousebot.domain.UserRef;
import com.warehousebot.service.AdminService.BanOutcome;
import com.warehousebot.service.AdminService.NameFix;
import com.warehousebot.service.AdminService.Removal;
import com.warehousebot.util.Html;
import com.warehousebot.util.Months;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;

/** Тексты и клавиатуры панели администратора и меню загрузок. */
final class AdminView {

    static final String PANEL_MAIN = "ADM_PANEL_MAIN";

    static final String UPLOAD_TURNOVER = "📥 Загрузить ведомость (Excel)";
    static final String UPLOAD_TOOLS = "📥 Загрузить инструмент (Excel)";
    static final String UPLOAD_SCHEDULE = "📥 Загрузить график (Excel)";
    static final String UPLOAD_ORSH = "📥 Загрузить план ОРШ (Excel)";

    static final String NEED_BIND_NO_NAMES = "⚠ <b>Внимание!</b>\nВам необходимо идентифицировать себя, но администратор еще не загрузил график с новыми фамилиями. Пожалуйста, обратитесь к нему напрямую.";
    static final String NEED_BIND = "⚠️ <b>ОГРАНИЧЕНИЕ ДОСТУПА!</b>\n\nОстальной функционал бота временно заблокирован.\n👇 <b>Пожалуйста, выберите СВОЮ настоящую фамилию из списка ниже, чтобы продолжить работу:</b>";
    static final String ADMIN_UNBIND_NO_NAMES = "⚠ <b>Внимание! Требование Администратора:</b>\nВам необходимо идентифицировать себя, но администратор еще не загрузил график с новыми фамилиями. Пожалуйста, обратитесь к нему напрямую.";
    static final String ADMIN_UNBIND = "⚠️ <b>Внимание! Требование Администратора:</b>\n\nВам необходимо идентифицировать себя в системе, чтобы ваш аккаунт не был заблокирован.\n\n👇 <b>Пожалуйста, выберите СВОЮ настоящую фамилию из списка ниже:</b>";

    private AdminView() {
    }

    // ------------------------------------------------------------------ панель управления

    static final String PANEL_TEXT = "⚙️ <b>Панель управления персоналом и системой</b>\nВыберите необходимое действие:";

    static InlineKeyboardMarkup panelKeyboard() {
        return Keyboards.of(
                row(btn("📋 Список сотрудников", "ADM_PANEL_LIST")),
                row(btn("🔐 Управление доступом к ТМ", "ADM_TM_ACCESS_MAIN")),
                row(btn("✏️ Переименовать (/fix_name)", "ADM_PANEL_RENAME"), btn("🗑 Удалить (/fire)", "ADM_PANEL_DELETE")),
                row(btn("🔗 Сбросить привязку (/unbind)", "ADM_PANEL_UNBIND")),
                row(btn("🧹 Очистить склад", "ADM_PANEL_CLR_STOCK"), btn("🛠 Сброс зависших заявок", "ADM_PANEL_FIX_REQ")),
                row(btn("🗓 Очистить отчет за месяц", "ADM_PANEL_CLR_REP")),
                row(btn("❌ Закрыть панель", Keyboards.GENERIC_CLOSE)));
    }

    static InlineKeyboardMarkup backTo(String label, String data) {
        return Keyboards.single(label, data);
    }

    static String usersListText(List<UserAccount> users) {
        StringBuilder sb = new StringBuilder("👥 <b>Список пользователей бота:</b>\n\n");
        for (UserAccount u : users) {
            String status = switch (u.role()) {
                case BANNED -> "🚫 ЗАБЛОКИРОВАН";
                case ADMIN -> "👑 АДМИН";
                case PENDING -> "⏳ ОЖИДАЕТ ОДОБРЕНИЯ";
                default -> "👷‍♂️ МАСТЕР";
            };
            long id = u.id();
            sb.append(String.format("👤 <b>%s</b>\n   ID: <code>%d</code>\n   Связь: <a href=\"tg://user?id=%d\">Написать в ЛС</a>\n   Статус: %s\n",
                    Html.esc(String.valueOf(u.fullName())), id, id, status));
            if (u.role().hasNoAccess()) {
                sb.append(String.format("   Разблокировать/Одобрить: /unban_%d\n", id));
            } else if (!u.role().isAdmin()) {
                sb.append(String.format("   Блокировать: /ban_%d\n", id));
                sb.append(String.format("   Сбросить ФИО: /unbind_%d\n", id));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ удаление / переименование / отвязка

    static final String DELETE_MENU_TEXT = "🗑 <b>Удаление сотрудника</b>\nВыберите, кого нужно удалить из графика и системы:";
    static final String RENAME_MENU_TEXT = "✏️ <b>Переименование сотрудника</b>\nВыберите, чью фамилию нужно исправить:";
    static final String UNBIND_MENU_TEXT = "🔗 <b>Сброс привязки аккаунта</b>\nВыберите сотрудника, которого нужно отвязать от Telegram-аккаунта:";

    /** Список фамилий кнопками с общим префиксом подписи и callback data + «🔙 Назад». */
    static InlineKeyboardMarkup namesKeyboard(List<String> names, String labelPrefix, String dataPrefix) {
        Keyboards.Rows rows = Keyboards.rows();
        for (String name : names) rows.add(btn(labelPrefix + name, dataPrefix + name));
        rows.add(btn("🔙 Назад", PANEL_MAIN));
        return rows.build();
    }

    /** Привязанные сотрудники (фамилия → Telegram ID) для сброса привязки. */
    static InlineKeyboardMarkup unbindKeyboard(Map<String, Long> bound) {
        Keyboards.Rows rows = Keyboards.rows();
        bound.forEach((name, id) -> rows.add(btn("🔗 Сбросить: " + name, "ADM_UNB_DO:" + id)));
        rows.add(btn("🔙 Назад", PANEL_MAIN));
        return rows.build();
    }

    static String deleteConfirmText(String name) {
        return "⚠️ <b>ВНИМАНИЕ!</b>\nВы действительно хотите полностью удалить сотрудника <b>" + Html.esc(name) + "</b>?";
    }

    static InlineKeyboardMarkup deleteConfirmKeyboard(String name) {
        return Keyboards.of(
                row(btn("🔥 Да, удалить навсегда", "ADM_DEL_DO:" + name)),
                row(btn("🔙 Отмена", "ADM_PANEL_DELETE")));
    }

    static String removalText(Removal removal) {
        return switch (removal) {
            case Removal.ScheduleFailed f -> "❌ Ошибка при удалении из schedules_v2: " + Html.esc(String.valueOf(f.error()));
            case Removal.DbFailed f -> "❌ Ошибка БД: " + Html.esc(String.valueOf(f.error()));
            case Removal.Removed r when r.shifts() == 0 -> String.format(
                    "ℹ️ В графике <code>schedules_v2</code> записей для <b>%s</b> уже нет (возможно, удалились при предыдущей попытке). Проверьте список сотрудников.",
                    Html.esc(r.name()));
            case Removal.Removed r -> String.format(
                    "✅ Сотрудник <b>%s</b> полностью удален!\n• Удалено смен из графика: <b>%d</b>\n• Сброшено привязок: <b>%d</b>",
                    Html.esc(r.name()), r.shifts(), r.bindings());
        };
    }

    static String renameSelectedPrompt(String oldName) {
        return "📝 Вы выбрали: <b>" + Html.esc(oldName) + "</b>\n\n👇 Введите правильное ФИО следующим сообщением (например: <i>Новосельский В.З.</i>):";
    }

    static String renameCommandPrompt(String oldName) {
        return "📝 Вы хотите переименовать сотрудника «<b>" + Html.esc(oldName) + "</b>».\n\n👇 Введите правильную фамилию и инициалы (например: <i>Новосельский В.З.</i>):";
    }

    static String nameFixText(NameFix fix) {
        if (!fix.ok()) return "❌ Ошибка базы данных при замене имени: " + Html.esc(String.valueOf(fix.error()));
        return "✅ Все записи с именем <b>" + Html.esc(fix.oldName()) + "</b> успешно заменены на <b>" + Html.esc(fix.newName()) + "</b>!";
    }

    // ------------------------------------------------------------------ очистка склада

    static final String CLEAR_STOCK_CONFIRM_TEXT = "⚠️ <b>ВНИМАНИЕ!</b>\nВы собираетесь <b>полностью удалить все остатки материалов на складе</b>.\nЭто действие нельзя отменить!";

    static final String CLEAR_REPORT_CONFIRM_TEXT = "⚠️ <b>ВНИМАНИЕ! Начало нового месяца</b>\n\n"
            + "Вы собираетесь <b>очистить историю списаний для Excel-отчета</b> (листы «По квитанциям», «Без квитанций» и «Сводная»).\n"
            + "<i>Текущие остатки на складе МОЛ и на руках у мастеров НЕ пострадают.</i>\n\n"
            + "Убедитесь, что вы уже скачали отчет за прошедший месяц! Продолжить?";

    static InlineKeyboardMarkup clearReportConfirmKeyboard() {
        return Keyboards.of(
                row(btn("🚨 Да, обнулить отчет для нового месяца", "ADM_CLR_REP_YES")),
                row(btn("🔙 Отмена", PANEL_MAIN)));
    }

    static InlineKeyboardMarkup clearStockConfirmKeyboard() {
        return Keyboards.of(
                row(btn("🚨 Да, полностью очистить склад", "ADM_CLR_STOCK_YES")),
                row(btn("🔙 Отмена", PANEL_MAIN)));
    }



    // ------------------------------------------------------------------ доступ к ТМ

    static final String TM_ACCESS_TEXT = "🔐 <b>Управление доступом к ТМ</b>\n\nВыберите категорию сотрудников для просмотра и изменения прав:";

    static InlineKeyboardMarkup tmAccessKeyboard() {
        return Keyboards.of(
                row(btn("🟢 С доступом", "ADM_TM_HAS_ACCESS"), btn("🔴 Без доступа", "ADM_TM_NO_ACCESS")),
                row(btn("🔙 Назад в панель управления", PANEL_MAIN)));
    }

    static String tmUsersTitle(boolean hasAccess) {
        return hasAccess
                ? "🟢 <b>Сотрудники С ДОСТУПОМ к ТМ:</b>\nНажмите на сотрудника, чтобы забрать доступ:"
                : "🔴 <b>Сотрудники БЕЗ ДОСТУПА к ТМ:</b>\nНажмите на сотрудника, чтобы дать доступ:";
    }

    static InlineKeyboardMarkup tmUsersKeyboard(List<UserRef> users, boolean hasAccess) {
        Keyboards.Rows rows = Keyboards.rows();
        if (users.isEmpty()) {
            rows.add(btn("ℹ️ Список пуст", "IGNORE"));
        } else {
            for (UserRef u : users) {
                if (hasAccess) rows.add(btn("👤 " + u.fullName() + " ❌ [Забрать]", "ADM_TM_TOGGLE:" + u.id() + ":false:true"));
                else rows.add(btn("👤 " + u.fullName() + " ✅ [Дать доступ]", "ADM_TM_TOGGLE:" + u.id() + ":true:false"));
            }
        }
        rows.add(btn("🔙 Назад к выбору", "ADM_TM_ACCESS_MAIN"));
        return rows.build();
    }

    // ------------------------------------------------------------------ блокировка

    static String banText(BanOutcome outcome, long userId, boolean ban) {
        return switch (outcome) {
            case DONE -> ban ? "✅ Пользователь " + userId + " заблокирован. Бот больше не будет ему отвечать." : "✅ Пользователь разблокирован.";
            case NOT_FOUND -> "❌ Пользователь не найден или это администратор (которого нельзя заблокировать).";
            case DB_ERROR -> "❌ Ошибка базы данных.";
        };
    }

    // ------------------------------------------------------------------ загрузки

    static final String UPLOADS_MENU_TEXT = "📥 <b>Меню загрузки файлов (Excel):</b>\nВыберите базу для обновления:";

    static ReplyKeyboardMarkup uploadsKeyboard() {
        List<KeyboardRow> rows = new ArrayList<>();
        for (String label : List.of(UPLOAD_TURNOVER, UPLOAD_TOOLS, UPLOAD_SCHEDULE, UPLOAD_ORSH, "🔙 Назад")) {
            KeyboardRow r = new KeyboardRow();
            r.add(label);
            rows.add(r);
        }
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        markup.setKeyboard(rows);
        return markup;
    }

    /** Выбор месяца графика: текущий и два следующих. */
    static InlineKeyboardMarkup scheduleMonthsKeyboard(LocalDate now) {
        Keyboards.Rows rows = Keyboards.rows();
        for (int i = 0; i < 3; i++) {
            LocalDate d = now.plusMonths(i);
            rows.add(btn("📅 " + Months.name(d.getMonthValue()) + " " + d.getYear(),
                    "UPL_SCHED:" + d.getYear() + ":" + d.getMonthValue()));
        }
        return rows.build();
    }
}
