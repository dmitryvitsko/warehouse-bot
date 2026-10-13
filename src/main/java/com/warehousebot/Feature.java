package com.warehousebot;

import com.warehousebot.bot.core.BotModule;

import java.util.List;

/** Функция бота в сборе: её модули маршрутов и фоновые задачи. */
public interface Feature {

    List<BotModule> modules();

    /** Запуск фоновых задач после регистрации бота. */
    default void start() {
    }
}
