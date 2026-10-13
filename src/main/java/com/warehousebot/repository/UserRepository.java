package com.warehousebot.repository;

import com.warehousebot.domain.ActiveUser;
import com.warehousebot.domain.Role;
import com.warehousebot.domain.UserRef;
import com.warehousebot.infrastructure.db.Database;

import java.util.List;
import java.util.Optional;

/** Таблицы users и user_excel_names (привязка Telegram-аккаунта к фамилии из графика). */
public final class UserRepository {

    private final Database db;

    public UserRepository(Database db) {
        this.db = db;
    }

    public Optional<String> findRole(long userId) {
        return db.queryOne("SELECT role FROM users WHERE id = ?", rs -> rs.getString("role"), userId);
    }

    public void insert(long userId, String fullName, Role role) {
        db.update("INSERT INTO users (id, full_name, role) VALUES (?, ?, ?)", userId, fullName, role.name());
    }

    public Optional<String> findFullName(long userId) {
        return db.queryOne("SELECT full_name FROM users WHERE id = ?", rs -> rs.getString("full_name"), userId);
    }

    /** Имя сотрудника для сообщений; для неизвестного ID — «Сотрудник (ID: …)». */
    public String displayName(long userId) {
        return findFullName(userId).orElse("Сотрудник (ID: " + userId + ")");
    }

    public List<Long> adminIds() {
        return db.query("SELECT id FROM users WHERE role = 'ADMIN'", rs -> rs.getLong("id"));
    }

    public List<Long> allUserIds() {
        return db.query("SELECT id FROM users", rs -> rs.getLong("id"));
    }

    /** Активные сотрудники: сначала мастера, затем администраторы (ORDER BY role DESC), по имени. */
    public List<ActiveUser> activeUsers() {
        return db.query("SELECT id, full_name AS d_name, role FROM users WHERE role != 'BANNED' AND role != 'PENDING' ORDER BY role DESC, d_name",
                rs -> new ActiveUser(rs.getLong("id"), rs.getString("d_name"), Role.fromDb(rs.getString("role"))));
    }

    public List<UserRef> pendingUsers() {
        return db.query("SELECT id, full_name FROM users WHERE role = 'PENDING'",
                rs -> new UserRef(rs.getLong("id"), rs.getString("full_name")));
    }

    /** Меняет роль на BANNED/WORKER. Администраторов не трогает. Возвращает число изменённых строк. */
    public int setBanned(long userId, boolean banned) {
        return db.update("UPDATE users SET role = ? WHERE id = ? AND role != 'ADMIN'",
                banned ? Role.BANNED.name() : Role.WORKER.name(), userId);
    }

    /** Решение по заявке нового пользователя: меняет роль, только если он всё ещё ожидает одобрения. */
    public boolean resolvePending(long userId, boolean approve) {
        return db.update("UPDATE users SET role = ? WHERE id = ? AND role = 'PENDING'",
                approve ? Role.WORKER.name() : Role.BANNED.name(), userId) > 0;
    }

    public boolean hasTmAccess(long userId) {
        return db.queryOne("SELECT tm_access FROM users WHERE id = ?", rs -> rs.getInt("tm_access") == 1, userId)
                .orElse(false);
    }

    public boolean setTmAccess(long userId, boolean grant) {
        return db.update("UPDATE users SET tm_access = ? WHERE id = ?", grant ? 1 : 0, userId) > 0;
    }

    /** Мастера (не админы и не заблокированные) с доступом к ТМ или без него. */
    public List<UserRef> usersByTmAccess(boolean access) {
        return db.query("SELECT id, full_name FROM users WHERE role != 'ADMIN' AND role != 'BANNED' AND tm_access = ? ORDER BY full_name",
                rs -> new UserRef(rs.getLong("id"), rs.getString("full_name")), access ? 1 : 0);
    }

    // ------------------------------------------------------------------ привязка к графику

    public Optional<String> findExcelName(long userId) {
        return db.queryOne("SELECT excel_name FROM user_excel_names WHERE user_id = ?",
                rs -> rs.getString("excel_name"), userId);
    }

    public Optional<Long> findUserIdByExcelName(String excelName) {
        return db.queryOne("SELECT user_id FROM user_excel_names WHERE excel_name = ?",
                rs -> rs.getLong("user_id"), excelName);
    }

    /** Фамилии из графика, к которым ещё никто не привязан. */
    public List<String> availableExcelNames() {
        return db.query("SELECT DISTINCT excel_name FROM schedules_v2 WHERE excel_name NOT IN (SELECT excel_name FROM user_excel_names) ORDER BY excel_name",
                rs -> rs.getString("excel_name"));
    }

    /** Все фамилии из загруженного графика. */
    public List<String> allExcelNames() {
        return db.query("SELECT DISTINCT excel_name FROM schedules_v2 ORDER BY excel_name",
                rs -> rs.getString("excel_name"));
    }

    /** Привязывает аккаунт к фамилии и обновляет отображаемое имя. */
    public void bindExcelName(long userId, String excelName) {
        db.inTransaction(() -> {
            db.update("INSERT INTO user_excel_names (user_id, excel_name) VALUES (?, ?) " +
                    "ON CONFLICT(user_id) DO UPDATE SET excel_name = excluded.excel_name", userId, excelName);
            db.update("UPDATE users SET full_name = ? WHERE id = ?", excelName, userId);
        });
    }

    /** Снимает привязку к фамилии; имя заменяется на «[Отвязан] имя из Telegram». */
    public boolean unbind(long userId, String telegramName) {
        return db.inTransaction(() -> {
            db.update("DELETE FROM user_excel_names WHERE user_id = ?", userId);
            return db.update("UPDATE users SET full_name = ? WHERE id = ?", "[Отвязан] " + telegramName, userId) > 0;
        });
    }

    /** Фамилия есть в графике и ещё ни к кому не привязана (то же условие, что в {@link #availableExcelNames()}). */
    public boolean isExcelNameAvailable(String excelName) {
        return db.queryOne("SELECT 1 FROM schedules_v2 WHERE excel_name = ? AND excel_name NOT IN (SELECT excel_name FROM user_excel_names) LIMIT 1",
                rs -> Boolean.TRUE, excelName).isPresent();
    }
}
