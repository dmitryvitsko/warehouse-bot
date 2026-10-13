package com.warehousebot.domain;

/** Единица инструмента: id, название и инвентарный номер (может быть {@code null}). */
public record Tool(int id, String name, String invNumber) {
}
