package com.warehousebot.bot.feature.inventory;

import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.service.StockFacade;
import com.warehousebot.service.ToolFacade;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;

/** «🧰 Мой подотчет»: материалы и инструмент, числящиеся за сотрудником. */
public final class InventoryModule implements BotModule {

    private static final String MENU_TEXT = "🧰 <b>Ваш подотчет:</b>\nВыберите, что хотите посмотреть:";

    private final Messenger messenger;
    private final StockFacade stock;
    private final ToolFacade tools;

    public InventoryModule(Messenger messenger, StockFacade stock, ToolFacade tools) {
        this.messenger = messenger;
        this.stock = stock;
        this.tools = tools;
    }

    @Override
    public void register(Routes r) {
        r.onCommand(MainMenu.MY_INVENTORY, ctx -> messenger.send(ctx.chatId(), MENU_TEXT, menuKeyboard()));
        r.onCallback("INV_MAIN").handle(ctx -> messenger.edit(ctx.chatId(), ctx.messageId(), MENU_TEXT, menuKeyboard()));
        r.onCallback("INV_MATS").handle(ctx -> showSection(ctx.chatId(), ctx.messageId(), stock.balanceText(ctx.chatId())));
        r.onCallback("INV_TOOLS").handle(ctx -> showSection(ctx.chatId(), ctx.messageId(), tools.userToolsText(ctx.chatId())));
        r.onCallback("INV_CLOSE").handle(ctx -> messenger.delete(ctx.chatId(), ctx.messageId()));
    }

    private void showSection(long chatId, int messageId, String text) {
        messenger.edit(chatId, messageId, text, Keyboards.single("🔙 Назад к выбору", "INV_MAIN"));
    }

    private static InlineKeyboardMarkup menuKeyboard() {
        return Keyboards.of(
                row(btn("📦 Мои материалы", "INV_MATS"), btn("🪛 Мой инструмент", "INV_TOOLS")),
                row(btn("❌ Закрыть", "INV_CLOSE")));
    }
}
