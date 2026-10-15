package com.warehousebot.domain;

/** Заявка мастера на возврат материала на склад, ожидающая решения МОЛ. */
public record PendingReturn(int id, String userName, String materialName, String materialCode,
                            double quantity, String unit) {
}
