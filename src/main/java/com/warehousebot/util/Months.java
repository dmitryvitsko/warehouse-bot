package com.warehousebot.util;

/** Названия месяцев для интерфейса. */
public final class Months {

    private static final String[] NOMINATIVE = {"", "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
            "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"};

    private Months() {
    }

    public static String name(int month) {
        return NOMINATIVE[month];
    }
}
