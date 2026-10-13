package com.warehousebot.domain;

/** Пользователь бота: Telegram chat id и имя из таблицы users. */
public record UserRef(long id, String fullName) {
}
