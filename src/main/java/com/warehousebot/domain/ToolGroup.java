package com.warehousebot.domain;

/** Группа одноимённого инструмента на складе: id первой единицы (ключ группы), название и количество. */
public record ToolGroup(int firstId, String name, int count) {
}
