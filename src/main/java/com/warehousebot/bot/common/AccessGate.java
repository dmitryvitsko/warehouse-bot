package com.warehousebot.bot.common;

import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.domain.PendingReturn;
import com.warehousebot.domain.Role;
import com.warehousebot.domain.UserRef;
import com.warehousebot.repository.ReturnRequestRepository;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.service.UserService;
import com.warehousebot.util.Formats;
import com.warehousebot.util.Html;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;

/**
 * Проверки доступа, которые выполняются до бизнес-логики:
 * регистрация нового пользователя, привязка к фамилии из графика и «входящие» администратора.
 */
public final class AccessGate {

    private final Messenger messenger;
    private final UserService userService;
    private final UserRepository users;
    private final ReturnRequestRepository returnRequests;

    public AccessGate(Messenger messenger, UserService userService, UserRepository users,
                      ReturnRequestRepository returnRequests) {
        this.messenger = messenger;
        this.userService = userService;
        this.users = users;
        this.returnRequests = returnRequests;
    }

    /** Определяет роль; новому пользователю отправляет уведомление, а администраторам — запрос на доступ. */
    public Role checkRole(long chatId, String firstName) {
        UserService.Access access = userService.resolve(chatId, firstName);
        if (access.isNew()) {
            sendNewUserRequestToAdmins(chatId, firstName);
            messenger.send(chatId, "⏳ <b>Ваша заявка отправлена.</b>");
        }
        return access.role();
    }

    private void sendNewUserRequestToAdmins(long newUserId, String newUserName) {
        InlineKeyboardMarkup markup = Keyboards.of(row(
                btn("✅ Одобрить", "NEW_USER_APP:" + newUserId),
                btn("❌ Заблокировать", "NEW_USER_REJ:" + newUserId)));
        String text = String.format("👤 <b>Новый пользователь!</b>\nИмя: <b>%s</b>\nID: <code>%d</code>", Html.esc(newUserName), newUserId);
        for (Long adminId : users.adminIds()) {
            messenger.sendPinned(adminId, text, markup);
        }
    }

    /**
     * Мастер, не выбравший свою фамилию из графика, не может пользоваться ботом.
     * Возвращает {@code true}, если доступ заблокирован (меню выбора фамилии уже отправлено).
     */
    public boolean blockIfUnbound(long chatId, Role role) {
        if (role.isAdmin()) return false;
        if (users.findExcelName(chatId).isPresent()) return false;

        List<String> names = users.availableExcelNames();
        if (names.isEmpty()) {
            messenger.send(chatId, "⚠ <b>Внимание!</b>\nВам необходимо идентифицировать себя, но администратор еще не загрузил график с новыми фамилиями. Пожалуйста, обратитесь к нему напрямую.");
        } else {
            messenger.send(chatId, "⚠️ <b>ОГРАНИЧЕНИЕ ДОСТУПА!</b>\n\nОстальной функционал бота временно заблокирован.\n👇 <b>Пожалуйста, выберите СВОЮ настоящую фамилию из списка ниже, чтобы продолжить работу:</b>",
                    bindNameKeyboard(names));
        }
        return true;
    }

    /** Кнопки выбора фамилии (BIND_NAME). */
    public static InlineKeyboardMarkup bindNameKeyboard(List<String> names) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String name : names) rows.add(row(btn(name, "BIND_NAME:" + name)));
        return Keyboards.of(rows);
    }

    /**
     * Пока у администратора есть необработанные заявки (новые пользователи, возвраты),
     * основное меню заблокировано, а заявки показываются заново.
     */
    public boolean blockAdminIfInbox(long chatId, Role role) {
        if (!role.isAdmin()) return false;
        List<UserRef> pendingUsers = users.pendingUsers();
        List<PendingReturn> pendingReturns = returnRequests.findPending();
        if (pendingUsers.isEmpty() && pendingReturns.isEmpty()) return false;

        messenger.send(chatId, "🚨 <b>ДЕЙСТВИЕ ПРИОСТАНОВЛЕНО!</b> 🚨\n\nУ вас висят необработанные заявки от сотрудников. Чтобы запросы не затерялись в истории чата, бот заблокировал основное меню.\n\n👇 <b>Пожалуйста, примите решение по заявкам ниже:</b>");

        for (UserRef u : pendingUsers) {
            InlineKeyboardMarkup markup = Keyboards.of(row(
                    btn("✅ Одобрить", "NEW_USER_APP:" + u.id()),
                    btn("❌ Заблокировать", "NEW_USER_REJ:" + u.id())));
            messenger.send(chatId, String.format("👤 <b>Новый пользователь ждет доступа!</b>\n\nИмя: <b>%s</b>\nID: <code>%d</code>\n\nРазрешить доступ?",
                    Html.esc(u.fullName()), u.id()), markup);
        }
        for (PendingReturn r : pendingReturns) {
            InlineKeyboardMarkup markup = Keyboards.of(row(
                    btn("✅ Принять на склад", "RET_APP:" + r.id()),
                    btn("❌ Отклонить", "RET_REJ:" + r.id())));
            String text = String.format("🔔 <b>Запрос на возврат на склад (№%d)</b>\n\n👤 Сотрудник: <b>%s</b>\n📦 Материал: <b>%s</b>\n🔢 Инв. №: <code>%s</code>\n↩️ Количество к возврату: <b>%s %s</b>",
                    r.id(), Html.esc(r.userName()), Html.esc(r.materialName()), Html.esc(r.materialCode()), Formats.qty(r.quantity()), r.unit());
            messenger.send(chatId, text, markup);
        }
        return true;
    }
}
