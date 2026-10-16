package com.warehousebot.bot.feature.schedule;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор дат, введённых пользователем: «15.11», «15.11.26», «15.11.2026», диапазоны «01.11-07.11»
 * и их сочетания через запятую («10.11, 14.11-19.11»), а также опциональное время смены в конце
 * («17.10 8:30-16:15» или «17.10-21.10 08:30-17:30»). Без состояния и без Telegram.
 */
final class DateInputParser {

    /** Шаблон для поиска времени в конце строки: например " 8:30-16:15" или " 08.30 - 17.30" */
    private static final Pattern TIME_SUFFIX = Pattern.compile(
            "^(.+?)\\s+(\\d{1,2})[:.](\\d{2})\\s*-\\s*(\\d{1,2})[:.](\\d{2})$"
    );

    /** Результат разбора для редактора графика: список дат + время начала/конца (если было введено). */
    record ParsedScheduleInput(List<LocalDate> dates, String startTime, String endTime) {
        boolean hasTime() {
            return startTime != null && endTime != null;
        }
    }

    private DateInputParser() {
    }

    /**
     * Разбирает строку для редактора графика: поддерживает как просто даты («17.10-21.10»),
     * так и даты сразу со временем («17.10 8:30-16:15», «17.10-21.10 08:30-17:30»).
     */
    static Optional<ParsedScheduleInput> parseWithOptionalTime(String input, LocalDate today) {
        if (input == null) return Optional.empty();
        String trimmed = input.trim();
        Matcher m = TIME_SUFFIX.matcher(trimmed);

        if (m.matches()) {
            String datePart = m.group(1).trim();
            int h1 = Integer.parseInt(m.group(2));
            int min1 = Integer.parseInt(m.group(3));
            int h2 = Integer.parseInt(m.group(4));
            int min2 = Integer.parseInt(m.group(5));

            if (h1 > 23 || min1 > 59 || h2 > 23 || min2 > 59) {
                return Optional.empty();
            }

            String start = String.format("%02d:%02d", h1, min1);
            String end = String.format("%02d:%02d", h2, min2);

            return parse(datePart, today)
                    .map(dates -> new ParsedScheduleInput(dates, start, end));
        }

        return parse(trimmed, today)
                .map(dates -> new ParsedScheduleInput(dates, null, null));
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
                    if (parts.length != 2) return Optional.empty();
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