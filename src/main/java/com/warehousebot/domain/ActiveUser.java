package com.warehousebot.domain;

/** Активный сотрудник (не заблокирован и не ожидает одобрения) — для списков выбора «кому выдать». */
public record ActiveUser(long id, String fullName, Role role) {
}
