package com.warehousebot.bot.core;

import com.warehousebot.bot.core.Handlers.CallbackHandler;
import com.warehousebot.bot.core.Handlers.CallbackInterceptor;
import com.warehousebot.bot.core.Handlers.CancelHandler;
import com.warehousebot.bot.core.Handlers.CommandHandler;
import com.warehousebot.bot.core.Handlers.MessageHandler;
import com.warehousebot.bot.core.Handlers.TextHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Реестр маршрутов. Каждая функция бота ({@link BotModule}) регистрирует здесь свои кнопки,
 * текстовые шаги и команды; диспетчер только ищет нужный обработчик.
 *
 * <p>Поиск callback-маршрута: сначала точное совпадение (HashMap, O(1)), затем префиксы.
 */
public final class Routes {

    /** Этап обработки нажатия: до или после проверки «мастер выбрал фамилию из графика». */
    public enum CallbackStage { BEFORE_BIND_CHECK, AFTER_BIND_CHECK }

    private record CallbackRoute(String key, boolean prefix, Predicate<CallbackContext> guard, CallbackHandler handler) {
        boolean matches(String data) {
            return prefix ? data.startsWith(key) : data.equals(key);
        }
    }

    private final Map<CallbackStage, Map<String, List<CallbackRoute>>> exact = new EnumMap<>(CallbackStage.class);
    private final Map<CallbackStage, List<CallbackRoute>> prefixes = new EnumMap<>(CallbackStage.class);
    private final List<CallbackInterceptor> callbackInterceptors = new ArrayList<>();

    private final Map<TextStage, List<TextHandler>> textStages = new EnumMap<>(TextStage.class);
    private final Map<String, CommandHandler> commands = new LinkedHashMap<>();
    private final List<CancelHandler> cancelHandlers = new ArrayList<>();

    private final List<MessageHandler> documentHandlers = new ArrayList<>();
    private final List<MessageHandler> photoHandlers = new ArrayList<>();
    private final List<MessageHandler> contactHandlers = new ArrayList<>();

    public Routes() {
        for (CallbackStage s : CallbackStage.values()) {
            exact.put(s, new HashMap<>());
            prefixes.put(s, new ArrayList<>());
        }
        for (TextStage s : TextStage.values()) textStages.put(s, new ArrayList<>());
    }

    // ------------------------------------------------------------------ callbacks

    /** Кнопка с точным значением callback data. */
    public CallbackRouteBuilder onCallback(String data) {
        return new CallbackRouteBuilder(data, false);
    }

    /** Кнопки, чьи данные начинаются с префикса (например, {@code "W_TAKE:"}). */
    public CallbackRouteBuilder onCallbackPrefix(String prefix) {
        return new CallbackRouteBuilder(prefix, true);
    }

    public void interceptCallbacks(CallbackInterceptor interceptor) {
        callbackInterceptors.add(interceptor);
    }

    public final class CallbackRouteBuilder {
        private final String key;
        private final boolean prefix;
        private CallbackStage stage = CallbackStage.AFTER_BIND_CHECK;
        private Predicate<CallbackContext> guard = ctx -> true;

        private CallbackRouteBuilder(String key, boolean prefix) {
            this.key = key;
            this.prefix = prefix;
        }

        /** Доступно и мастеру, не выбравшему фамилию (как было в прежней версии). */
        public CallbackRouteBuilder beforeBindCheck() {
            this.stage = CallbackStage.BEFORE_BIND_CHECK;
            return this;
        }

        public CallbackRouteBuilder adminOnly() {
            return when(CallbackContext::isAdmin);
        }

        /** Дополнительное условие; если не выполнено, нажатие ищет следующий подходящий маршрут. */
        public CallbackRouteBuilder when(Predicate<CallbackContext> condition) {
            this.guard = this.guard.and(condition);
            return this;
        }

        public void handle(CallbackHandler handler) {
            CallbackRoute route = new CallbackRoute(key, prefix, guard, handler);
            if (prefix) prefixes.get(stage).add(route);
            else exact.get(stage).computeIfAbsent(key, k -> new ArrayList<>()).add(route);
        }
    }

    /** Находит обработчик нажатия на этапе {@code stage} или {@code null}. */
    CallbackHandler findCallback(CallbackStage stage, CallbackContext ctx) {
        String data = ctx.data();
        for (CallbackRoute r : exact.get(stage).getOrDefault(data, List.of())) {
            if (r.guard().test(ctx)) return r.handler();
        }
        for (CallbackRoute r : prefixes.get(stage)) {
            if (r.matches(data) && r.guard().test(ctx)) return r.handler();
        }
        return null;
    }

    List<CallbackInterceptor> callbackInterceptors() {
        return callbackInterceptors;
    }

    // ------------------------------------------------------------------ text

    /** Обработчик текстового сообщения на заданной стадии конвейера. */
    public void onText(TextStage stage, TextHandler handler) {
        textStages.get(stage).add(handler);
    }

    /** Кнопка главного меню или команда — точное совпадение текста. */
    public void onCommand(String text, CommandHandler handler) {
        if (commands.putIfAbsent(text, handler) != null) {
            throw new IllegalStateException("Команда уже зарегистрирована: " + text);
        }
    }

    public void onCancel(CancelHandler handler) {
        cancelHandlers.add(handler);
    }

    List<TextHandler> textHandlers(TextStage stage) {
        return textStages.get(stage);
    }

    CommandHandler findCommand(String text) {
        return commands.get(text);
    }

    boolean isCommand(String text) {
        return commands.containsKey(text);
    }

    List<CancelHandler> cancelHandlers() {
        return Collections.unmodifiableList(cancelHandlers);
    }

    // ------------------------------------------------------------------ media

    public void onDocument(MessageHandler handler) {
        documentHandlers.add(handler);
    }

    public void onPhoto(MessageHandler handler) {
        photoHandlers.add(handler);
    }

    public void onContact(MessageHandler handler) {
        contactHandlers.add(handler);
    }

    List<MessageHandler> documentHandlers() { return documentHandlers; }
    List<MessageHandler> photoHandlers() { return photoHandlers; }
    List<MessageHandler> contactHandlers() { return contactHandlers; }
}
