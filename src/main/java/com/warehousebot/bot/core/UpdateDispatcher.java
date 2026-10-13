package com.warehousebot.bot.core;

import com.warehousebot.bot.common.AccessGate;
import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.core.Handlers.CallbackHandler;
import com.warehousebot.bot.core.Handlers.CallbackInterceptor;
import com.warehousebot.bot.core.Handlers.CancelHandler;
import com.warehousebot.bot.core.Handlers.CommandHandler;
import com.warehousebot.bot.core.Handlers.MessageHandler;
import com.warehousebot.bot.core.Handlers.TextHandler;
import com.warehousebot.bot.core.Routes.CallbackStage;
import com.warehousebot.bot.session.SessionStore;
import com.warehousebot.bot.session.UserSession;
import com.warehousebot.domain.Role;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;

/**
 * Разбор входящего апдейта и передача его нужному обработчику.
 *
 * <p>Последовательность проверок повторяет прежний WarehouseBot.handleUpdate: документ → фото →
 * контакт → нажатие кнопки → текст. Бизнес-логики здесь нет — только общие проверки доступа
 * и поиск маршрута в {@link Routes}.
 */
public final class UpdateDispatcher {

    /** Кнопки, начинающиеся с этих символов, считаются переходом в главное меню (как в прежней версии). */
    private static final List<String> MENU_PREFIXES = List.of("📦", "🧰", "📝", "📋", "🔢", "🤝", "📊", "📥", "📑",
            "🗓", "🔍", "📢", "👥", "🪛", "🛠", "📞", "🔌", "👁", "✏️", "🤒");

    private static final String START = "/start";

    private final Routes routes;
    private final SessionStore sessions;
    private final Messenger messenger;
    private final AccessGate gate;
    private final MainMenu menu;

    public UpdateDispatcher(Routes routes, SessionStore sessions, Messenger messenger, AccessGate gate, MainMenu menu) {
        this.routes = routes;
        this.sessions = sessions;
        this.messenger = messenger;
        this.gate = gate;
        this.menu = menu;
    }

    /** Чат, к которому относится апдейт (ключ для последовательной обработки). */
    public static Long chatIdOf(Update update) {
        if (update.hasMessage()) return update.getMessage().getChatId();
        if (update.hasCallbackQuery() && update.getCallbackQuery().getMessage() != null) {
            return update.getCallbackQuery().getMessage().getChatId();
        }
        return null;
    }

    public void dispatch(Update update) throws Exception {
        if (update.hasMessage()) {
            Message msg = update.getMessage();
            if (msg.hasDocument()) {
                onDocument(msg);
                return;
            }
            if (msg.hasPhoto()) {
                onMedia(msg, routes.photoHandlers(), true);
                return;
            }
            if (msg.hasContact()) {
                onMedia(msg, routes.contactHandlers(), false);
                return;
            }
        }
        if (update.hasCallbackQuery()) {
            onCallback(update.getCallbackQuery());
            return;
        }
        if (update.hasMessage() && update.getMessage().hasText()) {
            onText(update.getMessage());
        }
    }

    // ------------------------------------------------------------------ документы, фото, контакты

    private void onDocument(Message msg) throws Exception {
        long chatId = msg.getChatId();
        String firstName = msg.getFrom().getFirstName();
        Role role = gate.checkRole(chatId, firstName);
        if (role.hasNoAccess()) return;
        if (gate.blockIfUnbound(chatId, role)) return;
        if (!role.isAdmin()) {
            menu.show(chatId, role, "❌ Загружать файлы может только Администратор (МОЛ).");
            return;
        }
        if (gate.blockAdminIfInbox(chatId, role)) return;
        runMessageHandlers(routes.documentHandlers(), new MessageContext(chatId, firstName, role, sessions.get(chatId), msg));
    }

    private void onMedia(Message msg, List<MessageHandler> handlers, boolean checkAdminInbox) throws Exception {
        long chatId = msg.getChatId();
        String firstName = msg.getFrom().getFirstName();
        Role role = gate.checkRole(chatId, firstName);
        if (role.hasNoAccess()) return;
        if (gate.blockIfUnbound(chatId, role)) return;
        if (checkAdminInbox && gate.blockAdminIfInbox(chatId, role)) return;
        runMessageHandlers(handlers, new MessageContext(chatId, firstName, role, sessions.get(chatId), msg));
    }

    private static void runMessageHandlers(List<MessageHandler> handlers, MessageContext ctx) throws Exception {
        for (MessageHandler h : handlers) {
            if (h.handle(ctx)) return;
        }
    }

    // ------------------------------------------------------------------ нажатия кнопок

    private void onCallback(CallbackQuery query) throws Exception {
        long chatId = query.getMessage().getChatId();
        String data = query.getData();
        if (data == null) return;
        String firstName = query.getFrom().getFirstName();
        Role role = gate.checkRole(chatId, firstName);
        if (role.hasNoAccess()) return;

        CallbackContext ctx = new CallbackContext(chatId, query.getMessage().getMessageId(), data, firstName, role,
                query.getId(), sessions.get(chatId), messenger);
        try {
            routeCallback(ctx);
        } finally {
            // Убираем «часики» на кнопке, если обработчик не ответил сам.
            if (!ctx.isAnswered()) ctx.answer();
        }
    }

    private void routeCallback(CallbackContext ctx) throws Exception {
        for (CallbackInterceptor interceptor : routes.callbackInterceptors()) {
            if (interceptor.intercept(ctx)) return;
        }

        CallbackHandler handler = routes.findCallback(CallbackStage.BEFORE_BIND_CHECK, ctx);
        if (handler != null) {
            handler.handle(ctx);
            return;
        }

        if (!ctx.data().startsWith("BIND_NAME:") && gate.blockIfUnbound(ctx.chatId(), ctx.role())) {
            ctx.answer("⚠️ Сначала выберите фамилию!", true);
            return;
        }

        handler = routes.findCallback(CallbackStage.AFTER_BIND_CHECK, ctx);
        if (handler != null) handler.handle(ctx);
    }

    // ------------------------------------------------------------------ текст

    private void onText(Message msg) throws Exception {
        long chatId = msg.getChatId();
        String firstName = msg.getFrom().getFirstName();
        String text = msg.getText();

        // Сообщение пользователя сразу удаляется, чтобы чат оставался чистым.
        messenger.delete(chatId, msg.getMessageId());

        Role role = gate.checkRole(chatId, firstName);
        if (role == Role.BANNED) return;
        if (role == Role.PENDING) {
            if (text.equals(START)) messenger.send(chatId, "⏳ Ваша заявка все еще находится на рассмотрении администратора.");
            return;
        }

        UserSession session = sessions.get(chatId);
        TextContext ctx = new TextContext(chatId, text, firstName, role, session);

        for (TextStage stage : TextStage.values()) {
            if (runStage(stage, ctx)) return;
        }
    }

    /** Выполняет одну стадию конвейера; {@code true} — сообщение обработано. */
    private boolean runStage(TextStage stage, TextContext ctx) throws Exception {
        switch (stage) {
            case BIND_CHECK -> {
                return gate.blockIfUnbound(ctx.chatId(), ctx.role());
            }
            case CANCEL -> {
                return handleCancel(ctx);
            }
            case MENU_RESET -> {
                if (isMenuNavigation(ctx.text())) {
                    ctx.session().resetDialogs();
                    messenger.clearChatHistory(ctx.chatId());
                }
                return runHandlers(stage, ctx);
            }
            case ADMIN_INBOX_LOCK -> {
                String t = ctx.text();
                boolean exempt = t.equals(START) || t.equals("/fix") || t.startsWith("/take_")
                        || t.startsWith("/give_") || t.startsWith("/fix_name");
                if (!exempt && gate.blockAdminIfInbox(ctx.chatId(), ctx.role())) return true;
                return runHandlers(stage, ctx);
            }
            case MENU_COMMANDS -> {
                CommandHandler command = routes.findCommand(ctx.text());
                if (command == null) return false;
                command.handle(ctx);
                return true;
            }
            case FALLBACK -> {
                if (runHandlers(stage, ctx)) return true;
                menu.show(ctx.chatId(), ctx.role(), "Используйте кнопки меню внизу экрана 👇");
                return true;
            }
            default -> {
                return runHandlers(stage, ctx);
            }
        }
    }

    private boolean runHandlers(TextStage stage, TextContext ctx) throws Exception {
        for (TextHandler h : routes.textHandlers(stage)) {
            if (h.handle(ctx)) return true;
        }
        return false;
    }

    private boolean handleCancel(TextContext ctx) throws Exception {
        String text = ctx.text();
        boolean explicitCancel = text.equals(MainMenu.CANCEL);
        if (!explicitCancel && !text.equals(MainMenu.BACK)) return false;

        for (CancelHandler h : routes.cancelHandlers()) {
            if (h.onCancel(ctx, explicitCancel)) return true;
        }
        ctx.session().resetDialogs();
        menu.show(ctx.chatId(), ctx.role(), explicitCancel ? "🚫 <b>Действие отменено.</b>" : "Вы вернулись в главное меню:");
        return true;
    }

    /** Переход в главное меню: известная кнопка/команда меню, /start или текст с «меню-эмодзи». */
    private boolean isMenuNavigation(String text) {
        if (text.equals(START)) return true;
        if (routes.isCommand(text) && !text.startsWith("/")) return true;
        for (String p : MENU_PREFIXES) {
            if (text.startsWith(p)) return true;
        }
        return false;
    }
}
