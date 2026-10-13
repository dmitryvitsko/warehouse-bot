package com.warehousebot.domain;

/**
 * Запись журнала сварочных аппаратов.
 *
 * @param localTime время в формате «yyyy-MM-dd HH:mm:ss» (местное)
 * @param action    {@code TAKEN}, {@code RETURNED}, {@code FORCE_TAKEN} или {@code FORCE_RETURNED}
 * @param adminName имя администратора для принудительных действий (может быть {@code null})
 */
public record WelderHistoryEntry(String localTime, String welderName, String userName, String action, String adminName) {
}
