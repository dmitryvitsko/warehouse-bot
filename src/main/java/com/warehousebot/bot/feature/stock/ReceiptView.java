package com.warehousebot.bot.feature.stock;

import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.bot.feature.stock.StockSessions.Screen;
import com.warehousebot.domain.HeldMaterial;
import com.warehousebot.domain.ReceiptLine;
import com.warehousebot.domain.Tariff;
import com.warehousebot.util.Formats;
import com.warehousebot.util.Html;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;
import java.util.Locale;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.util.Formats.qty;

/** Экраны калькулятора квитанции и текст «📋 Тарифы услуг». */
final class ReceiptView {

    static final InlineKeyboardMarkup BACK_TO_CART = Keyboards.single("🔙 Назад в корзину", "CART_MENU");

    private ReceiptView() {
    }

    /** Корзина квитанции: список позиций и действия. */
    static Screen cart(List<ReceiptLine> items) {
        StringBuilder sb = new StringBuilder("🧾 <b>Калькулятор квитанции</b>\n\nВ квитанции позиций: <b>").append(items.size()).append("</b>\n\n");
        if (!items.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                ReceiptLine l = items.get(i);
                sb.append(i + 1).append(". ").append(Html.esc(l.name())).append(" (").append(qty(l.quantity())).append(" ")
                        .append(Html.esc(l.unit())).append(")\n");
            }
            sb.append("\nЧто делаем дальше?");
        } else {
            sb.append("Добавьте работы и материалы.");
        }

        Keyboards.Rows rows = Keyboards.rows().add(btn("➕ Услугу", "CART_ADD_SRV"), btn("➕ Материал", "CART_ADD_MAT"));
        if (!items.isEmpty()) {
            rows.add(btn("✅ РАССЧИТАТЬ ИТОГИ", "CART_FINISH"));
            rows.add(btn("🗑 Очистить", "CART_CLEAR"));
        }
        rows.add(btn("❌ Закрыть", Keyboards.GENERIC_CLOSE));
        return new Screen(sb.toString(), rows.build());
    }

    static Screen serviceChoice(List<Tariff> services) {
        Keyboards.Rows rows = Keyboards.rows();
        for (Tariff t : services) rows.add(btn("🛠 " + Formats.cut(t.name(), 35), "CART_SEL_SRV:" + t.id()));
        rows.add(btn("🔙 Назад в корзину", "CART_MENU"));
        return new Screen("🛠 <b>Выберите выполненную услугу:</b>", rows.build());
    }

    static Screen materialChoice(List<HeldMaterial> held) {
        Keyboards.Rows rows = Keyboards.rows();
        for (HeldMaterial m : held) {
            rows.add(btn("📦 " + Formats.cut(m.name(), 30) + " (" + qty(m.quantity()) + " " + m.unit() + ")", "CART_SEL_MAT:" + m.materialId()));
        }
        rows.add(btn("🔙 Назад в корзину", "CART_MENU"));
        return new Screen("📦 <b>Выберите использованный материал:</b>", rows.build());
    }

    /** Итоговая акт-квитанция: работы и материалы с НДС 20% (бывший generateReceiptText). */
    static String receiptText(List<ReceiptLine> items) {
        if (items.isEmpty()) return "🛒 Корзина квитанции пуста.";
        StringBuilder sb = new StringBuilder("🧾 <b>АКТ-КВИТАНЦИЯ (Расчет)</b>\n\n");

        sb.append("🛠 <b>ВЫПОЛНЕННЫЕ РАБОТЫ:</b>\n");
        double[] services = appendSection(sb, items, false);
        if (services == null) {
            sb.append("<i>Услуги не добавлялись</i>\n");
            services = new double[2];
        }
        sb.append("➖➖➖➖➖➖➖➖➖➖➖➖\n");
        sb.append(String.format("Итого по работам: <b>%.2f руб.</b>\n(В т.ч. НДС 20%%: %.2f руб.)\n\n", services[0], services[1]));

        sb.append("📦 <b>ИЗРАСХОДОВАННЫЕ МАТЕРИАЛЫ:</b>\n");
        double[] materials = appendSection(sb, items, true);
        if (materials == null) {
            sb.append("<i>Материалы не добавлялись</i>\n");
            materials = new double[2];
        }
        sb.append("➖➖➖➖➖➖➖➖➖➖➖➖\n");
        sb.append(String.format("Итого по материалам: <b>%.2f руб.</b>\n(В т.ч. НДС 20%%: %.2f руб.)\n\n", materials[0], materials[1]));

        sb.append(String.format("💰 <b>ВСЕГО К ОПЛАТЕ: %.2f руб.</b>", services[0] + materials[0]));
        return sb.toString();
    }

    /** Строки одного раздела; возвращает {сумма с НДС, в т.ч. НДС} или {@code null}, если строк нет. */
    private static double[] appendSection(StringBuilder sb, List<ReceiptLine> items, boolean material) {
        double total = 0;
        double vat = 0;
        boolean any = false;
        for (ReceiptLine item : items) {
            if (item.material() != material) continue;
            any = true;
            double sumWithVat = item.quantity() * item.priceWithVat();
            double vatSum = sumWithVat - (sumWithVat / 1.20);
            total += sumWithVat;
            vat += vatSum;
            String unit = material ? item.unit() : shortUnit(item.unit());
            sb.append(String.format("🔹 %s\n ┝ %s %s  х  %.2f  =  <b>%.2f руб.</b>\n", Html.esc(item.name()), qty(item.quantity()),
                    Html.esc(unit), item.priceWithVat(), sumWithVat));
        }
        return any ? new double[]{total, vat} : null;
    }

    /** Единица услуги в квитанции: «услуга»/«отверстие»/«устройство» → шт, «метр» → м. */
    private static String shortUnit(String fullUnit) {
        String u = fullUnit.toLowerCase();
        if (u.contains("услуг") || u.contains("отверст") || u.contains("устройств")) return "шт";
        if (u.contains("метр")) return "м";
        return fullUnit;
    }

    // ------------------------------------------------------------------ тарифы

    /** «📋 Тарифы услуг»: разделы определяются порядковым номером тарифа. */
    static String tariffsText(List<Tariff> tariffs) {
        StringBuilder sb = new StringBuilder("📋 <b>Утвержденные тарифы (с НДС):</b>\n");
        int currentCategory = -1;
        int rowNum = 1;
        for (Tariff t : tariffs) {
            int category = categoryGroup(rowNum);
            if (category != currentCategory) {
                sb.append("\n").append(categoryHeader(category)).append("\n");
                currentCategory = category;
            }
            String priceStr = t.priceWithVat() == 0 ? "<i>(не задана)</i>" : String.format(Locale.US, "<b>%.2f руб.</b>", t.priceWithVat());
            sb.append(String.format(" ▪ %s (<i>%s</i>) — %s\n", Html.esc(t.name()), Html.esc(t.unit()), priceStr));
            rowNum++;
        }
        return sb.toString();
    }

    private static int categoryGroup(int rowNum) {
        if (rowNum <= 2) return 1;
        if (rowNum <= 6) return 2;
        if (rowNum <= 9) return 3;
        if (rowNum <= 13) return 4;
        if (rowNum <= 15) return 5;
        if (rowNum <= 17) return 6;
        return 7;
    }

    private static String categoryHeader(int group) {
        return switch (group) {
            case 1 -> "🛠 <b>ОБЩИЕ УСЛУГИ</b>";
            case 2 -> "🔌 <b>РОЗЕТКИ И ШНУРЫ</b>";
            case 3 -> "🔄 <b>ЗАМЕНА ПРОВОДКИ (ЦЕЛИКОМ)</b>";
            case 4 -> "✂ <b>ОКОНЕЧИВАНИЕ И СВАРКА</b>";
            case 5 -> "🕳 <b>ОТВЕРСТИЯ И КРЕПЛЕНИЕ</b>";
            case 6 -> "🗑 <b>ДЕМОНТАЖ</b>";
            case 7 -> "📏 <b>ПРОКЛАДКА КАБЕЛЯ И КОРОБОВ</b>";
            default -> "🔹 <b>ПРОЧИЕ УСЛУГИ</b>";
        };
    }
}
