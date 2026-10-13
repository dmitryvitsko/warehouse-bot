package com.warehousebot.repository;

import com.warehousebot.domain.Role;
import com.warehousebot.domain.UserAccount;
import com.warehousebot.infrastructure.db.Database;

import java.util.List;

/** Кадровые операции администратора: список пользователей, удаление сотрудника, исправление фамилии. */
public final class EmployeeRepository {

    private final Database db;

    public EmployeeRepository(Database db) {
        this.db = db;
    }

    /** Все пользователи бота (в порядке хранения, как и раньше). */
    public List<UserAccount> allUsers() {
        return db.query("SELECT id, full_name, role FROM users",
                rs -> new UserAccount(rs.getLong("id"), rs.getString("full_name"), Role.fromDb(rs.getString("role"))));
    }

    /** Удаляет все смены сотрудника из графика; возвращает число удалённых строк. */
    public int deleteScheduleRows(String excelName) {
        return db.update("DELETE FROM schedules_v2 WHERE excel_name = ? OR TRIM(excel_name) = ?", excelName, excelName.trim());
    }

    /** Снимает привязку Telegram-аккаунтов к фамилии; возвращает число снятых привязок. */
    public int deleteBindingsByName(String excelName) {
        return db.update("DELETE FROM user_excel_names WHERE TRIM(excel_name) = ?", excelName.trim());
    }

    /** Заменяет фамилию во всех таблицах одной транзакцией. */
    public void fixNameTypo(String oldName, String newName) {
        db.inTransaction(() -> {
            db.update("UPDATE users SET full_name = ? WHERE full_name = ?", newName, oldName);
            db.update("UPDATE user_excel_names SET excel_name = ? WHERE excel_name = ?", newName, oldName);
            db.update("UPDATE schedules_v2 SET excel_name = ? WHERE excel_name = ?", newName, oldName);
            db.update("UPDATE schedules SET excel_name = ? WHERE excel_name = ?", newName, oldName);
            db.update("UPDATE sick_leave_requests SET excel_name = ? WHERE excel_name = ?", newName, oldName);
        });
    }
}
