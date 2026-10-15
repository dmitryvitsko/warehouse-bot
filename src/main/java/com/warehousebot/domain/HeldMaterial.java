package com.warehousebot.domain;

/** Материал на руках у сотрудника (подотчёт): количество в рабочих единицах и цена за единицу с НДС. */
public record HeldMaterial(int materialId, String code, String name, String unit, double quantity, double priceWithVat) {
}
