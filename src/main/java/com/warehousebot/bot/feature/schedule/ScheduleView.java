package com.warehousebot.bot.feature.schedule;

import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.domain.ShiftDay;
import com.warehousebot.service.ScheduleService;
import com.warehousebot.service.ScheduleService.PartnerShift;
import com.warehousebot.util.Html;
import com.warehousebot.util.Months;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;

/** Тексты и клавиатуры раздела «🗓 График работ». */
final class ScheduleView {

    static final String MENU_TITLE = "🗓 <b>Графики и смены:</b>\nВыберите, что хотите посмотреть:";
    static final String NOT_UPLOADED = "ℹ График работ еще не загружен администратором.";
    static final String NOT_UPLOADED_SHORT = "ℹ График работ еще не загружен.";
    static final String NOT_BOUND_PROFILE = "ℹ Ваш профиль еще не привязан к графику.";
    static final String BIND_NAME_PROMPT = "👤 <b>Вы еще не привязаны к графику.</b>\nВыберите вашу фамилию:";
    static final String COLLEAGUES_TITLE = "👁 <b>График коллеги</b>\nВыберите сотрудника:";
    static final String EDITOR_TITLE = "✏️ <b>Редактор графиков</b>\nВыберите сотрудника:";
    static final String NEW_EMPLOYEE_PROMPT = "👤 Введите ФИО нового сотрудника (например: <b>Баранов А.В.</b>):";
    static final String FRIDAY_QUESTION = "🗓 <b>Уточните дату</b>\n\nВы хотите посмотреть график на выходной день (Суббота) или на следующий рабочий (Понедельник)?";
    static final String EDIT_DATE_ERROR = "❌ <b>Не удалось распознать дату.</b>\n\nВы можете писать в любых форматах (с годом и без):\n• Одиночно: <code>15.11</code> или <code>15.11.26</code> или <code>15.11.2026</code>\n• Диапазон: <code>01.11-07.11</code> или <code>01.11.26-07.11.2026</code>\n• Комбинировано: <code>10.11, 15.11-18.11, 20.11.26</code>\n\nВведите даты еще раз:";
    static final String EDIT_SESSION_ERROR = "❌ Ошибка сессии редактирования. Начните заново.";
    static final String EDIT_SAVE_ERROR = "❌ Ошибка при сохранении дат.";
    static final String EDIT_SAVED = "✅ График успешно изменен!";
    static final String SCHEDULE_DB_ERROR = "❌ Ошибка при чтении графика из базы данных.";
    static final String PARTNERS_DB_ERROR = "❌ Ошибка при поиске напарников.";
    static final String ROSTER_DB_ERROR = "❌ Ошибка при получении сводки.";
    static final String EMPLOYEE_DB_ERROR = "❌ Ошибка базы данных при добавлении сотрудника.";
    static final String CANCELLED = "🚫 <b>Действие отменено.</b>";
    static final String BACK_TO_MENU = "Вы вернулись в меню графиков:";

    static final String SICK_NOT_BOUND = "ℹ️ Вы не привязаны к графику.";
    static final String SICK_INPUT_NOT_BOUND = "❌ Ошибка: вы не привязаны к графику.";
    static final String SICK_DATE_ERROR = "❌ <b>Не удалось распознать дату.</b>\n\nПожалуйста, используйте форматы:\n• Одиночно: <code>15.11</code>\n• Диапазоны: <code>15.10-22.10</code>\n\nВведите даты еще раз:";
    static final String REQUEST_ALREADY_PROCESSED = "Запрос уже обработан или не найден.";

    /** Разделитель между сменами в списке напарников. */
    private static final String PARTNER_SEPARATOR = "┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈\n";

    private ScheduleView() {
    }

    // ------------------------------------------------------------------ меню

    static InlineKeyboardMarkup menuKeyboard(boolean canEdit, boolean admin) {
        Keyboards.Rows rows = Keyboards.rows()
                .add(btn("🗓 Мой график", "SCHED_MINE"), btn("🤝 С кем я в смене?", "SCHED_WITH_ME"))
                .add(btn("👁 График коллеги", "SCHED_COL_LIST"), btn("👥 Кто сегодня работает?", "SCHED_TODAY"))
                .add(btn("🔜 Кто работает завтра?", "SCHED_TOMORROW"))
                .add(btn("🤒 Сообщить о больничном", "SCHED_SICK"));
        List<InlineKeyboardButton> adminRow = new ArrayList<>();
        if (canEdit) adminRow.add(btn("✏️ Изменить смену", "SCHED_EDIT"));
        if (admin) adminRow.add(btn("➕ Добавить сотрудника", "SCHED_ADD"));
        rows.addRow(adminRow);
        return rows.add(btn("❌ Закрыть графики", Keyboards.GENERIC_CLOSE)).build();
    }

    /** «🔙 Назад» в меню графиков + «❌ Закрыть» (под сводками и списком напарников). */
    static InlineKeyboardMarkup sectionKeyboard() {
        return Keyboards.of(row(btn("🔙 Назад", "SCHED_MAIN")), row(btn("❌ Закрыть", Keyboards.GENERIC_CLOSE)));
    }

    static InlineKeyboardMarkup fridayKeyboard() {
        return Keyboards.of(
                row(btn("Сб (Завтра)", "SCHED_DAY_1"), btn("Пн (След. рабочий)", "SCHED_DAY_3")),
                row(btn("🔙 Назад", "SCHED_MAIN"), btn("❌ Закрыть", Keyboards.GENERIC_CLOSE)));
    }

    static InlineKeyboardMarkup colleaguesKeyboard(List<String> names) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (String name : names) buttons.add(btn("👤 " + name, "VIEW_SCHED:" + name));
        return Keyboards.rows().addAll(Keyboards.grid(buttons, 2))
                .add(btn("🔙 Назад", "SCHED_MAIN"))
                .add(btn("❌ Закрыть", Keyboards.GENERIC_CLOSE))
                .build();
    }

    static InlineKeyboardMarkup editorKeyboard(List<String> names) {
        List<InlineKeyboardButton> buttons = new ArrayList<>();
        for (String name : names) buttons.add(btn("✏️ " + name, "ED_SCH_U:" + name));
        return Keyboards.rows().addAll(Keyboards.grid(buttons, 2)).add(btn("🔙 Назад", "SCHED_MAIN")).build();
    }

    static InlineKeyboardMarkup backToMenuKeyboard() {
        return Keyboards.single("🔙 Назад", "SCHED_MAIN");
    }

    // ------------------------------------------------------------------ график сотрудника

    static InlineKeyboardMarkup scheduleKeyboard(String excelName, int year, int month, boolean mine) {
        LocalDate current = LocalDate.of(year, month, 1);
        LocalDate prev = current.minusMonths(1);
        LocalDate next = current.plusMonths(1);
        String prefix = mine ? "MY_SCHED:" : "COL_SCHED:";
        String extra = mine ? "" : (":" + excelName);

        Keyboards.Rows rows = Keyboards.rows().add(
                btn("⬅️ " + Months.name(prev.getMonthValue()), prefix + prev.getYear() + ":" + prev.getMonthValue() + extra),
                btn(Months.name(next.getMonthValue()) + " ➡️", prefix + next.getYear() + ":" + next.getMonthValue() + extra));
        if (mine) rows.add(btn("🔙 Назад", "SCHED_MAIN"));
        else rows.add(btn("🔙 К списку коллег", "SCHED_COL_LIST"));
        return rows.add(btn("❌ Закрыть", Keyboards.GENERIC_CLOSE)).build();
    }

    static String scheduleText(String excelName, int year, int month, List<ShiftDay> days) {
        String mName = Months.name(month).toUpperCase(Locale.ROOT);
        String name = Html.esc(excelName);
        if (days.isEmpty()) {
            return "ℹ️ График для сотрудника <b>" + name + "</b> на " + mName + " " + year + " г. не найден в базе.";
        }

        StringBuilder sb = new StringBuilder();
        int weekNumber = 1;
        boolean first = true;
        for (ShiftDay d : days) {
            LocalDate date = LocalDate.of(year, month, d.day());
            if (first) {
                sb.append(String.format("🗓 <b>График на %s %d г.</b>\n👤 Сотрудник: <b>%s</b>\n\n➖ <b>Неделя %d</b> ➖➖➖➖➖➖\n", mName, year, name, weekNumber));
                first = false;
            } else if (date.getDayOfWeek() == DayOfWeek.MONDAY) {
                weekNumber++;
                sb.append(String.format("\n➖ <b>Неделя %d</b> ➖➖➖➖➖➖\n", weekNumber));
            }
            sb.append(scheduleLine(date, d)).append("\n");
        }
        return sb.toString();
    }

    private static String scheduleLine(LocalDate date, ShiftDay d) {
        String dow = dayOfWeekRu(date);
        int dayNum = d.day();
        String start = d.startTime();
        String end = d.endTime();
        String timeRange = (start != null && !start.isEmpty() && end != null && !end.isEmpty())
                ? Html.esc(start) + " - " + Html.esc(end) : "";

        String status = d.statusCode();
        if ("В".equals(status)) return String.format("🏖 <u><b>%s, %02d</b></u> — Выходной", dow, dayNum);
        if ("О".equals(status)) return String.format("🌴 <b>%s, %02d</b> — Отпуск", dow, dayNum);
        if ("Д".equals(status)) return String.format("🚨 <u><b>%s, %02d</b></u> — ❗️ <b>Дежурство</b>", dow, dayNum);
        if ("Б".equals(status)) return String.format("💊 <b>%s, %02d</b> — Больничный", dow, dayNum);
        if ("А".equals(status)) return String.format("📄 <b>%s, %02d</b> — За свой счет", dow, dayNum);
        if ("Г".equals(status)) return String.format("🪖 <b>%s, %02d</b> — Военкомат", dow, dayNum);
        if ("П".equals(status)) return String.format("🏢 <b>%s, %02d</b> — Др. подразделение", dow, dayNum);

        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return String.format("🟥 <u><b>%s, %02d</b></u> — %s ❗️ <b>РАБОЧАЯ СУББОТА</b>", dow, dayNum, timeRange);
        }
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return String.format("🟥 <u><b>%s, %02d</b></u> — %s ❗️ <b>РАБОЧЕЕ ВОСКРЕСЕНЬЕ</b>", dow, dayNum, timeRange);
        }
        String icon = ScheduleService.startsInSecondShiftHours(start) ? "🟧" : "🟩";
        return String.format("%s <b>%s, %02d</b> — %s", icon, dow, dayNum, timeRange);
    }

    // ------------------------------------------------------------------ напарники

    static String partnersText(List<PartnerShift> shifts) {
        if (shifts.isEmpty()) return "ℹ️ В этом месяце у вас нет рабочих суббот или вторых смен.";

        StringBuilder sb = new StringBuilder("🤝 <b>Ваши напарники (текущий месяц):</b>\n\n");
        for (PartnerShift s : shifts) {
            List<String> names = new ArrayList<>();
            for (String w : s.seniors()) names.add("👑 <b>" + Html.esc(w) + "</b>");
            for (String w : s.partners()) names.add(Html.esc(w));

            LocalDate date = s.date();
            String shiftName = s.saturday() ? "Рабочая суббота" : "Вторая смена";
            sb.append(String.format("🔹 <b>%02d.%02d (%s) | %s</b>", date.getDayOfMonth(), date.getMonthValue(), dayOfWeekRu(date), shiftName));
            if (s.iAmSenior()) sb.append("  👑 <i>(Вы — старший смены!)</i>");
            sb.append("\n");

            if (names.isEmpty()) {
                sb.append(" ▪ <i>(Никого не найдено)</i>\n");
            } else {
                // Две колонки
                for (int i = 0; i < names.size(); i += 2) {
                    sb.append("▪ ").append(names.get(i));
                    if (i + 1 < names.size()) sb.append("    ▪ ").append(names.get(i + 1));
                    sb.append("\n");
                }
            }
            sb.append(PARTNER_SEPARATOR);
        }
        // Убираем последний разделитель (раньше вместе с ним срезались и 4 символа предыдущей строки).
        sb.setLength(sb.length() - PARTNER_SEPARATOR.length());
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ сводки

    static String todayHeader(LocalDate date) {
        return String.format("📅 <b>Сводка на сегодня (%02d.%02d.%d, %s):</b>\n\n",
                date.getDayOfMonth(), date.getMonthValue(), date.getYear(), dayOfWeekRu(date));
    }

    static String targetDayHeader(LocalDate date, String dayLabel) {
        return String.format("📅 <b>Сводка на %s (%02d.%02d.%d, %s):</b>\n\n",
                dayLabel.toLowerCase(Locale.ROOT), date.getDayOfMonth(), date.getMonthValue(), date.getYear(), dayOfWeekRu(date));
    }

    /**
     * Кто работает в день {@code date}: смены, дежурство, другое подразделение, отсутствующие.
     * В субботу показывается одна общая смена со старшим (👑), в воскресенье — только дежурные.
     */
    static String rosterText(String header, String noDataText, LocalDate date, List<ShiftDay> rows) {
        if (rows.isEmpty()) return noDataText;

        List<ShiftDay> firstShift = new ArrayList<>();
        List<ShiftDay> secondShift = new ArrayList<>();
        List<String> duty = new ArrayList<>();
        List<String> otherDept = new ArrayList<>();
        List<String> absent = new ArrayList<>();

        for (ShiftDay r : rows) {
            String name = Html.esc(r.excelName());
            String status = r.statusCode();
            if ("В".equals(status)) absent.add("▪️ " + name + " — 🏖 Выходной");
            else if ("О".equals(status)) absent.add("▪️ " + name + " — 🌴 Отпуск");
            else if ("Б".equals(status)) absent.add("▪️ " + name + " — 💊 Больничный");
            else if ("А".equals(status)) absent.add("▪️ " + name + " — 📄 За свой счет");
            else if ("Г".equals(status)) absent.add("▪️ " + name + " — 🪖 Военкомат");
            else if ("П".equals(status)) otherDept.add("▪️ " + name);
            else if ("Д".equals(status)) duty.add("▪️ " + name + " — ❗️ Дежурство");
            else if (ScheduleService.isSecondShift(r.startTime(), r.endTime())) secondShift.add(r);
            else firstShift.add(r);
        }

        StringBuilder sb = new StringBuilder(header);
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SUNDAY) {
            appendDuty(sb, duty);
        } else if (dow == DayOfWeek.SATURDAY) {
            List<ShiftDay> saturday = new ArrayList<>(firstShift);
            saturday.addAll(secondShift);
            appendSection(sb, "☀️ <b>Рабочая смена:</b>\n", shiftLines(saturday, true));
            appendDuty(sb, duty);
        } else {
            appendSection(sb, "☀️ <b>Первая смена:</b>\n", shiftLines(firstShift, false));
            appendSection(sb, "🌙 <b>Вторая смена:</b>\n", shiftLines(secondShift, false));
            appendSection(sb, "🚨 <b>Дежурство:</b>\n", duty);
            appendSection(sb, "🏢 <b>В другом подразделении:</b>\n", otherDept);
            if (!absent.isEmpty()) sb.append("❌ <b>Отсутствуют:</b>\n").append(String.join("\n", absent));
        }
        return sb.toString().trim();
    }

    private static void appendSection(StringBuilder sb, String title, List<String> lines) {
        if (!lines.isEmpty()) sb.append(title).append(String.join("\n", lines)).append("\n\n");
    }

    private static void appendDuty(StringBuilder sb, List<String> duty) {
        if (duty.isEmpty()) sb.append("🚨 <b>Дежурство:</b>\n▪️ Дежурных нет.\n\n");
        else sb.append("🚨 <b>Дежурство:</b>\n").append(String.join("\n", duty)).append("\n\n");
    }

    /** Строки смены; при {@code showCrown} старший смены отмечается 👑. */
    private static List<String> shiftLines(List<ShiftDay> workers, boolean showCrown) {
        int minRank = ScheduleService.NO_RANK;
        if (showCrown) {
            for (ShiftDay w : workers) minRank = Math.min(minRank, ScheduleService.seniorityRank(w.excelName()));
        }
        List<String> lines = new ArrayList<>();
        for (ShiftDay w : workers) {
            String start = w.startTime();
            String end = w.endTime();
            String time = (start != null && end != null && !start.isEmpty() && !end.isEmpty())
                    ? " (" + Html.esc(start) + " - " + Html.esc(end) + ")" : "";
            boolean crown = showCrown && ScheduleService.seniorityRank(w.excelName()) == minRank && minRank != ScheduleService.NO_RANK;
            lines.add("▪️ " + Html.esc(w.excelName()) + (crown ? " 👑" : "") + time);
        }
        return lines;
    }

    // ------------------------------------------------------------------ редактирование

    static String editUserPrompt(String excelName) {
        return "✏️ Выбран сотрудник: <b>" + Html.esc(excelName) + "</b>\n\nНапишите дни, которые нужно изменить.\n\n<i>Можно писать без года:</i> <code>15.11</code>\n<i>Через запятую:</i> <code>10.11.2026, 15.11.26, 20.11</code>\n<i>Диапазоны:</i> <code>01.11-07.11</code>\n<i>Даже всё вместе:</i> <code>10.11, 14.11-19.11</code>";
    }

    static String chooseShiftText(String displayDate, String excelName) {
        return "⚙️ Устанавливаем смену на <b>" + displayDate + "</b> для <b>" + Html.esc(excelName) + "</b>.\n\nВыберите тип смены из кнопок:";
    }

    static InlineKeyboardMarkup shiftTypeKeyboard() {
        return Keyboards.of(
                row(btn("В (Выходной)", "ED_SCH_S:В"), btn("О (Отпуск)", "ED_SCH_S:О"), btn("Д (Дежурство)", "ED_SCH_S:Д")),
                row(btn("Б (Больничный)", "ED_SCH_S:Б"), btn("А (За свой счет)", "ED_SCH_S:А"), btn("Г (Военкомат)", "ED_SCH_S:Г")),
                row(btn("П (Другое подразделение)", "ED_SCH_S:П")),
                row(btn("08:30 - 17:30", "ED_SCH_S:08:30-17:30"), btn("12:00 - 21:00", "ED_SCH_S:12:00-21:00")),
                row(btn("08:00 - 16:00 (Суббота)", "ED_SCH_S:08:00-16:00")),
                row(btn("❌ Отменить", Keyboards.CANCEL_PROMPT)));
    }

    static String shiftChangedNotice(String datesText, String shift) {
        return "⚠ <b>ВНИМАНИЕ!</b>\nАдминистратор изменил ваш график!\nДата(ы): <b>" + datesText + "</b>\nНовая смена/статус: <b>" + Html.esc(shift) + "</b>";
    }

    static String employeeAdded(String excelName) {
        return "✅ Сотрудник <b>" + Html.esc(excelName) + "</b> успешно добавлен!\n\nЕму проставлены выходные до конца месяца. Теперь вы можете зайти в «✏️ Изменить смену» и задать ему рабочие дни.";
    }

    static String employeeExists(String excelName) {
        return "⚠️ Сотрудник <b>" + Html.esc(excelName) + "</b> уже числится в графике на этот месяц.";
    }

    // ------------------------------------------------------------------ больничный

    static String sickPrompt(String excelName) {
        return "🤒 <b>Оформление больничного</b>\n\nВы оформляете больничный для: <b>" + Html.esc(excelName) + "</b>\n\nНапишите дни болезни.\n\n<i>Можно писать без года:</i> <code>15.11</code>\n<i>Диапазоны:</i> <code>15.10-22.10</code>\n<i>Даже через запятую:</i> <code>10.11, 14.11-19.11</code>";
    }

    static String sickRecorded(String displayDate) {
        return "✅ Ваш больничный (" + displayDate + ") успешно зафиксирован! Выздоравливайте! 💊\n\n<i>Руководитель уведомлен, график обновлен.</i>";
    }

    static String sickReportedToAdmins(String excelName, String displayDate) {
        return "🚨 <b>ВНИМАНИЕ: Больничный!</b>\nСотрудник <b>" + Html.esc(excelName) + "</b> сообщил о болезни.\nПериод: <b>" + displayDate + "</b>\n<i>Его график автоматически обновлен (статус \"Б\").</i>";
    }

    static String sickNeedsApproval(String displayDate, long days) {
        return "⏳ Больничный (" + displayDate + ", " + days + " дн.) превышает 10 дней и требует подтверждения руководителя.\n\nВы получите уведомление, как только его согласуют.";
    }

    static String sickApprovalRequest(String excelName, String displayDate, long days) {
        return "🚨 <b>Запрос на больничный свыше 10 дней</b>\nСотрудник: <b>" + Html.esc(excelName) + "</b>\nПериод: <b>" + displayDate + "</b> (" + days + " дн.)\n\nПодтвердить изменение графика?";
    }

    static InlineKeyboardMarkup sickApprovalKeyboard(long requestId) {
        return Keyboards.of(row(btn("✅ Подтвердить", "SL_APPROVE:" + requestId), btn("❌ Отклонить", "SL_REJECT:" + requestId)));
    }

    static String sickApprovedNotice(String displayDate) {
        return "✅ Ваш больничный (" + displayDate + ") подтвержден руководителем и внесен в график.";
    }

    static String sickRejectedNotice(String displayDate) {
        return "❌ Ваш больничный (" + displayDate + ") отклонен руководителем. Обратитесь к нему за разъяснением.";
    }

    static String sickApprovedToManagers(String excelName, String displayDate) {
        return "🚨 <b>ВНИМАНИЕ: Больничный!</b>\nСотрудник <b>" + Html.esc(excelName) + "</b> уходит на больничный.\nПериод: <b>" + displayDate + "</b>\n<i>Администратор одобрил, график обновлен.</i>";
    }

    static String sickDecisionResult(boolean approved, String excelName, String displayDate) {
        return (approved ? "✅ Подтверждено: " : "❌ Отклонено: ") + Html.esc(excelName) + ", " + displayDate;
    }

    // ------------------------------------------------------------------ общее

    static String dayOfWeekRu(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case MONDAY -> "Пн";
            case TUESDAY -> "Вт";
            case WEDNESDAY -> "Ср";
            case THURSDAY -> "Чт";
            case FRIDAY -> "Пт";
            case SATURDAY -> "Сб";
            case SUNDAY -> "Вс";
        };
    }
}
