package com.warehousebot.domain;

/** Запись архива списанного инструмента; дата уже в формате дд.мм.гггг (может быть {@code null}). */
public record WrittenOffTool(String name, String invNumber, String reason, String date) {
}
