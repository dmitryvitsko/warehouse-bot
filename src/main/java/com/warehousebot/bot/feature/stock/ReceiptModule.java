package com.warehousebot.bot.feature.stock;

import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.bot.core.TextStage;
import com.warehousebot.bot.feature.stock.StockSessions.ReceiptCart;
import com.warehousebot.bot.feature.stock.StockSessions.Screen;
import com.warehousebot.bot.session.UserSession;
import com.warehousebot.domain.HeldMaterial;
import com.warehousebot.domain.ReceiptLine;
import com.warehousebot.domain.Role;
import com.warehousebot.domain.Tariff;
import com.warehousebot.service.ReceiptService;
import com.warehousebot.service.TariffService;
import com.warehousebot.util.Formats;

import java.util.List;
import java.util.Optional;

import static com.warehousebot.bot.feature.stock.StockSessions.RECEIPT_CART;

/** «🧾 Калькулятор квитанции», «📋 Тарифы услуг» и команда администратора «+услуга Название;Цена». */
public final class ReceiptModule implements BotModule {

    private static final String UPDATE_SERVICE_PREFIX = "+услуга ";

    private final Messenger messenger;
    private final Ui ui;
    private final MainMenu menu;
    private final ReceiptService receipts;
    private final TariffService tariffs;

    public ReceiptModule(Messenger messenger, Ui ui, MainMenu menu, ReceiptService receipts, TariffService tariffs) {
        this.messenger = messenger;
        this.ui = ui;
        this.menu = menu;
        this.receipts = receipts;
        this.tariffs = tariffs;
    }

    @Override
    public void register(Routes r) {
        r.onCommand(MainMenu.RECEIPT_CALCULATOR, ctx -> showCart(ctx.session(), ctx.chatId(), null));
        r.onCommand(MainMenu.TARIFFS, ctx -> ui.closable(ctx.chatId(), ReceiptView.tariffsText(tariffs.all())));

        r.onCallback("CART_MENU").handle(ctx -> showCart(ctx.session(), ctx.chatId(), ctx.messageId()));
        r.onCallback("CART_ADD_SRV").handle(ctx -> {
            Screen s = ReceiptView.serviceChoice(receipts.services());
            messenger.edit(ctx.chatId(), ctx.messageId(), s.html(), s.markup());
        });
        r.onCallback("CART_ADD_MAT").handle(this::chooseMaterial);
        r.onCallbackPrefix("CART_SEL_SRV:").handle(this::selectService);
        r.onCallbackPrefix("CART_SEL_MAT:").handle(this::selectMaterial);
        r.onCallback("CART_FINISH").handle(this::finish);
        r.onCallback("CART_CLEAR").handle(ctx -> {
            ctx.session().remove(RECEIPT_CART);
            messenger.edit(ctx.chatId(), ctx.messageId(), "🗑 Корзина очищена.", null);
        });

        r.onText(TextStage.RECEIPT_QTY_INPUT, this::onQuantityText);
        // Раньше остальных обработчиков FALLBACK (поиск инструмента): иначе «+услуга …» уйдёт в поиск.
        r.onText(TextStage.FALLBACK, ctx -> {
            if (!ctx.text().startsWith(UPDATE_SERVICE_PREFIX) || !ctx.isAdmin()) return false;
            updateServicePrice(ctx.chatId(), ctx.role(), ctx.text());
            return true;
        });
    }

    private void showCart(UserSession session, long chatId, Integer messageId) {
        ReceiptCart cart = session.get(RECEIPT_CART);
        Screen s = ReceiptView.cart(cart == null ? List.of() : cart.items);
        messenger.sendOrEdit(chatId, messageId, s.html(), s.markup());
    }

    private void chooseMaterial(CallbackContext ctx) {
        List<HeldMaterial> held = receipts.materials(ctx.chatId());
        if (held.isEmpty()) {
            ui.closable(ctx.chatId(), "🧰 У вас нет материалов в подотчете.");
            showCart(ctx.session(), ctx.chatId(), ctx.messageId());
            return;
        }
        Screen s = ReceiptView.materialChoice(held);
        messenger.edit(ctx.chatId(), ctx.messageId(), s.html(), s.markup());
    }

    private void selectService(CallbackContext ctx) {
        int serviceId = ctx.intPart(1);
        ReceiptCart cart = ctx.session().getOrCreate(RECEIPT_CART, ReceiptCart::new);
        cart.anchorMsgId = ctx.messageId();
        Optional<Tariff> tariff = receipts.service(serviceId);
        if (tariff.isEmpty()) return;
        if (tariff.get().single()) {
            cart.items.add(ReceiptService.line(tariff.get(), 1.0));
            showCart(ctx.session(), ctx.chatId(), ctx.messageId());
        } else {
            cart.waitingServiceId = serviceId;
            cart.waitingMaterialId = -1;
            messenger.edit(ctx.chatId(), ctx.messageId(), "✍ <b>Введите количество</b> для выбранной услуги (например: 1 или 2):",
                    ReceiptView.BACK_TO_CART);
        }
    }

    private void selectMaterial(CallbackContext ctx) {
        int materialId = ctx.intPart(1);
        ReceiptCart cart = ctx.session().getOrCreate(RECEIPT_CART, ReceiptCart::new);
        cart.anchorMsgId = ctx.messageId();
        cart.waitingMaterialId = materialId;
        cart.waitingServiceId = -1;
        messenger.edit(ctx.chatId(), ctx.messageId(), "✍️ <b>Введите количество</b> израсходованного материала (например: 15 или 0.02):",
                ReceiptView.BACK_TO_CART);
    }

    private void finish(CallbackContext ctx) {
        ReceiptCart cart = ctx.session().get(RECEIPT_CART);
        if (cart == null || cart.items.isEmpty()) {
            ctx.answer("❌ Ваша квитанция пуста.", false);
            return;
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), ReceiptView.receiptText(cart.items), Keyboards.close());
        ctx.session().remove(RECEIPT_CART);
        ctx.answer("✅ Квитанция успешно сформирована!", false);
    }

    private boolean onQuantityText(TextContext ctx) {
        ReceiptCart cart = ctx.session().get(RECEIPT_CART);
        if (cart == null || (cart.waitingServiceId == -1 && cart.waitingMaterialId == -1)) return false;
        double qty;
        try {
            qty = Formats.parseUserNumber(ctx.text());
            if (qty <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            if (cart.anchorMsgId != null) {
                messenger.edit(ctx.chatId(), cart.anchorMsgId, "❌ Введите корректное число больше нуля:", ReceiptView.BACK_TO_CART);
            }
            return true;
        }
        Optional<ReceiptLine> line;
        if (cart.waitingServiceId != -1) {
            line = receipts.serviceLine(cart.waitingServiceId, qty);
            cart.waitingServiceId = -1;
        } else {
            line = receipts.materialLine(ctx.chatId(), cart.waitingMaterialId, qty);
            cart.waitingMaterialId = -1;
        }
        line.ifPresent(cart.items::add);
        showCart(ctx.session(), ctx.chatId(), cart.anchorMsgId);
        return true;
    }

    /** «+услуга Название;Цена» — новая цена тарифа. */
    private void updateServicePrice(long chatId, Role role, String text) {
        try {
            String[] parts = text.substring(UPDATE_SERVICE_PREFIX.length()).split(";");
            if (parts.length == 2 && tariffs.updatePrice(parts[0].trim(), Double.parseDouble(parts[1].trim().replace(",", ".")))) {
                menu.show(chatId, role, "✅ Цена успешно обновлена.");
                return;
            }
        } catch (Exception ignored) {
            // неверный формат — общий ответ ниже
        }
        menu.show(chatId, role, "❌ Ошибка формата.");
    }
}
