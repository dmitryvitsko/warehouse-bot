package com.warehousebot.bot.feature.schedule;

import com.warehousebot.service.ScheduleService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор дат, введённых пользователем: «15.11», «15.11.26», «15.11.2026», диапазоны «01.11-07.11»
 * и их сочетания через запятую («10.11, 14.11-19.11»), а также пакетный ввод через «;» со временем или кодом статуса
 * («17.10-21.10.2026 8:30-18:00; 22.10, 30.10 В; 31.10.26 12:00-21:00»).
 */
final class DateInputParser {

    /** Шаблон для поиска времени в конце блока: например "17.10-21.10 8:30-16:15" */
    private static final Pattern TIME_SUFFIX = Pattern.compile(
            "^(.+?)\\s+(\\d{1,2})[:.](\\d{2})\\s*-\\s*(\\d{1,2})[:.](\\d{2})$"
    );

    /** Шаблон для поиска кода статуса в конце блока: например "22.10, 30.10 В" */
    private static final Pattern STATUS_SUFFIX = Pattern.compile(
            "^(.+?)\\s+([А-Яа-яA-Za-z])$"
    );

    /** Одна команда изменения: список дат + либо статус, либо время начала/конца. */
    record ScheduleEntry(List<LocalDate> dates, String statusCode, String startTime, String endTime) {
        String label() {
            return (statusCode != null && !statusCode.isEmpty()) ? statusCode : (startTime + "-" + endTime);
        }
    }

    /**
     * Результат разбора ввода в редакторе графика:
     * - если {@code entries} не пуст — это готовый пакет команд со временем/статусами (применяем сразу);
     * - иначе {@code datesOnly} содержит даты для выбора смены кнопкой.
     */
    record ParsedEditInput(List<ScheduleEntry> entries, List<LocalDate> datesOnly) {
        boolean isImmediateBatch() {
            return entries != null && !entries.isEmpty();
        }
    }

    private DateInputParser() {
    }

    /**
     * Разбирает ввод редактора графика:
     * 1) Если есть «;» или в конце указано время / код статуса — возвращает пакет {@link ScheduleEntry}.
     * 2) Если введены просто даты без «;» и без смены — возвращает {@code datesOnly} для показа кнопок.
     */
    static Optional<ParsedEditInput> parseEditInput(String input, LocalDate today) {
        if (input == null) return Optional.empty();
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return Optional.empty();

        // Если в строке несколько блоков через «;», каждый блок обязан иметь время или статус
        if (trimmed.contains(";")) {
            String[] chunks = trimmed.split(";");
            List<ScheduleEntry> entries = new ArrayList<>();
            for (String chunk : chunks) {
                String c = chunk.trim();
                if (c.isEmpty()) continue;
                Optional<ScheduleEntry> entry = parseSingleEntryWithShift(c, today);
                if (entry.isEmpty()) return Optional.empty();
                entries.add(entry.get());
            }
            if (entries.isEmpty()) return Optional.empty();
            return Optional.of(new ParsedEditInput(entries, List.of()));
        }

        // Один блок: сначала проверяем, нет ли в конце времени или буквы статуса
        Optional<ScheduleEntry> singleWithShift = parseSingleEntryWithShift(trimmed, today);
        if (singleWithShift.isPresent()) {
            return Optional.of(new ParsedEditInput(List.of(singleWithShift.get()), List.of()));
        }

        // Иначе это просто даты (например, "17.10" или "17.10-21.10" или "22.10, 30.10") — для выбора кнопкой
        return parse(trimmed, today).map(dates -> new ParsedEditInput(List.of(), dates));
    }

    /** Пытается разобрать один блок вида «даты время» или «даты КОД». */
    private static Optional<ScheduleEntry> parseSingleEntryWithShift(String chunk, LocalDate today) {
        Matcher timeMatcher = TIME_SUFFIX.matcher(chunk);
        if (timeMatcher.matches()) {
            String datePart = timeMatcher.group(1).trim();
            int h1 = Integer.parseInt(timeMatcher.group(2));
            int min1 = Integer.parseInt(timeMatcher.group(3));
            int h2 = Integer.parseInt(timeMatcher.group(4));
            int min2 = Integer.parseInt(timeMatcher.group(5));

            if (h1 > 23 || min1 > 59 || h2 > 23 || min2 > 59) {
                return Optional.empty();
            }
            String start = String.format("%02d:%02d", h1, min1);
            String end = String.format("%02d:%02d", h2, min2);
            return parse(datePart, today).map(dates -> new ScheduleEntry(dates, "", start, end));
        }

        Matcher statusMatcher = STATUS_SUFFIX.matcher(chunk);
        if (statusMatcher.matches()) {
            String datePart = statusMatcher.group(1).trim();
            String code = statusMatcher.group(2).toUpperCase(Locale.ROOT);
            if (!ScheduleService.STATUS_CODES.contains(code)) {
                return Optional.empty();
            }
            return parse(datePart, today).map(dates -> new ScheduleEntry(dates, code, "", ""));
        }

        return Optional.empty();
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
            return Optional.empty();
        }
        if (dates.isEmpty()) return Optional.empty();
        return Optional.of(new ArrayList<>(dates));
    }

    /**
     * Одна дата: «10.11», «10.11.26» или «10.11.2026». Двузначный год — 20xx.
     * Без года: в декабре «январь» означает январь следующего года.
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