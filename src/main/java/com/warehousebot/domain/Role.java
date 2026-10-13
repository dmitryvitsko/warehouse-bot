package com.warehousebot.domain;

/** Роль пользователя. Значения совпадают со строками в колонке users.role. */
public enum Role {
    ADMIN, WORKER, PENDING, BANNED;

    /** Неизвестные значения трактуются как мастер — так же, как в прежней версии. */
    public static Role fromDb(String value) {
        if (value == null) return WORKER;
        return switch (value) {
            case "ADMIN" -> ADMIN;
            case "PENDING" -> PENDING;
            case "BANNED" -> BANNED;
            default -> WORKER;
        };
    }

    public boolean isAdmin() {
        return this == ADMIN;
    }

    /** Заблокированные и ожидающие одобрения не получают доступ к функциям бота. */
    public boolean hasNoAccess() {
        return this == BANNED || this == PENDING;
    }
}
