package com.warehousebot.service;

import com.warehousebot.domain.Role;
import com.warehousebot.repository.UserRepository;

import java.util.List;
import java.util.Optional;

/** Пользователи, роли и права доступа. */
public final class UserService {

    /** Фамилии сотрудников, которым разрешено редактировать график (как и раньше — по вхождению подстроки). */
    private static final List<String> SCHEDULE_EDITORS = List.of("Белевич", "Козлов");

    private final UserRepository users;
    private final long superAdminId;

    public UserService(UserRepository users, long superAdminId) {
        this.users = users;
        this.superAdminId = superAdminId;
    }

    /** Результат определения роли: {@code isNew} — пользователь только что зарегистрирован и ждёт одобрения. */
    public record Access(Role role, boolean isNew) {
    }

    /**
     * Роль пользователя; неизвестный пользователь регистрируется со статусом PENDING
     * (главный администратор из конфигурации — сразу ADMIN).
     */
    public Access resolve(long userId, String telegramName) {
        Optional<String> role = users.findRole(userId);
        if (role.isPresent()) return new Access(Role.fromDb(role.get()), false);

        boolean superAdmin = userId == superAdminId;
        users.insert(userId, telegramName, superAdmin ? Role.ADMIN : Role.PENDING);
        return new Access(superAdmin ? Role.ADMIN : Role.PENDING, !superAdmin);
    }

    /** Роль другого пользователя (для обновления его меню). Регистрирует его, если записи нет. */
    public Role roleOf(long userId) {
        return resolve(userId, "Сотрудник").role();
    }

    public long superAdminId() {
        return superAdminId;
    }

    public boolean canAccessTm(long userId, Role role) {
        return role.isAdmin() || users.hasTmAccess(userId);
    }

    public boolean canEditSchedule(long userId, Role role) {
        if (role.isAdmin()) return true;
        return users.findExcelName(userId).map(UserService::isScheduleEditor).orElse(false);
    }

    public static boolean isScheduleEditor(String excelName) {
        for (String editor : SCHEDULE_EDITORS) {
            if (excelName.contains(editor)) return true;
        }
        return false;
    }
}
