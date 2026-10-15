package com.warehousebot.bot.core;

import com.warehousebot.bot.common.AccessGate;
import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.session.SessionKey;
import com.warehousebot.bot.session.SessionStore;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.repository.ReturnRequestRepository;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.service.UserService;
import com.warehousebot.testutil.FakeMessenger;
import com.warehousebot.testutil.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Сквозные проверки конвейера: регистрация, привязка, отмена, блокировки, порядок стадий. */
class UpdateDispatcherTest {

    private static final long ADMIN = 1;
    private static final long WORKER = 2;
    private static final long UNBOUND = 3;
    private static final SessionKey<String> DIALOG = SessionKey.dialog("test.dialog");

    private Database db;
    private FakeMessenger messenger;
    private Routes routes;
    private SessionStore sessions;
    private UpdateDispatcher dispatcher;
    private final List<String> hits = new ArrayList<>();

    @BeforeEach
    void setUp() {
        db = TestDb.create();
        db.update("INSERT INTO users (id, full_name, role) VALUES (?, 'Админ', 'ADMIN')", ADMIN);
        db.update("INSERT INTO users (id, full_name, role) VALUES (?, 'Иванов И.И.', 'WORKER')", WORKER);
        db.update("INSERT INTO users (id, full_name, role) VALUES (?, 'Петров', 'WORKER')", UNBOUND);
        db.update("INSERT INTO user_excel_names (user_id, excel_name) VALUES (?, 'Иванов И.И.')", WORKER);
        db.update("INSERT INTO schedules_v2 (excel_name, year, month, day) VALUES ('Сидоров С.С.', 2026, 10, 1)");

        messenger = new FakeMessenger();
        UserRepository users = new UserRepository(db);
        UserService userService = new UserService(users, ADMIN);
        AccessGate gate = new AccessGate(messenger, userService, users, new ReturnRequestRepository(db));
        MainMenu menu = new MainMenu(messenger, userService);
        routes = new Routes();
        sessions = new SessionStore();
        dispatcher = new UpdateDispatcher(routes, sessions, messenger, gate, menu);
    }

    @Test
    void newUserIsRegisteredAsPendingAndAdminsAreAsked() throws Exception {
        routes.onCommand("/start", c -> hits.add("start"));

        dispatcher.dispatch(text(99, "/start"));

        assertEquals("PENDING", db.queryOne("SELECT role FROM users WHERE id = 99", rs -> rs.getString(1)).orElseThrow());
        assertTrue(messenger.calls.stream().anyMatch(c -> c.type().equals("sendPinned") && c.chatId() == ADMIN));
        assertTrue(messenger.texts().contains("⏳ <b>Ваша заявка отправлена.</b>"));
        assertTrue(hits.isEmpty());
    }

    @Test
    void userMessageIsDeletedFirst() throws Exception {
        routes.onCommand("/start", c -> hits.add("start"));
        dispatcher.dispatch(text(WORKER, "/start"));
        assertEquals("delete", messenger.calls.get(0).type());
        assertEquals(List.of("start"), hits);
    }

    @Test
    void unboundWorkerGetsBindingMenu() throws Exception {
        routes.onCommand("/start", c -> hits.add("start"));
        dispatcher.dispatch(text(UNBOUND, "/start"));
        assertTrue(hits.isEmpty());
        assertTrue(messenger.texts().stream().anyMatch(t -> t.contains("ОГРАНИЧЕНИЕ ДОСТУПА")));
    }

    @Test
    void stagesRunInDeclaredOrder() throws Exception {
        routes.onText(TextStage.FALLBACK, c -> hits.add("fallback"));
        routes.onText(TextStage.TAKE_QTY_INPUT, c -> {
            hits.add("qty");
            return true;
        });
        dispatcher.dispatch(text(WORKER, "15"));
        assertEquals(List.of("qty"), hits);
    }

    @Test
    void workerFallbackShowsHint() throws Exception {
        dispatcher.dispatch(text(WORKER, "что-то"));
        assertEquals("Используйте кнопки меню внизу экрана 👇", messenger.last().text());
    }

    @Test
    void typedCancelResetsDialogsAndShowsMenu() throws Exception {
        sessions.get(WORKER).put(DIALOG, "x");
        dispatcher.dispatch(text(WORKER, MainMenu.CANCEL));
        assertFalse(sessions.get(WORKER).has(DIALOG));
        assertEquals("🚫 <b>Действие отменено.</b>", messenger.last().text());
    }

    @Test
    void cancelHandlerCanTakeOver() throws Exception {
        routes.onCancel((c, explicit) -> hits.add("feature cancel " + explicit));
        dispatcher.dispatch(text(WORKER, MainMenu.BACK));
        assertEquals(List.of("feature cancel false"), hits);
    }

    @Test
    void menuButtonResetsDialogsAndClearsChatBeforeCommand() throws Exception {
        sessions.get(WORKER).put(DIALOG, "x");
        routes.onCommand(MainMenu.WELDERS, c -> hits.add("welders " + c.session().has(DIALOG)));
        dispatcher.dispatch(text(WORKER, MainMenu.WELDERS));
        assertEquals(List.of("welders false"), hits);
        assertTrue(messenger.calls.stream().anyMatch(c -> c.type().equals("clear")));
    }

    @Test
    void adminWithPendingRequestsIsBlockedExceptStart() throws Exception {
        db.update("INSERT INTO users (id, full_name, role) VALUES (50, 'Новый', 'PENDING')");
        routes.onCommand("/start", c -> hits.add("start"));
        routes.onCommand(MainMenu.ADMIN_USERS, c -> hits.add("users"));

        dispatcher.dispatch(text(ADMIN, MainMenu.ADMIN_USERS));
        assertTrue(hits.isEmpty());
        assertTrue(messenger.texts().stream().anyMatch(t -> t.contains("ДЕЙСТВИЕ ПРИОСТАНОВЛЕНО")));

        dispatcher.dispatch(text(ADMIN, "/start"));
        assertEquals(List.of("start"), hits);
    }

    @Test
    void bannedUserIsIgnored() throws Exception {
        db.update("UPDATE users SET role = 'BANNED' WHERE id = ?", WORKER);
        routes.onCommand("/start", c -> hits.add("start"));
        dispatcher.dispatch(text(WORKER, "/start"));
        assertTrue(hits.isEmpty());
    }

    @Test
    void callbackBindCheckSeparatesStages() throws Exception {
        routes.onCallback("BEFORE").beforeBindCheck().handle(c -> hits.add("before"));
        routes.onCallback("AFTER").handle(c -> hits.add("after"));
        routes.onCallbackPrefix("BIND_NAME:").handle(c -> hits.add("bind " + c.payload("BIND_NAME:")));

        dispatcher.dispatch(callback(UNBOUND, "BEFORE"));
        dispatcher.dispatch(callback(UNBOUND, "AFTER"));
        dispatcher.dispatch(callback(UNBOUND, "BIND_NAME:Сидоров С.С."));

        assertEquals(List.of("before", "bind Сидоров С.С."), hits);
        assertTrue(messenger.texts().contains("⚠️ Сначала выберите фамилию!"));
    }

    @Test
    void unansweredCallbackIsAnsweredAutomatically() throws Exception {
        routes.onCallback("X").handle(c -> hits.add("x"));
        dispatcher.dispatch(callback(WORKER, "X"));
        assertEquals("answer", messenger.last().type());
    }

    @Test
    void interceptorRunsBeforeRoutes() throws Exception {
        routes.interceptCallbacks(c -> c.data().equals("LOCKED"));
        routes.onCallback("LOCKED").beforeBindCheck().handle(c -> hits.add("locked"));
        dispatcher.dispatch(callback(WORKER, "LOCKED"));
        assertTrue(hits.isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static Update text(long chatId, String text) {
        Message m = message(chatId);
        m.setText(text);
        Update u = new Update();
        u.setMessage(m);
        return u;
    }

    private static Update callback(long chatId, String data) {
        CallbackQuery q = new CallbackQuery();
        q.setId("cb-" + data);
        q.setData(data);
        q.setFrom(new User(chatId, "Тест", false));
        q.setMessage(message(chatId));
        Update u = new Update();
        u.setCallbackQuery(q);
        return u;
    }

    private static Message message(long chatId) {
        Message m = new Message();
        m.setMessageId(500);
        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setType("private");
        m.setChat(chat);
        m.setFrom(new User(chatId, "Тест", false));
        return m;
    }
}
