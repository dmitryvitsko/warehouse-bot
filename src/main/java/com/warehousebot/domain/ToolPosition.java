package com.warehousebot.domain;

/**
 * Несписанный инструмент с текущим положением — для аудита и поиска.
 *
 * @param status     IN_STOCK / ASSIGNED (значение колонки tools.status)
 * @param holderName имя сотрудника, если инструмент выдан (может быть {@code null})
 */
public record ToolPosition(Tool tool, String status, String holderName) {
}
