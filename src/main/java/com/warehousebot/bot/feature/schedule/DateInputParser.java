package com.warehousebot.bot.feature.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Разбор дат, введённых пользователем: «15.11», «15.11.26», «15.11.2026», диапазоны «01.11-07.11»
 * и их сочетания через запятую («10.11, 14.11-19.11»). Без состояния и без Telegram.
 */
final class DateInputParser {

    private DateInputParser() {
    }

    /**
     * Все даты из ввода — без повторов, по возрастанию. Пусто, если хоть один элемент не распознан,
     * диапазон перевёрнут (кроме перехода декабрь → январь) или дат нет вовсе.
     *
     * @param today текущая дата: год по умолчанию и переход через Новый год
     */
    static Optional<List<LocalDate>> parse(String input, LocalDate today) {
        int currentYear = today.getYear();
        int currentMonth = today.getMonthValue();
        SortedSet<LocalDate> dates = new TreeSet<>();
        try {
            for (String raw : input.split(",")) {
                String item = raw.trim();
                if (item.isEmpty()) continue;

                if (item.contains("-")) {
                    String[] parts = item.split("-");
                    LocalDate start = parseSmartDate(parts[0].trim(), currentYear, currentMonth);
                    LocalDate end = parseSmartDate(parts[1].trim(), currentYear, currentMonth);
                    if (start.isAfter(end)) {
                        // Легитимен только переход через Новый год (28.12-03.01), иначе это опечатка.
                        if (start.getMonthValue() == 12 && end.getMonthValue() == 1) end = end.plusYears(1);
                        else return Optional.empty();
                    }
                    for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                        dates.add(d);
                    }
                } else {
                    dates.add(parseSmartDate(item, currentYear, currentMonth));
                }
            }
        } catch (RuntimeException e) {
            // NumberFormatException, DateTimeException, нет второй половины диапазона — всё это ошибка ввода
            return Optional.empty();
        }
        if (dates.isEmpty()) return Optional.empty();
        return Optional.of(new ArrayList<>(dates));
    }

    /**
     * Одна дата: «10.11», «10.11.26» или «10.11.2026». Двузначный год — 20xx.
     * Без года: в декабре «январь» означает январь следующего года.
     *
     * @throws RuntimeException если дата не распознана
     */
    static LocalDate parseSmartDate(String input, int currentYear, int currentMonth) {
        String[] parts = input.trim().split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("Неверный формат даты");

        int day = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int year = currentYear;

        if (parts.length == 3) {
            year = Integer.parseInt(parts[2]);
            if (year < 100) year += 2000;
        } else if (currentMonth == 12 && month == 1) {
            year++;
        } else if (currentMonth == 1 && month == 12) {
            year--; // в январе «29.12» — это декабрь прошлого года
        }
        return LocalDate.of(year, month, day);
    }
}
