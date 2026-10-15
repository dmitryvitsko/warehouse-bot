package com.warehousebot.domain;

/** Шкаф ОРШ из плана планового осмотра. */
public record OrshCabinet(int id, String number, String address, String location) {
}
