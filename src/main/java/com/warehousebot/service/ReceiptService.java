package com.warehousebot.service;

import com.warehousebot.domain.HeldMaterial;
import com.warehousebot.domain.ReceiptLine;
import com.warehousebot.domain.Tariff;
import com.warehousebot.repository.BalanceRepository;

import java.util.List;
import java.util.Optional;

/** Калькулятор квитанции: строки услуг по тарифам и материалов из подотчёта. */
public final class ReceiptService {

    private final TariffService tariffs;
    private final BalanceRepository balances;

    public ReceiptService(TariffService tariffs, BalanceRepository balances) {
        this.tariffs = tariffs;
        this.balances = balances;
    }

    public List<Tariff> services() {
        return tariffs.all();
    }

    public Optional<Tariff> service(int id) {
        return tariffs.find(id);
    }

    /** Материалы, которые можно указать в квитанции (подотчёт сотрудника). */
    public List<HeldMaterial> materials(long userId) {
        return balances.findHeld(userId);
    }

    /** Строка услуги; пусто, если тариф удалён. */
    public Optional<ReceiptLine> serviceLine(int serviceId, double quantity) {
        return tariffs.find(serviceId).map(t -> line(t, quantity));
    }

    public static ReceiptLine line(Tariff t, double quantity) {
        return new ReceiptLine(t.id(), t.name(), t.unit(), quantity, t.priceWithVat(), false);
    }

    /** Строка материала; пусто, если материала уже нет в подотчёте. */
    public Optional<ReceiptLine> materialLine(long userId, int materialId, double quantity) {
        return balances.findHeld(userId, materialId)
                .map(m -> new ReceiptLine(materialId, m.name(), m.unit(), quantity, m.priceWithVat(), true));
    }
}
