package com.warehousebot.domain;

/**
 * Сварочный аппарат и его текущее состояние.
 *
 * @param status     {@code ON_BASE} — на базе, {@code IN_USE} — на руках
 * @param holderName имя сотрудника, за которым числится аппарат ({@code ""}, если свободен)
 * @param assignedTo ID сотрудника или {@code null}
 */
public record Welder(int id, String name, String status, String holderName, String assignedTime, Long assignedTo) {

    public static final String ON_BASE = "ON_BASE";
    public static final String IN_USE = "IN_USE";

    public boolean onBase() {
        return ON_BASE.equals(status);
    }

    public boolean inUse() {
        return IN_USE.equals(status);
    }

    public boolean heldBy(long userId) {
        return assignedTo != null && assignedTo == userId;
    }
}
