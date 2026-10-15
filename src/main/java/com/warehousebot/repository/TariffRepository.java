package com.warehousebot.repository;

import com.warehousebot.domain.Tariff;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.db.RowMapper;

import java.util.List;
import java.util.Optional;

/** Таблица service_tariffs — утверждённые тарифы на платные услуги. */
public final class TariffRepository {

    private static final RowMapper<Tariff> MAPPER = rs -> new Tariff(rs.getInt("id"), rs.getString("service_name"),
            rs.getString("unit"), rs.getDouble("price_with_vat"), rs.getInt("is_single") == 1);

    private final Database db;

    public TariffRepository(Database db) {
        this.db = db;
    }

    /** Все тарифы в порядке id (порядок задаёт группировку по разделам). */
    public List<Tariff> findAll() {
        return db.query("SELECT id, service_name, unit, price_with_vat, is_single FROM service_tariffs ORDER BY id", MAPPER);
    }

    public Optional<Tariff> findById(int id) {
        return db.queryOne("SELECT id, service_name, unit, price_with_vat, is_single FROM service_tariffs WHERE id = ?", MAPPER, id);
    }

    /** Меняет цену услуги по точному названию; {@code true}, если услуга найдена. */
    public boolean updatePrice(String serviceName, double price) {
        return db.update("UPDATE service_tariffs SET price_with_vat = ? WHERE service_name = ?", price, serviceName.trim()) > 0;
    }
}
