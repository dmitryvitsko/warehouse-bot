package com.warehousebot.bot.feature.tools;

import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Handlers.CallbackHandler;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.bot.core.TextStage;
import com.warehousebot.bot.session.SessionKey;
import com.warehousebot.domain.Role;
import com.warehousebot.domain.Tool;
import com.warehousebot.domain.ToolGroup;
import com.warehousebot.domain.ToolHolder;
import com.warehousebot.domain.UserRef;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.service.StockFacade;
import com.warehousebot.service.ToolService;
import com.warehousebot.service.ToolService.FastAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * «🛠 Управление инструментом» (панель МОЛ), быстрые команды /give_ и /take_
 * и поиск инструмента по произвольному тексту администратора.
 */
public final class ToolsModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(ToolsModule.class);
    private static final int ADD_MANUAL_TOOL_SENTINEL = -1;

    /** Единица инструмента, для которой администратор вводит причину списания. */
    private static final SessionKey<Integer> WAITING_WRITE_OFF_REASON = SessionKey.dialog("tools.waitingWriteOffReason");

    private final Messenger messenger;
    private final Ui ui;
    private final MainMenu menu;
    private final UserRepository users;
    private final ToolService tools;
    private final StockFacade stock;

    public ToolsModule(Messenger messenger, Ui ui, MainMenu menu, UserRepository users, ToolService tools, StockFacade stock) {
        this.messenger = messenger;
        this.ui = ui;
        this.menu = menu;
        this.users = users;
        this.tools = tools;
        this.stock = stock;
    }

    @Override
    public void register(Routes r) {
        r.onCommand(MainMenu.ADMIN_TOOLS, ctx -> {
            if (ctx.isAdmin()) sendPanel(ctx.chatId(), null);
        });

        // Панель: раньше эти кнопки не проверяли роль — теперь только для администратора.
        panelButton(r, "tool_menu_main", ctx -> {
            ctx.session().remove(WAITING_WRITE_OFF_REASON);
            editPanel(ctx, null);
        });
        panelButton(r, "tool_menu_close", ctx -> messenger.delete(ctx.chatId(), ctx.messageId()));
        panelButton(r, "tool_menu_audit", this::showAudit);
        panelButton(r, "tool_menu_archive", this::showArchive);
        panelButton(r, "tool_menu_notify", this::notifyAudit);
        panelButton(r, "tool_menu_audit_users", ctx -> showHoldersSummary(ctx, null));
        panelButton(r, "tool_menu_give", this::showGroupsForAssignment);
        panelButton(r, "tool_menu_return", this::showHoldersForReturn);
        panelButton(r, "tool_menu_restore", this::showWrittenOffForRestore);
        panelButton(r, "tool_menu_writeoff", ctx -> messenger.edit(ctx.chatId(), ctx.messageId(),
                "🗑 <b>Где сейчас находится инструмент, который нужно списать?</b>", ToolsView.writeOffLocationKeyboard()));
        panelButton(r, "tool_menu_transfer", ctx -> messenger.edit(ctx.chatId(), ctx.messageId(),
                "📦 <b>Передача в другое подразделение (удаление из базы)</b>\nГде сейчас числится инструмент?",
                ToolsView.transferLocationKeyboard()));
        panelButton(r, "tool_menu_add_manual", ctx -> {
            ctx.session().put(WAITING_WRITE_OFF_REASON, ADD_MANUAL_TOOL_SENTINEL);
            messenger.edit(ctx.chatId(), ctx.messageId(), ToolsView.ADD_MANUAL_PROMPT, ToolsView.writeOffReasonKeyboard());
        });

        adminPrefix(r, "T_TR_LOC:", ctx -> {
            if (ctx.part(1).equals("STOCK")) showGroupsForTransfer(ctx);
            else showHoldersForTransfer(ctx);
        });
        adminPrefix(r, "T_TR_U:", ctx -> showUserTools(ctx, ctx.longPart(1), "T_TR_ASK:", "T_TR_LOC:ASSIGNED",
                "📦 <b>Какой инструмент передаем (удаляем из базы)?</b>"));
        adminPrefix(r, "T_TR_G:", this::showStockUnitsForTransfer);
        adminPrefix(r, "T_TR_ASK:", this::askTransferConfirm);
        adminPrefix(r, "T_TR_YES:", this::confirmTransferDelete);

        adminPrefix(r, "T_SUMM_U:", ctx -> showUserSummary(ctx, ctx.longPart(1), null));
        adminPrefix(r, "T_SUMM_RET:", ctx -> {
            String res = returnText(ctx.intPart(1));
            showUserSummary(ctx, ctx.longPart(2), res);
        });
        adminPrefix(r, "T_SUMM_RALL:", ctx -> {
            long userId = ctx.longPart(1);
            showHoldersSummary(ctx, safely(() -> ToolsView.returnedAll(tools.returnAll(userId)), ToolsView.ERR_RETURN_ALL));
        });
        adminPrefix(r, "T_SEL_N:", this::showUsersForAssignment);
        adminPrefix(r, "T_ASS_U:", this::assign);
        adminPrefix(r, "T_RET_U:", ctx -> showUserTools(ctx, ctx.longPart(1), "T_RET_T:", "tool_menu_return",
                "↩️ <b>Какой инструмент возвращаем?</b>"));
        adminPrefix(r, "T_RET_T:", ctx -> editPanel(ctx, returnText(ctx.intPart(1))));
        adminPrefix(r, "T_WO_LOC:", ctx -> {
            if (ctx.part(1).equals("STOCK")) showGroupsForWriteOff(ctx);
            else showHoldersForWriteOff(ctx);
        });
        adminPrefix(r, "T_WO_U:", ctx -> showUserTools(ctx, ctx.longPart(1), "T_WO_DO:", "T_WO_LOC:ASSIGNED",
                "🗑 <b>Какой инструмент списываем?</b>"));
        adminPrefix(r, "T_WO_G:", this::showStockUnitsForWriteOff);
        adminPrefix(r, "T_WO_DO:", this::askWriteOffReason);
        adminPrefix(r, "T_RES_DO:", ctx -> {
            int toolId = ctx.intPart(1);
            editPanel(ctx, safely(() -> tools.restore(toolId).map(ToolsView::restored).orElseGet(() -> ToolsView.unchanged(tools.status(toolId))),
                    ToolsView.ERR_RESTORE));
        });

        r.onCallbackPrefix("F_ASS:").adminOnly().handle(this::fastAssign);

        r.onText(TextStage.TOOL_TAKE_COMMAND, this::onTakeCommand);
        r.onText(TextStage.TOOL_GIVE_COMMAND, this::onGiveCommand);
        r.onText(TextStage.TOOL_WRITE_OFF_REASON_INPUT, this::onWriteOffReason);
        r.onText(TextStage.FALLBACK, this::onSearch);
    }

    private static void panelButton(Routes r, String data, CallbackHandler handler) {
        r.onCallback(data).beforeBindCheck().adminOnly().handle(handler);
    }

    private static void adminPrefix(Routes r, String prefix, CallbackHandler handler) {
        r.onCallbackPrefix(prefix).beforeBindCheck().adminOnly().handle(handler);
    }

    /** Текст результата или прежний текст ошибки, если база недоступна. */
    private static String safely(Supplier<String> action, String errorText) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            log.error("Ошибка БД (инструмент)", e);
            return errorText;
        }
    }

    // ------------------------------------------------------------------ панель

    private void sendPanel(long chatId, String alert) {
        messenger.send(chatId, ToolsView.withAlert(alert, ToolsView.PANEL), ToolsView.panelKeyboard());
    }

    private void editPanel(CallbackContext ctx, String alert) {
        messenger.edit(ctx.chatId(), ctx.messageId(), ToolsView.withAlert(alert, ToolsView.PANEL), ToolsView.panelKeyboard());
    }

    /** Список пуст: окно удаляется, сообщение уходит с главным меню, панель открывается заново. */
    private void restartPanel(CallbackContext ctx, String text) {
        messenger.delete(ctx.chatId(), ctx.messageId());
        menu.show(ctx.chatId(), Role.ADMIN, text);
        sendPanel(ctx.chatId(), null);
    }

    private void showAudit(CallbackContext ctx) {
        messenger.delete(ctx.chatId(), ctx.messageId());
        menu.show(ctx.chatId(), ctx.role(), safely(() -> ToolsView.audit(tools.auditPositions()), ToolsView.ERR_AUDIT));
        sendPanel(ctx.chatId(), null);
    }

    private void showArchive(CallbackContext ctx) {
        messenger.delete(ctx.chatId(), ctx.messageId());
        menu.show(ctx.chatId(), ctx.role(), safely(() -> ToolsView.archive(tools.archive()), ToolsView.ERR_AUDIT));
        sendPanel(ctx.chatId(), null);
    }

    /** «📢 Рассылка: Аудит»: напоминание всем, за кем числятся материалы. */
    private void notifyAudit(CallbackContext ctx) {
        messenger.edit(ctx.chatId(), ctx.messageId(), "⏳ Начинаю рассылку уведомлений...", ToolsView.backToPanel());
        int successCount = 0;
        List<Long> workers;
        try {
            workers = stock.usersWithBalances();
        } catch (RuntimeException e) {
            log.error("Не удалось получить список сотрудников с подотчётом", e);
            workers = List.of();
        }
        for (Long workerId : workers) {
            try {
                if (messenger.send(workerId, ToolsView.AUDIT_NOTICE + stock.balanceText(workerId)) != null) successCount++;
            } catch (RuntimeException e) {
                // Сбой по одному сотруднику не должен останавливать рассылку остальным.
                log.warn("Не удалось отправить напоминание об аудите {}", workerId, e);
            }
        }
        messenger.edit(ctx.chatId(), ctx.messageId(),
                "✅ Уведомления об аудите успешно доставлены <b>" + successCount + "</b> сотрудникам!", ToolsView.backToPanel());
    }

    // ------------------------------------------------------------------ сводка по людям

    private void showHoldersSummary(CallbackContext ctx, String alert) {
        List<ToolHolder> holders = tools.holdersWithCounts();
        if (holders.isEmpty()) {
            editPanel(ctx, ToolsView.withAlert(alert, "ℹ️ Инструмента на руках нет."));
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(),
                ToolsView.withAlert(alert, "👤 <b>Сводка по людям</b>\nВыберите сотрудника:"), ToolsView.holdersSummaryKeyboard(holders));
    }

    private void showUserSummary(CallbackContext ctx, long userId, String alert) {
        List<Tool> list = tools.assignedTo(userId);
        String userName = users.displayName(userId);
        if (list.isEmpty()) {
            showHoldersSummary(ctx, ToolsView.withAlert(alert, ToolsView.userSummaryEmpty(userName)));
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(),
                ToolsView.withAlert(alert, ToolsView.userSummaryTitle(userName)), ToolsView.userSummaryKeyboard(list, userId));
    }

    // ------------------------------------------------------------------ выдача

    private void showGroupsForAssignment(CallbackContext ctx) {
        List<ToolGroup> groups = tools.inStockGroups();
        if (groups.isEmpty()) {
            restartPanel(ctx, "📦 На складе нет свободного инструмента.");
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), "🤝 <b>Шаг 1 из 2: Выберите инструмент:</b>", ToolsView.groupsForAssignment(groups));
    }

    private void showUsersForAssignment(CallbackContext ctx) {
        int toolId = ctx.intPart(1);
        messenger.edit(ctx.chatId(), ctx.messageId(),
                ToolsView.assignStep2(tools.find(toolId).orElse(null)), ToolsView.usersForAssignment(users.activeUsers(), toolId));
    }

    private void assign(CallbackContext ctx) {
        int toolId = ctx.intPart(1);
        long targetUserId = ctx.longPart(2);
        String result = safely(() -> tools.assign(toolId, targetUserId).map(ToolsView::assigned).orElse(ToolsView.ALREADY_TAKEN),
                ToolsView.ERR_ASSIGN);
        editPanel(ctx, result);
        if (result.startsWith("✅") && targetUserId != ctx.chatId()) ui.notify(targetUserId, ToolsView.ASSIGNED_NOTICE);
    }

    /** F_ASS:firstId:userId — выдача любой свободной единицы группы из окна /give_. */
    private void fastAssign(CallbackContext ctx) {
        int firstId = ctx.intPart(1);
        long userId = ctx.longPart(2);
        FastAssignment result;
        try {
            result = tools.assignAnyFromGroup(firstId, userId);
        } catch (DataAccessException e) {
            log.error("Ошибка БД при быстрой выдаче инструмента", e);
            ctx.answer(ToolsView.ERR_FAST_ASSIGN, false);
            return;
        }
        switch (result) {
            case FastAssignment.GroupNotFound g -> ctx.answer("Группа инструментов не найдена.", false);
            case FastAssignment.OutOfStock o -> ctx.answer("❌ Нет в наличии: " + o.toolName(), false);
            case FastAssignment.Assigned a -> {
                ctx.answer("✅ Выдано: " + a.userName(), false);
                ui.notify(userId, ToolsView.fastAssignedNotice(a.tool()));
            }
        }
    }

    // ------------------------------------------------------------------ возврат

    private String returnText(int toolId) {
        return safely(() -> tools.returnToStock(toolId).map(ToolsView::returned).orElseGet(() -> ToolsView.unchanged(tools.status(toolId))), ToolsView.ERR_RETURN);
    }

    private void showHoldersForReturn(CallbackContext ctx) {
        List<UserRef> holders = tools.holders();
        if (holders.isEmpty()) {
            restartPanel(ctx, "ℹ️ Нет инструмента на руках.");
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), "↩️ <b>У кого забираем инструмент?</b>",
                ToolsView.holdersList(holders, "T_RET_U:", "tool_menu_main"));
    }

    /** Инструмент сотрудника для возврата или списания. */
    private void showUserTools(CallbackContext ctx, long userId, String prefix, String backData, String title) {
        List<Tool> list = tools.assignedTo(userId);
        if (list.isEmpty()) {
            restartPanel(ctx, "У сотрудника нет инструмента.");
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), title, ToolsView.toolList(list, "🪛", prefix, backData));
    }

    // ------------------------------------------------------------------ списание и восстановление

    private void showHoldersForWriteOff(CallbackContext ctx) {
        List<UserRef> holders = tools.holders();
        if (holders.isEmpty()) {
            restartPanel(ctx, "ℹ️ Нет инструмента на руках.");
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), "🗑 <b>У кого списываем инструмент?</b>",
                ToolsView.holdersList(holders, "T_WO_U:", "tool_menu_writeoff"));
    }

    private void showGroupsForWriteOff(CallbackContext ctx) {
        List<ToolGroup> groups = tools.inStockGroups();
        if (groups.isEmpty()) {
            restartPanel(ctx, "📦 На складе нет инструмента.");
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), "🗑 <b>Выберите категорию на складе:</b>", ToolsView.groupsForWriteOff(groups));
    }

    private void showHoldersForTransfer(CallbackContext ctx) {
        List<UserRef> holders = tools.holders();
        if (holders.isEmpty()) {
            restartPanel(ctx, "ℹ️ Нет инструмента на руках.");
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), "📦 <b>У кого забираем инструмент для передачи?</b>",
                ToolsView.holdersList(holders, "T_TR_U:", "tool_menu_transfer"));
    }

    private void showGroupsForTransfer(CallbackContext ctx) {
        List<ToolGroup> groups = tools.inStockGroups();
        if (groups.isEmpty()) {
            restartPanel(ctx, "📦 На складе нет инструмента.");
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), "📦 <b>Выберите категорию на складе:</b>", ToolsView.groupsForTransfer(groups));
    }

    private void showStockUnitsForTransfer(CallbackContext ctx) {
        List<Tool> list = tools.inStockOfGroup(ctx.intPart(1));
        messenger.edit(ctx.chatId(), ctx.messageId(), "📦 <b>Выберите единицу для удаления из базы:</b>",
                ToolsView.toolList(list, "🪛", "T_TR_ASK:", "T_TR_LOC:STOCK"));
    }

    private void askTransferConfirm(CallbackContext ctx) {
        int toolId = ctx.intPart(1);
        messenger.edit(ctx.chatId(), ctx.messageId(),
                ToolsView.confirmTransferText(tools.find(toolId).orElse(null)),
                ToolsView.confirmTransferKeyboard(toolId));
    }

    private void confirmTransferDelete(CallbackContext ctx) {
        int toolId = ctx.intPart(1);
        String res = safely(() -> tools.deletePermanently(toolId)
                        .map(ToolsView::transferredOut)
                        .orElse(ToolsView.NOT_FOUND),
                ToolsView.ERR_DELETE);
        editPanel(ctx, res);
    }

    private void showStockUnitsForWriteOff(CallbackContext ctx) {
        List<Tool> list = tools.inStockOfGroup(ctx.intPart(1));
        messenger.edit(ctx.chatId(), ctx.messageId(), "🗑 <b>Выберите единицу для списания:</b>",
                ToolsView.toolList(list, "🪛", "T_WO_DO:", "T_WO_LOC:STOCK"));
    }

    private void askWriteOffReason(CallbackContext ctx) {
        int toolId = ctx.intPart(1);
        ctx.session().put(WAITING_WRITE_OFF_REASON, toolId);
        messenger.edit(ctx.chatId(), ctx.messageId(),
                ToolsView.writeOffReasonPrompt(tools.find(toolId).orElse(null)), ToolsView.writeOffReasonKeyboard());
    }

    private boolean onWriteOffReason(TextContext ctx) {
        Integer toolId = ctx.session().remove(WAITING_WRITE_OFF_REASON);
        if (toolId == null) return false;

        // Если это режим ручного добавления нового инструмента
        if (toolId == ADD_MANUAL_TOOL_SENTINEL) {
            String res = tools.addToolsManual(ctx.trimmed());
            if (res.startsWith("❌")) {
                ctx.session().put(WAITING_WRITE_OFF_REASON, ADD_MANUAL_TOOL_SENTINEL);
                messenger.clearChatHistory(ctx.chatId());
                sendPanel(ctx.chatId(), res);
                return true;
            }
            messenger.clearChatHistory(ctx.chatId());
            sendPanel(ctx.chatId(), res);
            return true;
        }

        String reason = ctx.text();
        String res = safely(() -> tools.writeOff(toolId, reason).map(t -> ToolsView.writtenOff(t, reason)).orElseGet(() -> ToolsView.unchanged(tools.status(toolId))),
                ToolsView.ERR_WRITE_OFF);
        messenger.clearChatHistory(ctx.chatId());
        sendPanel(ctx.chatId(), res);
        return true;
    }

    private void showWrittenOffForRestore(CallbackContext ctx) {
        List<Tool> list = tools.writtenOff();
        if (list.isEmpty()) {
            restartPanel(ctx, "🗄 В архиве пусто.");
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), "♻️ <b>Какой инструмент восстановить?</b>",
                ToolsView.toolList(list, "♻", "T_RES_DO:", "tool_menu_main"));
    }

    // ------------------------------------------------------------------ команды и поиск

    /** /take_ID — вернуть единицу на склад. Некорректный номер молча игнорируется. */
    private boolean onTakeCommand(TextContext ctx) {
        if (!ctx.text().startsWith("/take_") || !ctx.isAdmin()) return false;
        int toolId;
        try {
            toolId = Integer.parseInt(ctx.text().replace("/take_", ""));
        } catch (NumberFormatException e) {
            return true;
        }
        menu.show(ctx.chatId(), ctx.role(), returnText(toolId));
        return true;
    }

    /** /give_ID — окно быстрой выдачи инструмента этой группы. Некорректный номер молча игнорируется. */
    private boolean onGiveCommand(TextContext ctx) {
        if (!ctx.text().startsWith("/give_") || !ctx.isAdmin()) return false;
        int firstId;
        try {
            firstId = Integer.parseInt(ctx.text().replace("/give_", ""));
        } catch (NumberFormatException e) {
            return true;
        }
        String toolName = tools.find(firstId).map(Tool::name).orElse(ToolsView.UNKNOWN_TOOL);
        messenger.send(ctx.chatId(), ToolsView.fastAssignPrompt(toolName), ToolsView.fastAssignKeyboard(users.activeUsers(), firstId));
        return true;
    }

    /** Любой другой текст администратора — поиск инструмента; мастеру — общая подсказка (ядро). */
    private boolean onSearch(TextContext ctx) {
        if (!ctx.isAdmin()) return false;
        String query = ctx.trimmed();
        String result = safely(() -> {
            String found = ToolsView.searchResults(query, tools.search(query));
            return found != null ? found : ToolsView.searchNotFound(ctx.text());
        }, ToolsView.ERR_SEARCH);
        menu.show(ctx.chatId(), ctx.role(), result);
        return true;
    }
}
