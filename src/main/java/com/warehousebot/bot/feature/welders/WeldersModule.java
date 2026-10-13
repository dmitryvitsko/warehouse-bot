package com.warehousebot.bot.feature.welders;

import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.bot.core.TextStage;
import com.warehousebot.bot.feature.welders.WeldersView.Screen;
import com.warehousebot.domain.Role;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.service.UserService;
import com.warehousebot.service.WelderService;
import com.warehousebot.util.Html;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.time.LocalDate;
import java.util.Optional;

/** «⚡️ Сварочные аппараты»: взять/вернуть/передать коллеге, принудительные действия администратора, журнал. */
public final class WeldersModule implements BotModule {

    private final Messenger messenger;
    private final Ui ui;
    private final UserRepository users;
    private final UserService userService;
    private final WelderService welders;
    private final WelderLocks locks;

    public WeldersModule(Messenger messenger, Ui ui, UserRepository users, UserService userService,
                         WelderService welders, WelderLocks locks) {
        this.messenger = messenger;
        this.ui = ui;
        this.users = users;
        this.userService = userService;
        this.welders = welders;
        this.locks = locks;
    }

    @Override
    public void register(Routes r) {
        r.interceptCallbacks(this::interceptReceiver);
        r.onText(TextStage.WELDER_RECEIVER_LOCK, this::receiverLock);
        r.onText(TextStage.WELDER_RETURN_LOCK, this::returnLock);
        r.onCommand(MainMenu.WELDERS, ctx -> showMenu(ctx.chatId(), ctx.role(), null, null));

        r.onCallback("W_MAIN_MENU").handle(ctx -> showMenu(ctx.chatId(), ctx.role(), ctx.messageId(), null));
        r.onCallback("W_CLOSE").handle(ctx -> messenger.delete(ctx.chatId(), ctx.messageId()));
        r.onCallbackPrefix("W_TAKE:").handle(this::take);
        r.onCallbackPrefix("W_RET:").handle(this::returnToBase);
        r.onCallbackPrefix("W_TRANS_START:").handle(this::chooseReceiver);
        r.onCallbackPrefix("W_TRANS_SEL:").handle(this::startTransfer);
        r.onCallbackPrefix("W_TRANS_CANCEL:").handle(this::cancelTransfer);
        r.onCallbackPrefix("W_TRANS_ACC:").handle(this::acceptTransfer);
        r.onCallbackPrefix("W_TRANS_REJ:").handle(this::rejectTransfer);
        r.onCallback("W_KEEP_TOMORROW").handle(this::keepForTomorrow);
        r.onCallback("W_HISTORY").handle(ctx ->
                edit(ctx.chatId(), ctx.messageId(), WeldersView.history(welders.history())));

        r.onCallback("W_FORCE_TAKE_M").adminOnly().handle(this::showForceAssign);
        r.onCallbackPrefix("W_F_SEL:").adminOnly().handle(ctx -> {
            int welderId = ctx.intPart(1);
            edit(ctx.chatId(), ctx.messageId(),
                    WeldersView.forceAssignUsers(welders.nameOf(welderId), welderId, users.activeUsers()));
        });
        r.onCallbackPrefix("W_F_ASS:").adminOnly().handle(this::forceAssign);
        r.onCallback("W_FORCE_RET_M").adminOnly().handle(ctx ->
                edit(ctx.chatId(), ctx.messageId(), WeldersView.forceReturnWelders(welders.welders())));
        r.onCallbackPrefix("W_F_RET:").adminOnly().handle(this::forceReturn);
    }

    // ------------------------------------------------------------------ панель

    /** Панель сварочников: новым сообщением ({@code messageId == null}) или на месте старого. */
    private void showMenu(long chatId, Role role, Integer messageId, String statusMsg) {
        Screen s = WeldersView.mainMenu(welders.welders(), chatId, role.isAdmin(), statusMsg);
        messenger.sendOrEdit(chatId, messageId, s.text(), s.markup());
    }

    private void edit(long chatId, int messageId, Screen s) {
        messenger.edit(chatId, messageId, s.text(), s.markup());
    }

    private void take(CallbackContext ctx) {
        int welderId = ctx.intPart(1);
        long chatId = ctx.chatId();
        if (!welders.take(welderId, chatId)) {
            showMenu(chatId, ctx.role(), ctx.messageId(), "❌ Ошибка: аппарат уже занят.");
            return;
        }
        long superAdmin = userService.superAdminId();
        if (chatId != superAdmin) {
            ui.notify(superAdmin, "ℹ️ <b>Сварочный аппарат взят!</b>\n👷‍♂ " + Html.esc(users.displayName(chatId))
                    + " взял аппарат <b>" + Html.esc(welders.nameOf(welderId)) + "</b>.");
        }
        showMenu(chatId, ctx.role(), ctx.messageId(), "Вы успешно взяли сварочный аппарат!");
    }

    private void returnToBase(CallbackContext ctx) {
        int welderId = ctx.intPart(1);
        if (welders.returnToBase(welderId, ctx.chatId())) {
            locks.releaseReturnLock(ctx.chatId());
            showMenu(ctx.chatId(), ctx.role(), ctx.messageId(), "Вы успешно вернули сварочный аппарат на базу!");
        } else if (!welders.isHeldBy(welderId, ctx.chatId())) {
            // Аппарат уже сдан или передан (например, предупреждение пришло после возврата) — снимаем блокировку.
            locks.releaseReturnLock(ctx.chatId());
            showMenu(ctx.chatId(), ctx.role(), ctx.messageId(), null);
        }
    }

    private void keepForTomorrow(CallbackContext ctx) {
        locks.releaseReturnLock(ctx.chatId());
        locks.keepForTomorrow(ctx.chatId(), LocalDate.now());
        messenger.delete(ctx.chatId(), ctx.messageId());
        showMenu(ctx.chatId(), ctx.role(), null, "🌙 Вы оставили сварочный аппарат за собой на завтра. Блокировка снята.");
    }

    // ------------------------------------------------------------------ передача коллеге

    private void chooseReceiver(CallbackContext ctx) {
        int welderId = ctx.intPart(1);
        edit(ctx.chatId(), ctx.messageId(),
                WeldersView.transferUsers(welders.nameOf(welderId), welderId, users.activeUsers(), ctx.chatId()));
    }

    private void startTransfer(CallbackContext ctx) {
        int welderId = ctx.intPart(1);
        long receiverId = ctx.longPart(2);
        long chatId = ctx.chatId();

        // Новая передача заменяет прежнюю незавершённую передачу этого же сотрудника.
        Long previousReceiver = locks.activeReceiver(chatId);
        if (previousReceiver != null && previousReceiver == receiverId) {
            // Повторный выбор того же коллеги (например, двойное нажатие): старый запрос просто заменяется новым.
            WelderLocks.Transfer previous = locks.withdrawPending(receiverId, chatId);
            if (previous != null && previous.receiverMsgId != null) messenger.delete(receiverId, previous.receiverMsgId);
            previousReceiver = null;
        }

        WelderLocks.Transfer t = new WelderLocks.Transfer(welderId, chatId, receiverId, ctx.messageId());
        if (!locks.offer(t)) {
            ctx.answer("❌ Этот сотрудник сейчас уже рассматривает другой запрос!", true);
            return;
        }
        // Прежнюю передачу другому коллеге снимаем только после того, как новая зарегистрирована.
        if (previousReceiver != null) withdraw(locks.withdrawPending(previousReceiver, chatId));

        String welderName = welders.nameOf(welderId);
        // 1. Получатель блокируется запросом, который не исчезает при очистке чата.
        Screen request = WeldersView.transferRequest(ctx.firstName(), welderName, welderId, chatId);
        Message sent = messenger.sendPinned(receiverId, request.text(), request.markup());
        if (sent == null) {
            // Запрос не доставлен — не оставляем коллегу заблокированным.
            locks.resolve(receiverId, chatId, welderId);
            ctx.answer("❌ Не удалось отправить запрос коллеге. Попробуйте ещё раз.", true);
            return;
        }
        t.receiverMsgId = sent.getMessageId();

        // 2. Окно передающего переходит в ожидание.
        edit(chatId, ctx.messageId(), WeldersView.transferWaiting(welderName, welderId, users.displayName(receiverId)));
    }

    private void cancelTransfer(CallbackContext ctx) {
        withdraw(locks.cancelBySender(ctx.chatId()));
        showMenu(ctx.chatId(), ctx.role(), ctx.messageId(), "🚫 Вы отменили передачу аппарата.");
    }

    /** Убирает у получателя запрос отменённой передачи и сообщает ему об отмене. */
    private void withdraw(WelderLocks.Transfer t) {
        if (t == null || t.receiverMsgId == null) return;
        messenger.delete(t.receiverId, t.receiverMsgId);
        ui.notify(t.receiverId, WeldersView.TRANSFER_CANCELLED);
    }

    private void acceptTransfer(CallbackContext ctx) {
        int welderId = ctx.intPart(1);
        long senderId = ctx.longPart(2);
        long chatId = ctx.chatId();

        WelderLocks.Transfer t = locks.resolve(chatId, senderId, welderId);
        if (t == null) {
            acceptWithoutPendingTransfer(ctx, welderId, senderId);
            return;
        }
        if (!welders.transfer(welderId, senderId, chatId)) {
            ctx.answer("❌ Ошибка базы данных! Сварочник не передан.", true);
            return;
        }
        locks.releaseReturnLock(senderId);

        messenger.edit(chatId, ctx.messageId(), "✅ <b>Вы успешно приняли сварочный аппарат!</b>", Keyboards.close());
        showMenu(senderId, userService.roleOf(senderId), t.senderMsgId, "✅ Коллега подтвердил прием сварочника!");
    }

    /**
     * «Принять» без запроса в памяти: бот перезапускался или кнопку нажали повторно.
     * Как и раньше, передача выполняется по данным кнопки, если аппарат всё ещё у передающего.
     */
    private void acceptWithoutPendingTransfer(CallbackContext ctx, int welderId, long senderId) {
        long chatId = ctx.chatId();
        boolean accepted = welders.isHeldBy(welderId, chatId)
                || (welders.isHeldBy(welderId, senderId) && welders.transfer(welderId, senderId, chatId));
        if (!accepted) {
            messenger.edit(chatId, ctx.messageId(), WeldersView.TRANSFER_CANCELLED, Keyboards.close());
            return;
        }
        locks.releaseReturnLock(senderId);
        messenger.edit(chatId, ctx.messageId(), "✅ <b>Вы успешно приняли сварочный аппарат!</b>", Keyboards.close());
    }

    private void rejectTransfer(CallbackContext ctx) {
        long senderId = ctx.longPart(2);
        long chatId = ctx.chatId();

        WelderLocks.Transfer t = locks.resolve(chatId, senderId, null);
        messenger.edit(chatId, ctx.messageId(), "❌ Вы отказались от приема сварочного аппарата.", Keyboards.close());
        if (t != null) {
            showMenu(senderId, userService.roleOf(senderId), t.senderMsgId, "❌ Коллега отказался принять сварочник.");
        }
    }

    /** Получатель передачи может нажимать только «Принять»/«Отказаться». */
    private boolean interceptReceiver(CallbackContext ctx) {
        if (!locks.hasPendingTransfer(ctx.chatId())) return false;
        String data = ctx.data();
        if (data.startsWith("W_TRANS_ACC:") || data.startsWith("W_TRANS_REJ:")) return false;
        ctx.answer("⚠️ Сначала примите или отклоните передачу сварочника!", true);
        return true;
    }

    private boolean receiverLock(TextContext ctx) {
        if (!locks.hasPendingTransfer(ctx.chatId())) return false;
        messenger.send(ctx.chatId(), WeldersView.receiverLocked());
        return true;
    }

    private boolean returnLock(TextContext ctx) {
        Integer welderId = locks.lockedWelder(ctx.chatId());
        if (welderId == null) return false;
        messenger.send(ctx.chatId(), WeldersView.returnLocked(welders.nameOf(welderId)));
        return true;
    }

    // ------------------------------------------------------------------ администратор

    private void showForceAssign(CallbackContext ctx) {
        edit(ctx.chatId(), ctx.messageId(), WeldersView.forceAssignWelders(welders.welders()));
    }

    private void forceAssign(CallbackContext ctx) {
        int welderId = ctx.intPart(1);
        long targetUserId = ctx.longPart(2);
        if (!welders.forceAssign(welderId, targetUserId, ctx.chatId())) return;
        String name = Html.esc(welders.nameOf(welderId));
        ui.notify(targetUserId, "🔔 <b>Администратор выдал вам сварочный аппарат!</b>\nЗа вами закреплен: <b>" + name + "</b>");
        showMenu(ctx.chatId(), ctx.role(), ctx.messageId(), "Аппарат " + name + " принудительно выдан.");
    }

    private void forceReturn(CallbackContext ctx) {
        int welderId = ctx.intPart(1);
        Optional<Long> holder = welders.forceReturn(welderId, ctx.chatId());
        if (holder.isEmpty()) {
            // Аппарат уже на базе — обновляем устаревший список.
            edit(ctx.chatId(), ctx.messageId(), WeldersView.forceReturnWelders(welders.welders()));
            return;
        }
        locks.releaseReturnLock(holder.get());
        showMenu(ctx.chatId(), ctx.role(), ctx.messageId(),
                "⚠️ Аппарат " + Html.esc(welders.nameOf(welderId)) + " принудительно списан на базу.");
    }
}
