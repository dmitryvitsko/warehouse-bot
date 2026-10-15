package com.warehousebot.bot.feature.schedule;

import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.bot.core.TextStage;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.domain.SickLeaveRequest;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.service.SickLeaveService;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.warehousebot.bot.feature.schedule.ScheduleModule.SICK_LEAVE_ANCHOR;

/**
 * «🤒 Сообщить о больничном»: до 10 дней график обновляется сразу, дольше — после подтверждения администратором.
 */
public final class SickLeaveModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(SickLeaveModule.class);

    private final Messenger messenger;
    private final Ui ui;
    private final MainMenu menu;
    private final UserRepository users;
    private final SickLeaveService sickLeave;
    private final ScheduleModule scheduleModule;

    public SickLeaveModule(Messenger messenger, Ui ui, MainMenu menu, UserRepository users, SickLeaveService sickLeave,
                           ScheduleModule scheduleModule) {
        this.messenger = messenger;
        this.ui = ui;
        this.menu = menu;
        this.users = users;
        this.sickLeave = sickLeave;
        this.scheduleModule = scheduleModule;
    }

    @Override
    public void register(Routes r) {
        r.onCallback("SCHED_SICK").beforeBindCheck().handle(this::askDates);
        r.onCallbackPrefix("SL_APPROVE:").handle(ctx -> decide(ctx, true));
        r.onCallbackPrefix("SL_REJECT:").handle(ctx -> decide(ctx, false));
        r.onText(TextStage.SICK_LEAVE_INPUT, this::onDates);
    }

    private void askDates(CallbackContext ctx) {
        Optional<String> excelName = users.findExcelName(ctx.chatId());
        if (excelName.isEmpty()) {
            scheduleModule.showMenu(ctx.chatId(), ctx.role(), ScheduleView.SICK_NOT_BOUND, ctx.messageId());
            return;
        }
        ctx.session().put(SICK_LEAVE_ANCHOR, ctx.messageId());
        messenger.edit(ctx.chatId(), ctx.messageId(), ScheduleView.sickPrompt(excelName.get()), ScheduleView.backToMenuKeyboard());
    }

    private boolean onDates(TextContext ctx) {
        if (!ctx.session().has(SICK_LEAVE_ANCHOR)) return false;
        Integer anchorId = ctx.session().remove(SICK_LEAVE_ANCHOR);
        long chatId = ctx.chatId();

        Optional<String> bound = users.findExcelName(chatId);
        if (bound.isEmpty()) {
            menu.show(chatId, ctx.role(), ScheduleView.SICK_INPUT_NOT_BOUND);
            return true;
        }
        String excelName = bound.get();

        // Больничный — всегда непрерывный период: от самой ранней до самой поздней введённой даты.
        Optional<List<LocalDate>> parsed = DateInputParser.parse(ctx.trimmed(), LocalDate.now());
        if (parsed.isEmpty()) {
            ctx.session().put(SICK_LEAVE_ANCHOR, anchorId);
            if (anchorId != null) messenger.edit(chatId, anchorId, ScheduleView.SICK_DATE_ERROR, Keyboards.cancelPrompt());
            return true;
        }
        List<LocalDate> dates = parsed.get();
        LocalDate from = dates.get(0);
        LocalDate to = dates.get(dates.size() - 1);

        SickLeaveService.Report report;
        try {
            report = sickLeave.report(chatId, excelName, from, to);
        } catch (DataAccessException e) {
            // Как и раньше: при любой ошибке остаёмся в режиме ввода дат и просим повторить.
            log.error("Не удалось сохранить больничный для {}", chatId, e);
            ctx.session().put(SICK_LEAVE_ANCHOR, anchorId);
            if (anchorId != null) messenger.edit(chatId, anchorId, ScheduleView.SICK_DATE_ERROR, Keyboards.cancelPrompt());
            return true;
        }
        String displayDate = report.days() == 1
                ? from.format(SickLeaveService.DATE)
                : "с " + from.format(SickLeaveService.DATE) + " по " + to.format(SickLeaveService.DATE);

        if (!report.needsApproval()) {
            scheduleModule.showMenu(chatId, ctx.role(), ScheduleView.sickRecorded(displayDate), anchorId);
            String adminMsg = ScheduleView.sickReportedToAdmins(excelName, displayDate);
            for (Long adminId : users.adminIds()) ui.notify(adminId, adminMsg);
            notifyManagers(adminMsg);
        } else {
            scheduleModule.showMenu(chatId, ctx.role(), ScheduleView.sickNeedsApproval(displayDate, report.days()), anchorId);
            String adminMsg = ScheduleView.sickApprovalRequest(excelName, displayDate, report.days());
            InlineKeyboardMarkup markup = ScheduleView.sickApprovalKeyboard(report.requestId());
            // Запрос ждёт решения — не должен пропасть при очистке чата администратора.
            for (Long adminId : users.adminIds()) messenger.sendPinned(adminId, adminMsg, markup);
        }
        return true;
    }

    /** Подтверждение/отклонение длинного больничного администратором. */
    private void decide(CallbackContext ctx, boolean approve) {
        if (!ctx.isAdmin()) return;
        String data = ctx.data();
        long requestId = Long.parseLong(data.substring(data.indexOf(":") + 1));

        Optional<SickLeaveService.Decision> decision = sickLeave.decide(requestId, approve);
        if (decision.isEmpty()) {
            ctx.answer(ScheduleView.REQUEST_ALREADY_PROCESSED, false);
            return;
        }
        SickLeaveRequest req = decision.get().request();
        String displayDate = "с " + req.startDate() + " по " + req.endDate();

        if (approve) {
            ui.notify(req.chatId(), ScheduleView.sickApprovedNotice(displayDate));
            notifyManagers(ScheduleView.sickApprovedToManagers(req.excelName(), displayDate));
        } else {
            ui.notify(req.chatId(), ScheduleView.sickRejectedNotice(displayDate));
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), ScheduleView.sickDecisionResult(approve, req.excelName(), displayDate),
                Keyboards.close());
        ctx.answer();
    }

    /** Ответственные за график (Козлов, Белевич), если они не администраторы. */
    private void notifyManagers(String html) {
        for (Long id : sickLeave.managerIds()) ui.notify(id, html);
    }
}
