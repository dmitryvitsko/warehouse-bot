package com.warehousebot.bot.feature.schedule;

import com.warehousebot.bot.common.AccessGate;
import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.bot.core.TextStage;
import com.warehousebot.bot.session.SessionKey;
import com.warehousebot.bot.session.UserSession;
import com.warehousebot.domain.Role;
import com.warehousebot.domain.ShiftDay;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.service.ScheduleService;
import com.warehousebot.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * «🗓 График работ»: свой график и график коллег, напарники, кто работает сегодня/завтра,
 * редактор смен и добавление сотрудника. Больничные — в {@link SickLeaveModule}.
 */
public final class ScheduleModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(ScheduleModule.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** Сотрудник, чей график редактируем, и окно редактора (ждём ввод дат). */
    record EditTarget(String excelName, Integer anchorId) {
    }

    /** Выбранные даты (ждём нажатия кнопки с типом смены). */
    record PendingEdit(String excelName, List<LocalDate> dates, Integer anchorId) {
    }

    /** Окно меню графиков, в котором ждём даты больничного. */
    static final SessionKey<Integer> SICK_LEAVE_ANCHOR = SessionKey.dialog("schedule.waitingSickLeaveDate");
    static final SessionKey<EditTarget> EDIT_USER = SessionKey.dialog("schedule.waitingScheduleEditUser");
    static final SessionKey<PendingEdit> EDIT_DATES = SessionKey.dialog("schedule.waitingScheduleEditDate");
    private static final SessionKey<Boolean> NEW_EMPLOYEE = SessionKey.dialog("schedule.waitingNewEmployeeName");

    private final Messenger messenger;
    private final Ui ui;
    private final MainMenu menu;
    private final UserRepository users;
    private final UserService userService;
    private final ScheduleService schedule;

    public ScheduleModule(Messenger messenger, Ui ui, MainMenu menu, UserRepository users, UserService userService,
                          ScheduleService schedule) {
        this.messenger = messenger;
        this.ui = ui;
        this.menu = menu;
        this.users = users;
        this.userService = userService;
        this.schedule = schedule;
    }

    @Override
    public void register(Routes r) {
        r.onCommand(MainMenu.SCHEDULE, ctx -> showMenu(ctx.chatId(), ctx.role(), ScheduleView.MENU_TITLE, null));

        // Доступно и мастеру, не выбравшему фамилию
        r.onCallback("SCHED_MAIN").beforeBindCheck().handle(this::backToMenu);
        r.onCallback("SCHED_MINE").beforeBindCheck().handle(this::showMine);
        r.onCallback("SCHED_WITH_ME").beforeBindCheck().handle(this::showPartners);
        r.onCallback("SCHED_TODAY").beforeBindCheck().handle(ctx ->
                showSection(ctx, () -> roster(LocalDate.now(), null)));
        r.onCallback("SCHED_TOMORROW").beforeBindCheck().handle(this::showTomorrow);
        r.onCallbackPrefix("SCHED_DAY_").beforeBindCheck().handle(this::showChosenDay);
        r.onCallback("SCHED_EDIT").beforeBindCheck().when(this::canEdit).handle(this::showEditor);
        r.onCallback("SCHED_ADD").beforeBindCheck().adminOnly().handle(this::askNewEmployee);

        r.onCallbackPrefix("MY_SCHED:").handle(this::showMyMonth);
        r.onCallbackPrefix("COL_SCHED:").handle(ctx ->
                showSchedule(ctx.chatId(), ctx.part(3), ctx.intPart(1), ctx.intPart(2), false, ctx.messageId()));
        r.onCallbackPrefix("VIEW_SCHED:").handle(ctx -> {
            LocalDate now = LocalDate.now();
            showSchedule(ctx.chatId(), ctx.payload("VIEW_SCHED:"), now.getYear(), now.getMonthValue(), false, ctx.messageId());
        });
        r.onCallback("SCHED_COL_LIST").handle(ctx -> messenger.edit(ctx.chatId(), ctx.messageId(),
                ScheduleView.COLLEAGUES_TITLE, ScheduleView.colleaguesKeyboard(users.allExcelNames())));
        r.onCallbackPrefix("ED_SCH_U:").when(this::canEdit).handle(this::chooseEmployeeToEdit);
        r.onCallbackPrefix("ED_SCH_S:").when(this::canEdit).handle(this::applyShift);

        r.onText(TextStage.NEW_EMPLOYEE_INPUT, this::onNewEmployeeName);
        r.onText(TextStage.SCHEDULE_EDIT_INPUT, this::onEditDates);
        r.onCancel(this::onTypedCancel);
    }

    private boolean canEdit(CallbackContext ctx) {
        return userService.canEditSchedule(ctx.chatId(), ctx.role());
    }

    // ------------------------------------------------------------------ меню

    /** Меню графиков: новым сообщением ({@code messageId == null}) или в том же окне. */
    void showMenu(long chatId, Role role, String html, Integer messageId) {
        messenger.sendOrEdit(chatId, messageId, html,
                ScheduleView.menuKeyboard(userService.canEditSchedule(chatId, role), role.isAdmin()));
    }

    private void backToMenu(CallbackContext ctx) {
        UserSession s = ctx.session();
        s.remove(SICK_LEAVE_ANCHOR);
        s.remove(EDIT_USER);
        s.remove(EDIT_DATES);
        showMenu(ctx.chatId(), ctx.role(), ScheduleView.MENU_TITLE, ctx.messageId());
    }

    /** «❌ Отменить» / «🔙 Назад», набранные во время диалога графиков, возвращают в меню графиков. */
    private boolean onTypedCancel(TextContext ctx, boolean explicitCancel) {
        UserSession s = ctx.session();
        if (!s.has(SICK_LEAVE_ANCHOR) && !s.has(EDIT_USER) && !s.has(EDIT_DATES)) return false;
        s.resetDialogs();
        showMenu(ctx.chatId(), ctx.role(), explicitCancel ? ScheduleView.CANCELLED : ScheduleView.BACK_TO_MENU, null);
        return true;
    }

    /** Текстовый раздел (сводка, напарники) в окне меню с кнопками «Назад» и «Закрыть». */
    private void showSection(CallbackContext ctx, Supplier<String> text) {
        messenger.edit(ctx.chatId(), ctx.messageId(), text.get(), ScheduleView.sectionKeyboard());
    }

    private void sendNameBinding(long chatId, List<String> names) {
        messenger.send(chatId, ScheduleView.BIND_NAME_PROMPT, AccessGate.bindNameKeyboard(names));
    }

    // ------------------------------------------------------------------ свой график и график коллег

    private void showMine(CallbackContext ctx) {
        Optional<String> excelName = users.findExcelName(ctx.chatId());
        if (excelName.isPresent()) {
            LocalDate now = LocalDate.now();
            showSchedule(ctx.chatId(), excelName.get(), now.getYear(), now.getMonthValue(), true, ctx.messageId());
            return;
        }
        List<String> names = users.availableExcelNames();
        if (names.isEmpty()) ui.closable(ctx.chatId(), ScheduleView.NOT_UPLOADED);
        else sendNameBinding(ctx.chatId(), names);
    }

    private void showMyMonth(CallbackContext ctx) {
        // Как и раньше: администратор без привязки увидит «график не найден».
        String excelName = users.findExcelName(ctx.chatId()).orElse(null);
        showSchedule(ctx.chatId(), excelName, ctx.intPart(1), ctx.intPart(2), true, ctx.messageId());
    }

    private void showSchedule(long chatId, String excelName, int year, int month, boolean mine, Integer messageId) {
        String text;
        try {
            List<ShiftDay> days = excelName == null ? List.of() : schedule.month(excelName, year, month);
            text = ScheduleView.scheduleText(String.valueOf(excelName), year, month, days);
        } catch (DataAccessException e) {
            log.error("Чтение графика", e);
            text = ScheduleView.SCHEDULE_DB_ERROR;
        }
        messenger.sendOrEdit(chatId, messageId, text, ScheduleView.scheduleKeyboard(excelName, year, month, mine));
    }

    // ------------------------------------------------------------------ напарники и сводки

    private void showPartners(CallbackContext ctx) {
        Optional<String> excelName = users.findExcelName(ctx.chatId());
        if (excelName.isPresent()) {
            showSection(ctx, () -> partners(excelName.get()));
            return;
        }
        List<String> names = users.availableExcelNames();
        if (names.isEmpty()) showSection(ctx, () -> ScheduleView.NOT_BOUND_PROFILE);
        else sendNameBinding(ctx.chatId(), names);
    }

    private String partners(String excelName) {
        try {
            return ScheduleView.partnersText(schedule.partnerShifts(excelName, LocalDate.now()));
        } catch (DataAccessException e) {
            log.error("Поиск напарников", e);
            return ScheduleView.PARTNERS_DB_ERROR;
        }
    }

    /** Сводка на дату; {@code dayLabel == null} — «на сегодня». */
    private String roster(LocalDate date, String dayLabel) {
        try {
            if (dayLabel == null) {
                return ScheduleView.rosterText(ScheduleView.todayHeader(date),
                        "ℹ️ На сегодняшний день график еще не загружен.", date, schedule.day(date));
            }
            return ScheduleView.rosterText(ScheduleView.targetDayHeader(date, dayLabel),
                    "ℹ️ На этот день график еще не загружен.", date, schedule.day(date));
        } catch (DataAccessException e) {
            log.error("Сводка по графику", e);
            return ScheduleView.ROSTER_DB_ERROR;
        }
    }

    private String targetDayRoster(int daysOffset, String dayLabel) {
        return roster(LocalDate.now().plusDays(daysOffset), dayLabel);
    }

    /** В пятницу спрашиваем «суббота или понедельник», в выходные показываем понедельник. */
    private void showTomorrow(CallbackContext ctx) {
        DayOfWeek today = LocalDate.now().getDayOfWeek();
        switch (today) {
            case FRIDAY -> messenger.edit(ctx.chatId(), ctx.messageId(), ScheduleView.FRIDAY_QUESTION, ScheduleView.fridayKeyboard());
            case SATURDAY -> showSection(ctx, () -> targetDayRoster(2, "Понедельник"));
            case SUNDAY -> showSection(ctx, () -> targetDayRoster(1, "Понедельник"));
            default -> showSection(ctx, () -> targetDayRoster(1, "Завтра"));
        }
    }

    private void showChosenDay(CallbackContext ctx) {
        int offset = Integer.parseInt(ctx.data().split("_")[2]);
        String dayName = offset == 1 ? "Субботу" : "Понедельник";
        showSection(ctx, () -> targetDayRoster(offset, dayName));
    }

    // ------------------------------------------------------------------ новый сотрудник

    private void askNewEmployee(CallbackContext ctx) {
        ctx.session().put(NEW_EMPLOYEE, true);
        ui.cancelPrompt(ctx.chatId(), ScheduleView.NEW_EMPLOYEE_PROMPT);
    }

    private boolean onNewEmployeeName(TextContext ctx) {
        if (ctx.session().remove(NEW_EMPLOYEE) == null) return false;
        String name = ctx.trimmed();
        String result;
        try {
            result = schedule.addEmployee(name, LocalDate.now()) == ScheduleService.AddResult.ADDED
                    ? ScheduleView.employeeAdded(name) : ScheduleView.employeeExists(name);
        } catch (DataAccessException e) {
            log.error("Добавление сотрудника в график", e);
            result = ScheduleView.EMPLOYEE_DB_ERROR;
        }
        menu.show(ctx.chatId(), ctx.role(), result);
        return true;
    }

    // ------------------------------------------------------------------ редактор смен

    private void showEditor(CallbackContext ctx) {
        List<String> names = users.allExcelNames();
        if (names.isEmpty()) ui.closable(ctx.chatId(), ScheduleView.NOT_UPLOADED_SHORT);
        else messenger.edit(ctx.chatId(), ctx.messageId(), ScheduleView.EDITOR_TITLE, ScheduleView.editorKeyboard(names));
    }

    private void chooseEmployeeToEdit(CallbackContext ctx) {
        String excelName = ctx.payload("ED_SCH_U:");
        ctx.session().put(EDIT_USER, new EditTarget(excelName, ctx.messageId()));
        messenger.edit(ctx.chatId(), ctx.messageId(), ScheduleView.editUserPrompt(excelName), Keyboards.cancelPrompt());
    }

    private boolean onEditDates(TextContext ctx) {
        EditTarget target = ctx.session().remove(EDIT_USER);
        if (target == null) return false;
        Integer anchorId = target.anchorId();

        Optional<List<LocalDate>> parsed = DateInputParser.parse(ctx.trimmed(), LocalDate.now());
        if (parsed.isEmpty()) {
            ctx.session().put(EDIT_USER, target);
            if (anchorId != null) {
                messenger.edit(ctx.chatId(), anchorId, ScheduleView.EDIT_DATE_ERROR, Keyboards.cancelPrompt());
            }
            return true;
        }

        List<LocalDate> dates = parsed.get();
        String displayDate = dates.size() == 1
                ? dates.get(0).format(DATE) : "выбранные дни (" + dates.size() + " шт.)";
        ctx.session().put(EDIT_DATES, new PendingEdit(target.excelName(), dates, anchorId));
        if (anchorId != null) {
            messenger.edit(ctx.chatId(), anchorId, ScheduleView.chooseShiftText(displayDate, target.excelName()),
                    ScheduleView.shiftTypeKeyboard());
        }
        return true;
    }

    private void applyShift(CallbackContext ctx) {
        String payload = ctx.payload("ED_SCH_S:");
        PendingEdit edit = ctx.session().remove(EDIT_DATES);
        if (edit == null) {
            menu.show(ctx.chatId(), ctx.role(), ScheduleView.EDIT_SESSION_ERROR);
            return;
        }
        int anchorId = edit.anchorId() != null ? edit.anchorId() : ctx.messageId();

        String statusCode = "";
        String start = "";
        String end = "";
        if (ScheduleService.STATUS_CODES.contains(payload)) {
            statusCode = payload;
        } else {
            String[] times = payload.split("-");
            start = times[0];
            end = times[1];
        }

        try {
            schedule.setShifts(edit.excelName(), edit.dates(), statusCode, start, end);
        } catch (DataAccessException e) {
            log.error("Изменение графика", e);
            menu.show(ctx.chatId(), ctx.role(), ScheduleView.EDIT_SAVE_ERROR);
            return;
        }

        // Окно графика показывает месяц первой изменённой даты.
        LocalDate first = edit.dates().get(0);
        showSchedule(ctx.chatId(), edit.excelName(), first.getYear(), first.getMonthValue(), false, anchorId);

        List<String> dateStrings = new ArrayList<>();
        for (LocalDate d : edit.dates()) dateStrings.add(d.format(DATE));
        String datesText = dateStrings.size() > 2 ? dateStrings.size() + " дн." : String.join(",", dateStrings);
        String shift = statusCode.isEmpty() ? payload : statusCode;
        users.findUserIdByExcelName(edit.excelName())
                .ifPresent(id -> ui.notify(id, ScheduleView.shiftChangedNotice(datesText, shift)));

        ctx.answer(ScheduleView.EDIT_SAVED, false);
    }
}
