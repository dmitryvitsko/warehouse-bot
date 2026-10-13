package com.warehousebot.bot.session;

import java.util.function.UnaryOperator;

/**
 * Типизированный ключ состояния пользователя.
 *
 * <p>Каждая функция бота объявляет свои ключи сама, ядру не нужно знать о её внутренностях.
 * Политика сброса определяет, что происходит с состоянием при отмене диалога
 * (кнопка «❌ Отменить», «🔙 Назад» или переход в главное меню).
 */
public final class SessionKey<T> {

    private final String name;
    private final UnaryOperator<T> onReset;

    private SessionKey(String name, UnaryOperator<T> onReset) {
        this.name = name;
        this.onReset = onReset;
    }

    /** Состояние диалога: удаляется при отмене. */
    public static <T> SessionKey<T> dialog(String name) {
        return new SessionKey<>(name, v -> null);
    }

    /** Состояние, которое переживает отмену диалога. */
    public static <T> SessionKey<T> persistent(String name) {
        return new SessionKey<>(name, UnaryOperator.identity());
    }

    /** Собственная политика: вернуть {@code null}, чтобы удалить значение, или изменённое значение. */
    public static <T> SessionKey<T> custom(String name, UnaryOperator<T> onReset) {
        return new SessionKey<>(name, onReset);
    }

    T reset(T value) {
        return onReset.apply(value);
    }

    @Override
    public String toString() {
        return name;
    }
}
