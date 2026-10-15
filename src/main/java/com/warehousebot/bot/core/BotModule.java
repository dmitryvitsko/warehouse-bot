package com.warehousebot.bot.core;

/** Функциональный модуль бота (склад, инструмент, график, …), регистрирующий свои маршруты. */
public interface BotModule {
    void register(Routes routes);
}
