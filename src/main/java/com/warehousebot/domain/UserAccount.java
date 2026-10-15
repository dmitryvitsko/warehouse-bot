package com.warehousebot.domain;

/** Учётная запись бота для списка пользователей в панели администратора. */
public record UserAccount(long id, String fullName, Role role) {
}
