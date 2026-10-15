package com.warehousebot.bot.core;

/** Функциональные интерфейсы обработчиков. */
public final class Handlers {

    private Handlers() {
    }

    @FunctionalInterface
    public interface CallbackHandler {
        void handle(CallbackContext ctx) throws Exception;
    }

    /** Возвращает {@code true}, если сообщение обработано и дальше передавать его не нужно. */
    @FunctionalInterface
    public interface TextHandler {
        boolean handle(TextContext ctx) throws Exception;
    }

    @FunctionalInterface
    public interface CommandHandler {
        void handle(TextContext ctx) throws Exception;
    }

    /** Документ/фото/контакт. {@code true} — обработано. */
    @FunctionalInterface
    public interface MessageHandler {
        boolean handle(MessageContext ctx) throws Exception;
    }

    /** Перехватчик нажатий, срабатывающий до маршрутизации. {@code true} — нажатие поглощено. */
    @FunctionalInterface
    public interface CallbackInterceptor {
        boolean intercept(CallbackContext ctx) throws Exception;
    }

    /**
     * Реакция функции на «❌ Отменить» / «🔙 Назад», набранные текстом.
     * {@code true} — функция сама сбросила состояние и показала нужный экран.
     */
    @FunctionalInterface
    public interface CancelHandler {
        boolean onCancel(TextContext ctx, boolean explicitCancel) throws Exception;
    }
}
