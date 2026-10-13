package com.warehousebot.domain;

/** Заявка на возврат материала на склад вместе с данными материала и сотрудника. */
public record ReturnRequest(int id, long userId, int materialId, double quantity, String status,
                            String code, String name, String unit, double priceWithVat, String userName) {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    public boolean isPending() {
        return PENDING.equals(status);
    }
}
