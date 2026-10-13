package com.warehousebot.domain;

/**
 * Утверждённый тариф на услугу (таблица service_tariffs).
 *
 * @param single {@code true} — услуга добавляется в квитанцию сразу в количестве 1, без запроса количества
 */
public record Tariff(int id, String name, String unit, double priceWithVat, boolean single) {
}
