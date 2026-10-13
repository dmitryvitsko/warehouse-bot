package com.warehousebot.bot.feature.tm;

import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.BotModule;
import com.warehousebot.bot.core.CallbackContext;
import com.warehousebot.bot.core.Messenger;
import com.warehousebot.bot.core.Routes;
import com.warehousebot.bot.core.TextContext;
import com.warehousebot.bot.core.TextStage;
import com.warehousebot.bot.session.SessionKey;
import com.warehousebot.bot.session.UserSession;
import com.warehousebot.domain.Role;
import com.warehousebot.infrastructure.tm.TmClient;
import com.warehousebot.service.TmService;
import com.warehousebot.service.UserService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** «🛠 Мои заявки (ТМ)»: вход в ТМ, списки заявок и карточка заявки (АСТУП, параметры, история). */
public final class TmModule implements BotModule {

    private static final String WAIT_LOGIN = "WAIT_LOGIN";
    private static final String WAIT_PASSWORD = "WAIT_PASSWORD";

    /** Шаг входа в ТМ: ждём логин или пароль. */
    private static final SessionKey<String> AUTH_STEP = SessionKey.dialog("tm.authStep");
    /** Логин, введённый на первом шаге входа. */
    private static final SessionKey<String> TEMP_LOGIN = SessionKey.dialog("tm.tempLogin");
    /** Сообщение-меню «В работе / Закрытые», которое стирается при открытии списка. */
    private static final SessionKey<Integer> MENU_MESSAGE_ID = SessionKey.persistent("tm.menuMessageId");
    /** Карточки текущего списка заявок и кнопка «Обновить» — удаляются при выводе нового списка. */
    private static final SessionKey<List<Integer>> ACTIVE_MESSAGES = SessionKey.persistent("tm.activeMessages");
    /**
     * Исходный текст карточек текущего списка (id сообщения → текст) для кнопки «⬅ Назад к заявке».
     * Хранится по чату: id сообщений в разных личных чатах совпадают.
     */
    private static final SessionKey<Map<Integer, String>> CARD_TEXTS = SessionKey.persistent("tm.cardTexts");

    private final Messenger messenger;
    private final Ui ui;
    private final MainMenu menu;
    private final UserService userService;
    private final TmService tm;

    public TmModule(Messenger messenger, Ui ui, MainMenu menu, UserService userService, TmService tm) {
        this.messenger = messenger;
        this.ui = ui;
        this.menu = menu;
        this.userService = userService;
        this.tm = tm;
    }

    @Override
    public void register(Routes r) {
        r.onCommand(MainMenu.TM_TASKS, this::openTasks);
        r.onText(TextStage.TM_LOGIN_INPUT, this::onLoginInput);

        r.onCallback("TM_CLOSE").beforeBindCheck().handle(ctx -> clearPreviousTasks(ctx.session(), ctx.chatId()));
        r.onCallback("TM_REFRESH").when(this::canAccess).handle(this::refresh);
        r.onCallback("TM_INPROGRESS").when(this::canAccess).handle(this::inProgress);
        r.onCallback("TM_CLOSED").when(this::canAccess).handle(ctx ->
                ui.closable(ctx.chatId(), TmView.formatTasksText(tm.fetchTasks(ctx.chatId()), true)));
        r.onCallbackPrefix("TM_ASTUP:").when(this::canAccess).handle(ctx -> {
            String taskId = ctx.payload("TM_ASTUP:");
            showDetails(ctx, TmView.formatAstup(tm.astup(ctx.chatId(), taskId)), taskId);
        });
        r.onCallbackPrefix("TM_PARAMS:").when(this::canAccess).handle(ctx -> {
            String taskId = ctx.payload("TM_PARAMS:");
            showDetails(ctx, TmView.formatParams(tm.measureParams(ctx.chatId(), taskId)), taskId);
        });
        r.onCallbackPrefix("TM_HIST:").when(this::canAccess).handle(ctx -> {
            String taskId = ctx.payload("TM_HIST:");
            showDetails(ctx, TmView.formatHistory(tm.history(ctx.chatId(), taskId), taskId), taskId);
        });
        r.onCallbackPrefix("TM_BACK:").when(this::canAccess).handle(this::backToCard);
    }

    /** Доступ к ТМ: администратор или сотрудник с выданным доступом. */
    boolean canAccess(CallbackContext ctx) {
        return userService.canAccessTm(ctx.chatId(), ctx.role());
    }

    // ------------------------------------------------------------------ вход и меню

    private void openTasks(TextContext ctx) {
        if (!userService.canAccessTm(ctx.chatId(), ctx.role())) return;
        JSONArray tasks = tm.fetchTasks(ctx.chatId());
        if (tasks == null) {
            ctx.session().put(AUTH_STEP, WAIT_LOGIN);
            ui.cancelPrompt(ctx.chatId(), "🔑 <b>Вход в систему ТМ</b>\nВведите ваш логин:");
        } else {
            sendTasksMenu(ctx.session(), ctx.chatId(), tasks);
        }
    }

    private boolean onLoginInput(TextContext ctx) {
        String step = ctx.session().get(AUTH_STEP);
        if (WAIT_LOGIN.equals(step)) {
            ctx.session().put(TEMP_LOGIN, ctx.trimmed());
            ctx.session().put(AUTH_STEP, WAIT_PASSWORD);
            ui.cancelPrompt(ctx.chatId(), "🔑 Теперь введите пароль от ТМ:");
            return true;
        }
        if (!WAIT_PASSWORD.equals(step)) return false;

        ctx.session().remove(AUTH_STEP);
        String login = ctx.session().remove(TEMP_LOGIN);
        String session = tm.login(ctx.chatId(), login, ctx.trimmed());
        if (session == null) {
            menu.show(ctx.chatId(), ctx.role(), "❌ Неверный логин или пароль ТМ. Нажмите «🛠 Мои заявки (ТМ)», чтобы попробовать снова.");
            return true;
        }
        JSONArray tasks = tm.tasks(session);
        if (tasks != null) sendTasksMenu(ctx.session(), ctx.chatId(), tasks);
        else menu.show(ctx.chatId(), ctx.role(), "❌ Не удалось получить заявки.");
        return true;
    }

    private void sendTasksMenu(UserSession session, long chatId, JSONArray tasks) {
        Message sent = messenger.send(chatId, TmView.TASKS_MENU, TmView.tasksMenuKeyboard(tasks));
        if (sent != null) session.put(MENU_MESSAGE_ID, sent.getMessageId());
    }

    // ------------------------------------------------------------------ список «в работе»

    private void refresh(CallbackContext ctx) {
        messenger.edit(ctx.chatId(), ctx.messageId(), "⏳ <i>Опрашиваю сервер АСТУП...</i>", null);
        showInProgress(ctx.session(), ctx.chatId(), ctx.role());
    }

    private void inProgress(CallbackContext ctx) {
        deleteTasksMenu(ctx.session(), ctx.chatId());
        showInProgress(ctx.session(), ctx.chatId(), ctx.role());
    }

    /** Загружает заявки и выводит список «в работе» (карточки + кнопка «Обновить»). */
    void showInProgress(UserSession session, long chatId, Role role) {
        sendInProgressList(session, chatId, role, tm.fetchTasks(chatId));
    }

    private void sendInProgressList(UserSession session, long chatId, Role role, JSONArray tasks) {
        // Стираем предыдущий список, чтобы не захламлять чат
        clearPreviousTasks(session, chatId);

        if (tasks == null) {
            menu.show(chatId, role, "❌ Не удалось получить заявки.");
            return;
        }
        JSONArray inProgress = TmClient.byStatus(tasks, false);
        if (inProgress.isEmpty()) {
            menu.show(chatId, role, "📋 Заявок в работе нет.");
            return;
        }

        List<Integer> newIds = new ArrayList<>();
        Map<Integer, String> cardTexts = new HashMap<>();
        for (int i = 0; i < inProgress.length(); i++) {
            JSONObject task = inProgress.getJSONObject(i);
            String taskId = task.optString("id");
            String cardText = TmView.cardText(task);
            String buttonText = TmView.reportButtonText(task.optInt("task_status", 0));
            Message sent = messenger.send(chatId, cardText, TmView.cardKeyboard(buttonText, taskId));
            if (sent != null) {
                cardTexts.put(sent.getMessageId(), cardText);
                newIds.add(sent.getMessageId());
            }
        }

        int taskCount = inProgress.length();
        Message refresh = messenger.send(chatId, TmView.refreshText(taskCount), TmView.refreshKeyboard(taskCount));
        if (refresh != null) newIds.add(refresh.getMessageId());

        session.put(ACTIVE_MESSAGES, newIds);
        session.put(CARD_TEXTS, cardTexts);
    }

    private void clearPreviousTasks(UserSession session, long chatId) {
        List<Integer> oldIds = session.remove(ACTIVE_MESSAGES);
        if (oldIds == null) return;
        for (Integer id : oldIds) messenger.delete(chatId, id);
    }

    private void deleteTasksMenu(UserSession session, long chatId) {
        Integer menuId = session.remove(MENU_MESSAGE_ID);
        if (menuId != null) messenger.delete(chatId, menuId);
    }

    /**
     * Перед началом отчёта: убирает меню и весь список заявок, кроме карточки {@code keepMessageId},
     * которая становится окном мастера отчёта.
     */
    void clearListExcept(UserSession session, long chatId, int keepMessageId) {
        deleteTasksMenu(session, chatId);
        List<Integer> active = session.remove(ACTIVE_MESSAGES);
        if (active == null) return;
        for (Integer id : active) {
            if (id != keepMessageId) messenger.delete(chatId, id);
        }
    }

    // ------------------------------------------------------------------ карточка заявки

    /** АСТУП / Параметры / История: заменяет текст карточки, под ним кнопка «⬅ Назад к заявке». */
    private void showDetails(CallbackContext ctx, String text, String taskId) {
        messenger.edit(ctx.chatId(), ctx.messageId(), text, TmView.backToTaskKeyboard(taskId));
    }

    /** «⬅ Назад к заявке»: восстанавливает карточку с актуальным статусом. */
    private void backToCard(CallbackContext ctx) {
        String taskId = ctx.payload("TM_BACK:");
        JSONArray tasks = tm.fetchTasks(ctx.chatId());
        if (tasks == null) return;

        JSONObject current = null;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject t = tasks.optJSONObject(i);
            if (t != null && taskId.equals(t.optString("id"))) {
                current = t;
                break;
            }
        }
        if (current == null) {
            menu.show(ctx.chatId(), ctx.role(), "✅ Эта заявка уже закрыта диспетчером и убрана из вашего списка.");
            messenger.delete(ctx.chatId(), ctx.messageId());
            return;
        }

        Map<Integer, String> cardTexts = ctx.session().getOrCreate(CARD_TEXTS, HashMap::new);
        int status = current.optInt("task_status", 0);
        String cardText = cardTexts.get(ctx.messageId());
        if (cardText == null) cardText = TmView.formatTask(current);

        if (status == 1 && !cardText.contains(TmView.UNDER_REVIEW_MARK)) {
            cardText = TmView.underReviewPrefix() + cardText;
            cardTexts.put(ctx.messageId(), cardText);
        } else if (status == 2 && !cardText.contains(TmView.REJECTED_MARK)) {
            cardText = TmView.rejectedPrefix(current) + cardText;
            cardTexts.put(ctx.messageId(), cardText);
        }
        messenger.edit(ctx.chatId(), ctx.messageId(), cardText, TmView.cardKeyboard(TmView.reportButtonText(status), taskId));
    }
}
