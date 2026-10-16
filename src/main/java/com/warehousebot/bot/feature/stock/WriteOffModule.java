package com.warehousebot.bot.feature.stock;

import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.bot.core.TextStage;
import com.warehousebot.bot.feature.stock.StockSessions.CartItem;
import com.warehousebot.bot.feature.stock.StockSessions.PendingItem;
import com.warehousebot.bot.feature.stock.StockSessions.Screen;
import com.warehousebot.bot.feature.stock.StockSessions.WriteOffCart;
import com.warehousebot.bot.session.UserSession;
import com.warehousebot.domain.HeldMaterial;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.service.ReturnService;
import com.warehousebot.service.ReturnService.Created;
import com.warehousebot.service.StockService;
import com.warehousebot.util.Formats;
import com.warehousebot.util.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static com.warehousebot.bot.feature.stock.StockSessions.WAITING_TAKE_MATERIAL;
import static com.warehousebot.bot.feature.stock.StockSessions.WRITE_OFF_CART;
import static com.warehousebot.util.Formats.qty;

/**
 * «📝 Списать / Вернуть»: мастер собирает список материалов из подотчёта и оформляет его
 * как списание по квитанции, техническое списание или возврат на склад; МОЛ решает по возвратам.
 */
public final class WriteOffModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(WriteOffModule.class);

    private static final String STEP_QTY = "WAIT_QTY";
    private static final String STEP_TYPE = "WAIT_TYPE";
    private static final String STEP_RECEIPT_NUM = "WAIT_RECEIPT_NUM";
    private static final String STEP_PAID_PHONE = "WAIT_PAID_PHONE";
    private static final String STEP_PAID_CONTRACT = "WAIT_PAID_CONTRACT";
    private static final String STEP_PAID_ADDRESS = "WAIT_PAID_ADDRESS";
    private static final String STEP_FREE_PHONE = "WAIT_FREE_PHONE";
    private static final String STEP_FREE_CONTRACT = "WAIT_FREE_CONTRACT";
    private static final String STEP_FREE_ADDRESS = "WAIT_FREE_ADDRESS";
    private static final String STEP_CLOSING_CODE = "WAIT_CLOSING_CODE";

    private final Messenger messenger;
    private final Ui ui;
    private final MainMenu menu;
    private final UserRepository users;
    private final StockService stock;
    private final ReturnService returns;

    public WriteOffModule(Messenger messenger, Ui ui, MainMenu menu, UserRepository users, StockService stock,
                          ReturnService returns) {
        this.messenger = messenger;
        this.ui = ui;
        this.menu = menu;
        this.users = users;
        this.stock = stock;
        this.returns = returns;
    }

    @Override
    public void register(Routes r) {
        r.onCommand(MainMenu.WRITE_OFF, ctx -> showMenu(ctx.session(), ctx.chatId(), null, null));

        r.onCallbackPrefix("RET_APP:").adminOnly().handle(ctx -> decide(ctx, returns.approve(ctx.intPart(1))));
        r.onCallbackPrefix("RET_REJ:").adminOnly().handle(ctx -> decide(ctx, returns.reject(ctx.intPart(1))));

        r.onCallback("WO_CLEAR_CART").handle(ctx -> {
            ctx.session().remove(WRITE_OFF_CART);
            showMenu(ctx.session(), ctx.chatId(), ctx.messageId(), "🗑 Список материалов очищен.");
        });
        r.onCallback("WO_CHECKOUT").handle(this::onCheckout);
        r.onCallback("WO_BACK_LIST").handle(this::onBackToList);
        r.onCallbackPrefix("WO_MAT:").handle(this::onMaterial);
        r.onCallbackPrefix("WO_Q:").when(ctx -> ctx.session().has(WRITE_OFF_CART)).handle(this::onQuickQuantity);
        r.onCallbackPrefix("WO_TYPE:").when(ctx -> ctx.session().has(WRITE_OFF_CART)).handle(this::onType);
        r.onCallbackPrefix("WO_CODE:").when(ctx -> ctx.session().has(WRITE_OFF_CART)).handle(this::onClosingCode);

        r.onText(TextStage.WRITE_OFF_INPUT, this::onText);
    }

    private void showMenu(UserSession session, long chatId, Integer messageId, String alert) {
        Screen s = WriteOffView.menu(stock.heldMaterials(chatId), session.get(WRITE_OFF_CART), alert);
        messenger.sendOrEdit(chatId, messageId, s.html(), s.markup());
    }

    private void edit(long chatId, Integer messageId, String html) {
        if (messageId == null) return;
        messenger.edit(chatId, messageId, html, WriteOffView.stepKeyboard());
    }

    // ------------------------------------------------------------------ корзина

    private void onCheckout(CallbackContext ctx) {
        WriteOffCart cart = ctx.session().get(WRITE_OFF_CART);
        if (cart == null || cart.items.isEmpty()) return;
        cart.step = STEP_TYPE;
        Screen s = WriteOffView.typeChoice(cart.items.size());
        messenger.edit(ctx.chatId(), ctx.messageId(), s.html(), s.markup());
    }

    private void onBackToList(CallbackContext ctx) {
        WriteOffCart cart = ctx.session().get(WRITE_OFF_CART);
        if (cart != null) {
            cart.tempItem = null;
            if (cart.items.isEmpty()) ctx.session().remove(WRITE_OFF_CART);
        }
        showMenu(ctx.session(), ctx.chatId(), ctx.messageId(), null);
    }

    private void onMaterial(CallbackContext ctx) {
        UserSession session = ctx.session();
        session.remove(WAITING_TAKE_MATERIAL);
        Optional<HeldMaterial> held = stock.heldForWriteOff(ctx.chatId(), ctx.intPart(1));
        if (held.isEmpty()) {
            showMenu(session, ctx.chatId(), ctx.messageId(), null);
            return;
        }
        WriteOffCart cart = session.getOrCreate(WRITE_OFF_CART, WriteOffCart::new);
        cart.anchorMsgId = ctx.messageId();

        HeldMaterial m = held.get();
        // Из доступного вычитается то, что уже добавлено в корзину.
        double maxAvailable = m.quantity();
        for (CartItem item : cart.items) {
            if (item.materialId() == m.materialId()) maxAvailable -= item.quantity();
        }
        if (maxAvailable <= 0) {
            showMenu(session, ctx.chatId(), ctx.messageId(), "❌ Вы уже добавили весь доступный объем этого материала в список!");
            return;
        }
        cart.tempItem = new PendingItem(m.materialId(), m.name() + " [" + m.code() + "]", m.unit(), m.priceWithVat(), maxAvailable);
        cart.step = STEP_QTY;
        Screen s = WriteOffView.quantityPrompt(cart.tempItem);
        messenger.edit(ctx.chatId(), ctx.messageId(), s.html(), s.markup());
    }

    private void onQuickQuantity(CallbackContext ctx) {
        WriteOffCart cart = ctx.session().get(WRITE_OFF_CART);
        if (cart.tempItem == null) return;
        double qty;
        try {
            qty = Double.parseDouble(ctx.part(1));
        } catch (Exception e) {
            return;
        }
        if (qty <= 0 || qty > cart.tempItem.maxAvailable() + 1e-9) {
            ctx.answer("❌ Доступно не более " + qty(cart.tempItem.maxAvailable()) + " " + cart.tempItem.unit(), true);
            return;
        }
        addToCart(ctx.session(), cart, ctx.chatId(), ctx.messageId(), qty);
    }

    private void addToCart(UserSession session, WriteOffCart cart, long chatId, Integer messageId, double qty) {
        cart.items.add(cart.tempItem.withQuantity(qty));
        cart.tempItem = null;
        showMenu(session, chatId, messageId, "✅ Материал добавлен в список!");
    }

    // ------------------------------------------------------------------ оформление

    private void onType(CallbackContext ctx) {
        WriteOffCart cart = ctx.session().get(WRITE_OFF_CART);
        String type = ctx.part(1);
        if ("RETURN".equals(type)) {
            // Корзина снимается до создания заявок: повторное нажатие не создаст дубли.
            ctx.session().remove(WRITE_OFF_CART);
            StringBuilder result = new StringBuilder();
            for (CartItem item : cart.items) {
                Created req = returns.create(ctx.chatId(), item.materialId(), item.quantity());
                if (!req.success()) {
                    result.append(req.workerMessage()).append("\n");
                } else if (ctx.isAdmin()) {
                    // Возврат от самого МОЛ подтверждается сразу.
                    result.append("⚡️ <b>Возврат:</b> ").append(returns.approve(req.requestId()).adminMessage()).append("\n");
                } else {
                    result.append(req.workerMessage()).append("\n");
                    sendReturnApprovalToAdmins(req.requestId(), req.adminMessage());
                }
            }
            showMenu(ctx.session(), ctx.chatId(), ctx.messageId(), result.toString().trim());
        } else if ("PAID".equals(type)) {
            cart.paidReceipt = true;
            cart.step = STEP_RECEIPT_NUM;
            edit(ctx.chatId(), ctx.messageId(), "🧾 <b>Списание по квитанции (шаг 1 из 4)</b>\nВведите <b>номер квитанции</b>:");
        } else {
            cart.paidReceipt = false;
            cart.step = STEP_FREE_PHONE;
            edit(ctx.chatId(), ctx.messageId(), "🛠 <b>Техническое списание (шаг 1 из 5)</b>\nВведите <b>номер телефона</b>, на который оформлена заявка:");
        }
    }

    /** Запрос на возврат ждёт решения администратора — сообщение не удаляется при очистке чата. */
    private void sendReturnApprovalToAdmins(int requestId, String text) {
        String alert = "🚨 <b>ТРЕБУЕТСЯ ВАШЕ РАЗРЕШЕНИЕ!</b> 🚨\n\n" + text;
        List<Long> admins;
        try {
            admins = users.adminIds();
        } catch (DataAccessException e) {
            // Заявка уже сохранена; администратор увидит её при следующем входе (блокировка «входящих»).
            log.error("Не удалось получить список администраторов", e);
            return;
        }
        for (Long adminId : admins) {
            messenger.sendPinned(adminId, alert, WriteOffView.returnApprovalKeyboard(requestId));
        }
    }

    private void onClosingCode(CallbackContext ctx) {
        WriteOffCart cart = ctx.session().remove(WRITE_OFF_CART);
        String closingCode = ctx.part(1);
        String reason = WriteOffView.reasonFor(closingCode);

        StringBuilder result = new StringBuilder("🛠 <b>Результаты списания:</b>\n");
        for (CartItem item : cart.items) {
            // Номер квитанции при техническом списании не заполняется (в базе — NULL, как и раньше).
            result.append(stock.writeOff(ctx.chatId(), item.toWriteOff(false, null, cart.phoneNumber,
                    cart.contractNumber, cart.address, closingCode, reason))).append("\n");
        }
        if (!ctx.isAdmin()) {
            String workerName = users.displayName(ctx.chatId());
            ui.notifyAdmins(ctx.chatId(), "🔔 <b>ВНИМАНИЕ! Техническое списание:</b>\n"
                    + "👷‍♂️ Сотрудник: <b>" + Html.esc(workerName) + "</b>\n\n" + result);
        }
        showMenu(ctx.session(), ctx.chatId(), ctx.messageId(), result.toString().trim());
    }

    private void completePaid(TextContext ctx, WriteOffCart cart) {
        StringBuilder result = new StringBuilder("🧾 <b>Результаты списания:</b>\n");
        for (CartItem item : cart.items) {
            result.append(stock.writeOff(ctx.chatId(), item.toWriteOff(true, cart.receiptNumber, cart.phoneNumber,
                    cart.contractNumber, cart.address, "102", "Списание по квитанции"))).append("\n");
        }
        ctx.session().remove(WRITE_OFF_CART);
        if (!ctx.isAdmin()) {
            String workerName = users.displayName(ctx.chatId());
            ui.notifyAdmins(ctx.chatId(), "🧾 <b>ВНИМАНИЕ! Списание по квитанции:</b>\n"
                    + "👷‍♂️ Сотрудник: <b>" + Html.esc(workerName) + "</b>\n\n" + result);
        }
        showMenu(ctx.session(), ctx.chatId(), cart.anchorMsgId, result.toString().trim());
    }

    private void decide(CallbackContext ctx, ReturnService.Decision d) {
        messenger.delete(ctx.chatId(), ctx.messageId()); // 👈 Удаляем уведомление с кнопками из чата!
        menu.show(ctx.chatId(), ctx.role(), d.adminMessage());
        if (d.ok() && d.workerId() != ctx.chatId()) ui.notify(d.workerId(), d.workerMessage());
    }

    // ------------------------------------------------------------------ текстовые шаги

    private boolean onText(TextContext ctx) {
        WriteOffCart cart = ctx.session().get(WRITE_OFF_CART);
        if (cart == null) return false;
        if (cart.anchorMsgId == null) {
            ui.cancelPrompt(ctx.chatId(), "❌ Ошибка сессии. Начните заново.");
            ctx.session().remove(WRITE_OFF_CART);
            return true;
        }
        long chatId = ctx.chatId();
        Integer anchor = cart.anchorMsgId;
        String text = ctx.trimmed();
        switch (cart.step) {
            case STEP_QTY -> onQuantityText(ctx, cart);
            case STEP_RECEIPT_NUM -> {
                cart.receiptNumber = text;
                cart.step = STEP_PAID_PHONE;
                edit(chatId, anchor, "🧾 Введите <b>номер телефона</b>:");
            }
            case STEP_PAID_PHONE -> {
                cart.phoneNumber = text;
                cart.step = STEP_PAID_CONTRACT;
                edit(chatId, anchor, "🧾 Введите <b>номер договора</b> (или -):");
            }
            case STEP_PAID_CONTRACT -> {
                cart.contractNumber = text;
                cart.step = STEP_PAID_ADDRESS;
                edit(chatId, anchor, "🧾 Введите <b>адрес абонента</b>:");
            }
            case STEP_PAID_ADDRESS -> {
                cart.address = text;
                completePaid(ctx, cart);
            }
            case STEP_FREE_PHONE -> {
                cart.phoneNumber = text;
                cart.step = STEP_FREE_CONTRACT;
                edit(chatId, anchor, "🛠 Введите <b>номер договора</b> (или -):");
            }
            case STEP_FREE_CONTRACT -> {
                cart.contractNumber = text;
                cart.step = STEP_FREE_ADDRESS;
                edit(chatId, anchor, "🛠 Введите <b>адрес</b>:");
            }
            case STEP_FREE_ADDRESS -> {
                cart.address = text;
                cart.step = STEP_CLOSING_CODE;
                Screen s = WriteOffView.closingCodes(stock.code212Warning(cart.phoneNumber, cart.contractNumber));
                messenger.edit(chatId, anchor, s.html(), s.markup());
            }
            default -> {
                // На шагах выбора кнопкой текст игнорируется.
            }
        }
        return true;
    }

    private void onQuantityText(TextContext ctx, WriteOffCart cart) {
        double qty;
        try {
            if (cart.tempItem == null) throw new IllegalStateException("материал не выбран");
            qty = Formats.parseUserNumber(ctx.text());
        } catch (Exception e) {
            edit(ctx.chatId(), cart.anchorMsgId, "❌ Введите количество числом:");
            return;
        }
        if (qty <= 0 || qty > cart.tempItem.maxAvailable() + 1e-9 || Double.isNaN(qty)) {
            edit(ctx.chatId(), cart.anchorMsgId, "❌ Максимум: " + qty(cart.tempItem.maxAvailable()) + "\n\nВведите заново:");
            return;
        }
        addToCart(ctx.session(), cart, ctx.chatId(), cart.anchorMsgId, qty);
    }
}
