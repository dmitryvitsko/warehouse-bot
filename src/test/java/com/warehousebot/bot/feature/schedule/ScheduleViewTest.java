package com.warehousebot.bot.feature.schedule;

import com.warehousebot.domain.ShiftDay;
import com.warehousebot.service.ScheduleService.PartnerShift;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduleViewTest {

    @Test
    void partnersTextDropsOnlyTheLastSeparator() {
        List<PartnerShift> shifts = List.of(
                new PartnerShift(LocalDate.of(2026, 11, 7), true, false, List.of("Белевич А.А."), List.of("Иванов И.И.")),
                new PartnerShift(LocalDate.of(2026, 11, 10), false, true, List.of(), List.of()));
        String expected = "🤝 <b>Ваши напарники (текущий месяц):</b>\n\n"
                + "🔹 <b>07.11 (Сб) | Рабочая суббота</b>\n"
                + "▪ 👑 <b>Белевич А.А.</b>    ▪ Иванов И.И.\n"
                + "┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈\n"
                + "🔹 <b>10.11 (Вт) | Вторая смена</b>  👑 <i>(Вы — старший смены!)</i>\n"
                + " ▪ <i>(Никого не найдено)</i>";
        assertEquals(expected, ScheduleView.partnersText(shifts));
    }

    @Test
    void saturdayRosterShowsCrownAndDuty() {
        LocalDate saturday = LocalDate.of(2026, 11, 7);
        List<ShiftDay> rows = List.of(
                new ShiftDay("Витько В.В.", 7, "", "08:00", "16:00"),
                new ShiftDay("Петров <П>", 7, "", "08:00", "16:00"),
                new ShiftDay("Сидоров С.С.", 7, "В", "", ""));
        String expected = "📅 <b>Сводка на сегодня (07.11.2026, Сб):</b>\n\n"
                + "☀️ <b>Рабочая смена:</b>\n"
                + "▪️ Витько В.В. 👑 (08:00 - 16:00)\n"
                + "▪️ Петров &lt;П&gt; (08:00 - 16:00)\n\n"
                + "🚨 <b>Дежурство:</b>\n▪️ Дежурных нет.";
        assertEquals(expected, ScheduleView.rosterText(ScheduleView.todayHeader(saturday), "-", saturday, rows));
    }
}
