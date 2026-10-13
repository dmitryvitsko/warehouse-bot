package com.warehousebot.domain;

/** Позиция склада МОЛ: количество и цена с НДС — в рабочих единицах ({@code unit}). */
public record Material(int id, String account, String code, String name, String unit, double priceWithVat,
                       double warehouseQty, String batchInfo) {
}
