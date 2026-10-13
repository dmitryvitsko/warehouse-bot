package com.warehousebot.infrastructure.db;

/** Непроверяемая ошибка доступа к данным: репозитории не скрывают сбои БД за значениями по умолчанию. */
public class DataAccessException extends RuntimeException {
    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
