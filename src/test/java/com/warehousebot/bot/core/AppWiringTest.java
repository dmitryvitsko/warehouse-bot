package com.warehousebot.bot.core;

import com.warehousebot.App;
import com.warehousebot.AppContext;
import com.warehousebot.Feature;
import com.warehousebot.bot.common.AccessGate;
import com.warehousebot.bot.common.MainMenu;
import com.warehousebot.bot.common.Ui;
import com.warehousebot.bot.core.Routes.CallbackStage;
import com.warehousebot.bot.session.SessionStore;
import com.warehousebot.bot.session.UserSession;
import com.warehousebot.config.AppConfig;
import com.warehousebot.domain.Role;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.mail.EmailSender;
import com.warehousebot.repository.ReturnRequestRepository;
import com.warehousebot.repository.UserRepository;
import com.warehousebot.service.UserService;
import com.warehousebot.testutil.FakeMessenger;
import com.warehousebot.testutil.TestDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сборка всего приложения: модули регистрируются без конфликтов, и каждая кнопка/команда прежней
 * версии (в том числе старые кнопки, оставшиеся в чатах пользователей) находит обработчик.
 */
class AppWiringTest {

    /** Callback data всех веток прежнего WarehouseBot.handleUpdate (с примерами аргументов). */
    private static final List<String> LEGACY_CALLBACKS = List.of(
            "ADM_CLR_STOCK_YES", "ADM_PANEL_CLR_STOCK", "ADM_PANEL_DELETE", "ADM_PANEL_FIX_REQ", "ADM_PANEL_LIST",
            "ADM_PANEL_MAIN", "ADM_PANEL_RENAME", "ADM_PANEL_UNBIND", "ADM_TM_ACCESS_MAIN", "ADM_TM_HAS_ACCESS",
            "ADM_TM_NO_ACCESS", "CANCEL_PROMPT", "CART_ADD_MAT", "CART_ADD_SRV", "CART_CLEAR", "CART_FINISH", "CART_MENU",
            "DIR_ADD_MENU", "DIR_BACK", "DIR_DEL_MENU", "ERRORS_MAIN", "ERRORS_MODEMS", "ERRORS_STB", "GENERIC_CLOSE",
            "INV_CLOSE", "INV_MAIN", "INV_MATS", "INV_TOOLS", "SCHED_ADD", "SCHED_COL_LIST", "SCHED_EDIT", "SCHED_MAIN",
            "SCHED_MINE", "SCHED_SICK", "SCHED_TODAY", "SCHED_TOMORROW", "SCHED_WITH_ME", "TM_CLOSE", "TM_REFRESH",
            "TM_INPROGRESS", "TM_CLOSED", "TM_REP_CANCEL", "TM_REP_EDIT_MAT", "TM_REP_EDIT_TXT", "TM_REP_MAT_DONE",
            "TM_REP_PREVIEW", "TM_REP_SEND", "WH_CLOSE", "WO_BACK_LIST", "WO_CHECKOUT", "WO_CLEAR_CART", "W_CLOSE",
            "W_FORCE_RET_M", "W_FORCE_TAKE_M", "W_HISTORY", "W_KEEP_TOMORROW", "W_MAIN_MENU", "IGNORE",
            "ADM_DEL_CONFIRM:Иванов", "ADM_DEL_DO:Иванов", "ADM_REN_SEL:Иванов", "ADM_TM_TOGGLE:5:true:false",
            "ADM_UNB_DO:5", "BIND_NAME:Иванов", "CART_SEL_MAT:1", "CART_SEL_SRV:1", "COL_SCHED:2026:10:Иванов",
            "DIR_CAT:1", "DIR_DEL:1", "ED_SCH_S:В", "ED_SCH_U:Иванов", "F_ASS:1:5", "MY_SCHED:2026:10",
            "NEW_USER_APP:5", "NEW_USER_REJ:5", "ORSH_PROB:1", "ORSH_SEL:1", "RET_APP:1", "RET_REJ:1", "SCHED_DAY_1",
            "SL_APPROVE:1", "SL_REJECT:1", "TAKE_MAT:1", "TAKE_Q:5", "TM_ASTUP:7", "TM_BACK:7", "TM_HIST:7",
            "TM_PARAMS:7", "TM_REPORT:7", "TM_REP_CODE:102", "TM_REP_FAST_CONF:YES", "TM_REP_HAS_REC:YES",
            "TM_REP_MAT:1", "TM_REP_RET:PLUS", "TM_REP_TYPE:PON", "T_ASS_U:1:5", "T_RES_DO:1", "T_RET_T:1", "T_RET_U:5",
            "T_SEL_N:1", "T_SUMM_RALL:5", "T_SUMM_RET:1:5", "T_SUMM_U:5", "T_WO_DO:1", "T_WO_G:1", "T_WO_LOC:STOCK",
            "T_WO_U:5", "UPL_SCHED:2026:10", "VIEW_SCHED:Иванов", "WH_ITEM:1:1", "WH_PAGE:2", "WH_TAKE:1:5:1",
            "WO_CODE:212", "WO_MAT:1", "WO_Q:5", "WO_TYPE:PAID", "W_F_ASS:1:5", "W_F_RET:1", "W_F_SEL:1", "W_RET:1",
            "W_TAKE:1", "W_TRANS_ACC:1:5", "W_TRANS_CANCEL:1", "W_TRANS_REJ:1:5", "W_TRANS_SEL:1:5", "W_TRANS_START:1",
            "tool_menu_main", "tool_menu_close", "tool_menu_audit", "tool_menu_archive", "tool_menu_notify",
            "tool_menu_audit_users", "tool_menu_give", "tool_menu_return", "tool_menu_restore", "tool_menu_writeoff");

    /** Кнопки главного меню и точные команды прежнего switch. */
    private static final List<String> LEGACY_COMMANDS = List.of(
            "/clear_stock", "/fix", "/start", "⚡️ Сварочные аппараты", "👥 Пользователи", "📊 Статистика ОРШ",
            "📊 У кого что на руках", "📋 Тарифы услуг", "📑 Скачать отчет за месяц", "📝 Списать / Вернуть",
            "📞 Справочник", "📢 Сделать рассылку", "📥 Загрузить ведомость (Excel)", "📥 Загрузить график (Excel)",
            "📥 Загрузить инструмент (Excel)", "📥 Загрузить план ОРШ (Excel)", "📥 Загрузки (Excel)",
            "📦 Склад (Наличие и цены)", "📸 Плановый осмотр ОРШ", "🔢 Коды закрытия", "🗓 График работ",
            "🛠 Мои заявки (ТМ)", "🛠 Управление инструментом", "🧰 Мой подотчет", "🧾 Калькулятор квитанции");

    private Routes routes;
    private SessionStore sessions;

    @BeforeEach
    void wireApp() {
        Database db = TestDb.create();
        db.update("INSERT INTO users (id, full_name, role) VALUES (1, 'Админ', 'ADMIN')");
        FakeMessenger messenger = new FakeMessenger();
        UserRepository users = new UserRepository(db);
        ReturnRequestRepository returns = new ReturnRequestRepository(db);
        UserService userService = new UserService(users, 1);
        sessions = new SessionStore();
        AppContext ctx = new AppContext(AppConfig.of(new Properties()), db, messenger, sessions,
                new Ui(messenger, users), new MainMenu(messenger, userService),
                new AccessGate(messenger, userService, users, returns), users, userService, returns,
                new EmailSender(null, null, null), Executors.newSingleThreadScheduledExecutor());

        routes = new Routes();
        for (Feature f : App.createFeatures(ctx)) {
            for (BotModule m : f.modules()) m.register(routes); // дубликат команды бросит исключение
        }
    }

    @Test
    void everyLegacyCallbackHasHandler() {
        UserSession session = sessions.get(1);
        // Некоторые кнопки доступны только внутри открытого диалога (как и раньше) — открываем их сессии.
        sessions.get(1).resetDialogs();
        List<String> missing = new ArrayList<>();
        for (String data : LEGACY_CALLBACKS) {
            CallbackContext ctx = new CallbackContext(1, 10, data, "Админ", Role.ADMIN, "q", session, new FakeMessenger());
            boolean found = routes.findCallback(CallbackStage.BEFORE_BIND_CHECK, ctx) != null
                    || routes.findCallback(CallbackStage.AFTER_BIND_CHECK, ctx) != null
                    || isDialogOnly(data);
            if (!found) missing.add(data);
        }
        assertTrue(missing.isEmpty(), "Нет обработчика для: " + missing);
    }

    @Test
    void everyLegacyCommandIsRegistered() {
        List<String> missing = new ArrayList<>();
        for (String c : LEGACY_COMMANDS) if (!routes.isCommand(c)) missing.add(c);
        assertTrue(missing.isEmpty(), "Нет команды: " + missing);
    }

    /** Кнопки корзины списания работают только при открытой корзине (условие из прежней версии). */
    private static boolean isDialogOnly(String data) {
        return data.startsWith("WO_Q:") || data.startsWith("WO_TYPE:") || data.startsWith("WO_CODE:");
    }
}
