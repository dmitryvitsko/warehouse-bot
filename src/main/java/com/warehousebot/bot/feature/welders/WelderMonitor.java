package com.warehousebot.bot.feature.welders;

import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.feature.welders.WeldersView.Screen;
import com.warehousebot.domain.WelderAssignment;
import com.warehousebot.service.WelderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * Бот-надзиратель: за 0–15 минут до конца смены (по графику schedules_v2) блокирует меню сотрудника,
 * у которого числится сварочный аппарат, пока он не отчитается за прибор.
 */
final class WelderMonitor implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(WelderMonitor.class);

    /** Предупреждаем, если до конца смены осталось от 0 до 15 минут. */
    private static final long WARNING_WINDOW_MINUTES = 15;

    private final WelderService welders;
    private final WelderLocks locks;
    private final Messenger messenger;

    WelderMonitor(WelderService welders, WelderLocks locks, Messenger messenger) {
        this.welders = welders;
        this.locks = locks;
        this.messenger = messenger;
    }

    @Override
    public void run() {
        try {
            check(LocalDate.now(), LocalTime.now());
        } catch (Exception e) {
            // Ошибка не должна останавливать периодическую задачу.
            log.error("Ошибка проверки сварочных аппаратов", e);
        }
    }

    private void check(LocalDate today, LocalTime now) {
        for (WelderAssignment a : welders.inUse()) {
            if (locks.isReturnLocked(a.userId())) continue;   // уже заблокирован
            if (locks.keptOn(a.userId(), today)) continue;     // сегодня уже оставил аппарат на завтра

            LocalTime shiftEnd = parseTimeSafe(welders.shiftEnd(a.fullName(), today).orElse(null));
            if (shiftEnd == null) continue;
            long minutesLeft = minutesLeft(now, shiftEnd);
            if (!isWarningDue(minutesLeft)) continue;

            // Блокировка ставится до отправки: иначе быстрый ответ на кнопку мог бы прийти раньше блокировки.
            locks.lockForReturn(a.userId(), a.welderId());
            Screen warning = WeldersView.endOfShiftWarning(a.welderId(), a.welderName(), minutesLeft);
            Message sent = messenger.sendPinned(a.userId(), warning.text(), warning.markup());
            // Предупреждение не доставлено — без кнопок снять блокировку было бы нечем.
            if (sent == null) locks.releaseReturnLock(a.userId());
        }
    }

    /** Полных минут от {@code now} до конца смены (может быть отрицательным). */
    static long minutesLeft(LocalTime now, LocalTime shiftEnd) {
        return ChronoUnit.MINUTES.between(now, shiftEnd);
    }

    static boolean isWarningDue(long minutesLeft) {
        return minutesLeft >= 0 && minutesLeft <= WARNING_WINDOW_MINUTES;
    }

    /** Время из Excel: «17:30», «17.30», «9:00», «17:30:00»; иначе {@code null}. */
    static LocalTime parseTimeSafe(String t) {
        if (t == null || t.trim().isEmpty()) return null;
        try {
            t = t.trim().replace(".", ":");
            if (t.length() == 4) t = "0" + t;              // «9:30» → «09:30»
            if (t.length() > 5) t = t.substring(0, 5);    // отбрасываем секунды
            return LocalTime.parse(t);
        } catch (Exception e) {
            return null;
        }
    }
}
