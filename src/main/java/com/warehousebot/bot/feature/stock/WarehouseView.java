package com.warehousebot.bot.feature.stock;

import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.feature.stock.StockSessions.Screen;
import com.warehousebot.domain.Material;
import com.warehousebot.repository.BalanceRepository.Holding;
import com.warehousebot.util.Html;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;
import static com.warehousebot.util.Formats.price;
import static com.warehousebot.util.Formats.qty;

/** Экраны склада: каталог по страницам, карточка материала, остатки у сотрудников. */
final class WarehouseView {

    static final int PAGE_SIZE = 10;
    static final String SEPARATOR = "➖➖➖➖➖➖➖➖➖➖";

    private WarehouseView() {
    }

    /** Номер страницы в допустимых пределах (пустой склад — одна страница). */
    static int clampPage(int page, int itemCount) {
        int totalPages = Math.max(1, (int) Math.ceil((double) itemCount / PAGE_SIZE));
        return Math.min(Math.max(page, 1), totalPages);
    }

    /** Каталог склада: 10 позиций на странице, кнопки с номерами открывают карточку. */
    static Screen page(List<Material> materials, int requestedPage, String alertText) {
        int totalPages = Math.max(1, (int) Math.ceil((double) materials.size() / PAGE_SIZE));
        int page = clampPage(requestedPage, materials.size());
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, materials.size());

        StringBuilder sb = new StringBuilder("📦 <b>Склад (Наличие и цены)</b>\n\n");
        if (materials.isEmpty()) {
            sb.append("<i>Склад пуст.</i>");
        } else {
            for (int i = start; i < end; i++) {
                Material m = materials.get(i);
                sb.append(String.format("<b>%d.</b> %s — <b>%s %s</b>\n", i - start + 1, Html.esc(m.name()),
                        qty(m.warehouseQty()), Html.esc(m.unit())));
            }
        }
        if (alertText != null && !alertText.isEmpty()) sb.append("\n").append(SEPARATOR).append("\n").append(alertText);

        Keyboards.Rows rows = Keyboards.rows();
        if (!materials.isEmpty()) {
            List<InlineKeyboardButton> numRow1 = new ArrayList<>();
            List<InlineKeyboardButton> numRow2 = new ArrayList<>();
            for (int i = start; i < end; i++) {
                int num = i - start + 1;
                InlineKeyboardButton b = btn(String.valueOf(num), "WH_ITEM:" + materials.get(i).id() + ":" + page);
                if (num <= 5) numRow1.add(b);
                else numRow2.add(b);
            }
            rows.addRow(numRow1).addRow(numRow2);

            List<InlineKeyboardButton> pageRow = new ArrayList<>();
            if (page > 1) pageRow.add(btn("⬅️", "WH_PAGE:" + (page - 1)));
            pageRow.add(btn("Стр. " + page + " из " + totalPages, "IGNORE"));
            if (page < totalPages) pageRow.add(btn("➡️", "WH_PAGE:" + (page + 1)));
            rows.addRow(pageRow);
        }
        rows.add(btn("❌ Закрыть склад", "WH_CLOSE"));
        return new Screen(sb.toString(), rows.build());
    }

    /** Карточка материала: обычная или в режиме принудительной выдачи выбранному сотруднику. */
    static Screen card(Material m, int page, String alert) {
        return card(m, page, alert, false, null);
    }

    static Screen card(Material m, int page, String alert, boolean isAdmin, String targetWorkerName) {
        StringBuilder sb = new StringBuilder();
        if (alert != null) sb.append(alert).append("\n\n").append(SEPARATOR).append("\n");
        String unit = Html.esc(m.unit());

        if (targetWorkerName != null) {
            sb.append("👷‍♂️ <b>ПРИНУДИТЕЛЬНАЯ ВЫДАЧА СОТРУДНИКУ: ").append(Html.esc(targetWorkerName)).append("</b>\n\n");
        }

        sb.append(String.format("📦 <b>%s</b>\n\n• На складе: <b>%s %s</b>\n• Цена с НДС: <b>%s руб.</b>\n\n✍️ <b>Напишите количество (%s) в чат</b> ИЛИ выберите кнопкой:",
                Html.esc(m.name()), qty(m.warehouseQty()), unit, price(m.priceWithVat()), unit));

        String prefix = "WH_TAKE:" + m.id() + ":";
        Keyboards.Rows rows = Keyboards.rows();
        rows.addRow(row(btn("1", prefix + "1:" + page), btn("2", prefix + "2:" + page), btn("3", prefix + "3:" + page),
                btn("4", prefix + "4:" + page), btn("5", prefix + "5:" + page)));
        rows.addRow(row(btn("10", prefix + "10:" + page), btn("15", prefix + "15:" + page),
                btn("20", prefix + "20:" + page), btn("50", prefix + "50:" + page)));

        if (isAdmin && targetWorkerName == null) {
            rows.add(btn("👷‍♂️ Выдать сотруднику (принудительно)", "WH_F_USR:" + m.id() + ":" + page));
        }
        if (targetWorkerName != null) {
            rows.add(btn("🔄 Сменить сотрудника", "WH_F_USR:" + m.id() + ":" + page));
        }

        rows.add(btn("🔙 Назад к списку", "WH_PAGE:" + page));
        return new Screen(sb.toString(), rows.build());
    }

    /** Список сотрудников для принудительной выдачи материала со склада. */
    static Screen forceUsersMenu(Material m, int page, List<com.warehousebot.domain.ActiveUser> users) {
        String text = "👷‍♂️ <b>Принудительная выдача материала:</b>\n"
                + "📦 <b>" + Html.esc(m.name()) + "</b> (доступно: " + qty(m.warehouseQty()) + " " + Html.esc(m.unit()) + ")\n\n"
                + "👇 Выберите сотрудника, которому нужно записать материал в подотчет:";
        Keyboards.Rows rows = Keyboards.rows();
        for (com.warehousebot.domain.ActiveUser u : users) {
            String icon = u.role().isAdmin() ? "👑 " : "👷‍♂️ ";
            rows.add(btn(icon + u.fullName(), "WH_F_SEL:" + m.id() + ":" + page + ":" + u.id()));
        }
        rows.add(btn("🔙 Назад к материалу", "WH_ITEM:" + m.id() + ":" + page));
        return new Screen(text, rows.build());
    }

    /** Ввод количества для материала, выбранного кнопкой TAKE_MAT (старые сообщения со списком склада). */
    static final String TAKE_QTY_PROMPT = "✍ <b>Укажите количество:</b>\nНапишите число вручную (например: <code>15</code>) ИЛИ нажмите на кнопку:";

    static InlineKeyboardMarkup takeQtyKeyboard() {
        return Keyboards.of(
                row(btn("1", "TAKE_Q:1"), btn("2", "TAKE_Q:2"), btn("3", "TAKE_Q:3"), btn("4", "TAKE_Q:4"), btn("5", "TAKE_Q:5")),
                row(btn("10", "TAKE_Q:10"), btn("20", "TAKE_Q:20"), btn("50", "TAKE_Q:50"), btn("100", "TAKE_Q:100")),
                row(btn("❌ Отменить", "WH_CLOSE")));
    }

    /** «📊 У кого что на руках». */
    static String allBalancesText(List<Holding> holdings) {
        if (holdings.isEmpty()) return "📊 Сейчас на руках у сотрудников нет ни одного материала.";
        StringBuilder sb = new StringBuilder("📊 <b>Материалы на руках у сотрудников:</b>\n");
        String currentWorker = "";
        for (Holding h : holdings) {
            String worker = h.workerName();
            if (!worker.equals(currentWorker)) {
                sb.append("\n👤 <b>").append(Html.esc(worker)).append(":</b>\n");
                currentWorker = worker;
            }
            double totalSum = h.quantity() * h.priceWithVat();
            sb.append(String.format("   • <b>%s</b> [<code>%s</code>] — <b>%s %s</b> (сумма: <b>%s руб.</b>)\n",
                    Html.esc(h.name()), Html.esc(h.code()), qty(h.quantity()), Html.esc(h.unit()), price(totalSum)));
        }
        return sb.toString();
    }
}
