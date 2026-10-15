package com.warehousebot.domain;

/**
 * Одна позиция списания материала из подотчёта сотрудника (бывший WriteOffSession в момент сохранения).
 *
 * @param materialName название для текста результата (например, «Кабель [12345]»)
 * @param paidReceipt  {@code true} — списание по квитанции, иначе техническое
 */
public record WriteOffItem(int materialId, String materialName, String unit, double priceWithVat, double quantity,
                           boolean paidReceipt, String receiptNumber, String phoneNumber, String contractNumber,
                           String address, String closingCode, String reason) {
}
