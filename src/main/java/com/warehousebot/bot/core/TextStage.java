package com.warehousebot.bot.core;

/**
 * Порядок обработки текстового сообщения.
 *
 * <p>Порядок важен и полностью повторяет прежнюю цепочку if-ов в WarehouseBot.handleUpdate:
 * например, ввод количества материала должен перехватываться раньше глобального поиска инструмента.
 * Стадии, помеченные «ядро», регистрирует сам диспетчер.
 */
public enum TextStage {
    /** Сварочники: получатель передачи должен сначала ответить на запрос. */
    WELDER_RECEIVER_LOCK,
    /** Ядро: мастер не выбрал фамилию из графика. */
    BIND_CHECK,
    /** Справочник неисправностей (кнопка «🛠 Неисправности»). */
    ERRORS_MENU,
    /** Ядро: «❌ Отменить» / «🔙 Назад». */
    CANCEL,
    /** Ядро: кнопка главного меню сбрасывает незавершённые диалоги (не поглощает сообщение). */
    MENU_RESET,
    NAME_FIX_INPUT,
    TM_REPORT_INPUT,
    FIX_NAME_COMMAND,
    DIRECTORY_CONTACT_INPUT,
    NEW_EMPLOYEE_INPUT,
    SCHEDULE_EDIT_INPUT,
    SICK_LEAVE_INPUT,
    UNBIND_COMMAND,
    /** Сварочники: блокировка в конце смены, пока не отчитался за аппарат. */
    WELDER_RETURN_LOCK,
    /** Ядро: администратор не обработал заявки. */
    ADMIN_INBOX_LOCK,
    TOOL_TAKE_COMMAND,
    TOOL_GIVE_COMMAND,
    WAREHOUSE_QTY_INPUT,
    TAKE_QTY_INPUT,
    TOOL_WRITE_OFF_REASON_INPUT,
    WRITE_OFF_INPUT,
    RECEIPT_QTY_INPUT,
    BROADCAST_INPUT,
    BAN_COMMANDS,
    FIRE_COMMAND,
    ORSH_PROBLEM_INPUT,
    TM_LOGIN_INPUT,
    /** Ядро: точное совпадение с кнопкой меню или командой (/start, /fix, …). */
    MENU_COMMANDS,
    /** Всё остальное: «+услуга», поиск инструмента, подсказка. */
    FALLBACK
}
