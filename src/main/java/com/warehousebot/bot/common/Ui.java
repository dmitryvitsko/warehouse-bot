package com.warehousebot.bot.common;

import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.repository.UserRepository;

/** Типовые экраны и уведомления, общие для всех функций. */
public final class Ui {

    private final Messenger messenger;
    private final UserRepository users;

    public Ui(Messenger messenger, UserRepository users) {
        this.messenger = messenger;
        this.users = users;
    }

    public Messenger messenger() {
        return messenger;
    }

    /** Подсказка с кнопкой «❌ Отменить» — ожидание ввода текста. */
    public void cancelPrompt(long chatId, String html) {
        messenger.send(chatId, html, Keyboards.cancelPrompt());
    }

    /** Информационное сообщение с кнопкой «❌ Закрыть» (длинные тексты режутся на части). */
    public void closable(long chatId, String html) {
        messenger.sendLong(chatId, html, Keyboards.close());
    }

    /** Личное уведомление пользователю. */
    public void notify(long chatId, String html) {
        messenger.send(chatId, html);
    }

    /** Уведомление всем администраторам, кроме {@code excludeChatId}. */
    public void notifyAdmins(long excludeChatId, String html) {
        for (Long adminId : users.adminIds()) {
            if (adminId == excludeChatId) continue;
            messenger.send(adminId, html);
        }
    }
}
