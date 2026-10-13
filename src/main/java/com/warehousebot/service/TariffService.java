package com.warehousebot.service;

import com.warehousebot.domain.Tariff;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.repository.TariffRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/** Тарифы на платные услуги. */
public final class TariffService {

    private static final Logger log = LoggerFactory.getLogger(TariffService.class);

    private final TariffRepository tariffs;

    public TariffService(TariffRepository tariffs) {
        this.tariffs = tariffs;
    }

    public List<Tariff> all() {
        return tariffs.findAll();
    }

    public Optional<Tariff> find(int id) {
        return tariffs.findById(id);
    }

    /** Новая цена услуги (команда администратора «+услуга Название;Цена»). {@code false} — услуга не найдена или ошибка БД. */
    public boolean updatePrice(String serviceName, double price) {
        try {
            return tariffs.updatePrice(serviceName, price);
        } catch (DataAccessException e) {
            log.error("Ошибка обновления цены услуги", e);
            return false;
        }
    }
}
