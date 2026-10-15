package com.warehousebot.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Форматирование количеств и цен (ровно как в прежнем DatabaseManager.fmtQty/fmtPrice). */
public final class Formats {

    private static final ThreadLocal<DecimalFormat> QTY = ThreadLocal.withInitial(
            () -> new DecimalFormat("#.####", DecimalFormatSymbols.getInstance(Locale.US)));
    private static final ThreadLocal<DecimalFormat> PRICE = ThreadLocal.withInitial(
            () -> new DecimalFormat("0.00##", DecimalFormatSymbols.getInstance(Locale.US)));

    private Formats() {
    }

    public static String qty(double v) {
        if (Math.abs(v) < 1e-9) return "0";
        return QTY.get().format(v);
    }

    public static String price(double v) {
        return PRICE.get().format(v);
    }

    /** Обрезает строку до {@code max} символов, добавляя многоточие. */
    public static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    /** Разбирает число, введённое пользователем: допускает запятую как разделитель. */
    public static double parseUserNumber(String text) {
        return Double.parseDouble(text.trim().replace(",", "."));
    }
}
