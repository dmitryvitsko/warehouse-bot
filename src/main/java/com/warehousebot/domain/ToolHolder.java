package com.warehousebot.domain;

/** Сотрудник, за которым закреплён инструмент, и число единиц на руках. */
public record ToolHolder(long userId, String fullName, int toolCount) {
}
