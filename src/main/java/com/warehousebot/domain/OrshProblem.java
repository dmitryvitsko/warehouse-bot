package com.warehousebot.domain;

/** ОРШ, который не удалось осмотреть: номер, причина и кто зафиксировал. */
public record OrshProblem(String number, String reason, String inspectedBy) {
}
