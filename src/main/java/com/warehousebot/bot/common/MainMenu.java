package com.warehousebot.bot.common;

import com.warehousebot.bot.core.Messenger;
import com.warehousebot.domain.Role;
import com.warehousebot.service.UserService;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.ArrayList;
import java.util.List;

/** Нижняя клавиатура главного меню и тексты её кнопок. */
public final class MainMenu {

    public static final String TM_TASKS = "🛠 Мои заявки (ТМ)";
    public static final String WAREHOUSE = "📦 Склад (Наличие и цены)";
    public static final String MY_INVENTORY = "🧰 Мой подотчет";
    public static final String WRITE_OFF = "📝 Списать / Вернуть";
    public static final String WELDERS = "⚡️ Сварочные аппараты";
    public static final String RECEIPT_CALCULATOR = "🧾 Калькулятор квитанции";
    public static final String TARIFFS = "📋 Тарифы услуг";
    public static final String ORSH_INSPECTION = "📸 Плановый осмотр ОРШ";
    public static final String SCHEDULE = "🗓 График работ";
    public static final String CLOSING_CODES = "🔢 Коды закрытия";
    public static final String DIRECTORY = "📞 Справочник";
    public static final String ERRORS = "🛠 Неисправности";

    public static final String ADMIN_BALANCES = "📊 У кого что на руках";
    public static final String ADMIN_TOOLS = "🛠 Управление инструментом";
    public static final String ADMIN_REPORT = "📑 Скачать отчет за месяц";
    public static final String ADMIN_ORSH_STATS = "📊 Статистика ОРШ";
    public static final String ADMIN_BROADCAST = "📢 Сделать рассылку";
    public static final String ADMIN_USERS = "👥 Пользователи";
    public static final String ADMIN_UPLOADS = "📥 Загрузки (Excel)";

    public static final String CANCEL = "❌ Отменить";
    public static final String BACK = "🔙 Назад";

    private final Messenger messenger;
    private final UserService userService;

    public MainMenu(Messenger messenger, UserService userService) {
        this.messenger = messenger;
        this.userService = userService;
    }

    /** Отправляет текст вместе с клавиатурой главного меню (аналог прежнего sendMenu). */
    public void show(long chatId, Role role, String html) {
        messenger.sendLong(chatId, html, keyboard(chatId, role));
    }

    private ReplyKeyboardMarkup keyboard(long chatId, Role role) {
        List<KeyboardRow> rows = new ArrayList<>();
        if (userService.canAccessTm(chatId, role)) rows.add(row(TM_TASKS));
        rows.add(row(WAREHOUSE, MY_INVENTORY));
        rows.add(row(WRITE_OFF, WELDERS));
        rows.add(row(RECEIPT_CALCULATOR, TARIFFS));
        rows.add(row(ORSH_INSPECTION, SCHEDULE));
        rows.add(row(CLOSING_CODES, DIRECTORY));
        rows.add(row(ERRORS));
        if (role.isAdmin()) {
            rows.add(row(ADMIN_BALANCES, ADMIN_TOOLS));
            rows.add(row(ADMIN_REPORT, ADMIN_ORSH_STATS));
            rows.add(row(ADMIN_BROADCAST, ADMIN_USERS));
            rows.add(row(ADMIN_UPLOADS));
        }
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        markup.setKeyboard(rows);
        return markup;
    }

    private static KeyboardRow row(String... labels) {
        KeyboardRow row = new KeyboardRow();
        for (String l : labels) row.add(l);
        return row;
    }
}
