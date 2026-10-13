package com.warehousebot.domain;

import java.util.List;

/** Сводка по плану осмотра ОРШ. Список проблемных заполняется, только если они есть. */
public record OrshStatistics(int total, int completed, int pending, int problem, List<OrshProblem> problems) {
}
