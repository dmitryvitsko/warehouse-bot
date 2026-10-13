package com.warehousebot.domain;

/** Аппарат на руках у сотрудника — для проверки конца смены. {@code fullName} совпадает с фамилией из графика. */
public record WelderAssignment(int welderId, String welderName, long userId, String fullName) {
}
