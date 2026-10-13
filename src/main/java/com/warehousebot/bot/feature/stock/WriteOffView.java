package com.warehousebot.bot.feature.stock;

import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.feature.stock.StockSessions.CartItem;
import com.warehousebot.bot.feature.stock.StockSessions.PendingItem;
import com.warehousebot.bot.feature.stock.StockSessions.Screen;
import com.warehousebot.bot.feature.stock.StockSessions.WriteOffCart;
import com.warehousebot.domain.HeldMaterial;
import com.warehousebot.util.Formats;
import com.warehousebot.util.Html;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;
import static com.warehousebot.util.Formats.qty;

/** Экраны «📝 Списать / Вернуть»: корзина, ввод количества, тип операции, код закрытия. */
final class WriteOffView {

    private WriteOffView() {
    }

    /** Список материалов на руках и корзина (бывший startWriteOffMenu). */
    static Screen menu(List<HeldMaterial> held, WriteOffCart cart, String alertText) {
        StringBuilder sb = new StringBuilder();
        if (alertText != null && !alertText.isEmpty()) sb.append(alertText).append("\n\n").append(WarehouseView.SEPARATOR).append("\n");

        boolean hasCart = cart != null && !cart.items.isEmpty();
        if (hasCart) {
            sb.append("🛒 <b>К списанию/возврату:</b>\n");
            for (int i = 0; i < cart.items.size(); i++) {
                CartItem item = cart.items.get(i);
                sb.append(i + 1).append(". ").append(Html.esc(item.materialName())).append(" — ")
                        .append(qty(item.quantity())).append(" ").append(Html.esc(item.unit())).append("\n");
            }
            sb.append("\n");
        }
        if (held.isEmpty()) {
            if (!hasCart) sb.append("🧰 У вас на руках нет материалов.");
        } else {
            sb.append("📝 <b>Выберите материал для добавления в список:</b>");
        }

        Keyboards.Rows rows = Keyboards.rows();
        if (hasCart) {
            rows.add(btn("✅ ПЕРЕЙТИ К ОФОРМЛЕНИЮ", "WO_CHECKOUT"));
            rows.add(btn("🗑 Очистить список", "WO_CLEAR_CART"));
        }
        for (HeldMaterial m : held) {
            rows.add(btn(String.format("👉 %s [%s] (%s %s)", Formats.cut(m.name(), 30), m.code(), qty(m.quantity()), m.unit()),
                    "WO_MAT:" + m.materialId()));
        }
        rows.add(btn("❌ Закрыть", Keyboards.GENERIC_CLOSE));
        return new Screen(sb.toString(), rows.build());
    }

    /** Ввод количества выбранного материала. В «Всё» callback содержит Double.toString остатка (как раньше). */
    static Screen quantityPrompt(PendingItem item) {
        InlineKeyboardMarkup markup = Keyboards.of(
                row(btn("1", "WO_Q:1"), btn("2", "WO_Q:2"), btn("5", "WO_Q:5"), btn("10", "WO_Q:10")),
                row(btn("Всё (" + qty(item.maxAvailable()) + " " + item.unit() + ")", "WO_Q:" + item.maxAvailable())),
                row(btn("🔙 Назад к списку", "WO_BACK_LIST"), btn("❌ Отменить", Keyboards.CANCEL_PROMPT)));
        String text = String.format("Выбрано: <b>%s</b>\nДоступно к добавлению: <b>%s %s</b>\n\n✍️ Напишите количество вручную ИЛИ выберите быстрый вариант:",
                Html.esc(item.materialName()), qty(item.maxAvailable()), Html.esc(item.unit()));
        return new Screen(text, markup);
    }

    static Screen typeChoice(int itemCount) {
        InlineKeyboardMarkup markup = Keyboards.of(
                row(btn("🧾 Списать по квитанции", "WO_TYPE:PAID")),
                row(btn("🛠 Техническое списание", "WO_TYPE:FREE")),
                row(btn("↩️ Вернуть на склад", "WO_TYPE:RETURN")),
                row(btn("🔙 Назад к списку", "WO_BACK_LIST"), btn("❌ Отменить", Keyboards.CANCEL_PROMPT)));
        return new Screen(String.format("Выбрано позиций: <b>%d</b>.\nЧто делаем с этими материалами?", itemCount), markup);
    }

    /** Клавиатура под шагами ввода реквизитов. */
    static InlineKeyboardMarkup stepKeyboard() {
        return Keyboards.of(row(btn("🔙 Назад к списку", "WO_BACK_LIST"), btn("❌ Отменить", Keyboards.CANCEL_PROMPT)));
    }

    /** Выбор кода закрытия; {@code warning} — предупреждение о повторном закрытии (или пустая строка). */
    static Screen closingCodes(String warning) {
        InlineKeyboardMarkup markup = Keyboards.of(
                row(btn("212 — Участок ОРК–ОРА (не чаще 6 мес!)", "WO_CODE:212")),
                row(btn("227 — Выправление волокна (повтор)", "WO_CODE:227")),
                row(btn("215 — В ОРШ: выправление пигтейла", "WO_CODE:215")),
                row(btn("226 — В ОРШ: замена адаптера", "WO_CODE:226")),
                row(btn("214 — Участок ОРШ–ОРК: ремонт райзера", "WO_CODE:214")),
                row(btn("217 — Участок ОРШ–ОРК: запасной модуль", "WO_CODE:217")),
                row(btn("🔙 Назад к списку", "WO_BACK_LIST"), btn("❌ Отменить", Keyboards.CANCEL_PROMPT)));
        return new Screen("🔢 <b>Шаг 4 из 5:</b> Выберите <b>код закрытия заявки</b>:" + warning, markup);
    }

    /** Причина технического списания по коду закрытия. */
    static String reasonFor(String closingCode) {
        return switch (closingCode) {
            case "212" -> "Ремонт ВОК на участке ОРК-ОРА";
            case "227" -> "Выправление волокна";
            case "215" -> "В ОРШ: выправление пигтейла/волокна";
            case "226" -> "В ОРШ: замена пигтейла / адаптера";
            case "214" -> "Участок ОРШ-ОРК: ремонт/замена райзера";
            case "217" -> "Участок ОРШ-ОРК: запасной модуль";
            default -> "Тех. списание (Код " + closingCode + ")";
        };
    }

    /** Окно согласования возврата для администраторов. */
    static InlineKeyboardMarkup returnApprovalKeyboard(int requestId) {
        return Keyboards.of(row(btn("✅ Принять на склад", "RET_APP:" + requestId), btn("❌ Отклонить", "RET_REJ:" + requestId)));
    }
}
