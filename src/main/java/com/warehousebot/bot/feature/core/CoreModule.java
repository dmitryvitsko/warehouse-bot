package com.warehousebot.bot.feature.core;

import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.util.Html;

/** Общие кнопки и команды: /start, «❌ Закрыть», «❌ Отменить». */
public final class CoreModule implements BotModule {

    private final Messenger messenger;
    private final MainMenu menu;

    public CoreModule(Messenger messenger, MainMenu menu) {
        this.messenger = messenger;
        this.menu = menu;
    }

    @Override
    public void register(Routes r) {
        r.onCallback(Keyboards.GENERIC_CLOSE).beforeBindCheck().handle(this::close);
        r.onCallback(Keyboards.CANCEL_PROMPT).beforeBindCheck().handle(this::cancelPrompt);
        r.onCallback("IGNORE").beforeBindCheck().handle(CallbackContext::answer);
        r.onCommand("/start", this::start);
    }

    /** «❌ Закрыть»: убирает все окна бота из чата и сбрасывает незавершённые диалоги. */
    private void close(CallbackContext ctx) {
        ctx.session().resetDialogs();
        messenger.clearChatHistory(ctx.chatId());
    }

    /** «❌ Отменить» под подсказкой: сбрасывает диалог и удаляет подсказку. */
    private void cancelPrompt(CallbackContext ctx) {
        ctx.session().resetDialogs();
        messenger.delete(ctx.chatId(), ctx.messageId());
    }

    private void start(TextContext ctx) {
        messenger.clearChatHistory(ctx.chatId());
        String roleTitle = ctx.isAdmin() ? "Администратор (МОЛ)" : "Мастер ЦБР УЛКС №2 ЛКЦ";
        menu.show(ctx.chatId(), ctx.role(), "Привет, <b>" + Html.esc(ctx.firstName()) + "</b>! 👋\nВаша роль в системе: <b>" + roleTitle
                + "</b>.\n\nВыберите нужное действие на кнопках внизу экрана:");
    }
}
