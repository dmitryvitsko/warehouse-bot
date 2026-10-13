package com.warehousebot.bot.feature.reference;

import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextStage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;

/** Статические справочники: неисправности оборудования и коды закрытия заявок. */
public final class ReferenceModule implements BotModule {

    private final Messenger messenger;
    private final Ui ui;

    public ReferenceModule(Messenger messenger, Ui ui) {
        this.messenger = messenger;
        this.ui = ui;
    }

    @Override
    public void register(Routes r) {
        r.onText(TextStage.ERRORS_MENU, ctx -> {
            if (!ctx.text().equals(MainMenu.ERRORS)) return false;
            messenger.send(ctx.chatId(), ReferenceTexts.ERRORS_MENU, errorsMenuKeyboard());
            return true;
        });
        r.onCallback("ERRORS_MAIN").beforeBindCheck().handle(ctx ->
                messenger.edit(ctx.chatId(), ctx.messageId(), ReferenceTexts.ERRORS_MENU, errorsMenuKeyboard()));
        r.onCallback("ERRORS_MODEMS").beforeBindCheck().handle(ctx -> showSection(ctx, ReferenceTexts.MODEM_ERRORS));
        r.onCallback("ERRORS_STB").beforeBindCheck().handle(ctx -> showSection(ctx, ReferenceTexts.STB_ERRORS));

        r.onCommand(MainMenu.CLOSING_CODES, ctx -> ui.closable(ctx.chatId(), ReferenceTexts.CLOSING_CODES));
    }

    private void showSection(CallbackContext ctx, String text) {
        messenger.edit(ctx.chatId(), ctx.messageId(), text,
                Keyboards.of(row(btn("🔙 Назад", "ERRORS_MAIN"), btn("❌ Закрыть", Keyboards.GENERIC_CLOSE))));
    }

    private static InlineKeyboardMarkup errorsMenuKeyboard() {
        return Keyboards.of(
                row(btn("🌐 Модемы", "ERRORS_MODEMS"), btn("📺 Приставки", "ERRORS_STB")),
                row(btn("❌ Закрыть", Keyboards.GENERIC_CLOSE)));
    }
}
