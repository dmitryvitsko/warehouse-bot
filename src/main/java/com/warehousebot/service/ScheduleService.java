package com.warehousebot.service;

import com.warehousebot.domain.ShiftDay;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.excel.ExcelParser;
import com.warehousebot.repository.ScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** График работ: просмотр, напарники по сменам, редактирование, новый сотрудник, загрузка из Excel. */
public final class ScheduleService implements ScheduleFacade {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    /** Коды статусов «не рабочая смена»: выходной, отпуск, дежурство, больничный, за свой счёт, военкомат, др. подразделение. */
    public static final Set<String> STATUS_CODES = Set.of("В", "О", "Д", "Б", "А", "Г", "П");

    /** Ранг «нет в списке старших». */
    public static final int NO_RANK = 99;

    private final Database db;
    private final ScheduleRepository schedules;

    public ScheduleService(Database db, ScheduleRepository schedules) {
        this.db = db;
        this.schedules = schedules;
    }

    /**
     * Рабочая суббота или вторая смена сотрудника и его напарники в этот день.
     *
     * @param seniors  старшие смены (👑), кроме самого сотрудника
     * @param partners остальные напарники
     */
    public record PartnerShift(LocalDate date, boolean saturday, boolean iAmSenior, List<String> seniors, List<String> partners) {
    }

    public enum AddResult { ADDED, ALREADY_EXISTS }

    // ------------------------------------------------------------------ просмотр

    /** График сотрудника на месяц (дни, которых нет в месяце, пропускаются). */
    public List<ShiftDay> month(String excelName, int year, int month) {
        return validDays(schedules.findMonth(excelName, year, month), year, month);
    }

    /** Все сотрудники на дату, по фамилии. */
    public List<ShiftDay> day(LocalDate date) {
        return schedules.findDay(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    /**
     * Рабочие субботы и вторые смены сотрудника в месяце {@code today} с напарниками.
     * График месяца читается одним запросом (раньше — отдельный запрос на каждый день).
     */
    public List<PartnerShift> partnerShifts(String excelName, LocalDate today) {
        int year = today.getYear();
        int month = today.getMonthValue();

        Map<Integer, List<ShiftDay>> workersByDay = new LinkedHashMap<>();
        List<ShiftDay> myDays = new ArrayList<>();
        for (ShiftDay d : validDays(schedules.findMonthAll(year, month), year, month)) {
            if (!isWorkingShift(d)) continue;
            workersByDay.computeIfAbsent(d.day(), k -> new ArrayList<>()).add(d);
            if (d.excelName().equals(excelName)) myDays.add(d);
        }

        List<PartnerShift> result = new ArrayList<>();
        for (ShiftDay mine : myDays) {
            LocalDate date = LocalDate.of(year, month, mine.day());
            boolean saturday = date.getDayOfWeek() == DayOfWeek.SATURDAY;
            boolean second = isSecondShift(mine.startTime(), mine.endTime());
            if (!saturday && !second) continue;

            List<String> shift = new ArrayList<>();
            for (ShiftDay w : workersByDay.get(mine.day())) {
                if (saturday || isSecondShift(w.startTime(), w.endTime())) shift.add(w.excelName());
            }
            result.add(partnerShift(excelName, date, saturday, shift));
        }
        return result;
    }

    private static PartnerShift partnerShift(String excelName, LocalDate date, boolean saturday, List<String> shift) {
        int minRank = NO_RANK;
        for (String w : shift) minRank = Math.min(minRank, seniorityRank(w));

        boolean iAmSenior = false;
        List<String> seniors = new ArrayList<>();
        List<String> partners = new ArrayList<>();
        for (String w : shift) {
            boolean senior = seniorityRank(w) == minRank && minRank != NO_RANK;
            if (w.equals(excelName)) {
                if (senior) iAmSenior = true;
            } else if (senior) {
                seniors.add(w);
            } else {
                partners.add(w);
            }
        }
        return new PartnerShift(date, saturday, iAmSenior, seniors, partners);
    }

    /** Рабочая смена (не статус). NULL в status_code, как и в SQL «NOT IN», рабочей сменой не считается. */
    private static boolean isWorkingShift(ShiftDay d) {
        return d.statusCode() != null && !STATUS_CODES.contains(d.statusCode());
    }

    /** Вторая смена: заканчивается в 21:00 или начинается с 11 до 14 часов. */
    public static boolean isSecondShift(String start, String end) {
        if (end != null && (end.contains("21:00") || end.contains("21.00"))) return true;
        return startsInSecondShiftHours(start);
    }

    /** Начало смены с 11:00 до 14:59. */
    public static boolean startsInSecondShiftHours(String start) {
        return start != null && (start.startsWith("11:") || start.startsWith("12:") || start.startsWith("13:") || start.startsWith("14:"));
    }

    /** Старшинство в смене: чем меньше, тем старше; {@link #NO_RANK} — обычный сотрудник. */
    public static int seniorityRank(String excelName) {
        if (excelName == null) return NO_RANK;
        String lower = excelName.toLowerCase(Locale.ROOT);
        if (lower.contains("прищепчик") || lower.contains("витько")) return 1;
        if (lower.contains("белевич")) return 2;
        if (lower.contains("шаметько")) return 3;
        if (lower.contains("максимов")) return 4;
        return NO_RANK;
    }

    /** Строки базы с днями, которых нет в месяце (например, 31 февраля), не показываются. */
    private static List<ShiftDay> validDays(List<ShiftDay> days, int year, int month) {
        int length = YearMonth.of(year, month).lengthOfMonth();
        List<ShiftDay> valid = new ArrayList<>(days.size());
        for (ShiftDay d : days) {
            if (d.day() >= 1 && d.day() <= length) valid.add(d);
        }
        return valid;
    }

    // ------------------------------------------------------------------ изменения

    /** Ставит одинаковую смену/статус на все даты одной транзакцией. */
    public void setShifts(String excelName, List<LocalDate> dates, String statusCode, String startTime, String endTime) {
        db.inTransaction(() -> {
            for (LocalDate d : dates) {
                schedules.upsert(excelName, d.getYear(), d.getMonthValue(), d.getDayOfMonth(), statusCode, startTime, endTime);
            }
        });
    }

    /** Добавляет сотрудника в график текущего месяца: все дни — выходные («В»). */
    public AddResult addEmployee(String excelName, LocalDate today) {
        int year = today.getYear();
        int month = today.getMonthValue();
        return db.inTransaction(() -> {
            if (schedules.existsInMonth(excelName, year, month)) return AddResult.ALREADY_EXISTS;
            List<ShiftDay> days = new ArrayList<>();
            for (int day = 1; day <= today.lengthOfMonth(); day++) days.add(new ShiftDay(excelName, day, "В", "", ""));
            schedules.insertAll(days, year, month);
            return AddResult.ADDED;
        });
    }

    // ------------------------------------------------------------------ загрузка

    @Override
    public String importSchedule(File file, int year, int month) throws Exception {
        List<ExcelParser.ScheduleRow> parsed = ExcelParser.parseSchedule(file);
        int length = YearMonth.of(year, month).lengthOfMonth();

        List<ShiftDay> days = new ArrayList<>(parsed.size());
        int skipped = 0;
        for (ExcelParser.ScheduleRow r : parsed) {
            if (r.day() > length) {
                skipped++;
                continue;
            }
            days.add(new ShiftDay(r.excelName(), r.day(), r.statusCode(), r.startTime(), r.endTime()));
        }
        if (skipped > 0) log.warn("График {}.{}: пропущено {} дней за пределами месяца", month, year, skipped);

        try {
            db.inTransaction(() -> schedules.upsertAll(days, year, month));
        } catch (DataAccessException e) {
            log.error("Ошибка сохранения графика", e);
            return "❌ Ошибка при сохранении графика: " + e.getMessage();
        }
        return "✅ <b>График успешно загружен/обновлен!</b>\nДанные за " + month + "." + year + " сохранены в базе.";
    }
}
