package com.warehousebot.service;

import com.warehousebot.domain.Welder;
import com.warehousebot.domain.WelderAssignment;
import com.warehousebot.domain.WelderHistoryEntry;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.repository.WelderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Выдача, возврат и передача сварочных аппаратов.
 *
 * <p>Каждое изменение статуса выполняется в одной транзакции вместе с записью в журнал
 * и только из ожидаемого состояния (защита от двойных нажатий и гонок между чатами).
 * Ошибки БД, как и раньше, превращаются в {@code false}.
 */
public final class WelderService {

    private static final Logger log = LoggerFactory.getLogger(WelderService.class);

    public static final String TAKEN = "TAKEN";
    public static final String RETURNED = "RETURNED";
    public static final String FORCE_TAKEN = "FORCE_TAKEN";
    public static final String FORCE_RETURNED = "FORCE_RETURNED";

    private static final int HISTORY_LIMIT = 30;
    private static final String UNKNOWN_WELDER = "Неизвестный аппарат";

    private final Database db;
    private final WelderRepository welders;

    public WelderService(Database db, WelderRepository welders) {
        this.db = db;
        this.welders = welders;
    }

    public List<Welder> welders() {
        return welders.findAll();
    }

    public String nameOf(int welderId) {
        return welders.findName(welderId).orElse(UNKNOWN_WELDER);
    }

    /** Сотрудник берёт аппарат с базы. {@code false} — аппарат уже занят. */
    public boolean take(int welderId, long userId) {
        return assign(welderId, userId, null);
    }

    /** Администратор принудительно вешает свободный аппарат на сотрудника. */
    public boolean forceAssign(int welderId, long userId, long adminId) {
        return assign(welderId, userId, adminId);
    }

    private boolean assign(int welderId, long userId, Long adminId) {
        return guarded(() -> {
            if (welders.assign(welderId, userId) == 0) return false;
            welders.addHistory(welderId, userId, adminId == null ? TAKEN : FORCE_TAKEN, adminId);
            return true;
        });
    }

    /** Сотрудник возвращает на базу аппарат, который числится за ним. */
    public boolean returnToBase(int welderId, long userId) {
        return guarded(() -> {
            if (welders.releaseFrom(welderId, userId) == 0) return false;
            welders.addHistory(welderId, userId, RETURNED, null);
            return true;
        });
    }

    /**
     * Администратор принудительно возвращает аппарат на базу.
     * В журнал пишется сотрудник, у которого был аппарат, и администратор.
     *
     * @return ID сотрудника, у которого был аппарат, или пусто, если аппарат не был в работе
     */
    public Optional<Long> forceReturn(int welderId, long adminId) {
        try {
            return db.inTransaction(() -> {
                Optional<Long> holder = welders.findHolder(welderId);
                if (holder.isEmpty() || welders.release(welderId) == 0) return Optional.<Long>empty();
                welders.addHistory(welderId, holder.get(), FORCE_RETURNED, adminId);
                return holder;
            });
        } catch (DataAccessException e) {
            log.error("Не удалось принудительно вернуть аппарат {}", welderId, e);
            return Optional.empty();
        }
    }

    /** Числится ли аппарат сейчас за сотрудником. */
    public boolean isHeldBy(int welderId, long userId) {
        return welders.findHolder(welderId).map(h -> h == userId).orElse(false);
    }

    /** Передача аппарата от коллеги к коллеге: в журнал пишутся «вернул» и «взял». */
    public boolean transfer(int welderId, long fromUserId, long toUserId) {
        return guarded(() -> {
            if (welders.reassign(welderId, fromUserId, toUserId) == 0) return false;
            welders.addHistory(welderId, fromUserId, RETURNED, null);
            welders.addHistory(welderId, toUserId, TAKEN, null);
            return true;
        });
    }

    /** Последние 30 записей журнала, самые новые первыми. */
    public List<WelderHistoryEntry> history() {
        return welders.lastHistory(HISTORY_LIMIT);
    }

    public List<WelderAssignment> inUse() {
        return welders.findInUse();
    }

    /** Окончание смены сотрудника на указанный день (строка из графика, как в Excel). */
    public Optional<String> shiftEnd(String excelName, LocalDate day) {
        return welders.findShiftEnd(excelName, day);
    }

    private boolean guarded(Database.SqlSupplier<Boolean> work) {
        try {
            return db.inTransaction(work);
        } catch (DataAccessException e) {
            log.error("Ошибка БД при изменении статуса сварочного аппарата", e);
            return false;
        }
    }
}
