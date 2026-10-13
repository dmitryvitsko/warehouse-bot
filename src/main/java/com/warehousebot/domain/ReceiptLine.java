package com.warehousebot.domain;

/**
 * Строка калькулятора квитанции: услуга по тарифу или материал из подотчёта.
 *
 * @param refId    id тарифа или материала
 * @param material {@code true} — материал, иначе услуга
 */
public record ReceiptLine(int refId, String name, String unit, double quantity, double priceWithVat, boolean material) {
}
