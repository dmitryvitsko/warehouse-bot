package com.warehousebot.domain;

/**
 * Один день графика сотрудника (строка schedules_v2 внутри известного месяца).
 *
 * @param statusCode код статуса (В/О/Д/Б/А/Г/П) или пустая строка для рабочей смены
 * @param startTime  начало смены («08:30»), пусто для статусов
 * @param endTime    конец смены, пусто для статусов
 */
public record ShiftDay(String excelName, int day, String statusCode, String startTime, String endTime) {
}
