package com.warehousebot.bot.feature.admin;

import com.warehousebot.bot.common.AccessGate;
import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.bot.core.TextStage;
import com.warehousebot.bot.session.SessionKey;
import com.warehousebot.service.AdminService;
import com.warehousebot.service.AdminService.BindOutcome;
import com.warehousebot.service.UserService;
import com.warehousebot.util.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Chat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * «👥 Пользователи»: панель управления персоналом и системой, доступ к ТМ, одобрение новых пользователей,
 * привязка к фамилии из графика, команды /fix_name, /unbind_, /ban_, /unban_, /fire, /fix, /clear_stock и рассылка.
 */
public final class AdminModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(AdminModule.class);

    /** Старая фамилия, для которой администратор вводит исправленную. */
    private static final SessionKey<String> WAITING_NAME_FIX = SessionKey.dialog("admin.waitingNameFix");

    private static final String WAIT_BROADCAST_TEXT = "WAIT_BROADCAST_TEXT";

    private final Messenger messenger;
    private final Ui ui;
    private final MainMenu menu;
    private final UserService userService;
    private final AdminService admin;

    public AdminModule(Messenger messenger, Ui ui, MainMenu menu, UserService userService, AdminService admin) {
        this.messenger = messenger;
        this.ui = ui;
        this.menu = menu;
        this.userService = userService;
        this.admin = admin;
    }

    @Override
    public void register(Routes r) {
        // Доступ к ТМ
        r.onCallback("ADM_TM_ACCESS_MAIN").beforeBindCheck().adminOnly().handle(ctx ->
                messenger.edit(ctx.chatId(), ctx.messageId(), AdminView.TM_ACCESS_TEXT, AdminView.tmAccessKeyboard()));
        r.onCallback("ADM_TM_HAS_ACCESS").beforeBindCheck().adminOnly().handle(ctx -> showTmUsers(ctx, true));
        r.onCallback("ADM_TM_NO_ACCESS").beforeBindCheck().adminOnly().handle(ctx -> showTmUsers(ctx, false));
        r.onCallbackPrefix("ADM_TM_TOGGLE:").beforeBindCheck().adminOnly().handle(this::toggleTmAccess);

        // Панель управления персоналом
        r.onCallback(AdminView.PANEL_MAIN).beforeBindCheck().adminOnly().handle(ctx ->
                messenger.edit(ctx.chatId(), ctx.messageId(), AdminView.PANEL_TEXT, AdminView.panelKeyboard()));
        r.onCallback("ADM_PANEL_LIST").beforeBindCheck().adminOnly().handle(ctx ->
                messenger.edit(ctx.chatId(), ctx.messageId(), AdminView.usersListText(admin.allUsers()),
                        AdminView.backTo("🔙 Назад к управлению", AdminView.PANEL_MAIN)));
        r.onCallback("ADM_PANEL_DELETE").beforeBindCheck().adminOnly().handle(ctx ->
                messenger.edit(ctx.chatId(), ctx.messageId(), AdminView.DELETE_MENU_TEXT,
                        AdminView.namesKeyboard(admin.allExcelNames(), "🗑 ", "ADM_DEL_CONFIRM:")));
        r.onCallbackPrefix("ADM_DEL_CONFIRM:").beforeBindCheck().adminOnly().handle(ctx -> {
            String name = ctx.payload("ADM_DEL_CONFIRM:");
            messenger.edit(ctx.chatId(), ctx.messageId(), AdminView.deleteConfirmText(name), AdminView.deleteConfirmKeyboard(name));
        });
        r.onCallbackPrefix("ADM_DEL_DO:").beforeBindCheck().adminOnly().handle(ctx ->
                messenger.edit(ctx.chatId(), ctx.messageId(), AdminView.removalText(admin.removeWorker(ctx.payload("ADM_DEL_DO:"))),
                        AdminView.backTo("🔙 К списку управления", AdminView.PANEL_MAIN)));
        r.onCallback("ADM_PANEL_RENAME").beforeBindCheck().adminOnly().handle(ctx ->
                messenger.edit(ctx.chatId(), ctx.messageId(), AdminView.RENAME_MENU_TEXT,
                        AdminView.namesKeyboard(admin.allExcelNames(), "✏️ ", "ADM_REN_SEL:")));
        r.onCallbackPrefix("ADM_REN_SEL:").beforeBindCheck().adminOnly().handle(this::selectRename);
        r.onCallback("ADM_PANEL_UNBIND").beforeBindCheck().adminOnly().handle(this::showUnbindMenu);
        r.onCallbackPrefix("ADM_UNB_DO:").beforeBindCheck().adminOnly().handle(ctx -> {
            admin.unbind(Long.parseLong(ctx.payload("ADM_UNB_DO:")), "Сотрудник");
            messenger.edit(ctx.chatId(), ctx.messageId(), "✅ Привязка сброшена. Пользователь должен будет заново выбрать фамилию при входе.",
                    AdminView.backTo("🔙 Назад", AdminView.PANEL_MAIN));
        });
        r.onCallback("ADM_PANEL_CLR_STOCK").beforeBindCheck().adminOnly().handle(ctx ->
                messenger.edit(ctx.chatId(), ctx.messageId(), AdminView.CLEAR_STOCK_CONFIRM_TEXT, AdminView.clearStockConfirmKeyboard()));
        r.onCallback("ADM_CLR_STOCK_YES").beforeBindCheck().adminOnly().handle(this::clearStockFromPanel);
        r.onCallback("ADM_PANEL_FIX_REQ").beforeBindCheck().adminOnly().handle(this::fixRequestsFromPanel);

        // Новые пользователи и привязка к фамилии
        r.onCallbackPrefix("NEW_USER_APP:").adminOnly().handle(this::approveUser);
        r.onCallbackPrefix("NEW_USER_REJ:").adminOnly().handle(this::rejectUser);
        r.onCallbackPrefix("BIND_NAME:").handle(this::bindName);

        // Текстовые шаги и команды
        r.onText(TextStage.NAME_FIX_INPUT, this::onNameFixInput);
        r.onText(TextStage.FIX_NAME_COMMAND, this::onFixNameCommand);
        r.onText(TextStage.UNBIND_COMMAND, this::onUnbindCommand);
        r.onText(TextStage.BROADCAST_INPUT, this::onBroadcastInput);
        r.onText(TextStage.BAN_COMMANDS, this::onBanCommand);
        r.onText(TextStage.FIRE_COMMAND, this::onFireCommand);

        r.onCommand("/fix", this::fixCommand);
        r.onCommand("/clear_stock", this::clearStockCommand);
        r.onCommand(MainMenu.ADMIN_BROADCAST, ctx -> {
            if (!ctx.isAdmin()) return;
            ctx.session().put(UploadsModule.FILE_WAIT_STATE, WAIT_BROADCAST_TEXT);
            ui.cancelPrompt(ctx.chatId(), "📢 <b>Режим массовой рассылки</b>\n\nВведите текст сообщения:");
        });
        r.onCommand(MainMenu.ADMIN_USERS, ctx -> {
            if (ctx.isAdmin()) messenger.send(ctx.chatId(), AdminView.PANEL_TEXT, AdminView.panelKeyboard());
        });
    }

    // ------------------------------------------------------------------ доступ к ТМ

    private void showTmUsers(CallbackContext ctx, boolean hasAccess) {
        messenger.edit(ctx.chatId(), ctx.messageId(), AdminView.tmUsersTitle(hasAccess),
                AdminView.tmUsersKeyboard(admin.usersByTmAccess(hasAccess), hasAccess));
    }

    private void toggleTmAccess(CallbackContext ctx) {
        long targetId = ctx.longPart(1);
        boolean grant = Boolean.parseBoolean(ctx.part(2));
        boolean viewingHasAccess = Boolean.parseBoolean(ctx.part(3));

        admin.setTmAccess(targetId, grant);
        if (grant) {
            ui.notify(targetId, "✅ <b>Вам предоставлен доступ к разделу «Мои заявки (ТМ)»!</b>\nТеперь кнопка появилась в вашем главном меню.");
            menu.show(targetId, userService.roleOf(targetId), "🔄 <b>Ваше меню обновлено:</b> добавлен доступ к разделу ТМ.");
        } else {
            ui.notify(targetId, "⚠️ <b>Доступ к разделу «Мои заявки (ТМ)» был отозван руководителем.</b>");
            menu.show(targetId, userService.roleOf(targetId), "🔄 <b>Ваше меню обновлено:</b> доступ к разделу ТМ был закрыт.");
        }
        showTmUsers(ctx, viewingHasAccess);
    }

    // ------------------------------------------------------------------ панель управления

    private void selectRename(CallbackContext ctx) {
        String oldName = ctx.payload("ADM_REN_SEL:");
        ctx.session().put(WAITING_NAME_FIX, oldName);
        messenger.delete(ctx.chatId(), ctx.messageId());
        ui.cancelPrompt(ctx.chatId(), AdminView.renameSelectedPrompt(oldName));
    }

    private void showUnbindMenu(CallbackContext ctx) {
        Map<String, Long> bound = new LinkedHashMap<>();
        for (String name : admin.allExcelNames()) {
            admin.userIdByExcelName(name).ifPresent(id -> bound.put(name, id));
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), AdminView.UNBIND_MENU_TEXT, AdminView.unbindKeyboard(bound));
    }

    private void clearStockFromPanel(CallbackContext ctx) {
        try {
            admin.clearWarehouse();
        } catch (RuntimeException e) {
            log.error("Ошибка очистки склада", e);
            messenger.edit(ctx.chatId(), ctx.messageId(), "❌ Ошибка очистки склада: " + Html.esc(String.valueOf(e.getMessage())),
                    AdminView.backTo("🔙 Назад к управлению", "tool_menu_main"));
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), "🧹 <b>База склада полностью очищена!</b>\nТеперь вы можете загрузить свежую ведомость.",
                AdminView.backTo("🔙 Назад", AdminView.PANEL_MAIN));
    }

    private void fixRequestsFromPanel(CallbackContext ctx) {
        try {
            admin.deletePendingReturns();
        } catch (RuntimeException e) {
            log.error("Ошибка сброса зависших заявок", e);
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), "✅ <b>Все зависшие заявки на возврат успешно очищены!</b>",
                AdminView.backTo("🔙 Назад", AdminView.PANEL_MAIN));
    }

    private void fixCommand(TextContext ctx) {
        if (!ctx.isAdmin()) return;
        try {
            admin.deletePendingReturns();
        } catch (RuntimeException e) {
            log.error("Ошибка /fix", e);
            return;
        }
        menu.show(ctx.chatId(), ctx.role(), "✅ Зависшие заявки очищены!");
    }

    private void clearStockCommand(TextContext ctx) {
        if (!ctx.isAdmin()) return;
        try {
            admin.clearWarehouse();
        } catch (RuntimeException e) {
            menu.show(ctx.chatId(), ctx.role(), "❌ Ошибка: возможно таблица называется иначе. Детали: " + Html.esc(String.valueOf(e.getMessage())));
            return;
        }
        menu.show(ctx.chatId(), ctx.role(), "🧹 <b>База склада полностью очищена!</b>\nТеперь вы можете загрузить новую оборотную ведомость.");
    }

    // ------------------------------------------------------------------ новые пользователи и привязка

    private void approveUser(CallbackContext ctx) {
        long targetId = ctx.longPart(1);
        if (!decideNewUser(ctx, targetId, true)) return;
        menu.show(ctx.chatId(), ctx.role(), "✅ Пользователь " + targetId + " одобрен и получил доступ.");
        ui.notify(targetId, "✅ <b>Администратор одобрил ваш доступ!</b>\nТеперь вы можете пользоваться ботом. Нажмите /start для обновления меню.");
    }

    private void rejectUser(CallbackContext ctx) {
        long targetId = ctx.longPart(1);
        if (!decideNewUser(ctx, targetId, false)) return;
        menu.show(ctx.chatId(), ctx.role(), "❌ Пользователь " + targetId + " заблокирован.");
    }

    /**
     * Применяет решение только к пользователю, который всё ещё ждёт одобрения, и убирает запрос из чата.
     * Старая кнопка не может заблокировать уже одобренного сотрудника.
     */
    private boolean decideNewUser(CallbackContext ctx, long targetId, boolean approve) {
        boolean applied = admin.decideNewUser(targetId, approve);
        messenger.delete(ctx.chatId(), ctx.messageId());
        if (!applied) ctx.answer("Запрос уже обработан или не найден.", true);
        return applied;
    }

    /**
     * Мастер выбрал свою фамилию. Если он уже привязан (старая кнопка) — ничего не делаем;
     * если фамилию успели занять — показываем выбор заново.
     */
    private void bindName(CallbackContext ctx) {
        String excelName = ctx.payload("BIND_NAME:");
        BindOutcome outcome = admin.bindExcelName(ctx.chatId(), excelName);
        switch (outcome) {
            case BOUND -> menu.show(ctx.chatId(), ctx.role(), "✅ Отлично! Ваш профиль успешно привязан к: <b>" + Html.esc(excelName)
                    + "</b>.\n\nТеперь вам полностью доступны все функции системы.");
            case NAME_TAKEN -> sendBindMenu(ctx.chatId(), AdminView.NEED_BIND_NO_NAMES, AdminView.NEED_BIND);
            case ALREADY_BOUND -> {
                // Повторное нажатие на старую кнопку: привязка уже есть, перезаписывать её нельзя.
            }
        }
    }

    /** Меню выбора фамилии (или предупреждение, если свободных фамилий нет). */
    private void sendBindMenu(long chatId, String noNamesText, String chooseText) {
        List<String> names = admin.availableExcelNames();
        if (names.isEmpty()) ui.notify(chatId, noNamesText);
        else messenger.send(chatId, chooseText, AccessGate.bindNameKeyboard(names));
    }

    // ------------------------------------------------------------------ текстовые шаги и команды

    private boolean onNameFixInput(TextContext ctx) {
        String oldName = ctx.session().remove(WAITING_NAME_FIX);
        if (oldName == null) return false;
        String newName = ctx.trimmed();
        if (newName.isEmpty()) {
            menu.show(ctx.chatId(), ctx.role(), "❌ Имя не может быть пустым. Действие отменено.");
            return true;
        }
        menu.show(ctx.chatId(), ctx.role(), AdminView.nameFixText(admin.fixNameTypo(oldName, newName)));
        return true;
    }

    private boolean onFixNameCommand(TextContext ctx) {
        if (!ctx.text().startsWith("/fix_name") || !ctx.isAdmin()) return false;
        String oldName = ctx.text().replace("/fix_name", "").trim();
        if (oldName.isEmpty()) {
            menu.show(ctx.chatId(), ctx.role(), "⚠️ <b>Использование команды:</b>\nНапишите <code>/fix_name Старое Имя В.З.</code> (обязательно с пробелом после команды).");
            return true;
        }
        ctx.session().put(WAITING_NAME_FIX, oldName);
        ui.cancelPrompt(ctx.chatId(), AdminView.renameCommandPrompt(oldName));
        return true;
    }

    private boolean onUnbindCommand(TextContext ctx) {
        if (!ctx.text().startsWith("/unbind_") || !ctx.isAdmin()) return false;
        Long targetId = parseId(ctx.text().replace("/unbind_", ""));
        if (targetId == null) return true;

        if (admin.unbind(targetId, telegramName(targetId))) {
            menu.show(ctx.chatId(), ctx.role(), "✅ Пользователь сброшен. Ему отправлено меню выбора ФИО!");
            sendBindMenu(targetId, AdminView.ADMIN_UNBIND_NO_NAMES, AdminView.ADMIN_UNBIND);
        } else {
            menu.show(ctx.chatId(), ctx.role(), "❌ Ошибка: пользователь не найден в базе данных.");
        }
        return true;
    }

    /** Имя пользователя из Telegram для пометки «[Отвязан] …». */
    private String telegramName(long userId) {
        try {
            Chat chat = messenger.getChat(userId);
            if (chat.getFirstName() != null) {
                String name = chat.getFirstName();
                if (chat.getLastName() != null) name += " " + chat.getLastName();
                return name;
            }
            if (chat.getUserName() != null) return chat.getUserName();
            return "Сотрудник";
        } catch (Exception e) {
            return "ID " + userId;
        }
    }

    private boolean onBroadcastInput(TextContext ctx) {
        if (!WAIT_BROADCAST_TEXT.equals(ctx.session().get(UploadsModule.FILE_WAIT_STATE))) return false;
        ctx.session().remove(UploadsModule.FILE_WAIT_STATE);
        menu.show(ctx.chatId(), ctx.role(), "⏳ Отправляю сообщение всем сотрудникам...");

        // Текст администратора не экранируется: разметка HTML в рассылке допустима намеренно.
        String broadcast = "📢 <b>ИНФОРМАЦИЯ ОТ РУКОВОДИТЕЛЯ:</b>\n\n" + ctx.text();
        int delivered = 0;
        for (Long userId : admin.allUserIds()) {
            if (userId == ctx.chatId()) continue;
            if (messenger.send(userId, broadcast) != null) delivered++;
        }
        menu.show(ctx.chatId(), ctx.role(), "✅ Рассылка успешно доставлена <b>" + delivered + "</b> сотрудникам!");
        return true;
    }

    private boolean onBanCommand(TextContext ctx) {
        if (!ctx.isAdmin()) return false;
        String text = ctx.text();
        boolean ban;
        String rest;
        if (text.startsWith("/ban_")) {
            ban = true;
            rest = text.replace("/ban_", "");
        } else if (text.startsWith("/unban_")) {
            ban = false;
            rest = text.replace("/unban_", "");
        } else {
            return false;
        }
        Long targetId = parseId(rest);
        if (targetId == null) return true;
        menu.show(ctx.chatId(), ctx.role(), AdminView.banText(admin.setBanned(targetId, ban), targetId, ban));
        return true;
    }

    private boolean onFireCommand(TextContext ctx) {
        if (!ctx.text().startsWith("/fire ") || !ctx.isAdmin()) return false;
        String name = ctx.text().replace("/fire ", "").trim();
        menu.show(ctx.chatId(), ctx.role(), AdminView.removalText(admin.removeWorker(name)));
        return true;
    }

    /** ID из команды; {@code null} — некорректный ввод (команда молча игнорируется). */
    private static Long parseId(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
