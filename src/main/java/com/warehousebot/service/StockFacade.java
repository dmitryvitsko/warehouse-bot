package com.warehousebot.service;

import com.warehousebot.domain.HeldMaterial;
import com.warehousebot.domain.WriteOffItem;

import java.io.File;
import java.util.List;
import java.util.Optional;

/** Операции со складом и подотчётом, которые нужны другим функциям (ТМ, калькулятор, инструмент, загрузки). */
public interface StockFacade {

    /** Материалы на руках у сотрудника (количество > 0), по алфавиту. */
    List<HeldMaterial> heldMaterials(long userId);

    /** Конкретный материал из подотчёта сотрудника, если он есть на руках. */
    Optional<HeldMaterial> heldMaterial(long userId, int materialId);

    /** Списывает позицию из подотчёта и возвращает HTML-текст результата (как прежний completeWriteOff). */
    String writeOff(long userId, WriteOffItem item);

    /** HTML-текст «Числится за вами (в подотчете)» (как прежний getUserBalanceText). */
    String balanceText(long userId);

    /** Сотрудники, за которыми числятся материалы. */
    List<Long> usersWithBalances();

    /** Полная очистка склада (таблица materials). */
    void clearWarehouse();

    /** Загрузка оборотной ведомости; возвращает HTML-отчёт для администратора. */
    String importTurnover(File file);

    int clearTransactionsHistory();
}
