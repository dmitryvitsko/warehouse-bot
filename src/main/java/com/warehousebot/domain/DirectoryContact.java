package com.warehousebot.domain;

/** Контакт, добавленный сотрудниками в телефонный справочник. */
public record DirectoryContact(int id, int category, String text) {
}
