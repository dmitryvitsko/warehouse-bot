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
import com.warehousebot.bot.session.SessionKey;
import com.warehousebot.bot.session.UserSession;
import com.warehousebot.domain.Material;
import com.warehousebot.domain.Role;
import com.warehousebot.infrastructure.mail.EmailSender;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.service.ReportService;
import com.warehousebot.service.StockService;
import com.warehousebot.service.StockService.TakeResult;
import com.warehousebot.util.Formats;
import com.warehousebot.util.Html;

import java.io.File;
import java.util.Optional;

import static com.warehousebot.bot.feature.stock.StockSessions.WAITING_TAKE_MATERIAL;
import static com.warehousebot.bot.feature.stock.StockSessions.WAREHOUSE_TAKE;
import static com.warehousebot.bot.feature.stock.StockSessions.WRITE_OFF_CART;

/** «📦 Склад»: каталог, выдача материала в подотчёт; для администратора — принудительная выдача, остатки и отчёт. */
public final class WarehouseModule implements BotModule {

    /** Если задано — карточка материала работает в режиме принудительной выдачи этому сотруднику. */
    private static final SessionKey<Long> FORCE_TARGET_USER = SessionKey.dialog("stock.forceTargetUser");

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
            ctx.session().remove(FORCE_TARGET_USER);
            showPage(ctx.chatId(), 1, null, null);
        });
        r.onCommand(MainMenu.ADMIN_BALANCES, ctx -> {
            if (ctx.isAdmin()) ui.closable(ctx.chatId(), WarehouseView.allBalancesText(stock.allHoldings()));
        });
        r.onCommand(MainMenu.ADMIN_REPORT, ctx -> {
            if (ctx.isAdmin()) sendReport(ctx.chatId(), ctx.role());
        });

        // Каталог склада доступен и мастеру, ещё не выбравшему фамилию (как раньше).
        r.onCallbackPrefix("WH_PAGE:").beforeBindCheck().handle(this::onPage);
        r.onCallbackPrefix("WH_ITEM:").beforeBindCheck().handle(ctx -> {
            ctx.session().remove(FORCE_TARGET_USER);
            showCard(ctx.session(), ctx.chatId(), ctx.role(), ctx.intPart(1), ctx.intPart(2), ctx.messageId(), null);
        });
        r.onCallbackPrefix("WH_F_USR:").beforeBindCheck().adminOnly().handle(this::onSelectForceUserMenu);
        r.onCallbackPrefix("WH_F_SEL:").beforeBindCheck().adminOnly().handle(this::onSelectForceUser);
        r.onCallbackPrefix("WH_TAKE:").beforeBindCheck().handle(this::onQuickTake);
        r.onCallback("WH_CLOSE").beforeBindCheck().handle(this::onClose);

        r.onCallbackPrefix("TAKE_MAT:").handle(this::onTakeMaterial);
        r.onCallbackPrefix("TAKE_Q:").handle(this::onTakeQuantity);

        r.onText(TextStage.WAREHOUSE_QTY_INPUT, this::onCardQuantityText);
        r.onText(TextStage.TAKE_QTY_INPUT, this::onTakeQuantityText);
    }

    // ------------------------------------------------------------------ каталог и карточка

    private void showPage(long chatId, int page, Integer messageId, String alert) {
        Screen s = WarehouseView.page(stock.availableMaterials(), page, alert);
        messenger.sendOrEdit(chatId, messageId, s.html(), s.markup());
    }

    private void showCard(UserSession session, long chatId, Role role, int materialId, int page, int messageId, String alert) {
        Optional<Material> material = stock.availableMaterial(materialId);
        if (material.isEmpty()) {
            session.remove(WAREHOUSE_TAKE);
            session.remove(FORCE_TARGET_USER);
            showPage(chatId, page, messageId, "❌ Материал закончился на складе.");
            return;
        }
        session.put(WAREHOUSE_TAKE, new WhTake(materialId, page, messageId));
        Long targetUserId = session.get(FORCE_TARGET_USER);
        String targetName = targetUserId != null ? users.displayName(targetUserId) : null;

        Screen s = WarehouseView.card(material.get(), page, alert, role.isAdmin(), targetName);
        messenger.edit(chatId, messageId, s.html(), s.markup());
    }

    private void onSelectForceUserMenu(CallbackContext ctx) {
        int materialId = ctx.intPart(1);
        int page = ctx.intPart(2);
        Optional<Material> material = stock.availableMaterial(materialId);
        if (material.isEmpty()) {
            showPage(ctx.chatId(), page, ctx.messageId(), "❌ Материал закончился на складе.");
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
        showCard(ctx.session(), ctx.chatId(), ctx.role(), materialId, page, ctx.messageId(), null);
    }

    private void onPage(CallbackContext ctx) {
        ctx.session().remove(WAITING_TAKE_MATERIAL);
        ctx.session().remove(WAREHOUSE_TAKE);
        ctx.session().remove(FORCE_TARGET_USER);
        showPage(ctx.chatId(), ctx.intPart(1), ctx.messageId(), null);
    }

    private void onQuickTake(CallbackContext ctx) {
        ctx.session().remove(WAITING_TAKE_MATERIAL);
        int materialId = ctx.intPart(1);
        double qty = Double.parseDouble(ctx.part(2));
        int page = ctx.intPart(3);

        TakeResult result = executeTakeOrForce(ctx.session(), ctx.chatId(), materialId, qty);
        if (result.ok()) {
            ctx.session().remove(WAREHOUSE_TAKE);
            ctx.session().remove(FORCE_TARGET_USER);
            showPage(ctx.chatId(), page, ctx.messageId(), result.text());
        } else {
            showCard(ctx.session(), ctx.chatId(), ctx.role(), materialId, page, ctx.messageId(), result.text());
        }
        ctx.answer(result.ok() ? "✅ Добавлено в подотчет." : "❌ Недостаточно на складе", !result.ok());
    }

    private void onClose(CallbackContext ctx) {
        ctx.session().remove(WAITING_TAKE_MATERIAL);
        ctx.session().remove(WAREHOUSE_TAKE);
        ctx.session().remove(FORCE_TARGET_USER);
        messenger.delete(ctx.chatId(), ctx.messageId());
    }

    /** Количество, набранное в чате при открытой карточке: ответ — в той же карточке. */
    private boolean onCardQuantityText(TextContext ctx) {
        WhTake take = ctx.session().get(WAREHOUSE_TAKE);
        if (take == null) return false;
        double qty;
        try {
            qty = Formats.parseUserNumber(ctx.text());
            if (qty <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showCard(ctx.session(), ctx.chatId(), ctx.role(), take.materialId(), take.page(), take.anchorMsgId(),
                    "❌ Введите число больше нуля (например: <code>5</code> или <code>0.5</code>).");
            return true;
        }
        TakeResult result = executeTakeOrForce(ctx.session(), ctx.chatId(), take.materialId(), qty);
        if (result.ok()) {
            ctx.session().remove(WAREHOUSE_TAKE);
            ctx.session().remove(FORCE_TARGET_USER);
            showPage(ctx.chatId(), take.page(), take.anchorMsgId(), result.text());
        } else {
            showCard(ctx.session(), ctx.chatId(), ctx.role(), take.materialId(), take.page(), take.anchorMsgId(), result.text());
        }
        return true;
    }

    /** Выполняет либо обычное взятие себе, либо принудительную выдачу выбранному сотруднику с уведомлением. */
    private TakeResult executeTakeOrForce(UserSession session, long chatId, int materialId, double qty) {
        Long targetUserId = session.get(FORCE_TARGET_USER);
        if (targetUserId == null) {
            return stock.take(chatId, materialId, qty);
        }
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
        return res;
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