package com.warehousebot.domain;

/**
 * Запрос мастера на больничный дольше 10 дней (таблица sick_leave_requests).
 *
 * @param startDate первый день в формате dd.MM.yyyy (как хранится в базе)
 * @param endDate   последний день в формате dd.MM.yyyy
 * @param status    PENDING / APPROVED / REJECTED
 */
public record SickLeaveRequest(long id, long chatId, String excelName, String startDate, String endDate, String status) {

    public boolean isPending() {
        return "PENDING".equals(status);
    }
}
