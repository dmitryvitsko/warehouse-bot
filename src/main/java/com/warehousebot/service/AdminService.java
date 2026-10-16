package com.warehousebot.service;

import com.warehousebot.domain.UserAccount;
import com.warehousebot.domain.UserRef;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.repository.EmployeeRepository;
import com.warehousebot.repository.ReturnRequestRepository;
import com.warehousebot.repository.UserRepository;

import java.util.List;
import java.util.Optional;

/** Администрирование: пользователи, доступы, привязка к графику и служебные операции. */
public final class AdminService {

    /** Результат блокировки/разблокировки. */
    public enum BanOutcome { DONE, NOT_FOUND, DB_ERROR }

    /** Результат выбора фамилии мастером. */
    public enum BindOutcome { BOUND, ALREADY_BOUND, NAME_TAKEN }

    /** Результат полного удаления сотрудника. */
    public sealed interface Removal {
        record Removed(String name, int shifts, int bindings) implements Removal {
        }

        record ScheduleFailed(String error) implements Removal {
        }

        record DbFailed(String error) implements Removal {
        }
    }

    /** Результат исправления фамилии: {@code error == null} — успешно. */
    public record NameFix(String oldName, String newName, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private final Database db;
    private final UserRepository users;
    private final EmployeeRepository employees;
    private final ReturnRequestRepository returnRequests;
    private final StockFacade stock;

    public AdminService(Database db, UserRepository users, EmployeeRepository employees,
                        ReturnRequestRepository returnRequests, StockFacade stock) {
        this.db = db;
        this.users = users;
        this.employees = employees;
        this.returnRequests = returnRequests;
        this.stock = stock;
    }

    // ------------------------------------------------------------------ пользователи

    public List<UserAccount> allUsers() {
        return employees.allUsers();
    }

    /**
     * Решение по заявке нового пользователя. {@code false} — заявка уже обработана
     * (например, нажата старая кнопка у второго администратора).
     */
    public boolean decideNewUser(long userId, boolean approve) {
        return users.resolvePending(userId, approve);
    }

    public BanOutcome setBanned(long userId, boolean ban) {
        try {
            return users.setBanned(userId, ban) > 0 ? BanOutcome.DONE : BanOutcome.NOT_FOUND;
        } catch (DataAccessException e) {
            return BanOutcome.DB_ERROR;
        }
    }

    /** Сброс привязки к фамилии; {@code false} — пользователь не найден или ошибка базы (как и раньше). */
    public boolean unbind(long userId, String telegramName) {
        try {
            return users.unbind(userId, telegramName);
        } catch (DataAccessException e) {
            return false;
        }
    }

    public List<String> allExcelNames() {
        return users.allExcelNames();
    }

    public List<String> availableExcelNames() {
        return users.availableExcelNames();
    }

    public Optional<Long> userIdByExcelName(String excelName) {
        return users.findUserIdByExcelName(excelName);
    }

    /**
     * Привязывает мастера к фамилии из графика. Проверка «ещё не привязан» и «фамилия свободна»
     * выполняется в той же транзакции, что и запись, — чужую привязку перезаписать нельзя.
     */
    public BindOutcome bindExcelName(long userId, String excelName) {
        return db.inTransaction(() -> {
            if (users.findExcelName(userId).isPresent()) return BindOutcome.ALREADY_BOUND;
            if (!users.isExcelNameAvailable(excelName)) return BindOutcome.NAME_TAKEN;
            users.bindExcelName(userId, excelName);
            return BindOutcome.BOUND;
        });
    }

    /** Полное удаление сотрудника: смены из графика и привязка аккаунта. */
    public Removal removeWorker(String excelName) {
        int shifts;
        try {
            shifts = employees.deleteScheduleRows(excelName);
        } catch (DataAccessException e) {
            return new Removal.ScheduleFailed(e.getMessage());
        }
        try {
            return new Removal.Removed(excelName, shifts, employees.deleteBindingsByName(excelName));
        } catch (DataAccessException e) {
            return new Removal.DbFailed(e.getMessage());
        }
    }

    public NameFix fixNameTypo(String oldName, String newName) {
        try {
            employees.fixNameTypo(oldName, newName);
            return new NameFix(oldName, newName, null);
        } catch (DataAccessException e) {
            return new NameFix(oldName, newName, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ доступ к ТМ

    public List<UserRef> usersByTmAccess(boolean access) {
        return users.usersByTmAccess(access);
    }

    public void setTmAccess(long userId, boolean grant) {
        users.setTmAccess(userId, grant);
    }

    // ------------------------------------------------------------------ рассылка и служебные операции

    public List<Long> allUserIds() {
        return users.allUserIds();
    }

    /** Удаляет все зависшие заявки на возврат (бывшая команда /fix). */
    public void deletePendingReturns() {
        returnRequests.deleteAllPending();
    }

    /** Полная очистка склада (бывшая команда /clear_stock). */
    public void clearWarehouse() {
        stock.clearWarehouse();
    }

    /** Очистка истории транзакций для формирования отчета нового месяца. */
    public int clearMonthlyReportHistory() {
        return stock.clearTransactionsHistory();
    }
}
