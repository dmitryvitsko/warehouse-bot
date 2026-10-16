package com.warehousebot.bot.feature.stock;

import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.bot.core.TextStage;
import com.warehousebot.bot.feature.stock.StockSessions.Screen;
import com.warehousebot.bot.feature.stock.StockSessions.WhTake;
import com.warehousebot.bot.feature.stock.WarehouseView.CardMode;
import com.warehousebot.bot.session.SessionKey;
import com.warehousebot.bot.session.UserSession;
import com.warehousebot.domain.Material;
import com.warehousebot.domain.Role;
import com.warehousebot.infrastructure.mail.EmailSender;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.service.ReportService;
import com.warehousebot.service.StockService;
import com.warehousebot.service.StockService.StockUpdateResult;
import com.warehousebot.service.StockService.TakeResult;
import com.warehousebot.util.Formats;
import com.warehousebot.util.Html;

import java.io.File;
import java.util.Optional;

import static com.warehousebot.bot.feature.stock.StockSessions.WAITING_TAKE_MATERIAL;
import static com.warehousebot.bot.feature.stock.StockSessions.WAREHOUSE_TAKE;
import static com.warehousebot.bot.feature.stock.StockSessions.WRITE_OFF_CART;

/** «📦 Склад»: каталог, выдача, пополнение, передача в другой цех и принудительная выдача. */
public final class WarehouseModule implements BotModule {

    private static final SessionKey<Long> FORCE_TARGET_USER = SessionKey.dialog("stock.forceTargetUser");
    private static final SessionKey<CardMode> CARD_MODE = SessionKey.dialog("stock.cardMode");

    /** Если materialId == -1 в WAREHOUSE_TAKE, значит админ вводит строку нового материала. */
    private static final int ADD_NEW_MATERIAL_SENTINEL = -1;

    private final Messenger messenger;
    private final Ui ui;
    private final MainMenu menu;
    private final UserRepository users;
    private final StockService stock;
    private final ReportService reports;
    private final EmailSender email;

    public WarehouseModule(Messenger messenger, Ui ui, MainMenu menu, UserRepository users,
                           StockService stock, ReportService reports, EmailSender email) {
        this.messenger = messenger;
        this.ui = ui;
        this.menu = menu;
        this.users = users;
        this.stock = stock;
        this.reports = reports;
        this.email = email;
    }

    @Override
    public void register(Routes r) {
        r.onCommand(MainMenu.WAREHOUSE, ctx -> {
            clearCardState(ctx.session());
            showPage(ctx.chatId(), ctx.role(), 1, null, null);
        });
        r.onCommand(MainMenu.ADMIN_BALANCES, ctx -> {
            if (ctx.isAdmin()) ui.closable(ctx.chatId(), WarehouseView.allBalancesText(stock.allHoldings()));
        });
        r.onCommand(MainMenu.ADMIN_REPORT, ctx -> {
            if (ctx.isAdmin()) sendReport(ctx.chatId(), ctx.role());
        });

        r.onCallbackPrefix("WH_PAGE:").beforeBindCheck().handle(this::onPage);
        r.onCallbackPrefix("WH_ITEM:").beforeBindCheck().handle(ctx -> {
            clearCardState(ctx.session());
            showCard(ctx.session(), ctx.chatId(), ctx.role(), ctx.intPart(1), ctx.intPart(2), ctx.messageId(), null);
        });
        r.onCallbackPrefix("WH_F_USR:").beforeBindCheck().adminOnly().handle(this::onSelectForceUserMenu);
        r.onCallbackPrefix("WH_F_SEL:").beforeBindCheck().adminOnly().handle(this::onSelectForceUser);
        r.onCallbackPrefix("WH_MODE:").beforeBindCheck().adminOnly().handle(this::onSwitchCardMode);
        r.onCallbackPrefix("WH_ADD_NEW:").beforeBindCheck().adminOnly().handle(this::onAddNewMaterialPrompt);
        r.onCallbackPrefix("WH_TAKE:").beforeBindCheck().handle(this::onQuickTake);
        r.onCallback("WH_CLOSE").beforeBindCheck().handle(this::onClose);

        r.onCallbackPrefix("TAKE_MAT:").handle(this::onTakeMaterial);
        r.onCallbackPrefix("TAKE_Q:").handle(this::onTakeQuantity);

        r.onText(TextStage.WAREHOUSE_QTY_INPUT, this::onCardQuantityText);
        r.onText(TextStage.TAKE_QTY_INPUT, this::onTakeQuantityText);
    }

    private void clearCardState(UserSession session) {
        session.remove(WAITING_TAKE_MATERIAL);
        session.remove(WAREHOUSE_TAKE);
        session.remove(FORCE_TARGET_USER);
        session.remove(CARD_MODE);
    }

    // ------------------------------------------------------------------ каталог и карточка

    private void showPage(long chatId, Role role, int page, Integer messageId, String alert) {
        Screen s = WarehouseView.page(stock.availableMaterials(), page, alert, role != null && role.isAdmin());
        messenger.sendOrEdit(chatId, messageId, s.html(), s.markup());
    }

    private void showCard(UserSession session, long chatId, Role role, int materialId, int page, int messageId, String alert) {
        Optional<Material> material = stock.availableMaterial(materialId);
        if (material.isEmpty()) {
            clearCardState(session);
            showPage(chatId, role, page, messageId, "❌ Материал закончился на складе.");
            return;
        }
        session.put(WAREHOUSE_TAKE, new WhTake(materialId, page, messageId));
        CardMode mode = session.get(CARD_MODE);
        if (mode == null) mode = CardMode.TAKE_SELF;

        Long targetUserId = session.get(FORCE_TARGET_USER);
        String targetName = targetUserId != null ? users.displayName(targetUserId) : null;

        Screen s = WarehouseView.card(material.get(), page, alert, role.isAdmin(), mode, targetName);
        messenger.edit(chatId, messageId, s.html(), s.markup());
    }

    private void onSelectForceUserMenu(CallbackContext ctx) {
        int materialId = ctx.intPart(1);
        int page = ctx.intPart(2);
        Optional<Material> material = stock.availableMaterial(materialId);
        if (material.isEmpty()) {
            showPage(ctx.chatId(), ctx.role(), page, ctx.messageId(), "❌ Материал закончился на складе.");
            return;
        }
        ctx.session().remove(WAREHOUSE_TAKE);
        Screen s = WarehouseView.forceUsersMenu(material.get(), page, users.activeUsers());
        messenger.edit(ctx.chatId(), ctx.messageId(), s.html(), s.markup());
    }

    private void onSelectForceUser(CallbackContext ctx) {
        int materialId = ctx.intPart(1);
        int page = ctx.intPart(2);
        long targetUserId = ctx.longPart(3);
        ctx.session().put(FORCE_TARGET_USER, targetUserId);
        ctx.session().put(CARD_MODE, CardMode.FORCE_ASSIGN);
        showCard(ctx.session(), ctx.chatId(), ctx.role(), materialId, page, ctx.messageId(), null);
    }

    private void onSwitchCardMode(CallbackContext ctx) {
        String modeStr = ctx.part(1);
        int materialId = ctx.intPart(2);
        int page = ctx.intPart(3);
        ctx.session().remove(FORCE_TARGET_USER);
        ctx.session().put(CARD_MODE, "ADD".equals(modeStr) ? CardMode.REPLENISH : CardMode.TRANSFER_OUT);
        showCard(ctx.session(), ctx.chatId(), ctx.role(), materialId, page, ctx.messageId(), null);
    }

    private void onAddNewMaterialPrompt(CallbackContext ctx) {
        int page = ctx.intPart(1);
        clearCardState(ctx.session());
        ctx.session().put(WAREHOUSE_TAKE, new WhTake(ADD_NEW_MATERIAL_SENTINEL, page, ctx.messageId()));
        Screen s = WarehouseView.addNewMaterialPrompt(page, null);
        messenger.edit(ctx.chatId(), ctx.messageId(), s.html(), s.markup());
    }

    private void onPage(CallbackContext ctx) {
        clearCardState(ctx.session());
        showPage(ctx.chatId(), ctx.role(), ctx.intPart(1), ctx.messageId(), null);
    }

    private void onQuickTake(CallbackContext ctx) {
        ctx.session().remove(WAITING_TAKE_MATERIAL);
        int materialId = ctx.intPart(1);
        double qty = Double.parseDouble(ctx.part(2));
        int page = ctx.intPart(3);

        TakeResult result = executeCardAction(ctx.session(), ctx.chatId(), materialId, qty);
        if (result.ok()) {
            clearCardState(ctx.session());
            showPage(ctx.chatId(), ctx.role(), page, ctx.messageId(), result.text());
        } else {
            showCard(ctx.session(), ctx.chatId(), ctx.role(), materialId, page, ctx.messageId(), result.text());
        }
        ctx.answer(result.ok() ? "✅ Выполнено!" : "❌ Ошибка", !result.ok());
    }

    private void onClose(CallbackContext ctx) {
        clearCardState(ctx.session());
        messenger.delete(ctx.chatId(), ctx.messageId());
    }

    /** Ввод в чате при открытой карточке или окне добавления нового материала. */
    private boolean onCardQuantityText(TextContext ctx) {
        WhTake take = ctx.session().get(WAREHOUSE_TAKE);
        if (take == null) return false;

        // Режим добавления новой позиции строкой "Счёт; Инв; Название; Ед; Цена; Кол-во"
        if (take.materialId() == ADD_NEW_MATERIAL_SENTINEL) {
            StockUpdateResult res = stock.addOrUpdateMaterialManual(ctx.trimmed());
            if (res.ok()) {
                clearCardState(ctx.session());
                broadcastStockUpdate(ctx.chatId(), res.broadcastText());
                showPage(ctx.chatId(), ctx.role(), take.page(), take.anchorMsgId(), res.adminText());
            } else {
                Screen s = WarehouseView.addNewMaterialPrompt(take.page(), res.adminText());
                messenger.edit(ctx.chatId(), take.anchorMsgId(), s.html(), s.markup());
            }
            return true;
        }

        double qty;
        try {
            qty = Formats.parseUserNumber(ctx.text());
            if (qty <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showCard(ctx.session(), ctx.chatId(), ctx.role(), take.materialId(), take.page(), take.anchorMsgId(),
                    "❌ Введите число больше нуля (например: <code>5</code> или <code>0.5</code>).");
            return true;
        }
        TakeResult result = executeCardAction(ctx.session(), ctx.chatId(), take.materialId(), qty);
        if (result.ok()) {
            clearCardState(ctx.session());
            showPage(ctx.chatId(), ctx.role(), take.page(), take.anchorMsgId(), result.text());
        } else {
            showCard(ctx.session(), ctx.chatId(), ctx.role(), take.materialId(), take.page(), take.anchorMsgId(), result.text());
        }
        return true;
    }

    /** Выполняет действие в зависимости от текущего режима карточки (взять себе / выдать / пополнить / списать). */
    private TakeResult executeCardAction(UserSession session, long chatId, int materialId, double qty) {
        CardMode mode = session.get(CARD_MODE);
        if (mode == null) mode = CardMode.TAKE_SELF;

        return switch (mode) {
            case REPLENISH -> {
                StockUpdateResult res = stock.replenishStock(materialId, qty);
                if (res.ok()) broadcastStockUpdate(chatId, res.broadcastText());
                yield new TakeResult(res.ok(), res.adminText());
            }
            case TRANSFER_OUT -> stock.transferOutFromWarehouse(materialId, qty);
            case FORCE_ASSIGN -> {
                Long targetUserId = session.get(FORCE_TARGET_USER);
                if (targetUserId == null) yield stock.take(chatId, materialId, qty);
                String targetName = users.displayName(targetUserId);
                Optional<Material> mat = stock.availableMaterial(materialId);
                TakeResult res = stock.forceAssign(targetUserId, targetName, materialId, qty);
                if (res.ok() && targetUserId != chatId && mat.isPresent()) {
                    Material m = mat.get();
                    ui.notify(targetUserId, String.format("""
                                    🔔 <b>Администратор выдал вам материал в подотчет:</b>
                                    • Наименование: <b>%s</b>
                                    • Инв. номер: <code>%s</code>
                                    • Количество: <b>%s %s</b>""",
                            Html.esc(m.name()), Html.esc(m.code()), Formats.qty(qty), Html.esc(m.unit())));
                }
                yield res;
            }
            case TAKE_SELF -> stock.take(chatId, materialId, qty);
        };
    }

    /** Рассылает уведомление всем активным сотрудникам об обновлении склада. */
    private void broadcastStockUpdate(long excludeChatId, String broadcastText) {
        if (broadcastText == null || broadcastText.isEmpty()) return;
        for (Long userId : users.allUserIds()) {
            if (userId == excludeChatId) continue;
            ui.notify(userId, broadcastText);
        }
    }

    // ------------------------------------------------------------------ старый список склада (TAKE_MAT / TAKE_Q)

    private void onTakeMaterial(CallbackContext ctx) {
        ctx.session().remove(WRITE_OFF_CART);
        ctx.session().put(WAITING_TAKE_MATERIAL, ctx.intPart(1));
        messenger.edit(ctx.chatId(), ctx.messageId(), WarehouseView.TAKE_QTY_PROMPT, WarehouseView.takeQtyKeyboard());
    }

    private void onTakeQuantity(CallbackContext ctx) {
        Integer materialId = ctx.session().get(WAITING_TAKE_MATERIAL);
        if (materialId == null) {
            menu.show(ctx.chatId(), ctx.role(), "❌ Ошибка: вы не выбрали материал.");
            return;
        }
        try {
            takeAndReport(ctx.session(), ctx.chatId(), ctx.role(), materialId, Double.parseDouble(ctx.part(1)));
        } catch (Exception ignored) {
        }
    }

    private boolean onTakeQuantityText(TextContext ctx) {
        Integer materialId = ctx.session().get(WAITING_TAKE_MATERIAL);
        if (materialId == null) return false;
        double qty;
        try {
            qty = Formats.parseUserNumber(ctx.text());
        } catch (NumberFormatException e) {
            ui.cancelPrompt(ctx.chatId(), "❌ Введите корректное число (например: <code>5</code> или <code>0.05</code>).");
            return true;
        }
        takeAndReport(ctx.session(), ctx.chatId(), ctx.role(), materialId, qty);
        return true;
    }

    private void takeAndReport(UserSession session, long chatId, Role role, int materialId, double qty) {
        TakeResult result = stock.take(chatId, materialId, qty);
        if (result.ok()) {
            session.remove(WAITING_TAKE_MATERIAL);
            menu.show(chatId, role, result.text());
        } else {
            messenger.send(chatId, result.text());
        }
    }

    // ------------------------------------------------------------------ отчёт

    private void sendReport(long chatId, Role role) {
        menu.show(chatId, role, "⏳ Формирую отчет...");
        File report = reports.generate();
        if (report == null || !report.exists()) {
            menu.show(chatId, role, "❌ Ошибка формирования.");
            return;
        }
        try {
            messenger.sendDocument(chatId, report);
            email.send("Отчет по складу", "Во вложении Excel.", report);
            menu.show(chatId, role, "✉️ Отправлено на почту!");
        } catch (Exception e) {
            menu.show(chatId, role, "⚠️ В Telegram отправлено, но на почту ошибка: " + Html.esc(e.getMessage()));
        } finally {
            reports.discard(report);
        }
    }
}