import org.json.JSONObject;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.json.JSONArray;
import java.util.function.Function;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class WarehouseBot extends TelegramLongPollingBot {

    private final Map<Long, DatabaseManager.ReceiptSession> receiptSessions = new HashMap<>();
    private final Map<Long, Integer> waitingTakeMaterialId = new HashMap<>();
    private final Map<Long, String> fileWaitState = new HashMap<>();
    private final Map<Long, Integer> waitingToolWriteOffReason = new HashMap<>();
    private final Map<Long, Integer> waitingOrshPhoto = new HashMap<>();
    private final Map<Long, Integer> waitingOrshProblemReason = new HashMap<>();
    private final Map<Long, Integer> waitingDirContactCat = new HashMap<>();
    private final Map<Long, Boolean> waitingNewEmployeeName = new ConcurrentHashMap<>();
    private final Map<Long, Integer> waitingSickLeaveDate = new ConcurrentHashMap<>();
    private final Map<Long, String> tmAuthStep = new HashMap<>();
    private final Map<Long, String> waitingTmReportTaskId = new HashMap<>();
    private final Map<Long, String> tmTempLogin = new HashMap<>();
    private final Map<Integer, String> tmOriginalCardText = new HashMap<>();
    private final Map<Long, String> waitingNameFix = new HashMap<>();

    // --- ПАМЯТЬ И КЛАСС ДЛЯ ЗАКРЫТИЯ ЗАЯВКИ ТМ ---
    private final Map<Long, TmReportSession> tmReportSessions = new ConcurrentHashMap<>();
    // Запоминаем ID сообщения "Мои заявки ТМ (В работе / Закрытые)", чтобы вовремя его стирать
    private final Map<Long, Integer> tmMenuMessageIds = new ConcurrentHashMap<>();

    public static class TmReportSession {
        public String taskId;
        public Integer anchorMsgId;
        public String type = ""; // "PON" или "SHPD"
        public String closingCode = "";
        public boolean hasReceipt = false;
        public String receiptNumber = "";

        public List<DatabaseManager.ReceiptItem> usedMaterials = new ArrayList<>();
        public int waitingMaterialId = -1;

        public String reportText = "";
        public String retailStatus = ""; // "Ритейл+", "Ритейл-", "Не указывать"
        public String step = "";
        public boolean isEditing = false; // 👈 ДОБАВЛЕНО: флаг точечного редактирования
    }

    private final Map<Long, WriteOffCartSession> writeOffCartSessions = new ConcurrentHashMap<>();

    public static class WriteOffCartSession {
        public Integer anchorMsgId;
        public String step = "";

        // Список уже добавленных в корзину материалов
        public List<WriteOffSession> items = new ArrayList<>();
        // Временное хранение материала, пока вводится количество
        public WriteOffSession tempItem = null;

        public boolean isPaidReceipt = false;
        public String receiptNumber = "";
        public String phoneNumber = "";
        public String contractNumber = "";
        public String address = "";
        public String closingCode = "";
        public String reason = "";
    }

    private final Map<Long, List<Integer>> tmActiveTaskMessages = new ConcurrentHashMap<>();

    private final Map<Long, String> waitingScheduleEditUser = new HashMap<>();
    private final Map<Long, String> waitingScheduleEditDate = new HashMap<>();
    private final Map<Long, Integer> forceWelderReturnIds = new HashMap<>();
    // Память: кто уже нажал "Оставить на завтра" (чтобы не спамить)
    private final Map<Long, LocalDate> keptWelderForTomorrow = new ConcurrentHashMap<>();

    public static class PendingWelderTransfer {
        public int welderId;
        public long senderId;
        public long receiverId;
        public Integer senderMsgId;
        public Integer receiverMsgId;
    }
    // Блокировка и память для передачи аппаратов
    private final Map<Long, PendingWelderTransfer> pendingWelderTransfers = new ConcurrentHashMap<>();
    private final Map<Long, Long> activeSenderTransfers = new ConcurrentHashMap<>();

    private static final Properties config = new Properties();
    static {
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            config.load(fis);
        } catch (IOException e) {
            throw new RuntimeException("❌ Ошибка: не найден файл config.properties в корне проекта!", e);
        }
    }

    public WarehouseBot() {
        super(config.getProperty("bot.token"));
    }

    @Override
    public String getBotUsername() {
        return config.getProperty("bot.username");
    }

    private String getMonthName(int month) {
        String[] monthNames = {"", "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"};
        return monthNames[month];
    }

    private void sendScheduleView(long chatId, String excelName, int year, int month, boolean isMine, Integer messageId) {
        String scheduleText = DatabaseManager.getFormattedSchedule(excelName, year, month);

        LocalDate current = LocalDate.of(year, month, 1);
        LocalDate prev = current.minusMonths(1);
        LocalDate next = current.plusMonths(1);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        String prefix = isMine ? "MY_SCHED:" : "COL_SCHED:";
        String extra = isMine ? "" : (":" + excelName);

        rows.add(List.of(
                createBtn("⬅️ " + getMonthName(prev.getMonthValue()), prefix + prev.getYear() + ":" + prev.getMonthValue() + extra),
                createBtn(getMonthName(next.getMonthValue()) + " ➡️", prefix + next.getYear() + ":" + next.getMonthValue() + extra)
        ));

        if (!isMine) {
            rows.add(List.of(createBtn("🔙 К списку коллег", "SCHED_COL_LIST")));
        } else {
            // Добавляем кнопку "Назад" для своего графика
            rows.add(List.of(createBtn("🔙 Назад", "SCHED_MAIN")));
        }

        // Добавим заодно и крестик, чтобы график можно было закрыть прямо отсюда!
        rows.add(List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE")));

        markup.setKeyboard(rows);

        try {
            if (messageId == null) {
                SendMessage msg = new SendMessage(String.valueOf(chatId), scheduleText);
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                execute(msg);
            } else {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(scheduleText); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                execute(edit);
            }
        } catch (TelegramApiException e) {}
    }

    private void sendScheduleMenu(long chatId, String role, String text, Integer messageId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(List.of(createBtn("🗓 Мой график", "SCHED_MINE"), createBtn("🤝 С кем я в смене?", "SCHED_WITH_ME")));
        rows.add(List.of(createBtn("👁 График коллеги", "SCHED_COL_LIST"), createBtn("👥 Кто сегодня работает?", "SCHED_TODAY")));
        rows.add(List.of(createBtn("🔜 Кто работает завтра?", "SCHED_TOMORROW")));
        rows.add(List.of(createBtn("🤒 Сообщить о больничном", "SCHED_SICK")));

        List<InlineKeyboardButton> adminRow = new ArrayList<>();
        if (canEditSchedule(chatId, role)) adminRow.add(createBtn("✏️ Изменить смену", "SCHED_EDIT"));
        if ("ADMIN".equals(role)) adminRow.add(createBtn("➕ Добавить сотрудника", "SCHED_ADD"));
        if (!adminRow.isEmpty()) rows.add(adminRow);

        rows.add(List.of(createBtn("❌ Закрыть графики", "GENERIC_CLOSE")));
        markup.setKeyboard(rows);

        try {
            if (messageId == null) {
                SendMessage msg = new SendMessage(String.valueOf(chatId), text);
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                execute(msg);
            } else {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(text); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                execute(edit);
            }
        } catch (TelegramApiException e) {}
    }

    private void sendWeldersMenu(long chatId, String role, Integer messageId, String statusMsg) {
        List<String[]> welders = DatabaseManager.getWeldersStatus();
        StringBuilder sb = new StringBuilder();

        if (statusMsg != null && !statusMsg.isEmpty()) {
            sb.append("✅ <i>").append(statusMsg).append("</i>\n\n");
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        boolean hasBusy = false;
        String myBusyWelderId = null;
        String myBusyWelderName = null;

        for (String[] w : welders) {
            String wId = w[0], wName = w[1], status = w[2], userName = w[3];
            String assignedToId = w.length > 5 ? w[5] : "";

            if ("IN_USE".equals(status)) {
                if (!hasBusy) { sb.append("🔴 <b>В работе у коллег:</b>\n"); hasBusy = true; }
                sb.append("▫ ").append(wName).append(" 👉 у <b>").append(userName).append("</b>\n");

                if (String.valueOf(chatId).equals(assignedToId)) {
                    myBusyWelderId = wId; myBusyWelderName = wName;
                }
            }
        }

        if (myBusyWelderId != null) {
            sb.insert(statusMsg != null ? sb.indexOf("\n\n") + 2 : 0, "⚠️ <b>На вас сейчас числится: " + myBusyWelderName + "</b>\n\n");
            // ДОБАВЛЕНА КНОПКА ПЕРЕДАЧИ
            rows.add(List.of(
                    createBtn("↩️ ВЕРНУТЬ НА БАЗУ", "W_RET:" + myBusyWelderId),
                    createBtn("🔄 Передать коллеге", "W_TRANS_START:" + myBusyWelderId)
            ));
            sb.append("\n👇 <b>Управление и свободные аппараты:</b>");
        } else {
            sb.append("\n👇 <b>Выберите свобод аппарат, который берете с базы:</b>");
        }

        for (String[] w : welders) {
            if ("ON_BASE".equals(w[2])) rows.add(List.of(createBtn("⚡️ " + w[1], "W_TAKE:" + w[0])));
        }

        rows.add(List.of(createBtn("📜 История логов", "W_HISTORY")));

        if ("ADMIN".equals(role)) {
            rows.add(List.of(createBtn("➕ Выдать принудительно", "W_FORCE_TAKE_M"), createBtn("⚠️ Вернуть принудительно", "W_FORCE_RET_M")));
        }

        rows.add(List.of(createBtn("❌ Закрыть панель", "W_CLOSE")));

        markup.setKeyboard(rows);

        try {
            if (messageId == null) {
                SendMessage msg = new SendMessage(String.valueOf(chatId), sb.toString());
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                execute(msg);
            } else {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(sb.toString()); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                execute(edit);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void sendWelderTransferUsersMenu(long chatId, int welderId, int messageId) {
        List<String[]> users = DatabaseManager.getUsersForToolAssignment();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> currentRow = new ArrayList<>();

        for (String[] u : users) {
            long uId = Long.parseLong(u[0]);
            if (uId == chatId) continue; // Исключаем самого себя
            currentRow.add(createBtn("👤 " + u[1], "W_TRANS_SEL:" + welderId + ":" + uId));
            if (currentRow.size() == 2) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            }
        }
        if (!currentRow.isEmpty()) rows.add(currentRow);
        rows.add(List.of(createBtn("🔙 Назад", "W_MAIN_MENU")));
        markup.setKeyboard(rows);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("🔄 <b>Кому передаем " + DatabaseManager.getWelderNameById(welderId) + "?</b>\nВыберите сотрудника из списка:");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
        try { execute(edit); } catch (Exception e) {}
    }

    private void sendWeldersHistoryMenu(long chatId, int messageId) {
        String history = DatabaseManager.getWeldersHistoryText();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("🔙 Назад к списку", "W_MAIN_MENU"))));
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText(history); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
        try { execute(edit); } catch (TelegramApiException e) {}
    }

    private void sendWeldersForceAssignMenu(long chatId, int messageId) {
        List<String[]> welders = DatabaseManager.getWeldersStatus();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] w : welders) {
            if ("ON_BASE".equals(w[2])) rows.add(List.of(createBtn("⚡️ " + w[1], "W_F_SEL:" + w[0])));
        }
        rows.add(List.of(createBtn("🔙 Назад", "W_MAIN_MENU")));
        markup.setKeyboard(rows);
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("➕ <b>Принудительная выдача (Шаг 1)</b>\nВыберите свободный сварочник:");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
        try { execute(edit); } catch (TelegramApiException e) {}
    }

    private void sendWeldersForceAssignUsers(long chatId, int welderId, int messageId) {
        String wName = DatabaseManager.getWelderNameById(welderId);
        List<String[]> users = DatabaseManager.getUsersForToolAssignment();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) rows.add(List.of(createBtn("👤 " + u[1], "W_F_ASS:" + welderId + ":" + u[0])));
        rows.add(List.of(createBtn("🔙 Назад", "W_FORCE_TAKE_M")));
        markup.setKeyboard(rows);
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("➕ Принудительная выдача: <b>" + wName + "</b>\n\n👤 <b>Шаг 2: Выберите сотрудника</b>, на которого нужно повесить аппарат:");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
        try { execute(edit); } catch (TelegramApiException e) {}
    }

    private void sendWeldersForceReturnMenu(long chatId, int messageId) {
        List<String[]> welders = DatabaseManager.getWeldersStatus();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] w : welders) {
            if ("IN_USE".equals(w[2])) rows.add(List.of(createBtn("⚠️ Списать: " + w[1] + " (у " + w[3] + ")", "W_F_RET:" + w[0])));
        }
        rows.add(List.of(createBtn("🔙 Назад", "W_MAIN_MENU")));
        markup.setKeyboard(rows);
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("⚠️ <b>Принудительный возврат на базу</b>\nВыберите аппарат, который хотите списать с сотрудника:");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
        try { execute(edit); } catch (TelegramApiException e) {}
    }

    private boolean isAdminBlockedByPendingRequests(long chatId, String role) {
        if (!"ADMIN".equals(role)) return false;
        List<String[]> pendingUsers = DatabaseManager.getPendingUsers();
        List<String[]> pendingReturns = DatabaseManager.getPendingReturnRequests();

        if (!pendingUsers.isEmpty() || !pendingReturns.isEmpty()) {
            sendPendingAdminRequestsLocked(chatId, pendingUsers, pendingReturns);
            return true;
        }
        return false;
    }

    private void sendPendingAdminRequestsLocked(long chatId, List<String[]> pendingUsers, List<String[]> pendingReturns) {
        SendMessage blockMsg = new SendMessage(String.valueOf(chatId), "🚨 <b>ДЕЙСТВИЕ ПРИОСТАНОВЛЕНО!</b> 🚨\n\nУ вас висят необработанные заявки от сотрудников. Чтобы запросы не затерялись в истории чата, бот заблокировал основное меню.\n\n👇 <b>Пожалуйста, примите решение по заявкам ниже:</b>");
        blockMsg.setParseMode("HTML");
        try { execute(blockMsg); } catch (TelegramApiException e) { e.printStackTrace(); }

        for (String[] pu : pendingUsers) {
            long newUserId = Long.parseLong(pu[0]);
            String newUserName = pu[1];
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(
                    createBtn("✅ Одобрить", "NEW_USER_APP:" + newUserId), createBtn("❌ Заблокировать", "NEW_USER_REJ:" + newUserId)
            )));
            SendMessage msg = new SendMessage(String.valueOf(chatId), String.format("👤 <b>Новый пользователь ждет доступа!</b>\n\nИмя: <b>%s</b>\nID: <code>%d</code>\n\nРазрешить доступ?", newUserName, newUserId));
            msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
            try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
        }

        for (String[] pr : pendingReturns) {
            int reqId = Integer.parseInt(pr[0]);
            String alertMsg = String.format("🔔 <b>Запрос на возврат на склад (№%d)</b>\n\n👤 Сотрудник: <b>%s</b>\n📦 Материал: <b>%s</b>\n🔢 Инв. №: <code>%s</code>\n↩️ Количество к возврату: <b>%s %s</b>", reqId, pr[1], pr[2], pr[3], DatabaseManager.fmtQty(Double.parseDouble(pr[4])), pr[5]);
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(
                    createBtn("✅ Принять на склад", "RET_APP:" + reqId), createBtn("❌ Отклонить", "RET_REJ:" + reqId)
            )));
            SendMessage msg = new SendMessage(String.valueOf(chatId), alertMsg);
            msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
            try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
        }
    }

    private boolean checkUnboundAndNotify(long chatId, String role) {
        if ("ADMIN".equals(role)) return false;
        String excelName = DatabaseManager.getUserExcelName(chatId);
        if (excelName == null) {
            List<String> availableNames = DatabaseManager.getAvailableExcelNames();
            if (availableNames.isEmpty()) {
                sendDirectNotification(chatId, "⚠ <b>Внимание!</b>\nВам необходимо идентифицировать себя, но администратор еще не загрузил график с новыми фамилиями. Пожалуйста, обратитесь к нему напрямую.");
            } else {
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (String name : availableNames) rows.add(List.of(createBtn(name, "BIND_NAME:" + name)));
                markup.setKeyboard(rows);
                SendMessage msg = new SendMessage(String.valueOf(chatId), "⚠️ <b>ОГРАНИЧЕНИЕ ДОСТУПА!</b>\n\nОстальной функционал бота временно заблокирован.\n👇 <b>Пожалуйста, выберите СВОЮ настоящую фамилию из списка ниже, чтобы продолжить работу:</b>");
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
            }
            return true;
        }
        return false;
    }

    private boolean canEditSchedule(long chatId, String role) {
        if ("ADMIN".equals(role)) return true;

        String excelName = DatabaseManager.getUserExcelName(chatId);
        if (excelName != null) {
            return excelName.contains("Белевич") || excelName.contains("Козлов");
        }
        return false;
    }
    private String checkRoleAndNotify(long chatId, String firstName) {
        String role = DatabaseManager.getUserRole(chatId, firstName);
        if ("NEW_PENDING".equals(role)) {
            sendNewUserRequestToAdmins(chatId, firstName);
            SendMessage msg = new SendMessage(String.valueOf(chatId), "⏳ <b>Ваша заявка отправлена.</b>");
            msg.setParseMode("HTML"); try { execute(msg); } catch (TelegramApiException e) {}
            return "PENDING";
        }
        return role;
    }

    private void sendNewUserRequestToAdmins(long newUserId, String newUserName) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("✅ Одобрить", "NEW_USER_APP:" + newUserId), createBtn("❌ Заблокировать", "NEW_USER_REJ:" + newUserId))));
        String text = String.format("👤 <b>Новый пользователь!</b>\nИмя: <b>%s</b>\nID: <code>%d</code>", newUserName, newUserId);
        for (Long adminId : DatabaseManager.getAdminIds()) {
            SendMessage msg = new SendMessage(String.valueOf(adminId), text); msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
            try { execute(msg); } catch (TelegramApiException e) {}
        }
    }

    private void sendUsersToolSummaryMenu(long chatId, Integer messageId, String alertText) {
        List<String[]> users = DatabaseManager.getUsersWithToolCounts();
        StringBuilder sb = new StringBuilder();
        if (alertText != null && !alertText.isEmpty()) sb.append(alertText).append("\n\n➖➖➖➖➖➖➖➖➖➖\n");

        if (users.isEmpty()) {
            sb.append("ℹ️ Инструмента на руках нет.");
            if (messageId != null) editToolManagementMenu(chatId, messageId, sb.toString());
            else sendToolManagementMenu(chatId, sb.toString());
            return;
        }

        sb.append("👤 <b>Сводка по людям</b>\nВыберите сотрудника:");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) rows.add(List.of(createBtn(String.format("👤 %s (%s шт.)", u[1], u[2]), "T_SUMM_U:" + u[0])));
        rows.add(List.of(createBtn("🔙 Назад", "tool_menu_main")));
        markup.setKeyboard(rows);

        if (messageId == null) {
            SendMessage msg = new SendMessage(String.valueOf(chatId), sb.toString());
            msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
            try { execute(msg); } catch (TelegramApiException e) {}
        } else {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
            edit.setText(sb.toString()); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
            try { execute(edit); } catch (TelegramApiException e) {}
        }
    }

    private void sendUserToolSummary(long chatId, long targetUserId, Integer messageId, String alertText) {
        List<String[]> tools = DatabaseManager.getUserAssignedToolsForReturn(targetUserId);
        String userName = DatabaseManager.getUserFullName(targetUserId);

        StringBuilder sb = new StringBuilder();
        if (alertText != null && !alertText.isEmpty()) sb.append(alertText).append("\n\n➖➖➖➖➖➖➖➖➖➖\n");

        if (tools.isEmpty()) {
            sb.append("ℹ️ У <b>").append(userName).append("</b> нет инструмента.");
            sendUsersToolSummaryMenu(chatId, messageId, sb.toString());
            return;
        }

        sb.append("👤 <b>Инструмент: ").append(userName).append("</b>");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) {
            String inv = (t[2] != null && !t[2].isEmpty() && !t[2].equals("null")) ? " [" + t[2] + "]" : "";
            rows.add(List.of(createBtn(String.format("↩️ Забрать: %s%s", t[1].length() > 15 ? t[1].substring(0, 15) + "…" : t[1], inv), "T_SUMM_RET:" + t[0] + ":" + targetUserId)));
        }
        rows.add(List.of(createBtn("🚨 Вернуть ВСЁ на склад", "T_SUMM_RALL:" + targetUserId)));
        rows.add(List.of(createBtn("🔙 Назад к списку", "tool_menu_audit_users")));
        markup.setKeyboard(rows);

        if (messageId == null) {
            SendMessage msg = new SendMessage(String.valueOf(chatId), sb.toString());
            msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
            try { execute(msg); } catch (TelegramApiException e) {}
        } else {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
            edit.setText(sb.toString()); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
            try { execute(edit); } catch (TelegramApiException e) {}
        }
    }

    private void sendAvailableToolsForAssignment(long chatId, Integer messageId) {
        List<String[]> groups = DatabaseManager.getAvailableToolGroups();
        if (groups.isEmpty()) {
            try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
            sendMenu(chatId, "ADMIN", "📦 На складе нет свободного инструмента.");
            sendToolManagementMenu(chatId); return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] g : groups) rows.add(List.of(createBtn((g[1].length() > 30 ? g[1].substring(0, 30) + "…" : g[1]) + " (в наличии: " + g[2] + " шт)", "T_SEL_N:" + g[0])));
        rows.add(List.of(createBtn("🔙 Назад", "TOOL_MAIN_MENU")));
        markup.setKeyboard(rows);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("🤝 <b>Шаг 1 из 2: Выберите инструмент:</b>");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup); try { execute(edit); } catch (Exception e) {}
    }

    private void sendUsersForToolAssignment(long chatId, int toolId, Integer messageId) {
        List<String[]> users = DatabaseManager.getUsersForToolAssignment();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) rows.add(List.of(createBtn(("ADMIN".equals(u[2]) ? "👑 " : "👷‍♂️ ") + u[1], "T_ASS_U:" + toolId + ":" + u[0])));
        rows.add(List.of(createBtn("🔙 Назад", "tool_menu_give")));
        markup.setKeyboard(rows);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("🪛 Вы выбрали: <b>" + DatabaseManager.getToolNameAndInvById(toolId) + "</b>\n\n👤 <b>Шаг 2 из 2: Кому выдать?</b>");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup); try { execute(edit); } catch (Exception e) {}
    }

    private void sendUsersForToolReturn(long chatId, Integer messageId) {
        List<String[]> users = DatabaseManager.getUsersWithAssignedTools();
        if (users.isEmpty()) {
            try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
            sendMenu(chatId, "ADMIN", "ℹ️ Нет инструмента на руках."); sendToolManagementMenu(chatId); return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) rows.add(List.of(createBtn("👤 " + u[1], "T_RET_U:" + u[0])));
        rows.add(List.of(createBtn("🔙 Назад", "TOOL_MAIN_MENU")));
        markup.setKeyboard(rows);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("↩️ <b>У кого забираем инструмент?</b>");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup); try { execute(edit); } catch (Exception e) {}
    }

    private void sendUserToolsForReturn(long chatId, long targetUserId, Integer messageId) {
        List<String[]> tools = DatabaseManager.getUserAssignedToolsForReturn(targetUserId);
        if (tools.isEmpty()) {
            try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
            sendMenu(chatId, "ADMIN", "У сотрудника нет инструмента."); sendToolManagementMenu(chatId); return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) rows.add(List.of(createBtn("🪛 " + (t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1]) + " (Инв: " + t[2] + ")", "T_RET_T:" + t[0])));
        rows.add(List.of(createBtn("🔙 Назад", "tool_menu_return")));
        markup.setKeyboard(rows);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("↩️ <b>Какой инструмент возвращаем?</b>");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup); try { execute(edit); } catch (Exception e) {}
    }

    private void sendUsersForToolWriteOff(long chatId, Integer messageId) {
        List<String[]> users = DatabaseManager.getUsersWithAssignedTools();
        if (users.isEmpty()) {
            try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
            sendMenu(chatId, "ADMIN", "ℹ️ Нет инструмента на руках."); sendToolManagementMenu(chatId); return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) rows.add(List.of(createBtn("👤 " + u[1], "T_WO_U:" + u[0])));
        rows.add(List.of(createBtn("🔙 Назад", "tool_menu_writeoff")));
        markup.setKeyboard(rows);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("🗑 <b>У кого списываем инструмент?</b>");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup); try { execute(edit); } catch (Exception e) {}
    }

    private void sendUserToolsForWriteOff(long chatId, long targetUserId, Integer messageId) {
        List<String[]> tools = DatabaseManager.getUserAssignedToolsForReturn(targetUserId);
        if (tools.isEmpty()) {
            try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
            sendMenu(chatId, "ADMIN", "У сотрудника нет инструмента."); sendToolManagementMenu(chatId); return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) rows.add(List.of(createBtn("🪛 " + (t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1]) + " (Инв: " + t[2] + ")", "T_WO_DO:" + t[0])));
        rows.add(List.of(createBtn("🔙 Назад", "T_WO_LOC:ASSIGNED")));
        markup.setKeyboard(rows);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("🗑 <b>Какой инструмент списываем?</b>");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup); try { execute(edit); } catch (Exception e) {}
    }

    private void sendGroupsForToolWriteOff(long chatId, Integer messageId) {
        List<String[]> groups = DatabaseManager.getAvailableToolGroups();
        if (groups.isEmpty()) {
            try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
            sendMenu(chatId, "ADMIN", "📦 На складе нет инструмента."); sendToolManagementMenu(chatId); return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] g : groups) rows.add(List.of(createBtn((g[1].length() > 30 ? g[1].substring(0, 30) + "…" : g[1]) + " (" + g[2] + " шт)", "T_WO_G:" + g[0])));
        rows.add(List.of(createBtn("🔙 Назад", "tool_menu_writeoff")));
        markup.setKeyboard(rows);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("🗑 <b>Выберите категорию на складе:</b>");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup); try { execute(edit); } catch (Exception e) {}
    }

    private void sendSpecificToolsInStockForWriteOff(long chatId, int firstId, Integer messageId) {
        List<String[]> tools = DatabaseManager.getToolsInStockByGroup(String.valueOf(firstId));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) rows.add(List.of(createBtn("🪛 " + (t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1]) + " (Инв: " + t[2] + ")", "T_WO_DO:" + t[0])));
        rows.add(List.of(createBtn("🔙 Назад", "T_WO_LOC:STOCK")));
        markup.setKeyboard(rows);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("🗑 <b>Выберите единицу для списания:</b>");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup); try { execute(edit); } catch (Exception e) {}
    }

    private void sendToolsForRestore(long chatId, Integer messageId) {
        List<String[]> tools = DatabaseManager.getWrittenOffToolsForRestore();
        if (tools.isEmpty()) {
            try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
            sendMenu(chatId, "ADMIN", "🗄 В архиве пусто."); sendToolManagementMenu(chatId); return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) rows.add(List.of(createBtn("♻ " + (t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1]) + " (Инв: " + t[2] + ")", "T_RES_DO:" + t[0])));
        rows.add(List.of(createBtn("🔙 Назад", "TOOL_MAIN_MENU")));
        markup.setKeyboard(rows);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("♻️ <b>Какой инструмент восстановить?</b>");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup); try { execute(edit); } catch (Exception e) {}
    }

    private void sendMyInventoryMenu(long chatId, Integer messageId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(
                List.of(createBtn("📦 Мои материалы", "INV_MATS"), createBtn("🪛 Мой инструмент", "INV_TOOLS")),
                List.of(createBtn("❌ Закрыть", "INV_CLOSE"))
        ));

        String text = "🧰 <b>Ваш подотчет:</b>\nВыберите, что хотите посмотреть:";
        try {
            if (messageId == null) {
                SendMessage msg = new SendMessage(String.valueOf(chatId), text);
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                execute(msg);
            } else {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(text); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                execute(edit);
            }
        } catch (TelegramApiException e) {}
    }

    private void sendInventorySection(long chatId, int messageId, String text) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(createBtn("🔙 Назад к выбору", "INV_MAIN"))
        ));
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText(text); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
        try { execute(edit); } catch (Exception e) {}
    }

    private void sendColleagueSelectionMenu(long chatId, List<String> names, Integer messageId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> currentRow = new ArrayList<>();

        for (String name : names) {
            currentRow.add(createBtn("👤 " + name, "VIEW_SCHED:" + name));
            if (currentRow.size() == 2) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            }
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }

        // --- НОВЫЕ КНОПКИ НАВИГАЦИИ ---
        rows.add(List.of(createBtn("🔙 Назад", "SCHED_MAIN")));
        rows.add(List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE")));

        markup.setKeyboard(rows);

        try {
            if (messageId == null) {
                SendMessage msg = new SendMessage(String.valueOf(chatId), "👁 <b>График коллеги</b>\nВыберите сотрудника:");
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                execute(msg);
            } else {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("👁 <b>График коллеги</b>\nВыберите сотрудника:");
                edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                execute(edit);
            }
        } catch (TelegramApiException e) {}
    }

    private void sendUploadsMenu(long chatId, String text) {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        KeyboardRow row1 = new KeyboardRow(); row1.add("📥 Загрузить ведомость (Excel)");
        KeyboardRow row2 = new KeyboardRow(); row2.add("📥 Загрузить инструмент (Excel)");
        KeyboardRow row3 = new KeyboardRow(); row3.add("📥 Загрузить график (Excel)");
        KeyboardRow row4 = new KeyboardRow(); row4.add("📥 Загрузить план ОРШ (Excel)");
        KeyboardRow row5 = new KeyboardRow(); row5.add("🔙 Назад");
        markup.setKeyboard(List.of(row1, row2, row3, row4, row5));
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        message.setParseMode("HTML");
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) {}
    }

    private void sendPendingOrshList(long chatId) {
        List<String[]> list = DatabaseManager.getPendingOrshList();
        if (list.isEmpty()) { sendMenu(chatId, "WORKER", "🎉 <b>План осмотра пуст!</b>"); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        int count = 0;
        for (String[] o : list) {
            if (count >= 30) break;
            rows.add(List.of(createBtn("📍 " + (o[2].length() > 35 ? o[2].substring(0, 35) + "…" : o[2]), "ORSH_SEL:" + o[0]))); count++;
        }
        rows.add(List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE")));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "📸 <b>Осталось проверить: " + list.size() + " шт.</b>\nВыберите адрес:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendOrshDetails(long chatId, int orshId) {
        String[] orsh = DatabaseManager.getOrshById(orshId);
        if (orsh == null) { sendMenu(chatId, "WORKER", "❌ Шкаф не найден."); return; }
        waitingOrshPhoto.put(chatId, orshId);
        sendCancelKeyboard(chatId, "Подготовка к осмотру...");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(createBtn("⚠ Невозможно сделать фото", "ORSH_PROB:" + orshId)),
                List.of(createBtn("❌ Закрыть", "CANCEL_PROMPT"))
        ));
        SendMessage msg = new SendMessage(String.valueOf(chatId), String.format("📸 <b>Выбран ОРШ-%s</b>\n📍 Адрес: %s\n🧭 Местоположение: %s\n\n👇 <b>Отправьте фото!</b>", orsh[0], orsh[1], orsh[2]));
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }
    public void sendMenu(long chatId, String role, String text) {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);

        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow r1 = new KeyboardRow(); r1.add("📦 Склад (Наличие и цены)"); r1.add("🧰 Мой подотчет"); keyboard.add(r1);
        KeyboardRow r2 = new KeyboardRow(); r2.add("📝 Списать / Вернуть"); r2.add("⚡️ Сварочные аппараты"); keyboard.add(r2);
        KeyboardRow r3 = new KeyboardRow(); r3.add("🧾 Калькулятор квитанции"); r3.add("📋 Тарифы услуг"); keyboard.add(r3);
        KeyboardRow r4 = new KeyboardRow(); r4.add("📸 Плановый осмотр ОРШ"); r4.add("🗓 График работ"); keyboard.add(r4);
        KeyboardRow r5 = new KeyboardRow(); r5.add("🔢 Коды закрытия"); r5.add("📞 Справочник"); keyboard.add(r5);
        KeyboardRow r6 = new KeyboardRow(); r6.add("🛠 Неисправности"); keyboard.add(r6);

        boolean tmAccess = canAccessTm(chatId, role);

        if ("ADMIN".equals(role)) {
            KeyboardRow a1 = new KeyboardRow(); a1.add("📊 У кого что на руках"); a1.add("🛠 Управление инструментом"); keyboard.add(a1);
            KeyboardRow a2 = new KeyboardRow(); a2.add("📑 Скачать отчет за месяц"); a2.add("📊 Статистика ОРШ"); keyboard.add(a2);
            KeyboardRow a3 = new KeyboardRow(); a3.add("📢 Сделать рассылку"); a3.add("👥 Пользователи"); keyboard.add(a3);
            KeyboardRow a4 = new KeyboardRow(); a4.add("📥 Загрузки (Excel)"); keyboard.add(a4);
            KeyboardRow a5 = new KeyboardRow(); a5.add("🛠 Мои заявки (ТМ)"); keyboard.add(a5);
        } else if (tmAccess) {
            KeyboardRow w6 = new KeyboardRow(); w6.add("🛠 Мои заявки (ТМ)"); keyboard.add(w6);
        }
        keyboardMarkup.setKeyboard(keyboard);
        try {
            if (text.length() <= 3900) { SendMessage msg = new SendMessage(String.valueOf(chatId), text); msg.setParseMode("HTML"); msg.setReplyMarkup(keyboardMarkup); execute(msg); }
            else {
                String remaining = text;
                while (remaining.length() > 3900) {
                    int split = remaining.lastIndexOf("\n\n", 3900);
                    if (split == -1) split = 3900;
                    SendMessage msgPart = new SendMessage(String.valueOf(chatId), remaining.substring(0, split)); msgPart.setParseMode("HTML"); execute(msgPart);
                    remaining = remaining.substring(split).trim();
                }
                if (!remaining.isEmpty()) { SendMessage msg = new SendMessage(String.valueOf(chatId), remaining); msg.setParseMode("HTML"); msg.setReplyMarkup(keyboardMarkup); execute(msg); }
            }
        } catch (TelegramApiException e) {}
    }

    private JSONArray fetchTmTasks(long chatId) {
        String session = DatabaseManager.getTmSession(chatId);
        JSONArray tasks = session != null ? TmClient.getTasks(session) : null;
        if (tasks == null) {
            String[] creds = DatabaseManager.getTmCredentials(chatId);
            if (creds != null) {
                session = TmClient.login(creds[0], creds[1]);
                if (session != null) { DatabaseManager.saveTmSession(chatId, session); tasks = TmClient.getTasks(session); }
            }
        }
        return tasks;
    }

    private JSONObject tmCallWithRetry(long chatId, Function<String, JSONObject> call) {
        String session = DatabaseManager.getTmSession(chatId);
        JSONObject result = session != null ? call.apply(session) : null;
        if (result == null) {
            String[] creds = DatabaseManager.getTmCredentials(chatId);
            if (creds != null) {
                String newSession = TmClient.login(creds[0], creds[1]);
                if (newSession != null) {
                    DatabaseManager.saveTmSession(chatId, newSession);
                    result = call.apply(newSession);
                }
            }
        }
        return result;
    }

    private void sendTmTasksMenu(long chatId, JSONArray tasks) {
        int inProgress = TmClient.countByStatus(tasks, false);
        int closedCount = TmClient.countByStatus(tasks, true);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createBtn("📋 В работе (" + inProgress + ")", "TM_INPROGRESS")));
        rows.add(List.of(createBtn("✅ Закрытые (" + closedCount + ")", "TM_CLOSED")));
        rows.add(List.of(createBtn("❌ Закрыть панель", "GENERIC_CLOSE")));
        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage(String.valueOf(chatId), "🛠 <b>Мои заявки ТМ</b>\nВыберите раздел:");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try {
            var sent = execute(msg);
            tmMenuMessageIds.put(chatId, sent.getMessageId()); // 👈 Запоминаем ID сообщения
        } catch (TelegramApiException e) {}
    }

    private void clearPreviousTmTasks(long chatId) {
        List<Integer> oldIds = tmActiveTaskMessages.remove(chatId);
        if (oldIds != null) {
            for (Integer id : oldIds) {
                try { execute(new DeleteMessage(String.valueOf(chatId), id)); } catch (Exception e) {}
            }
        }
    }

    private void sendTmInProgressList(long chatId, String role, JSONArray tasks) {
        // 1. Стираем предыдущий список заявок из чата, чтобы не захламлять историю
        clearPreviousTmTasks(chatId);

        if (tasks == null) {
            sendMenu(chatId, role, "❌ Не удалось получить заявки.");
            return;
        }
        JSONArray inProgress = TmClient.byStatus(tasks, false);
        if (inProgress.isEmpty()) {
            sendMenu(chatId, role, "📋 Заявок в работе нет.");
            return;
        }

        List<Integer> newMsgIds = new ArrayList<>(); // Собираем ID новых сообщений

        // 2. Выводим актуальные карточки
        for (int i = 0; i < inProgress.length(); i++) {
            var task = inProgress.getJSONObject(i);
            String taskId = task.optString("id");
            String cardText = TmClient.formatTask(task);

            int taskStatus = task.optInt("task_status", 0);
            String reportBtnText;

            if (taskStatus == 1) {
                reportBtnText = "⏳ Отчёт отправлен (Ожидает)";
                cardText = "💠 <b>ОТЧЁТ НА РАССМОТРЕНИИ</b> 💠\n<i>Ожидает проверки диспетчером...</i>\n➖➖➖➖➖➖➖➖➖➖\n" + cardText;
            } else if (taskStatus == 2) {
                reportBtnText = "⚠️ ДОРАБОТАТЬ (Отправить заново)";
                String rejectReason = task.optString("task_note", "Причина не указана");
                if (rejectReason.isEmpty() || rejectReason.equals("null")) rejectReason = "Причина не указана диспетчером";
                cardText = "❌ <b>ВОЗВРАТ НА ДОРАБОТКУ!</b>\n💬 <i>Причина: " + rejectReason + "</i>\n➖➖➖➖➖➖➖➖➖➖\n" + cardText;
            } else {
                reportBtnText = "📝 Отправить отчёт";
            }

            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
            markup.setKeyboard(List.of(
                    List.of(createBtn(reportBtnText, "TM_REPORT:" + taskId)),
                    List.of(createBtn("📍 АСТУП", "TM_ASTUP:" + taskId), createBtn("📊 Параметры", "TM_PARAMS:" + taskId)),
                    List.of(createBtn("📜 История", "TM_HIST:" + taskId))
            ));

            SendMessage msg = new SendMessage(String.valueOf(chatId), cardText);
            msg.setParseMode("HTML");
            msg.setReplyMarkup(markup);
            try {
                var sent = execute(msg);
                tmOriginalCardText.put(sent.getMessageId(), cardText);
                newMsgIds.add(sent.getMessageId()); // Запоминаем ID карточки
            } catch (TelegramApiException e) {}
        }

        // 3. Выводим кнопку "Обновить" с количеством заявок и кнопку "Закрыть"
        int taskCount = inProgress.length();

        InlineKeyboardMarkup refreshMarkup = new InlineKeyboardMarkup(List.of(
                List.of(createBtn("🔄 Обновить (Заявок: " + taskCount + ")", "TM_REFRESH")),
                List.of(createBtn("❌ Закрыть панель", "TM_CLOSE"))
        ));

        SendMessage refreshMsg = new SendMessage(String.valueOf(chatId),
                "📋 Актуально заявок в работе: <b>" + taskCount + "</b>\n" +
                        "👇 <i>Нажмите кнопку ниже, чтобы загрузить свежие данные:</i>");

        refreshMsg.setParseMode("HTML");
        refreshMsg.setReplyMarkup(refreshMarkup);
        try {
            var sentRefresh = execute(refreshMsg);
            newMsgIds.add(sentRefresh.getMessageId()); // Запоминаем ID кнопки обновления
        } catch (TelegramApiException e) {}

        // Сохраняем весь блок сообщений в память бота
        tmActiveTaskMessages.put(chatId, newMsgIds);
    } // конец метода sendTmInProgressList

    private void editTmCardMessage(long chatId, int messageId, String newText, InlineKeyboardMarkup markup) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText(newText);
        edit.setParseMode("HTML");
        edit.setReplyMarkup(markup);
        try { execute(edit); } catch (TelegramApiException e) {}
    }

    private InlineKeyboardMarkup backButtonMarkup(String taskId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(createBtn("⬅ Назад к заявке", "TM_BACK:" + taskId))));
        return markup;
    }
    // Метод 1: Отрисовка каталога склада с пагинацией
    private void sendWarehousePage(long chatId, int page, Integer messageId, String alertText) {
        List<String[]> materials = DatabaseManager.getAvailableMaterials();
        int pageSize = 10;
        int totalPages = (int) Math.ceil((double) materials.size() / pageSize);
        if (totalPages == 0) totalPages = 1;
        if (page < 1) page = 1;
        if (page > totalPages) page = totalPages;

        StringBuilder sb = new StringBuilder();

        sb.append("📦 <b>Склад (Наличие и цены)</b>\n\n");

        if (materials.isEmpty()) {
            sb.append("<i>Склад пуст.</i>");
        } else {
            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, materials.size());
            for (int i = start; i < end; i++) {
                String[] m = materials.get(i);
                // m = [id, account_number, code, name, work_unit, priceNoVat, priceVat, qty, batch]
                sb.append(String.format("<b>%d.</b> %s — <b>%s %s</b>\n", (i - start + 1), m[3], m[7], m[4]));
            }
        }

        if (alertText != null && !alertText.isEmpty()) {
            sb.append("\n➖➖➖➖➖➖➖➖➖➖\n").append(alertText);
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        if (!materials.isEmpty()) {
            // Ряд 1: Кнопки с номерами позиций (от 1 до 10)
            List<InlineKeyboardButton> numRow1 = new ArrayList<>();
            List<InlineKeyboardButton> numRow2 = new ArrayList<>();
            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, materials.size());

            for (int i = start; i < end; i++) {
                String[] m = materials.get(i);
                int num = i - start + 1;
                InlineKeyboardButton btn = createBtn(String.valueOf(num), "WH_ITEM:" + m[0] + ":" + page);
                if (num <= 5) numRow1.add(btn);
                else numRow2.add(btn);
            }
            if (!numRow1.isEmpty()) rows.add(numRow1);
            if (!numRow2.isEmpty()) rows.add(numRow2);

            // Ряд 2: Навигация (Стрелочки)
            List<InlineKeyboardButton> pageRow = new ArrayList<>();
            if (page > 1) {
                pageRow.add(createBtn("⬅️", "WH_PAGE:" + (page - 1)));
            }
            pageRow.add(createBtn("Стр. " + page + " из " + totalPages, "IGNORE"));
            if (page < totalPages) {
                pageRow.add(createBtn("➡️", "WH_PAGE:" + (page + 1)));
            }
            rows.add(pageRow);
        }

        // Ряд 3: Кнопка закрытия
        rows.add(List.of(createBtn("❌ Закрыть склад", "WH_CLOSE")));
        markup.setKeyboard(rows);

        try {
            if (messageId == null) {
                SendMessage msg = new SendMessage(String.valueOf(chatId), sb.toString());
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                execute(msg);
            } else {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(sb.toString()); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                execute(edit);
            }
        } catch (TelegramApiException e) {}
    }

    // Метод 2: Карточка конкретного материала (Выбор количества)
    private void sendMaterialCard(long chatId, int materialId, int page, Integer messageId) {
        List<String[]> materials = DatabaseManager.getAvailableMaterials();
        String[] mat = null;
        for (String[] m : materials) {
            if (Integer.parseInt(m[0]) == materialId) {
                mat = m; break;
            }
        }

        if (mat == null) {
            sendWarehousePage(chatId, page, messageId, "❌ Материал закончился на складе.");
            return;
        }

        String name = mat[3];
        String unit = mat[4];
        String price = mat[6];
        String qty = mat[7];

        String text = String.format("📦 <b>%s</b>\n\n• На складе: <b>%s %s</b>\n• Цена с НДС: <b>%s руб.</b>\n\n👇 <b>Сколько %s берем в подотчет?</b>",
                name, qty, unit, price, unit);

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(List.of(
                createBtn("1", "WH_TAKE:" + materialId + ":1:" + page),
                createBtn("2", "WH_TAKE:" + materialId + ":2:" + page),
                createBtn("3", "WH_TAKE:" + materialId + ":3:" + page),
                createBtn("4", "WH_TAKE:" + materialId + ":4:" + page),
                createBtn("5", "WH_TAKE:" + materialId + ":5:" + page)
        ));
        rows.add(List.of(
                createBtn("10", "WH_TAKE:" + materialId + ":10:" + page),
                createBtn("15", "WH_TAKE:" + materialId + ":15:" + page),
                createBtn("20", "WH_TAKE:" + materialId + ":20:" + page),
                createBtn("50", "WH_TAKE:" + materialId + ":50:" + page)
        ));

        rows.add(List.of(createBtn("🔙 Назад к списку", "WH_PAGE:" + page)));
        markup.setKeyboard(rows);

        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
            edit.setText(text); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
            execute(edit);
        } catch (TelegramApiException e) {}
    }
    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasDocument()) {
            long chatId = update.getMessage().getChatId();
            String firstName = update.getMessage().getFrom().getFirstName();
            String role = checkRoleAndNotify(chatId, firstName);

            if ("BANNED".equals(role) || "PENDING".equals(role)) return;
            if (checkUnboundAndNotify(chatId, role)) return;

            if (!"ADMIN".equals(role)) {
                sendMenu(chatId, role, "❌ Загружать файлы может только Администратор (МОЛ).");
                return;
            }
            if (isAdminBlockedByPendingRequests(chatId, role)) return;

            String state = fileWaitState.getOrDefault(chatId, "");
            if (state.isEmpty()) {
                sendMenu(chatId, role, "Сначала нажмите кнопку загрузки в меню, а затем отправьте файл.");
                return;
            }

            Document doc = update.getMessage().getDocument();
            String fileName = doc.getFileName() != null ? doc.getFileName().toLowerCase() : "";
            if (!fileName.endsWith(".xls") && !fileName.endsWith(".xlsx")) {
                sendMenu(chatId, role, "❌ Пожалуйста, отправьте файл в формате <b>.xls</b> или <b>.xlsx</b>.");
                return;
            }

            sendMenu(chatId, role, "⏳ Загружаю и обрабатываю файл...");
            try {
                GetFile getFile = new GetFile(doc.getFileId());
                org.telegram.telegrambots.meta.api.objects.File tgFile = execute(getFile);
                File localFile = downloadFile(tgFile);
                String result;

                if ("TURNOVER".equals(state)) result = ExcelImporter.importTurnoverSheet(localFile);
                else if (state.startsWith("SCHEDULE:")) {
                    String[] p = state.split(":");
                    int year = Integer.parseInt(p[1]);
                    int month = Integer.parseInt(p[2]);
                    List<DatabaseManager.ScheduleDayV2> parsed = ExcelImporter.parseScheduleSheet(localFile);
                    result = DatabaseManager.saveImportedScheduleV2(parsed, year, month);
                }
                else if ("TOOLS".equals(state)) result = ExcelImporter.importToolsSheet(localFile);
                else if ("ORSH".equals(state)) result = ExcelImporter.importOrshSheet(localFile);
                else result = "❌ Неизвестное состояние загрузки файла.";

                localFile.delete();
                fileWaitState.remove(chatId);
                sendMenu(chatId, role, result);
            } catch (Exception e) {
                e.printStackTrace();
                sendMenu(chatId, role, "❌ Ошибка при скачивании/обработке файла: " + e.getMessage());
            }
            return;
        }

        if (update.hasMessage() && update.getMessage().hasPhoto()) {
            long chatId = update.getMessage().getChatId();
            String firstName = update.getMessage().getFrom().getFirstName();
            String role = checkRoleAndNotify(chatId, firstName);

            if ("BANNED".equals(role) || "PENDING".equals(role)) return;
            if (checkUnboundAndNotify(chatId, role)) return;

            if (isAdminBlockedByPendingRequests(chatId, role)) return;

            if (waitingOrshPhoto.containsKey(chatId)) {
                int orshId = waitingOrshPhoto.remove(chatId);
                sendMenu(chatId, role, "⏳ Скачиваю фото и отправляю на почту руководству...");

                try {
                    var photos = update.getMessage().getPhoto();
                    String fileId = photos.get(photos.size() - 1).getFileId();
                    GetFile getFile = new GetFile(fileId);
                    org.telegram.telegrambots.meta.api.objects.File tgFile = execute(getFile);
                    File localFile = downloadFile(tgFile);

                    String[] orsh = DatabaseManager.getOrshById(orshId);
                    String subject = "Осмотр ОРШ-" + orsh[0] + " (" + firstName + ")";
                    String textBody = String.format("Плановый осмотр ОРШ\n\nНомер: %s\nАдрес: %s\nМестоположение: %s\n\nВыполнил: %s",
                            orsh[0], orsh[1], orsh[2], firstName);

                    String safeAddress = orsh[1].replaceAll("[\\\\/:*?\"<>|]", " ");
                    File renamedFile = new File(localFile.getParent(), "ОРШ " + orsh[0] + " - " + safeAddress + ".jpg");
                    localFile.renameTo(renamedFile);

                    EmailSender.sendOrshReport(subject, textBody, renamedFile);
                    renamedFile.delete();
                    DatabaseManager.markOrshCompleted(orshId, firstName, false, "ОК");

                    sendMenu(chatId, role, "✅ <b>Фото успешно отправлено!</b>\nОРШ-" + orsh[0] + " вычеркнут из плана осмотра.");
                } catch (Exception e) {
                    e.printStackTrace();
                    sendMenu(chatId, role, "❌ Ошибка при отправке фото: " + e.getMessage());
                }
                return;
            }
        }

        if (update.hasMessage() && update.getMessage().hasContact()) {
            long chatId = update.getMessage().getChatId();
            String firstName = update.getMessage().getFrom().getFirstName();
            String role = checkRoleAndNotify(chatId, firstName);

            if ("BANNED".equals(role) || "PENDING".equals(role)) return;
            if (checkUnboundAndNotify(chatId, role)) return;

            if (waitingDirContactCat.containsKey(chatId)) {
                int category = waitingDirContactCat.remove(chatId);
                org.telegram.telegrambots.meta.api.objects.Contact contact = update.getMessage().getContact();

                String contactName = contact.getFirstName();
                if (contact.getLastName() != null && !contact.getLastName().isEmpty()) contactName += " " + contact.getLastName();
                String contactPhone = contact.getPhoneNumber();
                if (!contactPhone.startsWith("+")) contactPhone = "+" + contactPhone;

                String textToSave = contactName + ": " + contactPhone;
                if (DatabaseManager.addDirectoryContact(category, textToSave, chatId)) {
                    sendMenu(chatId, role, "✅ <b>Контакт успешно добавлен из телефонной книги!</b>\nОн теперь отображается в справочнике у всех сотрудников.");
                    sendDirectory(chatId, role);
                } else {
                    sendMenu(chatId, role, "❌ Ошибка при сохранении контакта.");
                }
                return;
            }
        }

        if (update.hasCallbackQuery()) {

            long chatId = update.getCallbackQuery().getMessage().getChatId();
            String data = update.getCallbackQuery().getData();
            String firstName = update.getCallbackQuery().getFrom().getFirstName();
            String role = checkRoleAndNotify(chatId, firstName);
            int messageId = update.getCallbackQuery().getMessage().getMessageId();

            if ("BANNED".equals(role) || "PENDING".equals(role)) return;

            // Блокировка кнопок для принимающего сварочник
            if (pendingWelderTransfers.containsKey(chatId)) {
                if (!data.startsWith("W_TRANS_ACC:") && !data.startsWith("W_TRANS_REJ:")) {
                    AnswerCallbackQuery ans = new AnswerCallbackQuery();
                    ans.setCallbackQueryId(update.getCallbackQuery().getId());
                    ans.setText("⚠️ Сначала примите или отклоните передачу сварочника!");
                    ans.setShowAlert(true);
                    try { execute(ans); } catch (Exception e) {}
                    return;
                }
            }

            if (data.equals("SCHED_MAIN")) {
                // Очищаем память, если мы вернулись назад из других меню
                waitingSickLeaveDate.remove(chatId);
                waitingScheduleEditUser.remove(chatId);
                waitingScheduleEditDate.remove(chatId);

                sendScheduleMenu(chatId, role, "🗓 <b>Графики и смены:</b>\nВыберите, что хотите посмотреть:", messageId);
                return;
            }
            if (data.equals("SCHED_MINE")) {
                String excelName = DatabaseManager.getUserExcelName(chatId);
                if (excelName != null) {
                    LocalDate now = LocalDate.now();
                    sendScheduleView(chatId, excelName, now.getYear(), now.getMonthValue(), true, messageId);
                } else {
                    List<String> names = DatabaseManager.getAvailableExcelNames();
                    if (names.isEmpty()) sendClosableMessage(chatId, "ℹ График работ еще не загружен администратором.");
                    else sendNameBindingMenu(chatId, names);
                }
                return;
            }
            if (data.equals("SCHED_WITH_ME")) {
                String excelName = DatabaseManager.getUserExcelName(chatId);
                if (excelName != null) sendScheduleTextSection(chatId, messageId, DatabaseManager.getShiftPartners(excelName));
                else {
                    List<String> names = DatabaseManager.getAvailableExcelNames();
                    if (names.isEmpty()) sendScheduleTextSection(chatId, messageId, "ℹ Ваш профиль еще не привязан к графику.");
                    else sendNameBindingMenu(chatId, names);
                }
                return;
            }
            if (data.equals("SCHED_TODAY")) {
                sendScheduleTextSection(chatId, messageId, DatabaseManager.getTodayRoster());
                return;
            }
            // --- КНОПКА "КТО РАБОТАЕТ ЗАВТРА?" ---
            if (data.equals("SCHED_TOMORROW")) {
                int dayOfWeek = java.time.LocalDate.now().getDayOfWeek().getValue(); // 1=Пн, 5=Пт, 6=Сб, 7=Вс

                if (dayOfWeek == 5) {
                    // ПЯТНИЦА: Спрашиваем, какой день нужен
                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                            List.of(createBtn("Сб (Завтра)", "SCHED_DAY_1"), createBtn("Пн (След. рабочий)", "SCHED_DAY_3")),
                            List.of(createBtn("🔙 Назад", "SCHED_MAIN"), createBtn("❌ Закрыть", "GENERIC_CLOSE"))
                    ));
                    EditMessageText edit = new EditMessageText();
                    edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                    edit.setText("🗓 <b>Уточните дату</b>\n\nВы хотите посмотреть график на выходной день (Суббота) или на следующий рабочий (Понедельник)?");
                    edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                    try { execute(edit); } catch (Exception e) {}
                } else if (dayOfWeek == 6) {
                    // СУББОТА: Сразу показываем понедельник (+2 дня)
                    sendScheduleTextSection(chatId, messageId, DatabaseManager.getTargetDayRoster(2, "Понедельник"));
                } else if (dayOfWeek == 7) {
                    // ВОСКРЕСЕНЬЕ: Сразу показываем понедельник (+1 день)
                    sendScheduleTextSection(chatId, messageId, DatabaseManager.getTargetDayRoster(1, "Понедельник"));
                } else {
                    // ПН, ВТ, СР, ЧТ: Сразу показываем завтра (+1 день)
                    sendScheduleTextSection(chatId, messageId, DatabaseManager.getTargetDayRoster(1, "Завтра"));
                }
                return;
            }

            // Обработка выбора дня, если сегодня Пятница
            if (data.startsWith("SCHED_DAY_")) {
                int offset = Integer.parseInt(data.split("_")[2]);
                String dayName = (offset == 1) ? "Субботу" : "Понедельник";
                sendScheduleTextSection(chatId, messageId, DatabaseManager.getTargetDayRoster(offset, dayName));
                return;
            }
            if (data.equals("SCHED_SICK")) {
                String excelName = DatabaseManager.getUserExcelName(chatId);
                if (excelName == null) {
                    sendScheduleMenu(chatId, role, "ℹ️ Вы не привязаны к графику.", messageId);
                } else {
                    waitingSickLeaveDate.put(chatId, messageId); // Запоминаем ID окна для SPA

                    // Используем кнопку Назад, чтобы не убивать меню, а возвращаться
                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("🔙 Назад", "SCHED_MAIN"))));

                    // Жестко используем EditMessageText для изменения текущего пузыря
                    EditMessageText edit = new EditMessageText();
                    edit.setChatId(String.valueOf(chatId));
                    edit.setMessageId(messageId);
                    edit.setText("🤒 <b>Оформление больничного</b>\n\nВы оформляете больничный для: <b>" + excelName + "</b>\n\nНапишите дни болезни.\n\n<i>Можно писать без года:</i> <code>15.11</code>\n<i>Диапазоны:</i> <code>15.10-22.10</code>\n<i>Даже через запятую:</i> <code>10.11, 14.11-19.11</code>");
                    edit.setParseMode("HTML");
                    edit.setReplyMarkup(markup);
                    try { execute(edit); } catch (Exception e) {}
                }
                return;
            }
            if (data.equals("SCHED_EDIT") && canEditSchedule(chatId, role)) {
                List<String> names = DatabaseManager.getAllExcelNames();
                if (names.isEmpty()) sendClosableMessage(chatId, "ℹ График работ еще не загружен.");
                else {
                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    List<InlineKeyboardButton> currentRow = new ArrayList<>();
                    for (String name : names) {
                        currentRow.add(createBtn("✏️ " + name, "ED_SCH_U:" + name));
                        if (currentRow.size() == 2) { rows.add(currentRow); currentRow = new ArrayList<>(); }
                    }
                    if (!currentRow.isEmpty()) rows.add(currentRow);
                    rows.add(List.of(createBtn("🔙 Назад", "SCHED_MAIN")));
                    markup.setKeyboard(rows);

                    EditMessageText edit = new EditMessageText();
                    edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                    edit.setText("✏️ <b>Редактор графиков</b>\nВыберите сотрудника:");
                    edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                    try { execute(edit); } catch (TelegramApiException e) {}
                }
                return;
            }
            if (data.equals("SCHED_ADD") && "ADMIN".equals(role)) {
                waitingNewEmployeeName.put(chatId, true);
                sendCancelKeyboard(chatId, "👤 Введите ФИО нового сотрудника (например: <b>Баранов А.В.</b>):");
                return;
            }

            // --- РАЗДЕЛ "НЕИСПРАВНОСТИ" ---
            if (data.equals("ERRORS_MAIN")) {
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("🌐 Модемы", "ERRORS_MODEMS"), createBtn("📺 Приставки", "ERRORS_STB")),
                        List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE"))
                ));
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("🛠 <b>Справочник неисправностей</b>\n\nВыберите тип оборудования, чтобы просмотреть частые проблемы:");
                edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            if (data.equals("ERRORS_MODEMS")) {
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("🔙 Назад", "ERRORS_MAIN"), createBtn("❌ Закрыть", "GENERIC_CLOSE"))
                ));
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(getModemErrorsText());
                edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            if (data.equals("ERRORS_STB")) {
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("🔙 Назад", "ERRORS_MAIN"), createBtn("❌ Закрыть", "GENERIC_CLOSE"))
                ));
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(getStbErrorsText());
                edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            if (data.equals("GENERIC_CLOSE")) {
                clearChatHistory(chatId); // 🌪 Пылесосим весь текущий раздел!
                return;
            }
            // --- ПАНЕЛЬ УПРАВЛЕНИЯ ПЕРСОНАЛОМ ---
            if (data.equals("ADM_PANEL_MAIN") && "ADMIN".equals(role)) {
                sendAdminUsersControlPanel(chatId, messageId);
                return;
            }

            if (data.equals("ADM_PANEL_LIST") && "ADMIN".equals(role)) {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(DatabaseManager.getUsersListText());
                edit.setParseMode("HTML");
                edit.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(createBtn("🔙 Назад к управлению", "ADM_PANEL_MAIN")))));
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            // 1. УДАЛЕНИЕ СОТРУДНИКА (ВЫБОР КНОПКОЙ)
            if (data.equals("ADM_PANEL_DELETE") && "ADMIN".equals(role)) {
                List<String> names = DatabaseManager.getAllExcelNames();
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (String name : names) {
                    rows.add(List.of(createBtn("🗑 " + name, "ADM_DEL_CONFIRM:" + name)));
                }
                rows.add(List.of(createBtn("🔙 Назад", "ADM_PANEL_MAIN")));
                markup.setKeyboard(rows);

                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("🗑 <b>Удаление сотрудника</b>\nВыберите, кого нужно удалить из графика и системы:");
                edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            if (data.startsWith("ADM_DEL_CONFIRM:") && "ADMIN".equals(role)) {
                String targetName = data.substring("ADM_DEL_CONFIRM:".length());
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("🔥 Да, удалить навсегда", "ADM_DEL_DO:" + targetName)),
                        List.of(createBtn("🔙 Отмена", "ADM_PANEL_DELETE"))
                ));
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("⚠️ <b>ВНИМАНИЕ!</b>\nВы действительно хотите полностью удалить сотрудника <b>" + targetName + "</b>?");
                edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            if (data.startsWith("ADM_DEL_DO:") && "ADMIN".equals(role)) {
                String targetName = data.substring("ADM_DEL_DO:".length());
                String result = DatabaseManager.removeWorkerCompletely(targetName);

                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(result);
                edit.setParseMode("HTML");
                edit.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(createBtn("🔙 К списку управления", "ADM_PANEL_MAIN")))));
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            // 2. ПЕРЕИМЕНОВАНИЕ СОТРУДНИКА (ВЫБОР КНОПКОЙ)
            if (data.equals("ADM_PANEL_RENAME") && "ADMIN".equals(role)) {
                List<String> names = DatabaseManager.getAllExcelNames();
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (String name : names) {
                    rows.add(List.of(createBtn("✏️ " + name, "ADM_REN_SEL:" + name)));
                }
                rows.add(List.of(createBtn("🔙 Назад", "ADM_PANEL_MAIN")));
                markup.setKeyboard(rows);

                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("✏️ <b>Переименование сотрудника</b>\nВыберите, чью фамилию нужно исправить:");
                edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            if (data.startsWith("ADM_REN_SEL:") && "ADMIN".equals(role)) {
                String oldName = data.substring("ADM_REN_SEL:".length());
                waitingNameFix.put(chatId, oldName);
                try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
                sendCancelKeyboard(chatId, "📝 Вы выбрали: <b>" + oldName + "</b>\n\n👇 Введите правильное ФИО следующим сообщением (например: <i>Новосельский В.З.</i>):");
                return;
            }

            // 3. СБРОС ПРИВЯЗКИ (UNBIND КНОПКОЙ)
            if (data.equals("ADM_PANEL_UNBIND") && "ADMIN".equals(role)) {
                List<String> names = DatabaseManager.getAllExcelNames();
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (String name : names) {
                    Long uId = DatabaseManager.getUserIdByExcelName(name);
                    if (uId != null) {
                        rows.add(List.of(createBtn("🔗 Сбросить: " + name, "ADM_UNB_DO:" + uId)));
                    }
                }
                rows.add(List.of(createBtn("🔙 Назад", "ADM_PANEL_MAIN")));
                markup.setKeyboard(rows);

                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("🔗 <b>Сброс привязки аккаунта</b>\nВыберите сотрудника, которого нужно отвязать от Telegram-аккаунта:");
                edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            if (data.startsWith("ADM_UNB_DO:") && "ADMIN".equals(role)) {
                long targetId = Long.parseLong(data.substring("ADM_UNB_DO:".length()));
                DatabaseManager.unbindUser(targetId, "Сотрудник");
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("✅ Привязка сброшена. Пользователь должен будет заново выбрать фамилию при входе.");
                edit.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(createBtn("🔙 Назад", "ADM_PANEL_MAIN")))));
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            // 4. ОЧИСТКА СКЛАДА
            if (data.equals("ADM_PANEL_CLR_STOCK") && "ADMIN".equals(role)) {
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("🚨 Да, полностью очистить склад", "ADM_CLR_STOCK_YES")),
                        List.of(createBtn("🔙 Отмена", "ADM_PANEL_MAIN"))
                ));
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("⚠️ <b>ВНИМАНИЕ!</b>\nВы собираетесь <b>полностью удалить все остатки материалов на складе</b>.\nЭто действие нельзя отменить!");
                edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            if (data.equals("ADM_CLR_STOCK_YES") && "ADMIN".equals(role)) {
                try (Connection conn = DatabaseManager.getConnection(); Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM materials");
                    EditMessageText edit = new EditMessageText();
                    edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                    edit.setText("🧹 <b>База склада полностью очищена!</b>\nТеперь вы можете загрузить свежую ведомость.");
                    edit.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(createBtn("🔙 Назад", "ADM_PANEL_MAIN")))));
                    try { execute(edit); } catch (Exception e) {}
                } catch (Exception e) {
                    showToolMenuSection(chatId, messageId, "❌ Ошибка очистки склада: " + e.getMessage());
                }
                return;
            }

            // 5. СБРОС ЗАВИСШИХ ЗАЯВОК (БЫВШИЙ /fix)
            if (data.equals("ADM_PANEL_FIX_REQ") && "ADMIN".equals(role)) {
                try (Connection conn = DatabaseManager.getConnection(); Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM return_requests WHERE status = 'PENDING'");
                    EditMessageText edit = new EditMessageText();
                    edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                    edit.setText("✅ <b>Все зависшие заявки на возврат успешно очищены!</b>");
                    edit.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(createBtn("🔙 Назад", "ADM_PANEL_MAIN")))));
                    try { execute(edit); } catch (Exception e) {}
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return;
            }
            if (data.equals("CANCEL_PROMPT")) {
                waitingNameFix.remove(chatId); waitingTakeMaterialId.remove(chatId); writeOffCartSessions.remove(chatId); fileWaitState.remove(chatId);
                waitingToolWriteOffReason.remove(chatId); waitingOrshPhoto.remove(chatId); waitingOrshProblemReason.remove(chatId);
                waitingDirContactCat.remove(chatId); waitingScheduleEditUser.remove(chatId); waitingScheduleEditDate.remove(chatId);
                waitingNewEmployeeName.remove(chatId); waitingSickLeaveDate.remove(chatId); waitingTmReportTaskId.remove(chatId); tmAuthStep.remove(chatId);
                tmReportSessions.remove(chatId);
                DatabaseManager.ReceiptSession cartSession = receiptSessions.get(chatId);
                if (cartSession != null) { cartSession.waitingServiceId = -1; cartSession.waitingMaterialId = -1; }
                try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
                return;
            }

            if (data.startsWith("tool_menu_")) {
                switch (data) {
                    case "tool_menu_main" -> {
                        waitingToolWriteOffReason.remove(chatId);
                        editToolManagementMenu(chatId, messageId);
                    }
                    case "tool_menu_close" -> {
                        try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
                    }
                    case "tool_menu_audit" -> {
                        try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
                        sendMenu(chatId, role, DatabaseManager.getToolsAuditText());
                        sendToolManagementMenu(chatId);
                    }
                    case "tool_menu_archive" -> {
                        try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
                        sendMenu(chatId, role, DatabaseManager.getWrittenOffToolsArchiveText());
                        sendToolManagementMenu(chatId);
                    }
                    case "tool_menu_notify" -> {
                        showToolMenuSection(chatId, messageId, "⏳ Начинаю рассылку уведомлений...");
                        List<Long> workersWithMaterials = DatabaseManager.getUsersWithBalances();
                        int successCount = 0;
                        for (Long workerId : workersWithMaterials) {
                            try {
                                SendMessage msg = new SendMessage(String.valueOf(workerId), "⚠ <b>ВНИМАНИЕ: АУДИТ ОСТАТКОВ!</b> ⚠️\n\nНапоминаем о необходимости закрыть подотчет до конца месяца. Пожалуйста, <b>спишите</b> или <b>верните</b> материалы.\n\n" + DatabaseManager.getUserBalanceText(workerId));
                                msg.setParseMode("HTML"); execute(msg); successCount++;
                            } catch (TelegramApiException e) {}
                        }
                        showToolMenuSection(chatId, messageId, "✅ Уведомления об аудите успешно доставлены <b>" + successCount + "</b> сотрудникам!");
                    }
                    case "tool_menu_audit_users" -> sendUsersToolSummaryMenu(chatId, messageId, null);
                    case "tool_menu_give" -> sendAvailableToolsForAssignment(chatId, messageId);
                    case "tool_menu_return" -> sendUsersForToolReturn(chatId, messageId);
                    case "tool_menu_restore" -> sendToolsForRestore(chatId, messageId);
                    case "tool_menu_writeoff" -> {
                        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                                List.of(createBtn("📦 Со склада", "T_WO_LOC:STOCK"), createBtn("👤 У сотрудника", "T_WO_LOC:ASSIGNED")),
                                List.of(createBtn("🔙 Назад", "TOOL_MAIN_MENU"))
                        ));
                        EditMessageText edit = new EditMessageText();
                        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                        edit.setText("🗑 <b>Где сейчас находится инструмент, который нужно списать?</b>");
                        edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                        try { execute(edit); } catch (Exception e) {}
                    }
                }
                return;
            }

            if (data.startsWith("T_SUMM_U:") && "ADMIN".equals(role)) {
                sendUserToolSummary(chatId, Long.parseLong(data.split(":")[1]), messageId, null);
                return;
            }
            if (data.startsWith("T_SUMM_RET:") && "ADMIN".equals(role)) {
                String[] parts = data.split(":");
                String res = DatabaseManager.returnToolToWarehouse(Integer.parseInt(parts[1]));
                sendUserToolSummary(chatId, Long.parseLong(parts[2]), messageId, res);
                return;
            }
            if (data.startsWith("T_SUMM_RALL:") && "ADMIN".equals(role)) {
                String res = DatabaseManager.returnAllUserToolsToWarehouse(Long.parseLong(data.split(":")[1]));
                sendUsersToolSummaryMenu(chatId, messageId, res);
                return;
            }

            if (data.startsWith("T_SEL_N:") && "ADMIN".equals(role)) { sendUsersForToolAssignment(chatId, Integer.parseInt(data.split(":")[1]), messageId); return; }
            if (data.startsWith("T_ASS_U:") && "ADMIN".equals(role)) {
                String[] parts = data.split(":");
                long targetUserId = Long.parseLong(parts[2]);
                String result = DatabaseManager.assignTool(Integer.parseInt(parts[1]), targetUserId);
                editToolManagementMenu(chatId, messageId, result);
                if (result.startsWith("✅") && targetUserId != chatId) sendDirectNotification(targetUserId, "🔔 <b>Вам выдан новый инструмент!</b>\nМОЛ закрепил за вами новую позицию.");
                return;
            }

            if (data.startsWith("T_RET_U:") && "ADMIN".equals(role)) { sendUserToolsForReturn(chatId, Long.parseLong(data.split(":")[1]), messageId); return; }
            if (data.startsWith("T_RET_T:") && "ADMIN".equals(role)) {
                String res = DatabaseManager.returnToolToWarehouse(Integer.parseInt(data.split(":")[1]));
                editToolManagementMenu(chatId, messageId, res);
                return;
            }

            if (data.startsWith("T_WO_LOC:") && "ADMIN".equals(role)) {
                if (data.split(":")[1].equals("STOCK")) sendGroupsForToolWriteOff(chatId, messageId);
                else sendUsersForToolWriteOff(chatId, messageId);
                return;
            }
            if (data.startsWith("T_WO_U:") && "ADMIN".equals(role)) { sendUserToolsForWriteOff(chatId, Long.parseLong(data.split(":")[1]), messageId); return; }
            if (data.startsWith("T_WO_G:") && "ADMIN".equals(role)) { sendSpecificToolsInStockForWriteOff(chatId, Integer.parseInt(data.split(":")[1]), messageId); return; }
            if (data.startsWith("T_WO_DO:") && "ADMIN".equals(role)) {
                int toolId = Integer.parseInt(data.split(":")[1]);
                waitingToolWriteOffReason.put(chatId, toolId);

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("❌ Отменить", "tool_menu_main"))));
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("✍️ Выбран инструмент: <b>" + DatabaseManager.getToolNameAndInvById(toolId) + "</b>\n\nВведите <b>причину списания</b> (например: утерян, сломался):");
                edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            if (data.startsWith("T_RES_DO:") && "ADMIN".equals(role)) {
                String res = DatabaseManager.restoreToolToStock(Integer.parseInt(data.split(":")[1]));
                editToolManagementMenu(chatId, messageId, res);
                return;
            }

            if (data.startsWith("WH_PAGE:")) {
                int page = Integer.parseInt(data.split(":")[1]);
                sendWarehousePage(chatId, page, messageId, null);
                return;
            }
            if (data.startsWith("WH_ITEM:")) {
                String[] p = data.split(":");
                sendMaterialCard(chatId, Integer.parseInt(p[1]), Integer.parseInt(p[2]), messageId);
                return;
            }
            if (data.startsWith("WH_TAKE:")) {
                String[] p = data.split(":");
                int matId = Integer.parseInt(p[1]);
                double qtyToTake = Double.parseDouble(p[2]);
                int page = Integer.parseInt(p[3]);

                String resultMsg = DatabaseManager.takeMaterialFromWarehouse(chatId, matId, qtyToTake);
                sendWarehousePage(chatId, page, messageId, resultMsg);

                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                answer.setText("✅ Успешно! Добавлено в подотчет.");
                answer.setShowAlert(false);
                try { execute(answer); } catch (TelegramApiException e) {}

                return;
            }
            if (data.equals("WH_CLOSE")) {
                try {
                    DeleteMessage delete = new DeleteMessage(String.valueOf(chatId), messageId);
                    execute(delete);
                } catch (TelegramApiException e) {}
                return;
            }

            if (data.equals("TM_CLOSE")) {
                clearPreviousTmTasks(chatId);
                return;
            }

            if (!data.startsWith("BIND_NAME:") && checkUnboundAndNotify(chatId, role)) {
                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                answer.setText("⚠️ Сначала выберите фамилию!");
                answer.setShowAlert(true);
                try { execute(answer); } catch (TelegramApiException e) {}
                return;
            }

            if (data.startsWith("SL_APPROVE:") || data.startsWith("SL_REJECT:")) {
                if (!"ADMIN".equals(role)) return;
                boolean approve = data.startsWith("SL_APPROVE:");
                long requestId = Long.parseLong(data.substring(data.indexOf(":") + 1));
                String[] req = DatabaseManager.getSickLeaveRequest(requestId);

                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());

                if (req == null || !"PENDING".equals(req[4])) {
                    answer.setText("Запрос уже обработан или не найден.");
                    try { execute(answer); } catch (TelegramApiException e) {}
                    return;
                }

                long empChatId = Long.parseLong(req[0]);
                String excelName = req[1];
                java.time.format.DateTimeFormatter dtf2 = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
                java.time.LocalDate start = java.time.LocalDate.parse(req[2], dtf2);
                java.time.LocalDate end = java.time.LocalDate.parse(req[3], dtf2);
                String displayDate = "с " + req[2] + " по " + req[3];

                if (approve) {
                    java.time.LocalDate current = start;
                    while (!current.isAfter(end)) {
                        DatabaseManager.updateSingleShift(excelName, current.getYear(), current.getMonthValue(), current.getDayOfMonth(), "Б", "", "");
                        current = current.plusDays(1);
                    }
                    DatabaseManager.updateSickLeaveRequestStatus(requestId, "APPROVED");
                    sendDirectNotification(empChatId, "✅ Ваш больничный (" + displayDate + ") подтвержден руководителем и внесен в график.");

                    // --- НОВОЕ: Уведомляем Козлова и Белевича ТОЛЬКО ПОСЛЕ одобрения админом ---
                    String managerMsg = "🚨 <b>ВНИМАНИЕ: Больничный!</b>\nСотрудник <b>" + excelName + "</b> уходит на больничный.\nПериод: <b>" + displayDate + "</b>\n<i>Администратор одобрил, график обновлен.</i>";
                    notifyManagersAboutSickLeave(managerMsg);

                } else {
                    DatabaseManager.updateSickLeaveRequestStatus(requestId, "REJECTED");
                    sendDirectNotification(empChatId, "❌ Ваш больничный (" + displayDate + ") отклонен руководителем. Обратитесь к нему за разъяснением.");
                }

                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId));
                edit.setMessageId(update.getCallbackQuery().getMessage().getMessageId());
                edit.setText((approve ? "✅ Подтверждено: " : "❌ Отклонено: ") + excelName + ", " + displayDate);
                // 👇 ДОБАВЛЕНА ЭТА СТРОКА:
                edit.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE")))));
                try { execute(edit); } catch (TelegramApiException e) {}

                try { execute(answer); } catch (TelegramApiException e) {}
                return;
            }

            if (data.startsWith("UPL_SCHED:") && "ADMIN".equals(role)) {
                String[] p = data.split(":");
                fileWaitState.put(chatId, "SCHEDULE:" + p[1] + ":" + p[2]);
                sendCancelKeyboard(chatId, "🗓 Отправьте файл <b>ГРАФИКА РАБОТ</b> (Excel) на <b>" + getMonthName(Integer.parseInt(p[2])) + " " + p[1] + "</b> прямо в этот чат.");
                return;
            }

            if (data.equals("TM_REFRESH")) {
                if (!canAccessTm(chatId, role)) return;

                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId));
                edit.setMessageId(messageId);
                edit.setText("⏳ <i>Опрашиваю сервер АСТУП...</i>");
                edit.setParseMode("HTML");
                try { execute(edit); } catch (Exception e) {}

                JSONArray tasks = fetchTmTasks(chatId);
                sendTmInProgressList(chatId, role, tasks);

                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                try { execute(answer); } catch (TelegramApiException e) {}
                return;
            }

            if (data.equals("INV_MAIN")) { sendMyInventoryMenu(chatId, messageId); return; }
            if (data.equals("INV_MATS")) { sendInventorySection(chatId, messageId, DatabaseManager.getUserBalanceText(chatId)); return; }
            if (data.equals("INV_TOOLS")) { sendInventorySection(chatId, messageId, DatabaseManager.getUserToolsText(chatId)); return; }
            if (data.equals("INV_CLOSE")) {
                try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
                return;
            }

            if ("TM_INPROGRESS".equals(data)) {
                if (!canAccessTm(chatId, role)) return;

                Integer menuId = tmMenuMessageIds.remove(chatId);
                if (menuId != null) {
                    try { execute(new DeleteMessage(String.valueOf(chatId), menuId)); } catch (Exception e) {}
                }

                JSONArray tasks = fetchTmTasks(chatId);
                sendTmInProgressList(chatId, role, tasks);
                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                try { execute(answer); } catch (TelegramApiException e) {}
                return;
            }
            if ("TM_CLOSED".equals(data)) {
                if (!canAccessTm(chatId, role)) return;
                JSONArray tasks = fetchTmTasks(chatId);
                sendClosableMessage(chatId, TmClient.formatTasksText(tasks, true));
                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                try { execute(answer); } catch (TelegramApiException e) {}
                return;
            }
            if (data.equals("TM_REP_CANCEL")) {
                TmReportSession session = tmReportSessions.remove(chatId);
                try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}

                JSONArray tasks = fetchTmTasks(chatId);
                sendTmInProgressList(chatId, role, tasks);

                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                try { execute(answer); } catch (TelegramApiException e) {}
                return;
            }

            if (data.startsWith("TM_REPORT:")) {
                if (!canAccessTm(chatId, role)) return;
                String taskId = data.substring("TM_REPORT:".length());

                Integer menuId = tmMenuMessageIds.remove(chatId);
                if (menuId != null) {
                    try { execute(new DeleteMessage(String.valueOf(chatId), menuId)); } catch (Exception e) {}
                }

                List<Integer> activeMsgs = tmActiveTaskMessages.remove(chatId);
                if (activeMsgs != null) {
                    for (Integer id : activeMsgs) {
                        if (id != messageId) {
                            try { execute(new DeleteMessage(String.valueOf(chatId), id)); } catch (Exception e) {}
                        }
                    }
                }

                TmReportSession session = new TmReportSession();
                session.taskId = taskId;
                session.anchorMsgId = messageId;
                tmReportSessions.put(chatId, session);

                session.step = "WAIT_TYPE";
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("🟢 ПОН", "TM_REP_TYPE:PON")),
                        List.of(createBtn("🔵 ШПД", "TM_REP_TYPE:SHPD")),
                        List.of(createBtn("💼 Выполнил служебное поручение", "TM_REP_TYPE:FAST_TRACK")),
                        List.of(createBtn("❌ Отменить", "TM_REP_CANCEL"))
                ));
                updateReportUI(chatId, session, "🛠 <b>Отчет по заявке #" + taskId + "</b>\n\n<b>Шаг 1:</b> Укажите тип заявки:", markup);

                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                try { execute(answer); } catch (TelegramApiException e) {}
                return;
            }

            if (data.startsWith("TM_REP_TYPE:")) {
                TmReportSession session = tmReportSessions.get(chatId);
                if (session == null) return;
                String type = data.substring("TM_REP_TYPE:".length());
                session.type = type;

                if (type.equals("FAST_TRACK")) {
                    session.step = "CONFIRM_FAST";
                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                            List.of(createBtn("✅ Да, отправить", "TM_REP_FAST_CONF:YES")),
                            List.of(createBtn("🔙 Назад к выбору", "TM_REPORT:" + session.taskId), createBtn("❌ Отменить", "TM_REP_CANCEL"))
                    ));
                    updateReportUI(chatId, session, "💼 Отправить: <b>«Выполнил служебное поручение»</b>.\nВы уверены?", markup);
                }
                else if (type.equals("PON")) {
                    session.step = "WAIT_CODE";
                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                            List.of(createBtn("102", "TM_REP_CODE:102"), createBtn("109", "TM_REP_CODE:109"), createBtn("111", "TM_REP_CODE:111")),
                            List.of(createBtn("113", "TM_REP_CODE:113"), createBtn("212", "TM_REP_CODE:212"), createBtn("214", "TM_REP_CODE:214")),
                            List.of(createBtn("215", "TM_REP_CODE:215"), createBtn("217", "TM_REP_CODE:217"), createBtn("226", "TM_REP_CODE:226")),
                            List.of(createBtn("227", "TM_REP_CODE:227")),
                            List.of(createBtn("🔙 Назад", "TM_REPORT:" + session.taskId), createBtn("❌ Отменить", "TM_REP_CANCEL"))
                    ));
                    String text = "🟢 <b>Заявка ПОН (Шаг 2)</b>\nВыберите код закрытия:\n\n<b>102</b> — Квитанция\n<b>109</b> — Перемонтаж в ОРА\n<b>111</b> — Перемонтаж ВОК-1 в ОРА\n<b>113</b> — Повреждения на абон. участке\n<b>212</b> — Перемонтаж ВОК-1 в ОРК\n<b>214</b> — Замена райзера\n<b>215</b> — Выправил пигтейл в ОРШ\n<b>217</b> — Запасное волокно\n<b>226</b> — Замена пигтейла в ОРШ\n<b>227</b> — Выправил кабель ВОК-1";
                    updateReportUI(chatId, session, text, markup);
                }
                else if (type.equals("SHPD")) {
                    session.step = "WAIT_RECEIPT_Q";
                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                            List.of(createBtn("🧾 Да", "TM_REP_HAS_REC:YES"), createBtn("🚫 Нет", "TM_REP_HAS_REC:NO")),
                            List.of(createBtn("🔙 Назад", "TM_REPORT:" + session.taskId), createBtn("❌ Отменить", "TM_REP_CANCEL"))
                    ));
                    updateReportUI(chatId, session, "🔵 <b>Заявка ШПД (Шаг 2)</b>\n\nВыписана квитанция?", markup);
                }
                AnswerCallbackQuery answer = new AnswerCallbackQuery(); answer.setCallbackQueryId(update.getCallbackQuery().getId()); try { execute(answer); } catch (Exception e) {} return;
            }

            if (data.startsWith("TM_REP_FAST_CONF:")) {
                TmReportSession session = tmReportSessions.remove(chatId);
                if (session != null && data.split(":")[1].equals("YES")) {
                    String repTxt = "Выполнил служебное поручение";
                    String tmSess = DatabaseManager.getTmSession(chatId);
                    boolean ok = tmSess != null && TmClient.performTask(tmSess, session.taskId, repTxt);
                    if (ok) updateReportUI(chatId, session, "✅ Отчёт #" + session.taskId + " отправлен:\n<i>" + repTxt + "</i>", new InlineKeyboardMarkup(List.of(List.of(createBtn("❌ Закрыть", "CANCEL_PROMPT")))));
                }
                return;
            }

            if (data.startsWith("TM_REP_CODE:")) {
                TmReportSession session = tmReportSessions.get(chatId); if (session == null) return;
                String code = data.split(":")[1]; session.closingCode = code;
                if (code.equals("102")) {
                    session.hasReceipt = true; session.step = "WAIT_RECEIPT_NUM";
                    updateReportUI(chatId, session, "🧾 <b>Код 102 (Квитанция)</b>\n\nВведите номер выписанной квитанции:", null);
                } else {
                    session.hasReceipt = false; session.step = "WAIT_MATERIALS";
                    sendMaterialSelectionForReport(chatId, session, session.anchorMsgId);
                }
                return;
            }

            if (data.startsWith("TM_REP_HAS_REC:")) {
                TmReportSession session = tmReportSessions.get(chatId); if (session == null) return;
                if (data.split(":")[1].equals("YES")) {
                    session.hasReceipt = true; session.step = "WAIT_RECEIPT_NUM";
                    updateReportUI(chatId, session, "🧾 <b>Заявка ШПД</b>\n\nВведите номер выписанной квитанции:", null);
                } else {
                    session.hasReceipt = false; session.step = "WAIT_MATERIALS";
                    sendMaterialSelectionForReport(chatId, session, session.anchorMsgId);
                }
                return;
            }

            if (data.startsWith("TM_REP_MAT:")) {
                TmReportSession session = tmReportSessions.get(chatId); if (session == null) return;
                session.waitingMaterialId = Integer.parseInt(data.split(":")[1]); session.step = "WAIT_MAT_QTY";
                updateReportUI(chatId, session, "✍️ <b>Введите количество</b> списываемого материала (например: 1 или 15.5):", null);
                return;
            }

            if (data.equals("TM_REP_MAT_DONE")) {
                TmReportSession session = tmReportSessions.get(chatId); if (session == null) return;

                if (session.isEditing) {
                    session.isEditing = false;
                    session.step = "PREVIEW";
                    showTmReportPreview(chatId, session, session.anchorMsgId);
                } else {
                    session.step = "WAIT_REPORT_TEXT";
                    updateReportUI(chatId, session, "📝 <b>Шаг 4: Текст отчета</b>\n\nНапишите текст отчета, который увидит диспетчер:", null);
                }
                return;
            }

            if (data.equals("TM_REP_PREVIEW")) {
                TmReportSession session = tmReportSessions.get(chatId); if (session == null) return;
                session.isEditing = false;
                session.step = "PREVIEW";
                showTmReportPreview(chatId, session, session.anchorMsgId);
                return;
            }

            if (data.equals("TM_REP_SEND")) {
                TmReportSession session = tmReportSessions.remove(chatId);
                if (session == null) return;

                List<String> tmParts = new ArrayList<>();
                if ("PON".equals(session.type) && !session.closingCode.isEmpty()) {
                    tmParts.add("КОД " + session.closingCode);
                }
                if (session.hasReceipt && !session.receiptNumber.isEmpty()) {
                    tmParts.add("квитанция #" + session.receiptNumber);
                }
                if (!session.reportText.isEmpty()) {
                    tmParts.add(session.reportText);
                }
                if (!session.retailStatus.isEmpty()) {
                    tmParts.add(session.retailStatus);
                }
                String finalReportText = String.join(", ", tmParts);

                String tmSession = DatabaseManager.getTmSession(chatId);
                boolean sentToTm = tmSession != null && TmClient.performTask(tmSession, session.taskId, finalReportText);

                for (DatabaseManager.ReceiptItem item : session.usedMaterials) {
                    WriteOffSession wo = new WriteOffSession();
                    wo.materialId = item.dbId;
                    wo.quantity = item.quantity;
                    wo.unit = item.unit;
                    wo.priceWithVat = item.priceWithVatPerUnit;
                    wo.materialName = item.name;
                    wo.isPaidReceipt = session.hasReceipt;
                    wo.receiptNumber = session.receiptNumber;
                    wo.closingCode = session.closingCode;
                    wo.reason = "Закрытие заявки ТМ #" + session.taskId;
                    wo.phoneNumber = "-";
                    wo.contractNumber = "-";
                    wo.address = "-";
                    DatabaseManager.completeWriteOff(chatId, wo);
                }

                String statusMsg = sentToTm
                        ? "✅ <b>Отчет по заявке #" + session.taskId + " успешно отправлен в ТМ!</b>\n\nТекст отчета:\n<code>" + finalReportText + "</code>"
                        : "⚠️ Материалы списаны, но <b>не удалось отправить отчет в ТМ</b> (проверьте подключение или статус заявки).";

                updateReportUI(chatId, session, statusMsg, new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("📋 Вернуться к заявкам", "TM_INPROGRESS"))
                )));
                return;
            }

            if (data.startsWith("TM_REP_RET:")) {
                TmReportSession session = tmReportSessions.get(chatId); if (session == null) return;
                String retVal = data.split(":")[1];
                if (retVal.equals("PLUS")) session.retailStatus = "Ритейл+";
                else if (retVal.equals("MINUS")) session.retailStatus = "Ритейл-";
                else session.retailStatus = "";
                session.step = "PREVIEW";
                showTmReportPreview(chatId, session, session.anchorMsgId);
                return;
            }

            if (data.equals("TM_REP_EDIT_TXT")) {
                TmReportSession session = tmReportSessions.get(chatId); if (session == null) return;
                session.isEditing = true;
                session.step = "WAIT_REPORT_TEXT";
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("🔙 Назад в превью", "TM_REP_PREVIEW"))
                ));
                updateReportUI(chatId, session, "📝 <b>Изменение текста</b>\n\nНапишите новый текст отчета для диспетчера:", markup);
                return;
            }

            if (data.equals("TM_REP_EDIT_MAT")) {
                TmReportSession session = tmReportSessions.get(chatId); if (session == null) return;
                session.isEditing = true;
                session.usedMaterials.clear();
                session.step = "WAIT_MATERIALS";
                sendMaterialSelectionForReport(chatId, session, session.anchorMsgId);
                return;
            }

            if (data.startsWith("TM_ASTUP:")) {
                if (!canAccessTm(chatId, role)) return;
                String taskId = data.substring("TM_ASTUP:".length());
                var astup = tmCallWithRetry(chatId, s -> TmClient.getAstup(s, taskId));
                editTmCardMessage(chatId, messageId, TmClient.formatAstup(astup), backButtonMarkup(taskId));
                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                try { execute(answer); } catch (TelegramApiException e) {}
                return;
            }
            if (data.startsWith("TM_PARAMS:")) {
                if (!canAccessTm(chatId, role)) return;
                String taskId = data.substring("TM_PARAMS:".length());
                var params = tmCallWithRetry(chatId, s -> TmClient.measureParams(s, taskId));
                editTmCardMessage(chatId, messageId, TmClient.formatParams(params), backButtonMarkup(taskId));
                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                try { execute(answer); } catch (TelegramApiException e) {}
                return;
            }
            if (data.startsWith("TM_HIST:")) {
                if (!canAccessTm(chatId, role)) return;
                String taskId = data.substring("TM_HIST:".length());
                var histObj = tmCallWithRetry(chatId, s -> TmClient.getTaskHistory(s, taskId));
                editTmCardMessage(chatId, messageId, TmClient.formatHistory(histObj, taskId), backButtonMarkup(taskId));
                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                try { execute(answer); } catch (TelegramApiException e) {}
                return;
            }
            if (data.startsWith("TM_BACK:")) {
                if (!canAccessTm(chatId, role)) return;
                String taskId = data.substring("TM_BACK:".length());

                JSONArray tasks = fetchTmTasks(chatId);
                if (tasks != null) {
                    JSONObject currentTask = null;
                    for (int i = 0; i < tasks.length(); i++) {
                        JSONObject t = tasks.optJSONObject(i);
                        if (taskId.equals(t.optString("id"))) {
                            currentTask = t;
                            break;
                        }
                    }

                    if (currentTask != null) {
                        int taskStatus = currentTask.optInt("task_status", 0);
                        String reportBtnText = "📝 Отправить отчёт";
                        String cardText = tmOriginalCardText.get(messageId);

                        if (cardText == null) {
                            cardText = TmClient.formatTask(currentTask);
                        }

                        if (taskStatus == 1) {
                            reportBtnText = "⏳ Отчёт отправлен (Ожидает)";
                            if (!cardText.contains("ОТЧЁТ НА РАССМОТРЕНИИ")) {
                                cardText = "💠 <b>ОТЧЁТ НА РАССМОТРЕНИИ</b> 💠\n<i>Ожидает проверки диспетчером...</i>\n➖➖➖➖➖➖➖➖➖➖\n" + cardText;
                                tmOriginalCardText.put(messageId, cardText);
                            }
                        } else if (taskStatus == 2) {
                            reportBtnText = "⚠️ ДОРАБОТАТЬ (Отправить заново)";
                            if (!cardText.contains("ВОЗВРАТ НА ДОРАБОТКУ")) {
                                String rejectReason = currentTask.optString("task_note", "Причина не указана");
                                if (rejectReason.isEmpty() || rejectReason.equals("null")) rejectReason = "Причина не указана диспетчером";
                                cardText = "❌ <b>ВОЗВРАТ НА ДОРАБОТКУ!</b>\n💬 <i>Причина: " + rejectReason + "</i>\n➖➖➖➖➖➖➖➖➖➖\n" + cardText;
                                tmOriginalCardText.put(messageId, cardText);
                            }
                        }

                        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                        markup.setKeyboard(List.of(
                                List.of(createBtn(reportBtnText, "TM_REPORT:" + taskId)),
                                List.of(createBtn("📍 АСТУП", "TM_ASTUP:" + taskId), createBtn("📊 Параметры", "TM_PARAMS:" + taskId)),
                                List.of(createBtn("📜 История", "TM_HIST:" + taskId))
                        ));

                        editTmCardMessage(chatId, messageId, cardText, markup);
                    } else {
                        sendMenu(chatId, role, "✅ Эта заявка уже закрыта диспетчером и убрана из вашего списка.");
                        try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
                    }
                }

                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                try { execute(answer); } catch (TelegramApiException e) {}
                return;
            }
            if (data.startsWith("MY_SCHED:")) {
                String[] p = data.split(":");
                String excelName = DatabaseManager.getUserExcelName(chatId);
                sendScheduleView(chatId, excelName, Integer.parseInt(p[1]), Integer.parseInt(p[2]), true, messageId);
                return;
            }

            if (data.startsWith("COL_SCHED:")) {
                String[] p = data.split(":");
                sendScheduleView(chatId, p[3], Integer.parseInt(p[1]), Integer.parseInt(p[2]), false, messageId);
                return;
            }

            if (data.startsWith("VIEW_SCHED:")) {
                String excelName = data.substring(11);
                LocalDate now = LocalDate.now();
                sendScheduleView(chatId, excelName, now.getYear(), now.getMonthValue(), false, messageId);
                return;
            }

            if (data.equals("SCHED_COL_LIST")) {
                List<String> names = DatabaseManager.getAllExcelNames();
                sendColleagueSelectionMenu(chatId, names, messageId);
                return;
            }

            if (data.startsWith("ED_SCH_U:") && canEditSchedule(chatId, role)) {
                String excelName = data.substring(9);
                // Сохраняем имя и ID текущего окна для идеального SPA
                waitingScheduleEditUser.put(chatId, excelName + ":" + messageId);

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("❌ Отменить", "CANCEL_PROMPT"))));
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("✏️ Выбран сотрудник: <b>" + excelName + "</b>\n\nНапишите дни, которые нужно изменить.\n\n<i>Можно писать без года:</i> <code>15.11</code>\n<i>Через запятую:</i> <code>10.11.2026, 15.11.26, 20.11</code>\n<i>Диапазоны:</i> <code>01.11-07.11</code>\n<i>Даже всё вместе:</i> <code>10.11, 14.11-19.11</code>");
                edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            if (data.startsWith("ED_SCH_S:") && canEditSchedule(chatId, role)) {
                String payload = data.substring(9);
                String savedData = waitingScheduleEditDate.remove(chatId);
                if (savedData == null) { sendMenu(chatId, role, "❌ Ошибка сессии редактирования. Начните заново."); return; }

                // Достаем сохраненный messageId окна, если он есть
                String[] mainParts = savedData.split(":");
                String targetUser = mainParts[0];
                String dateRangeStr = mainParts[1];
                Integer anchorMsgId = mainParts.length > 2 ? Integer.parseInt(mainParts[2]) : messageId;

                String statusCode = ""; String start = ""; String end = "";
                if (payload.equals("В") || payload.equals("О") || payload.equals("Д") || payload.equals("Б") || payload.equals("А") || payload.equals("Г") || payload.equals("П")) {
                    statusCode = payload;
                } else {
                    String[] times = payload.split("-");
                    start = times[0]; end = times[1];
                }

                try {
                    java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
                    String[] dateStrings = dateRangeStr.split(",");

                    int firstYear = 0;
                    int firstMonth = 0;

                    for (String ds : dateStrings) {
                        java.time.LocalDate current = java.time.LocalDate.parse(ds, dtf);
                        DatabaseManager.updateSingleShift(targetUser, current.getYear(), current.getMonthValue(), current.getDayOfMonth(), statusCode, start, end);
                        if (firstYear == 0) { firstYear = current.getYear(); firstMonth = current.getMonthValue(); }
                    }

                    // Обновляем окно графика, опираясь на первую измененную дату
                    sendScheduleView(chatId, targetUser, firstYear, firstMonth, false, anchorMsgId);

                    Long targetUserId = DatabaseManager.getUserIdByExcelName(targetUser);
                    if (targetUserId != null) {
                        sendDirectNotification(targetUserId, "⚠ <b>ВНИМАНИЕ!</b>\nАдминистратор изменил ваш график!\nДата(ы): <b>" + (dateStrings.length > 2 ? dateStrings.length + " дн." : dateRangeStr) + "</b>\nНовая смена/статус: <b>" + (statusCode.isEmpty() ? payload : statusCode) + "</b>");
                    }

                    AnswerCallbackQuery ans = new AnswerCallbackQuery();
                    ans.setCallbackQueryId(update.getCallbackQuery().getId());
                    ans.setText("✅ График успешно изменен!");
                    try { execute(ans); } catch (Exception e) {}

                } catch (Exception e) {
                    sendMenu(chatId, role, "❌ Ошибка при сохранении дат.");
                }
                return;
            }

            if (data.equals("DIR_ADD_MENU")) {
                sendDirectoryAddMenu(chatId);
                return;
            }
            if (data.startsWith("DIR_CAT:")) {
                int category = Integer.parseInt(data.split(":")[1]);
                waitingDirContactCat.put(chatId, category);
                sendCancelKeyboard(chatId, "✍ <b>Отлично!</b>\n\nВы можете <b>поделиться контактом</b> из телефонной книги (📎 Скрепка ➔ Контакт)\n\n👇 ИЛИ напишите данные вручную одним сообщением:\n<i>Пример: Электрик Иванов Иван: +375(29)111-22-33</i>");
                return;
            }
            if (data.equals("DIR_DEL_MENU") && "ADMIN".equals(role)) {
                sendDirectoryDelMenu(chatId);
                return;
            }
            if (data.startsWith("DIR_DEL:") && "ADMIN".equals(role)) {
                int contactId = Integer.parseInt(data.split(":")[1]);
                DatabaseManager.deleteDirectoryContact(contactId);
                sendDirectoryDelMenu(chatId);
                return;
            }
            if (data.equals("DIR_BACK")) {
                sendDirectory(chatId, role);
                return;
            }

            if (data.equals("W_MAIN_MENU")) {
                sendWeldersMenu(chatId, role, messageId, null); return;
            }
            if (data.equals("W_CLOSE")) {
                try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
                return;
            }
            if (data.startsWith("W_TAKE:")) {
                int welderId = Integer.parseInt(data.split(":")[1]);
                if (DatabaseManager.takeWelder(welderId, chatId, (Long) null)) {
                    if (chatId != DatabaseManager.ADMIN_ID) {
                        sendDirectNotification(DatabaseManager.ADMIN_ID, "ℹ️ <b>Сварочный аппарат взят!</b>\n👷‍♂ " + DatabaseManager.getUserFullName(chatId) + " взял аппарат <b>" + DatabaseManager.getWelderNameById(welderId) + "</b>.");
                    }
                    sendWeldersMenu(chatId, role, messageId, "Вы успешно взяли сварочный аппарат!");
                } else {
                    sendWeldersMenu(chatId, role, messageId, "❌ Ошибка: аппарат уже занят.");
                }
                return;
            }
            if (data.startsWith("W_RET:")) {
                int welderId = Integer.parseInt(data.split(":")[1]);
                if (DatabaseManager.returnWelder(welderId, chatId, null)) {
                    forceWelderReturnIds.remove(chatId);
                    sendWeldersMenu(chatId, role, messageId, "Вы успешно вернули сварочный аппарат на базу!");
                }
                return;
            }

            if (data.startsWith("W_TRANS_START:")) {
                int welderId = Integer.parseInt(data.split(":")[1]);
                sendWelderTransferUsersMenu(chatId, welderId, messageId);
                return;
            }

            if (data.startsWith("W_TRANS_SEL:")) {
                String[] p = data.split(":");
                int welderId = Integer.parseInt(p[1]);
                long receiverId = Long.parseLong(p[2]);

                if (pendingWelderTransfers.containsKey(receiverId)) {
                    AnswerCallbackQuery ans = new AnswerCallbackQuery();
                    ans.setCallbackQueryId(update.getCallbackQuery().getId());
                    ans.setText("❌ Этот сотрудник сейчас уже рассматривает другой запрос!");
                    ans.setShowAlert(true);
                    try { execute(ans); } catch (Exception e) {}
                    return;
                }

                PendingWelderTransfer pt = new PendingWelderTransfer();
                pt.welderId = welderId;
                pt.senderId = chatId;
                pt.receiverId = receiverId;
                pt.senderMsgId = messageId;

                pendingWelderTransfers.put(receiverId, pt);
                activeSenderTransfers.put(chatId, receiverId);

                // 1. Блокируем принимающего новым сообщением
                InlineKeyboardMarkup recMarkup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("✅ Принять", "W_TRANS_ACC:" + welderId + ":" + chatId),
                                createBtn("❌ Отказаться", "W_TRANS_REJ:" + welderId + ":" + chatId))
                ));
                SendMessage recMsg = new SendMessage(String.valueOf(receiverId), "🚨 <b>ПЕРЕДАЧА ОБОРУДОВАНИЯ!</b>\n\nКоллега <b>" + firstName + "</b> передает вам сварочный аппарат <b>" + DatabaseManager.getWelderNameById(welderId) + "</b>.\n\n👇 Подтвердите получение:");
                recMsg.setParseMode("HTML"); recMsg.setReplyMarkup(recMarkup);
                try { var sent = execute(recMsg); pt.receiverMsgId = sent.getMessageId(); } catch (Exception e) {}

                // 2. Окно передающего уходит в ожидание (SPA)
                String receiverName = DatabaseManager.getUserFullName(receiverId);
                InlineKeyboardMarkup sndMarkup = new InlineKeyboardMarkup(List.of(List.of(createBtn("🚫 Отменить передачу", "W_TRANS_CANCEL:" + welderId))));
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("⏳ <b>Ожидание подтверждения...</b>\n\nВы передаете аппарат <b>" + DatabaseManager.getWelderNameById(welderId) + "</b> сотруднику <b>" + receiverName + "</b>.\nЗапрос отправлен, ожидаем решения...");
                edit.setParseMode("HTML"); edit.setReplyMarkup(sndMarkup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            if (data.startsWith("W_TRANS_CANCEL:")) {
                Long recId = activeSenderTransfers.remove(chatId);
                if (recId != null) {
                    PendingWelderTransfer pt = pendingWelderTransfers.remove(recId);
                    if (pt != null && pt.receiverMsgId != null) {
                        try { execute(new DeleteMessage(String.valueOf(recId), pt.receiverMsgId)); } catch (Exception e) {}
                        sendDirectNotification(recId, "ℹ️ Коллега отменил передачу сварочного аппарата.");
                    }
                }
                sendWeldersMenu(chatId, role, messageId, "🚫 Вы отменили передачу аппарата.");
                return;
            }

            if (data.startsWith("W_TRANS_ACC:")) {
                String[] p = data.split(":");
                int welderId = Integer.parseInt(p[1]);
                long senderId = Long.parseLong(p[2]);

                PendingWelderTransfer pt = pendingWelderTransfers.remove(chatId);
                activeSenderTransfers.remove(senderId);

                // Запись в БД с проверкой успеха
                boolean success = DatabaseManager.transferWelder(welderId, senderId, chatId);
                if (!success) {
                    AnswerCallbackQuery ans = new AnswerCallbackQuery();
                    ans.setCallbackQueryId(update.getCallbackQuery().getId());
                    ans.setText("❌ Ошибка базы данных! Сварочник не передан.");
                    ans.setShowAlert(true);
                    try { execute(ans); } catch (Exception e) {}
                    return;
                }

                // Окно принимающего
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("✅ <b>Вы успешно приняли сварочный аппарат!</b>");
                edit.setParseMode("HTML"); edit.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE")))));
                try { execute(edit); } catch (Exception e) {}

                // SPA обновление окна передающего
                if (pt != null && pt.senderMsgId != null) {
                    sendWeldersMenu(senderId, DatabaseManager.getUserRole(senderId, "Сотрудник"), pt.senderMsgId, "✅ Коллега подтвердил прием сварочника!");
                }
                return;
            }

            if (data.startsWith("W_TRANS_REJ:")) {
                String[] p = data.split(":");
                long senderId = Long.parseLong(p[2]);

                PendingWelderTransfer pt = pendingWelderTransfers.remove(chatId);
                activeSenderTransfers.remove(senderId);

                // Окно принимающего
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("❌ Вы отказались от приема сварочного аппарата.");
                edit.setParseMode("HTML"); edit.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE")))));
                try { execute(edit); } catch (Exception e) {}

                // SPA обновление окна передающего
                if (pt != null && pt.senderMsgId != null) {
                    sendWeldersMenu(senderId, DatabaseManager.getUserRole(senderId, "Сотрудник"), pt.senderMsgId, "❌ Коллега отказался принять сварочник.");
                }
                return;
            }

            if (data.equals("W_KEEP_TOMORROW")) {
                forceWelderReturnIds.remove(chatId);
                keptWelderForTomorrow.put(chatId, LocalDate.now());

                try { execute(new DeleteMessage(String.valueOf(chatId), messageId)); } catch (Exception e) {}
                sendWeldersMenu(chatId, role, null, "🌙 Вы оставили сварочный аппарат за собой на завтра. Блокировка снята.");
                return;
            }
            if (data.equals("W_HISTORY")) {
                sendWeldersHistoryMenu(chatId, messageId); return;
            }
            if (data.equals("W_FORCE_TAKE_M") && "ADMIN".equals(role)) {
                sendWeldersForceAssignMenu(chatId, messageId); return;
            }
            if (data.startsWith("W_F_SEL:") && "ADMIN".equals(role)) {
                sendWeldersForceAssignUsers(chatId, Integer.parseInt(data.split(":")[1]), messageId); return;
            }
            if (data.startsWith("W_F_ASS:") && "ADMIN".equals(role)) {
                String[] parts = data.split(":");
                int welderId = Integer.parseInt(parts[1]);
                long targetUserId = Long.parseLong(parts[2]);
                if (DatabaseManager.takeWelder(welderId, targetUserId, Long.valueOf(chatId))) {
                    sendDirectNotification(targetUserId, "🔔 <b>Администратор выдал вам сварочный аппарат!</b>\nЗа вами закреплен: <b>" + DatabaseManager.getWelderNameById(welderId) + "</b>");
                    sendWeldersMenu(chatId, role, messageId, "Аппарат " + DatabaseManager.getWelderNameById(welderId) + " принудительно выдан.");
                }
                return;
            }
            if (data.equals("W_FORCE_RET_M") && "ADMIN".equals(role)) {
                sendWeldersForceReturnMenu(chatId, messageId); return;
            }
            if (data.startsWith("W_F_RET:") && "ADMIN".equals(role)) {
                int welderId = Integer.parseInt(data.split(":")[1]);
                if (DatabaseManager.returnWelder(welderId, chatId, chatId)) {
                    sendWeldersMenu(chatId, role, messageId, "⚠️ Аппарат " + DatabaseManager.getWelderNameById(welderId) + " принудительно списан на базу.");
                }
                return;
            }

            if (data.startsWith("F_ASS:") && "ADMIN".equals(role)) {
                String[] res = DatabaseManager.assignAnyAvailableToolFast(Integer.parseInt(data.split(":")[1]), Long.parseLong(data.split(":")[2]));
                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                answer.setText(res[1]); answer.setShowAlert(false);
                try { execute(answer); } catch (TelegramApiException e) {}
                if ("OK".equals(res[0])) sendDirectNotification(Long.parseLong(data.split(":")[2]), res[2]);
                return;
            }

            if (data.startsWith("ORSH_SEL:")) {
                sendOrshDetails(chatId, Integer.parseInt(data.split(":")[1])); return;
            }
            if (data.startsWith("ORSH_PROB:")) {
                int orshId = Integer.parseInt(data.split(":")[1]);
                waitingOrshPhoto.remove(chatId);
                waitingOrshProblemReason.put(chatId, orshId);
                sendCancelKeyboard(chatId, "⚠️ <b>Невозможно сделать фото!</b>\nНапишите причину (например: затоплен подвал, нет ключа):");
                return;
            }

            if (data.startsWith("NEW_USER_APP:") && "ADMIN".equals(role)) {
                long targetId = Long.parseLong(data.split(":")[1]);
                DatabaseManager.setBanStatus(targetId, false);
                sendMenu(chatId, role, "✅ Пользователь " + targetId + " одобрен и получил доступ.");
                sendDirectNotification(targetId, "✅ <b>Администратор одобрил ваш доступ!</b>\nТеперь вы можете пользоваться ботом. Нажмите /start для обновления меню.");
                return;
            }
            if (data.startsWith("NEW_USER_REJ:") && "ADMIN".equals(role)) {
                long targetId = Long.parseLong(data.split(":")[1]);
                DatabaseManager.setBanStatus(targetId, true);
                sendMenu(chatId, role, "❌ Пользователь " + targetId + " заблокирован.");
                return;
            }

            if (data.equals("CART_MENU")) { sendCartMenu(chatId, role, messageId); return; }
            if (data.equals("CART_ADD_SRV")) { sendServiceSelectionForCart(chatId, messageId); return; }
            if (data.equals("CART_ADD_MAT")) { sendMaterialSelectionForCart(chatId, role, messageId); return; }
            if (data.startsWith("CART_SEL_SRV:")) {
                int srvId = Integer.parseInt(data.split(":")[1]);
                DatabaseManager.ReceiptSession s = receiptSessions.computeIfAbsent(chatId, k -> new DatabaseManager.ReceiptSession());
                s.anchorMsgId = messageId;
                DatabaseManager.ReceiptItem item = DatabaseManager.getServiceById(srvId);
                if (item != null) {
                    if (item.isSingle) {
                        item.quantity = 1.0; s.items.add(item);
                        sendCartMenu(chatId, role, messageId);
                    } else {
                        s.waitingServiceId = srvId; s.waitingMaterialId = -1;
                        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("🔙 Назад в корзину", "CART_MENU"))));
                        EditMessageText edit = new EditMessageText();
                        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                        edit.setText("✍ <b>Введите количество</b> для выбранной услуги (например: 1 или 2):");
                        edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                        try { execute(edit); } catch (Exception e) {}
                    }
                }
                return;
            }
            if (data.startsWith("CART_SEL_MAT:")) {
                int matId = Integer.parseInt(data.split(":")[1]);
                DatabaseManager.ReceiptSession s = receiptSessions.computeIfAbsent(chatId, k -> new DatabaseManager.ReceiptSession());
                s.anchorMsgId = messageId;
                s.waitingMaterialId = matId; s.waitingServiceId = -1;
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("🔙 Назад в корзину", "CART_MENU"))));
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("✍️ <b>Введите количество</b> израсходованного материала (например: 15 или 0.02):");
                edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }
            if (data.equals("CART_FINISH")) {
                DatabaseManager.ReceiptSession s = receiptSessions.get(chatId);
                if (s == null || s.items.isEmpty()) {
                    AnswerCallbackQuery ans = new AnswerCallbackQuery();
                    ans.setCallbackQueryId(update.getCallbackQuery().getId());
                    ans.setText("❌ Ваша квитанция пуста.");
                    try { execute(ans); } catch (Exception e) {}
                } else {
                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                            List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE"))
                    ));
                    EditMessageText edit = new EditMessageText();
                    edit.setChatId(String.valueOf(chatId));
                    edit.setMessageId(messageId);
                    edit.setText(DatabaseManager.generateReceiptText(s));
                    edit.setParseMode("HTML");
                    edit.setReplyMarkup(markup);
                    try { execute(edit); } catch (TelegramApiException e) {}

                    receiptSessions.remove(chatId);

                    AnswerCallbackQuery ans = new AnswerCallbackQuery();
                    ans.setCallbackQueryId(update.getCallbackQuery().getId());
                    ans.setText("✅ Квитанция успешно сформирована!");
                    try { execute(ans); } catch (Exception e) {}
                }
                return;
            }
            if (data.equals("CART_CLEAR")) {
                receiptSessions.remove(chatId);
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("🗑 Корзина очищена.");
                try { execute(edit); } catch (TelegramApiException e) {}
                return;
            }

            if (data.startsWith("BIND_NAME:")) {
                String excelName = data.substring(10);
                DatabaseManager.bindUserToExcelName(chatId, excelName);
                sendMenu(chatId, role, "✅ Отлично! Ваш профиль успешно привязан к: <b>" + excelName + "</b>.\n\nТеперь вам полностью доступны все функции системы.");
                return;
            }

            if (data.startsWith("TAKE_MAT:")) {
                writeOffCartSessions.remove(chatId);
                waitingTakeMaterialId.put(chatId, Integer.parseInt(data.split(":")[1]));

                InlineKeyboardMarkup inlineMarkup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("1", "TAKE_Q:1"), createBtn("2", "TAKE_Q:2"), createBtn("3", "TAKE_Q:3"), createBtn("4", "TAKE_Q:4"), createBtn("5", "TAKE_Q:5")),
                        List.of(createBtn("10", "TAKE_Q:10"), createBtn("20", "TAKE_Q:20"), createBtn("50", "TAKE_Q:50"), createBtn("100", "TAKE_Q:100")),
                        List.of(createBtn("❌ Отменить", "WH_CLOSE"))
                ));
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText("✍ <b>Укажите количество:</b>\nНапишите число вручную (например: <code>15</code>) ИЛИ нажмите на кнопку:");
                edit.setParseMode("HTML"); edit.setReplyMarkup(inlineMarkup);
                try { execute(edit); } catch (Exception e) {}
                return;
            }

            if (data.startsWith("TAKE_Q:")) {
                if (waitingTakeMaterialId.containsKey(chatId)) {
                    try {
                        double qty = Double.parseDouble(data.split(":")[1]);
                        String result = DatabaseManager.takeMaterialFromWarehouse(chatId, waitingTakeMaterialId.get(chatId), qty);
                        if (result.startsWith("✅")) { waitingTakeMaterialId.remove(chatId); sendMenu(chatId, role, result); }
                        else { SendMessage msg = new SendMessage(String.valueOf(chatId), result); msg.setParseMode("HTML"); try { execute(msg); } catch (TelegramApiException e) {} }
                    } catch (Exception e) {}
                } else sendMenu(chatId, role, "❌ Ошибка: вы не выбрали материал.");
                return;
            }

            if (data.startsWith("RET_APP:") && "ADMIN".equals(role)) {
                String[] res = DatabaseManager.approveReturnRequest(Integer.parseInt(data.split(":")[1]));
                sendMenu(chatId, role, res[2]);
                if ("OK".equals(res[0])) if (Long.parseLong(res[1]) != chatId) sendDirectNotification(Long.parseLong(res[1]), res[3]);
                return;
            }

            if (data.startsWith("RET_REJ:") && "ADMIN".equals(role)) {
                String[] res = DatabaseManager.rejectReturnRequest(Integer.parseInt(data.split(":")[1]));
                sendMenu(chatId, role, res[2]);
                if ("OK".equals(res[0])) if (Long.parseLong(res[1]) != chatId) sendDirectNotification(Long.parseLong(res[1]), res[3]);
                return;
            }

            // --- НАЧАЛО НОВОЙ ЛОГИКИ КОРЗИНЫ СПИСАНИЯ ---
            if (data.equals("WO_CLEAR_CART")) {
                writeOffCartSessions.remove(chatId);
                startWriteOffMenu(chatId, role, messageId, "🗑 Список материалов очищен.");
                return;
            }

            if (data.equals("WO_CHECKOUT")) {
                WriteOffCartSession cart = writeOffCartSessions.get(chatId);
                if (cart == null || cart.items.isEmpty()) return;
                cart.step = "WAIT_TYPE";
                sendTypeButtons(chatId, cart, messageId);
                return;
            }

            if (data.equals("WO_BACK_LIST")) {
                WriteOffCartSession cart = writeOffCartSessions.get(chatId);
                if (cart != null) {
                    cart.tempItem = null;
                    if (cart.items.isEmpty()) writeOffCartSessions.remove(chatId);
                }
                startWriteOffMenu(chatId, role, messageId, null);
                return;
            }

            if (data.startsWith("WO_MAT:")) {
                waitingTakeMaterialId.remove(chatId);
                WriteOffSession temp = DatabaseManager.createWriteOffSession(chatId, Integer.parseInt(data.split(":")[1]));
                if (temp == null) { startWriteOffMenu(chatId, role, messageId, null); return; }

                WriteOffCartSession cart = writeOffCartSessions.computeIfAbsent(chatId, k -> new WriteOffCartSession());
                cart.anchorMsgId = messageId;

                // Вычитаем из доступного то, что уже добавлено в корзину
                for (WriteOffSession item : cart.items) {
                    if (item.materialId == temp.materialId) {
                        temp.maxAvailable -= item.quantity;
                    }
                }

                if (temp.maxAvailable <= 0) {
                    startWriteOffMenu(chatId, role, messageId, "❌ Вы уже добавили весь доступный объем этого материала в список!");
                    return;
                }

                cart.tempItem = temp;
                cart.step = "WAIT_QTY";

                InlineKeyboardMarkup inlineMarkup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("1", "WO_Q:1"), createBtn("2", "WO_Q:2"), createBtn("5", "WO_Q:5"), createBtn("10", "WO_Q:10")),
                        List.of(createBtn("Всё (" + DatabaseManager.fmtQty(temp.maxAvailable) + " " + temp.unit + ")", "WO_Q:" + temp.maxAvailable)),
                        List.of(createBtn("🔙 Назад к списку", "WO_BACK_LIST"), createBtn("❌ Отменить", "CANCEL_PROMPT"))
                ));

                String text = String.format("Выбрано: <b>%s</b>\nДоступно к добавлению: <b>%s %s</b>\n\n✍️ Напишите количество вручную ИЛИ выберите быстрый вариант:", temp.materialName, DatabaseManager.fmtQty(temp.maxAvailable), temp.unit);
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(text); edit.setParseMode("HTML"); edit.setReplyMarkup(inlineMarkup);
                try { execute(edit); } catch (TelegramApiException e) {} return;
            }

            if (data.startsWith("WO_Q:") && writeOffCartSessions.containsKey(chatId)) {
                WriteOffCartSession s = writeOffCartSessions.get(chatId);
                if (s.tempItem == null) return;
                try {
                    double qty = Double.parseDouble(data.split(":")[1]);
                    if (qty <= 0 || qty > s.tempItem.maxAvailable + 1e-9) {
                        AnswerCallbackQuery ans = new AnswerCallbackQuery();
                        ans.setCallbackQueryId(update.getCallbackQuery().getId());
                        ans.setText("❌ Доступно не более " + DatabaseManager.fmtQty(s.tempItem.maxAvailable) + " " + s.tempItem.unit);
                        ans.setShowAlert(true);
                        try { execute(ans); } catch (Exception e){}
                        return;
                    }
                    s.tempItem.quantity = qty;
                    s.items.add(s.tempItem);
                    s.tempItem = null;
                    startWriteOffMenu(chatId, role, messageId, "✅ Материал добавлен в список!");
                } catch (Exception e) {} return;
            }

            if (data.startsWith("WO_TYPE:") && writeOffCartSessions.containsKey(chatId)) {
                WriteOffCartSession cart = writeOffCartSessions.get(chatId);
                String type = data.split(":")[1];
                if ("RETURN".equals(type)) {
                    StringBuilder finalRes = new StringBuilder();
                    for (WriteOffSession item : cart.items) {
                        DatabaseManager.ReturnRequestInfo req = DatabaseManager.createReturnRequest(chatId, item.materialId, item.quantity);
                        if (req.success) {
                            if ("ADMIN".equals(role)) {
                                finalRes.append("⚡️ <b>Возврат:</b> ").append(DatabaseManager.approveReturnRequest(req.requestId)[2]).append("\n");
                            } else {
                                finalRes.append(req.messageForWorker).append("\n");
                                sendReturnApprovalToAdmins(req.requestId, req.messageForAdmin);
                            }
                        } else {
                            finalRes.append(req.messageForWorker).append("\n");
                        }
                    }
                    writeOffCartSessions.remove(chatId);
                    startWriteOffMenu(chatId, role, messageId, finalRes.toString().trim());
                } else if ("PAID".equals(type)) {
                    cart.isPaidReceipt = true; cart.step = "WAIT_RECEIPT_NUM";
                    updateWriteOffUI(chatId, messageId, "🧾 <b>Списание по квитанции (шаг 1 из 4)</b>\nВведите <b>номер квитанции</b>:");
                } else {
                    cart.isPaidReceipt = false; cart.step = "WAIT_FREE_PHONE";
                    updateWriteOffUI(chatId, messageId, "🛠 <b>Техническое списание (шаг 1 из 5)</b>\nВведите <b>номер телефона</b>, на который оформлена заявка:");
                }
                return;
            }

            if (data.startsWith("WO_CODE:") && writeOffCartSessions.containsKey(chatId)) {
                WriteOffCartSession s = writeOffCartSessions.remove(chatId);
                String closingCode = data.split(":")[1];
                String reason = switch (closingCode) {
                    case "212" -> "Ремонт ВОК на участке ОРК-ОРА"; case "227" -> "Выправление волокна";
                    case "215" -> "В ОРШ: выправление пигтейла/волокна"; case "226" -> "В ОРШ: замена пигтейла / адаптера";
                    case "214" -> "Участок ОРШ-ОРК: ремонт/замена райзера"; case "217" -> "Участок ОРШ-ОРК: запасной модуль";
                    default -> "Тех. списание (Код " + closingCode + ")";
                };

                StringBuilder finalRes = new StringBuilder("🛠 <b>Результаты списания:</b>\n");
                for (WriteOffSession item : s.items) {
                    item.isPaidReceipt = false;
                    item.phoneNumber = s.phoneNumber;
                    item.contractNumber = s.contractNumber;
                    item.address = s.address;
                    item.closingCode = closingCode;
                    item.reason = reason;
                    finalRes.append(DatabaseManager.completeWriteOff(chatId, item)).append("\n");
                }

                if (!"ADMIN".equals(role)) notifyAdminsForAction(chatId, "🔔 <b>ВНИМАНИЕ! Списание:</b>\nМастер <b>" + firstName + "</b> выполнил техническое списание:\n\n" + finalRes.toString());

                startWriteOffMenu(chatId, role, messageId, finalRes.toString().trim());
                return;
            }
            // --- КОНЕЦ НОВОЙ ЛОГИКИ КОРЗИНЫ СПИСАНИЯ ---

            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String firstName = update.getMessage().getFrom().getFirstName();
            String text = update.getMessage().getText();

            // --- НОВЫЙ БЛОК: УДАЛЯЕМ СООБЩЕНИЕ ПОЛЬЗОВАТЕЛЯ ИЗ ЧАТА ---
            int userMessageId = update.getMessage().getMessageId();
            try {
                execute(new DeleteMessage(String.valueOf(chatId), userMessageId));
            } catch (Exception e) {
                // Игнорируем ошибку, если сообщение уже удалено или недоступно
            }
            // ----------------------------------------------------------

            String role = checkRoleAndNotify(chatId, firstName);

            if ("BANNED".equals(role)) return;
            if ("PENDING".equals(role)) {
                if (text.equals("/start")) sendDirectNotification(chatId, "⏳ Ваша заявка все еще находится на рассмотрении администратора.");
                return;
            }

            // Блокировка текстовых команд для принимающего
            if (pendingWelderTransfers.containsKey(chatId)) {
                SendMessage blockMsg = new SendMessage(String.valueOf(chatId), "⚠️ <b>ДОСТУП ЗАБЛОКИРОВАН!</b>\nКоллега передает вам сварочный аппарат.\n\n👇 Пожалуйста, найдите сообщение ниже с кнопками <b>«Принять»</b> / <b>«Отказаться»</b> и сделайте выбор.");
                blockMsg.setParseMode("HTML"); try { execute(blockMsg); } catch (Exception e) {}
                return;
            }

            if (checkUnboundAndNotify(chatId, role)) return;

            if (text.equals("🛠 Неисправности")) {
                // Очищаем чат от мусора (сообщения пользователя)
                try { execute(new DeleteMessage(String.valueOf(chatId), update.getMessage().getMessageId())); } catch (Exception e) {}

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("🌐 Модемы", "ERRORS_MODEMS"), createBtn("📺 Приставки", "ERRORS_STB")),
                        List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE"))
                ));
                SendMessage msg = new SendMessage(String.valueOf(chatId), "🛠 <b>Справочник неисправностей</b>\n\nВыберите тип оборудования, чтобы просмотреть частые проблемы:");
                msg.setParseMode("HTML");
                msg.setReplyMarkup(markup);
                try { execute(msg); } catch (Exception e) {}
                return;
            }

            if (text.equals("❌ Отменить") || text.equals("🔙 Назад")) {
                TmReportSession cancelledSession = tmReportSessions.remove(chatId);
                if (cancelledSession != null) {
                    if (cancelledSession.anchorMsgId != null) {
                        try { execute(new DeleteMessage(String.valueOf(chatId), cancelledSession.anchorMsgId)); } catch (Exception e) {}
                    }
                    JSONArray tasks = fetchTmTasks(chatId);
                    sendTmInProgressList(chatId, role, tasks);
                    return;
                }
                boolean returnToSchedule = waitingSickLeaveDate.containsKey(chatId) ||
                        waitingScheduleEditUser.containsKey(chatId) ||
                        waitingScheduleEditDate.containsKey(chatId);

                waitingNameFix.remove(chatId);
                waitingTakeMaterialId.remove(chatId); writeOffCartSessions.remove(chatId); fileWaitState.remove(chatId);
                waitingToolWriteOffReason.remove(chatId); waitingOrshPhoto.remove(chatId); waitingOrshProblemReason.remove(chatId);
                waitingDirContactCat.remove(chatId); waitingScheduleEditUser.remove(chatId); waitingScheduleEditDate.remove(chatId);
                waitingNewEmployeeName.remove(chatId);
                waitingSickLeaveDate.remove(chatId);
                waitingTmReportTaskId.remove(chatId);
                tmAuthStep.remove(chatId);
                tmReportSessions.remove(chatId);

                DatabaseManager.ReceiptSession cartSession = receiptSessions.get(chatId);
                if (cartSession != null) {
                    cartSession.waitingServiceId = -1;
                    cartSession.waitingMaterialId = -1;
                }

                if (returnToSchedule) {
                    sendScheduleMenu(chatId, role, text.equals("❌ Отменить") ? "🚫 <b>Действие отменено.</b>" : "Вы вернулись в меню графиков:", null);
                } else {
                    if (text.equals("❌ Отменить")) sendMenu(chatId, role, "🚫 <b>Действие отменено.</b>");
                    else sendMenu(chatId, role, "Вы вернулись в главное меню:");
                }
                return;
            }

            if (text.startsWith("📦") || text.startsWith("🧰") || text.startsWith("📝") || text.startsWith("📋") || text.startsWith("🔢") || text.startsWith("🤝") || text.startsWith("📊") || text.startsWith("📥") || text.startsWith("📑") || text.startsWith("🗓") || text.startsWith("🔍") || text.startsWith("📢") || text.startsWith("👥") || text.startsWith("🪛") || text.startsWith("🛠") || text.startsWith("📞") || text.startsWith("🔌") || text.startsWith("👁") || text.startsWith("✏️") || text.startsWith("🤒") || text.equals("/start")) {
                clearChatHistory(chatId);
                waitingNameFix.remove(chatId);
                waitingTakeMaterialId.remove(chatId); waitingDirContactCat.remove(chatId); writeOffCartSessions.remove(chatId);
                fileWaitState.remove(chatId); waitingToolWriteOffReason.remove(chatId); waitingOrshPhoto.remove(chatId);
                waitingOrshProblemReason.remove(chatId); waitingScheduleEditUser.remove(chatId); waitingScheduleEditDate.remove(chatId);
                waitingNewEmployeeName.remove(chatId);
                waitingSickLeaveDate.remove(chatId);

                waitingTmReportTaskId.remove(chatId);
                tmAuthStep.remove(chatId);

                DatabaseManager.ReceiptSession cartSession = receiptSessions.get(chatId);
                if (cartSession != null) {
                    cartSession.waitingServiceId = -1;
                    cartSession.waitingMaterialId = -1;
                }
            }

            if (waitingNameFix.containsKey(chatId)) {
                String oldName = waitingNameFix.remove(chatId);
                String newName = text.trim();

                if (newName.isEmpty()) {
                    sendMenu(chatId, role, "❌ Имя не может быть пустым. Действие отменено.");
                    return;
                }

                String result = DatabaseManager.fixNameTypo(oldName, newName);
                sendMenu(chatId, role, result);
                return;
            }

            TmReportSession repSession = tmReportSessions.get(chatId);
            if (repSession != null) {
                if ("WAIT_RECEIPT_NUM".equals(repSession.step)) {
                    repSession.receiptNumber = text.trim();
                    repSession.step = "WAIT_MATERIALS";
                    sendMaterialSelectionForReport(chatId, repSession, repSession.anchorMsgId);
                    return;
                }

                if ("WAIT_MAT_QTY".equals(repSession.step)) {
                    try {
                        double qty = Double.parseDouble(text.trim().replace(",", "."));
                        if (qty <= 0) throw new NumberFormatException();
                        DatabaseManager.ReceiptItem item = DatabaseManager.getMaterialFromBalanceById(chatId, repSession.waitingMaterialId);
                        if (item != null) { item.quantity = qty; repSession.usedMaterials.add(item); }
                        repSession.waitingMaterialId = -1;
                        repSession.step = "WAIT_MATERIALS";
                        sendMaterialSelectionForReport(chatId, repSession, repSession.anchorMsgId);
                    } catch (NumberFormatException e) {
                        updateReportUI(chatId, repSession, "❌ Введите корректное число больше нуля (например: 5 или 0.5):", null);
                    }
                    return;
                }

                if ("WAIT_REPORT_TEXT".equals(repSession.step)) {
                    repSession.reportText = text.trim();

                    // ЕСЛИ МЫ РЕДАКТИРОВАЛИ ТОЛЬКО ТЕКСТ — СРАЗУ В ПРЕВЬЮ!
                    if (repSession.isEditing) {
                        repSession.isEditing = false;
                        repSession.step = "PREVIEW";
                        showTmReportPreview(chatId, repSession, repSession.anchorMsgId);
                        return;
                    }

                    // Если это обычный первый проход — спрашиваем про Ритейл
                    repSession.step = "WAIT_RETAIL";
                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                            List.of(createBtn("Ритейл +", "TM_REP_RET:PLUS"), createBtn("Ритейл -", "TM_REP_RET:MINUS")),
                            List.of(createBtn("Не указывать", "TM_REP_RET:NONE")),
                            List.of(createBtn("🔙 Назад к тексту", "TM_REP_MAT_DONE"), createBtn("❌ Отменить", "TM_REP_CANCEL"))
                    ));
                    updateReportUI(chatId, repSession, "🛍 <b>Шаг 5: Допродажи (Ритейл)</b>\n\nПредлагались ли абоненту товары в рассрочку?", markup);
                    return;
                }
            }

            if (text.startsWith("/fix_name") && "ADMIN".equals(role)) {
                String oldName = text.replace("/fix_name", "").trim();

                if (oldName.isEmpty()) {
                    sendMenu(chatId, role, "⚠️ <b>Использование команды:</b>\nНапишите <code>/fix_name Старое Имя В.З.</code> (обязательно с пробелом после команды).");
                    return;
                }

                waitingNameFix.put(chatId, oldName);
                sendCancelKeyboard(chatId, "📝 Вы хотите переименовать сотрудника «<b>" + oldName + "</b>».\n\n👇 Введите правильную фамилию и инициалы (например: <i>Новосельский В.З.</i>):");
                return;
            }

            if (waitingDirContactCat.containsKey(chatId)) {
                int category = waitingDirContactCat.remove(chatId);
                if (DatabaseManager.addDirectoryContact(category, text.trim(), chatId)) {
                    sendMenu(chatId, role, "✅ <b>Контакт успешно добавлен!</b>\nОн теперь отображается в справочнике у всех сотрудников.");
                    sendDirectory(chatId, role);
                } else sendMenu(chatId, role, "❌ Ошибка при сохранении контакта.");
                return;
            }
            if (waitingNewEmployeeName.containsKey(chatId)) {
                waitingNewEmployeeName.remove(chatId);
                String newName = text.trim();
                sendMenu(chatId, role, DatabaseManager.addNewEmployeeToSchedule(newName));
                return;
            }

            if (waitingScheduleEditUser.containsKey(chatId)) {
                String savedState = waitingScheduleEditUser.remove(chatId);
                String[] stateParts = savedState.split(":");
                String targetUser = stateParts[0];
                Integer anchorId = stateParts.length > 1 ? Integer.parseInt(stateParts[1]) : null;

                String input = text.trim();
                java.time.format.DateTimeFormatter dtfFull = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

                int currentYear = java.time.LocalDate.now().getYear();
                int currentMonth = java.time.LocalDate.now().getMonthValue();

                List<java.time.LocalDate> targetDates = new ArrayList<>();
                String displayDate = "";

                try {
                    // Разбиваем строку по запятым, чтобы получить отдельные элементы (даты или диапазоны)
                    String[] items = input.split(",");

                    for (String item : items) {
                        item = item.trim();
                        if (item.isEmpty()) continue;

                        if (item.contains("-")) {
                            // Это диапазон
                            String[] parts = item.split("-");
                            java.time.LocalDate start = parseSmartDate(parts[0].trim(), currentYear, currentMonth);
                            java.time.LocalDate end = parseSmartDate(parts[1].trim(), currentYear, currentMonth);

                            if (start.isAfter(end)) {
                                if (start.getMonthValue() == 12 && end.getMonthValue() == 1) {
                                    end = end.plusYears(1); // Легитимный переход через Новый год
                                } else {
                                    throw new Exception("Начальная дата больше конечной"); // Опечатка
                                }
                            }

                            java.time.LocalDate current = start;
                            while (!current.isAfter(end)) {
                                if (!targetDates.contains(current)) targetDates.add(current);
                                current = current.plusDays(1);
                            }
                        } else {
                            // Это одиночная дата
                            java.time.LocalDate date = parseSmartDate(item, currentYear, currentMonth);
                            if (!targetDates.contains(date)) targetDates.add(date);
                        }
                    }

                    if (targetDates.isEmpty()) throw new Exception("Не найдено ни одной даты");

                    // Сортируем даты по порядку
                    java.util.Collections.sort(targetDates);

                    if (targetDates.size() == 1) {
                        displayDate = targetDates.get(0).format(dtfFull);
                    } else {
                        displayDate = "выбранные дни (" + targetDates.size() + " шт.)";
                    }

                } catch (Exception e) {
                    waitingScheduleEditUser.put(chatId, savedState);
                    if (anchorId != null) {
                        EditMessageText edit = new EditMessageText();
                        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(anchorId);
                        edit.setText("❌ <b>Не удалось распознать дату.</b>\n\nВы можете писать в любых форматах (с годом и без):\n• Одиночно: <code>15.11</code> или <code>15.11.26</code> или <code>15.11.2026</code>\n• Диапазон: <code>01.11-07.11</code> или <code>01.11.26-07.11.2026</code>\n• Комбинировано: <code>10.11, 15.11-18.11, 20.11.26</code>\n\nВведите даты еще раз:");
                        edit.setParseMode("HTML");
                        edit.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(createBtn("❌ Отменить", "CANCEL_PROMPT")))));
                        try { execute(edit); } catch (Exception ignored) {}
                    }
                    return;
                }

                // Формируем строку дат для передачи в кнопку
                StringBuilder sbDates = new StringBuilder();
                for (java.time.LocalDate d : targetDates) {
                    sbDates.append(d.format(dtfFull)).append(",");
                }
                String datesString = sbDates.toString();
                if (datesString.endsWith(",")) datesString = datesString.substring(0, datesString.length() - 1);

                waitingScheduleEditDate.put(chatId, targetUser + ":" + datesString + (anchorId != null ? ":" + anchorId : ""));

                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("В (Выходной)", "ED_SCH_S:В"), createBtn("О (Отпуск)", "ED_SCH_S:О"), createBtn("Д (Дежурство)", "ED_SCH_S:Д")),
                        List.of(createBtn("Б (Больничный)", "ED_SCH_S:Б"), createBtn("А (За свой счет)", "ED_SCH_S:А"), createBtn("Г (Военкомат)", "ED_SCH_S:Г")),
                        List.of(createBtn("П (Другое подразделение)", "ED_SCH_S:П")),
                        List.of(createBtn("08:30 - 17:30", "ED_SCH_S:08:30-17:30"), createBtn("12:00 - 21:00", "ED_SCH_S:12:00-21:00")),
                        List.of(createBtn("08:00 - 16:00 (Суббота)", "ED_SCH_S:08:00-16:00")),
                        List.of(createBtn("❌ Отменить", "CANCEL_PROMPT"))
                ));

                String msgText = "⚙️ Устанавливаем смену на <b>" + displayDate + "</b> для <b>" + targetUser + "</b>.\n\nВыберите тип смены из кнопок:";

                if (anchorId != null) {
                    EditMessageText edit = new EditMessageText();
                    edit.setChatId(String.valueOf(chatId)); edit.setMessageId(anchorId);
                    edit.setText(msgText); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                    try { execute(edit); } catch (Exception e) {}
                }
                return;
            }

            if (waitingSickLeaveDate.containsKey(chatId)) {
                Integer anchorId = waitingSickLeaveDate.remove(chatId);
                String excelName = DatabaseManager.getUserExcelName(chatId);
                if (excelName == null) {
                    sendMenu(chatId, role, "❌ Ошибка: вы не привязаны к графику.");
                    return;
                }

                try {
                    String input = text.trim();
                    java.time.format.DateTimeFormatter dtfFull = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");

                    int currentYear = java.time.LocalDate.now().getYear();
                    int currentMonth = java.time.LocalDate.now().getMonthValue();

                    List<java.time.LocalDate> parsedDates = new ArrayList<>();

                    // Используем умный парсинг для любых форматов
                    String[] items = input.split(",");
                    for (String item : items) {
                        item = item.trim();
                        if (item.isEmpty()) continue;

                        if (item.contains("-")) {
                            String[] parts = item.split("-");
                            java.time.LocalDate start = parseSmartDate(parts[0].trim(), currentYear, currentMonth);
                            java.time.LocalDate end = parseSmartDate(parts[1].trim(), currentYear, currentMonth);
                            if (start.isAfter(end)) end = end.plusYears(1);

                            java.time.LocalDate current = start;
                            while (!current.isAfter(end)) {
                                parsedDates.add(current);
                                current = current.plusDays(1);
                            }
                        } else {
                            parsedDates.add(parseSmartDate(item, currentYear, currentMonth));
                        }
                    }

                    if (parsedDates.isEmpty()) throw new Exception("Даты не найдены");

                    // Больничный - это всегда непрерывный период. Находим начало и конец:
                    java.time.LocalDate minDate = java.util.Collections.min(parsedDates);
                    java.time.LocalDate maxDate = java.util.Collections.max(parsedDates);

                    long days = java.time.temporal.ChronoUnit.DAYS.between(minDate, maxDate) + 1;
                    String displayDate = (days == 1) ? minDate.format(dtfFull) : "с " + minDate.format(dtfFull) + " по " + maxDate.format(dtfFull);

                    // --- ИЗМЕНЕНИЕ: ЛИМИТ 10 ДНЕЙ ---
                    if (days <= 10) {
                        java.time.LocalDate current = minDate;
                        while (!current.isAfter(maxDate)) {
                            DatabaseManager.updateSingleShift(excelName, current.getYear(), current.getMonthValue(), current.getDayOfMonth(), "Б", "", "");
                            current = current.plusDays(1);
                        }

                        // Рисуем SPA ответ для сотрудника
                        sendScheduleMenu(chatId, role, "✅ Ваш больничный (" + displayDate + ") успешно зафиксирован! Выздоравливайте! 💊\n\n<i>Руководитель уведомлен, график обновлен.</i>", anchorId);

                        String adminMsg = "🚨 <b>ВНИМАНИЕ: Больничный!</b>\nСотрудник <b>" + excelName + "</b> сообщил о болезни.\nПериод: <b>" + displayDate + "</b>\n<i>Его график автоматически обновлен (статус \"Б\").</i>";
                        // 1. Уведомляем админа
                        for (Long adminId : DatabaseManager.getAdminIds()) sendDirectNotification(adminId, adminMsg);
                        // 2. Уведомляем Козлова и Белевича (автоматически)
                        notifyManagersAboutSickLeave(adminMsg);

                    } else {
                        // Больше 10 дней - требует подтверждения (уведомляем ТОЛЬКО АДМИНА)
                        long requestId = DatabaseManager.createSickLeaveRequest(chatId, excelName, minDate.format(dtfFull), maxDate.format(dtfFull));
                        sendScheduleMenu(chatId, role, "⏳ Больничный (" + displayDate + ", " + days + " дн.) превышает 10 дней и требует подтверждения руководителя.\n\nВы получите уведомление, как только его согласуют.", anchorId);

                        String adminMsg = "🚨 <b>Запрос на больничный свыше 10 дней</b>\nСотрудник: <b>" + excelName + "</b>\nПериод: <b>" + displayDate + "</b> (" + days + " дн.)\n\nПодтвердить изменение графика?";
                        InlineKeyboardMarkup approvalMarkup = new InlineKeyboardMarkup(List.of(List.of(
                                createBtn("✅ Подтвердить", "SL_APPROVE:" + requestId),
                                createBtn("❌ Отклонить", "SL_REJECT:" + requestId)
                        )));
                        for (Long adminId : DatabaseManager.getAdminIds()) {
                            SendMessage msg = new SendMessage(String.valueOf(adminId), adminMsg);
                            msg.setParseMode("HTML"); msg.setReplyMarkup(approvalMarkup);
                            try { execute(msg); } catch (TelegramApiException e) {}
                        }
                    }

                } catch (Exception e) {
                    waitingSickLeaveDate.put(chatId, anchorId); // Возвращаем в режим ожидания
                    if (anchorId != null) {
                        EditMessageText edit = new EditMessageText();
                        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(anchorId);
                        edit.setText("❌ <b>Не удалось распознать дату.</b>\n\nПожалуйста, используйте форматы:\n• Одиночно: <code>15.11</code>\n• Диапазоны: <code>15.10-22.10</code>\n\nВведите даты еще раз:");
                        edit.setParseMode("HTML");
                        edit.setReplyMarkup(new InlineKeyboardMarkup(List.of(List.of(createBtn("❌ Отменить", "CANCEL_PROMPT")))));
                        try { execute(edit); } catch (Exception ignored) {}
                    }
                }
                return;
            }

            if (text.startsWith("/unbind_") && "ADMIN".equals(role)) {
                long targetId = Long.parseLong(text.replace("/unbind_", ""));
                String realTgName = "Сотрудник";
                try {
                    org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat getChat = new org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat(String.valueOf(targetId));
                    org.telegram.telegrambots.meta.api.objects.Chat targetChat = execute(getChat);
                    if (targetChat.getFirstName() != null) { realTgName = targetChat.getFirstName(); if (targetChat.getLastName() != null) realTgName += " " + targetChat.getLastName(); }
                    else if (targetChat.getUserName() != null) realTgName = targetChat.getUserName();
                } catch (Exception e) { realTgName = "ID " + targetId; }

                if (DatabaseManager.unbindUser(targetId, realTgName)) {
                    sendMenu(chatId, role, "✅ Пользователь сброшен. Ему отправлено меню выбора ФИО!");
                    List<String> availableNames = DatabaseManager.getAvailableExcelNames();
                    if (availableNames.isEmpty()) sendDirectNotification(targetId, "⚠ <b>Внимание! Требование Администратора:</b>\nВам необходимо идентифицировать себя, но администратор еще не загрузил график с новыми фамилиями. Пожалуйста, обратитесь к нему напрямую.");
                    else {
                        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                        for (String name : availableNames) rows.add(List.of(createBtn(name, "BIND_NAME:" + name)));
                        markup.setKeyboard(rows);
                        SendMessage msg = new SendMessage(String.valueOf(targetId), "⚠️ <b>Внимание! Требование Администратора:</b>\n\nВам необходимо идентифицировать себя в системе, чтобы ваш аккаунт не был заблокирован.\n\n👇 <b>Пожалуйста, выберите СВОЮ настоящую фамилию из списка ниже:</b>");
                        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                        try { execute(msg); } catch (TelegramApiException e) {}
                    }
                } else sendMenu(chatId, role, "❌ Ошибка: пользователь не найден в базе данных.");
                return;
            }

            if (forceWelderReturnIds.containsKey(chatId)) {
                SendMessage blockMsg = new SendMessage(String.valueOf(chatId), "⚠️ <b>ДОСТУП ЗАБЛОКИРОВАН!</b>\n\nУ вас висит необработанный запрос по возврату сварочного аппарата: <b>" + DatabaseManager.getWelderNameById(forceWelderReturnIds.get(chatId)) + "</b>.\n\nПоднимитесь чуть выше в истории чата и нажмите одну из кнопок под сообщением-напоминанием!");
                blockMsg.setParseMode("HTML"); try { execute(blockMsg); } catch (TelegramApiException e) {} return;
            }

            if (!text.equals("/start") && !text.equals("/fix") && !text.startsWith("/take_") && !text.startsWith("/give_") && !text.startsWith("/fix_name")) {
                if (isAdminBlockedByPendingRequests(chatId, role)) return;
            }

            if (text.startsWith("/take_") && "ADMIN".equals(role)) {
                try { sendMenu(chatId, role, DatabaseManager.returnToolToWarehouse(Integer.parseInt(text.replace("/take_", "")))); } catch (Exception e) {} return;
            }

            if (text.startsWith("/give_") && "ADMIN".equals(role)) {
                try {
                    int firstId = Integer.parseInt(text.replace("/give_", ""));
                    String toolName = DatabaseManager.getToolNameById(firstId);
                    List<String[]> users = DatabaseManager.getUsersForToolAssignment();
                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                    for (String[] u : users) rows.add(List.of(createBtn(("ADMIN".equals(u[2]) ? "👑" : "👷‍♂️") + " " + u[1], "F_ASS:" + firstId + ":" + u[0])));
                    markup.setKeyboard(rows);
                    SendMessage msg = new SendMessage(String.valueOf(chatId), String.format("🪛 Быстрая выдача: <b>%s</b>\n\n👤 <b>Нажимайте на фамилии сотрудников</b>, чтобы выдать им этот инструмент со склада:", toolName));
                    msg.setParseMode("HTML"); msg.setReplyMarkup(markup); execute(msg);
                } catch (Exception e) {} return;
            }

            if (waitingTakeMaterialId.containsKey(chatId)) {
                try {
                    double qty = Double.parseDouble(text.trim().replace(",", "."));
                    String result = DatabaseManager.takeMaterialFromWarehouse(chatId, waitingTakeMaterialId.get(chatId), qty);
                    if (result.startsWith("✅")) { waitingTakeMaterialId.remove(chatId); sendMenu(chatId, role, result); }
                    else { SendMessage msg = new SendMessage(String.valueOf(chatId), result); msg.setParseMode("HTML"); try { execute(msg); } catch (TelegramApiException e) {} }
                } catch (NumberFormatException e) { sendCancelKeyboard(chatId, "❌ Введите корректное число (например: <code>5</code> или <code>0.05</code>)."); }
                return;
            }
            if (waitingToolWriteOffReason.containsKey(chatId)) {
                String res = DatabaseManager.writeOffTool(waitingToolWriteOffReason.remove(chatId), text);
                clearChatHistory(chatId); // Удаляет и сообщение юзера, и окно вопроса
                sendToolManagementMenu(chatId, res); // Заново шлет меню с уведомлением сверху!
                return;
            }
            if (writeOffCartSessions.containsKey(chatId)) { handleWriteOffStep(chatId, firstName, role, text); return; }

            DatabaseManager.ReceiptSession cartSession = receiptSessions.get(chatId);
            if (cartSession != null && (cartSession.waitingServiceId != -1 || cartSession.waitingMaterialId != -1)) {
                try {
                    double qty = Double.parseDouble(text.trim().replace(",", "."));
                    if (qty <= 0) throw new NumberFormatException();
                    if (cartSession.waitingServiceId != -1) {
                        DatabaseManager.ReceiptItem item = DatabaseManager.getServiceById(cartSession.waitingServiceId);
                        if (item != null) { item.quantity = qty; cartSession.items.add(item); }
                        cartSession.waitingServiceId = -1;
                    } else if (cartSession.waitingMaterialId != -1) {
                        DatabaseManager.ReceiptItem item = DatabaseManager.getMaterialFromBalanceById(chatId, cartSession.waitingMaterialId);
                        if (item != null) { item.quantity = qty; cartSession.items.add(item); }
                        cartSession.waitingMaterialId = -1;
                    }
                    sendCartMenu(chatId, role, cartSession.anchorMsgId); // Возвращаемся в текущую карточку
                } catch (NumberFormatException e) {
                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("🔙 Назад в корзину", "CART_MENU"))));
                    EditMessageText edit = new EditMessageText();
                    edit.setChatId(String.valueOf(chatId)); edit.setMessageId(cartSession.anchorMsgId);
                    edit.setText("❌ Введите корректное число больше нуля:");
                    edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                    try { execute(edit); } catch (Exception ex) {}
                }
                return;
            }

            if ("WAIT_BROADCAST_TEXT".equals(fileWaitState.get(chatId))) {
                fileWaitState.remove(chatId);
                sendMenu(chatId, role, "⏳ Отправляю сообщение всем сотрудникам...");
                List<Long> allUsers = DatabaseManager.getAllUserIds();
                int successCount = 0;
                String broadcastMsg = "📢 <b>ИНФОРМАЦИЯ ОТ РУКОВОДИТЕЛЯ:</b>\n\n" + text;
                for (Long userId : allUsers) {
                    if (userId == chatId) continue;
                    try { SendMessage msg = new SendMessage(String.valueOf(userId), broadcastMsg); msg.setParseMode("HTML"); execute(msg); successCount++; } catch (TelegramApiException e) {}
                }
                sendMenu(chatId, role, "✅ Рассылка успешно доставлена <b>" + successCount + "</b> сотрудникам!"); return;
            }

            if (text.startsWith("/ban_") && "ADMIN".equals(role)) { sendMenu(chatId, role, DatabaseManager.setBanStatus(Long.parseLong(text.replace("/ban_", "")), true)); return; }
            if (text.startsWith("/unban_") && "ADMIN".equals(role)) { sendMenu(chatId, role, DatabaseManager.setBanStatus(Long.parseLong(text.replace("/unban_", "")), false)); return; }

            if (text.startsWith("/fire ") && "ADMIN".equals(role)) {
                String nameToRemove = text.replace("/fire ", "").trim();
                sendMenu(chatId, role, DatabaseManager.removeWorkerCompletely(nameToRemove));
                return;
            }

            if (waitingOrshProblemReason.containsKey(chatId)) {
                DatabaseManager.markOrshCompleted(waitingOrshProblemReason.remove(chatId), firstName, true, text);
                sendMenu(chatId, role, "⚠️ Причина зафиксирована. Этот ОРШ переведен в статус проблемных."); return;
            }

            if ("WAIT_LOGIN".equals(tmAuthStep.get(chatId))) {
                tmTempLogin.put(chatId, text.trim());
                tmAuthStep.put(chatId, "WAIT_PASSWORD");
                sendCancelKeyboard(chatId, "🔑 Теперь введите пароль от ТМ:");
                return;
            }
            if ("WAIT_PASSWORD".equals(tmAuthStep.get(chatId))) {
                tmAuthStep.remove(chatId);
                String login = tmTempLogin.remove(chatId);
                String session = TmClient.login(login, text.trim());
                if (session == null) {
                    sendMenu(chatId, role, "❌ Неверный логин или пароль ТМ. Нажмите «🛠 Мои заявки (ТМ)», чтобы попробовать снова.");
                    return;
                }
                DatabaseManager.saveTmCredentials(chatId, login, text.trim());
                DatabaseManager.saveTmSession(chatId, session);
                JSONArray tasks = TmClient.getTasks(session);
                if (tasks != null) sendTmTasksMenu(chatId, tasks);
                else sendMenu(chatId, role, "❌ Не удалось получить заявки.");
                return;
            }
            if (waitingTmReportTaskId.containsKey(chatId)) {
                String reportText = text.trim();

                if (reportText.isEmpty()) {
                    sendCancelKeyboard(chatId, "❌ <b>Отчёт не может быть пустым.</b>\nПожалуйста, напишите текст отчёта:");
                    return;
                }

                String taskId = waitingTmReportTaskId.remove(chatId);
                String session = DatabaseManager.getTmSession(chatId);
                boolean ok = session != null && TmClient.performTask(session, taskId, reportText);
                sendMenu(chatId, role, ok
                        ? "✅ Отчёт по заявке #" + taskId + " отправлен."
                        : "❌ Не удалось отправить отчёт. Попробуйте ещё раз через «Мои заявки (ТМ)».");
                return;
            }

            switch (text) {
                case "/start" -> {
                    clearChatHistory(chatId); // 🌪 Полная зачистка чата перед стартом
                    String roleTitle = role.equals("ADMIN") ? "Администратор (МОЛ)" : "Мастер ЦБР УЛКС №2 ЛКЦ";
                    sendMenu(chatId, role, "Привет, <b>" + firstName + "</b>! 👋\nВаша роль в системе: <b>" + roleTitle + "</b>.\n\nВыберите нужное действие на кнопках внизу экрана:");
                }
                case "/fix" -> {
                    if ("ADMIN".equals(role)) {
                        try (java.sql.Connection conn = DatabaseManager.getConnection(); java.sql.Statement stmt = conn.createStatement()) {
                            stmt.execute("DELETE FROM return_requests WHERE status = 'PENDING'");
                            sendMenu(chatId, role, "✅ Зависшие заявки очищены!");
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                }
                case "/clear_stock" -> {
                    if ("ADMIN".equals(role)) {
                        try (java.sql.Connection conn = DatabaseManager.getConnection(); java.sql.Statement stmt = conn.createStatement()) {
                            // Очищаем главную таблицу склада (предполагаемое название 'materials')
                            stmt.execute("DELETE FROM materials");
                            sendMenu(chatId, role, "🧹 <b>База склада полностью очищена!</b>\nТеперь вы можете загрузить новую оборотную ведомость.");
                        } catch (Exception e) {
                            sendMenu(chatId, role, "❌ Ошибка: возможно таблица называется иначе. Детали: " + e.getMessage());
                        }
                    }
                }
                case "⚡️ Сварочные аппараты" -> sendWeldersMenu(chatId, role, null, null);
                case "📞 Справочник" -> sendDirectory(chatId, role);
                case "🗓 График работ" -> sendScheduleMenu(chatId, role, "🗓 <b>Графики и смены:</b>\nВыберите, что хотите посмотреть:", null);

                case "🛠 Мои заявки (ТМ)" -> {
                    if (!canAccessTm(chatId, role)) break;
                    JSONArray tasks = fetchTmTasks(chatId);

                    if (tasks == null) {
                        tmAuthStep.put(chatId, "WAIT_LOGIN");
                        sendCancelKeyboard(chatId, "🔑 <b>Вход в систему ТМ</b>\nВведите ваш логин:");
                    } else {
                        sendTmTasksMenu(chatId, tasks);
                    }
                }





                case "📦 Склад (Наличие и цены)" -> {
                    sendWarehousePage(chatId, 1, null, null);
                }
                case "📝 Списать / Вернуть" -> startWriteOffMenu(chatId, role);
                case "📋 Тарифы услуг" -> sendClosableMessage(chatId, DatabaseManager.getServiceTariffsText());
                case "🔢 Коды закрытия" -> sendClosableMessage(chatId, DatabaseManager.getClosingCodesText());
                case "🧾 Калькулятор квитанции" -> sendCartMenu(chatId, role, null);
                case "🧰 Мой подотчет" -> sendMyInventoryMenu(chatId, null);
                case "📸 Плановый осмотр ОРШ" -> sendPendingOrshList(chatId);
                case "📊 Статистика ОРШ" -> { if (role.equals("ADMIN")) sendClosableMessage(chatId, DatabaseManager.getOrshStatistics()); }
                case "📥 Загрузки (Excel)" -> {
                    if (role.equals("ADMIN")) {
                        sendUploadsMenu(chatId, "📥 <b>Меню загрузки файлов (Excel):</b>\nВыберите базу для обновления:");
                    }
                }

                case "📥 Загрузить план ОРШ (Excel)" -> { if (role.equals("ADMIN")) { fileWaitState.put(chatId, "ORSH"); sendCancelKeyboard(chatId, "📁 Отправьте файл ПЛАНА ОРШ (Excel)."); } }
                case "📥 Загрузить ведомость (Excel)" -> { if (role.equals("ADMIN")) { fileWaitState.put(chatId, "TURNOVER"); sendCancelKeyboard(chatId, "📎 Отправьте файл ОБОРОТНОЙ ВЕДОМОСТИ."); } }
                case "📥 Загрузить инструмент (Excel)" -> { if (role.equals("ADMIN")) { fileWaitState.put(chatId, "TOOLS"); sendCancelKeyboard(chatId, "🪛 Отправьте файл базы ИНСТРУМЕНТА."); } }
                case "📥 Загрузить график (Excel)" -> {
                    if (role.equals("ADMIN")) {
                        LocalDate now = LocalDate.now();
                        LocalDate next = now.plusMonths(1);
                        LocalDate next2 = now.plusMonths(2);
                        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                                List.of(createBtn("📅 " + getMonthName(now.getMonthValue()) + " " + now.getYear(), "UPL_SCHED:" + now.getYear() + ":" + now.getMonthValue())),
                                List.of(createBtn("📅 " + getMonthName(next.getMonthValue()) + " " + next.getYear(), "UPL_SCHED:" + next.getYear() + ":" + next.getMonthValue())),
                                List.of(createBtn("📅 " + getMonthName(next2.getMonthValue()) + " " + next2.getYear(), "UPL_SCHED:" + next2.getYear() + ":" + next2.getMonthValue()))
                        ));
                        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗓 <b>На какой месяц вы загружаете график?</b>\nВыберите из списка:");
                        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
                    }
                }
                case "📊 У кого что на руках" -> { if (role.equals("ADMIN")) sendClosableMessage(chatId, DatabaseManager.getAllWorkersBalancesText()); }                case "📑 Скачать отчет за месяц" -> { if (role.equals("ADMIN")) sendExcelReport(chatId, role); }
                case "📢 Сделать рассылку" -> { if (role.equals("ADMIN")) { fileWaitState.put(chatId, "WAIT_BROADCAST_TEXT"); sendCancelKeyboard(chatId, "📢 <b>Режим массовой рассылки</b>\n\nВведите текст сообщения:"); } }
                case "👥 Пользователи" -> {
                    if (role.equals("ADMIN")) sendAdminUsersControlPanel(chatId, null);
                }
                case "🛠 Управление инструментом" -> {
                    if ("🛠 Управление инструментом".equals(text) && "ADMIN".equals(role)) {
                        sendToolManagementMenu(chatId); // Вызываем нашу новую Inline-панель
                        return;
                    }
                }
                default -> {
                    if (text.startsWith("+услуга ") && "ADMIN".equals(role)) {
                        handleUpdateService(chatId, role, text);
                    } else if ("ADMIN".equals(role)) {
                        // Теперь вызываем универсальный метод searchTools
                        String searchResult = DatabaseManager.searchTools(text.trim());
                        if (searchResult != null) {
                            sendMenu(chatId, role, searchResult);
                        } else {
                            sendMenu(chatId, role, "ℹ️ По запросу «" + text + "» инструмент не найден. Попробуйте использовать кнопки меню 👇");
                        }
                    } else {
                        // Для обычных работников
                        sendMenu(chatId, role, "Используйте кнопки меню внизу экрана 👇");
                    }
                }
            }
        }
    }

    private InlineKeyboardButton createBtn(String text, String callbackData) {
        InlineKeyboardButton btn = new InlineKeyboardButton(text);
        btn.setCallbackData(callbackData);
        return btn;
    }

    private String getDirectoryText() {
        Map<Integer, List<String>> dyn = DatabaseManager.getDynamicContacts();
        StringBuilder sb = new StringBuilder("📞 <b>ТЕЛЕФОННЫЙ СПРАВОЧНИК</b>\n\n");
        sb.append("🏢 <b>Руководство ЛКЦ</b>\n▪ Нач. цеха Богино А.Е.: +375(17) 335-24-44\n▪️ Зам. нач. цеха Жук С.Г.: +375(17) 334-44-45\n");
        for (String s : dyn.getOrDefault(1, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n👷‍♂ <b>ИТР УЛКС №2</b>\n▪️ Нач. УЛКС №2 Лазуко С.В.: +375(17) 200-02-98, +375(29) 501-01-11\n▪ Зам. нач. УЛКС №2 Журко А.Н.: +375(17) 264-93-74, +375(29) 231-69-23\n▪ Рук. каб. группы Прохорчик В.Ю.: +375(17) 200-40-40, +375(29) 176-58-88\n▪️ Рук. гр. технадзора Снитко А.В.: +375(33) 347-28-85\n▪ Рук. гр. малопарщиков Никитин Д.Д.: +375(44) 752-02-69\n▪ Рук. изм. группы Лабуза С.И.: +375(29) 701-88-29\n");
        for (String s : dyn.getOrDefault(2, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n💻 <b>Технический отдел</b>\n▪ Рук. гр. технадзора Шашков В.П.: +375(29) 373-35-55\n▪️ Инж. технадзора Кононова Светлана: +375(29) 577-57-46\n▪ Магистральщики (Протасевич Юлия): +375(29) 560-80-82\n▪️ Профсоюзные дела (Луговцова Оксана): +375(29) 317-19-56\n");
        for (String s : dyn.getOrDefault(3, new ArrayList<>())) sb.append("▪ ").append(s).append("\n");
        sb.append("\n🗂 <b>Администрация</b>\n▪️ Профком (Нестерова Е.Ю.): +375(17) 359-45-70\n▪️ Бухгалтерия по ЗП (Ромашко Ю.Г.): +375(17) 359-45-20\n▪️ Специалист по кадрам (Субач Е.В.): +375(17) 359-45-03\n");
        for (String s : dyn.getOrDefault(4, new ArrayList<>())) sb.append("▪ ").append(s).append("\n");
        sb.append("\n🚗 <b>АТЦ</b>\n▪️ Начальник Савицкий А.В.: +375(17) 369-05-25, +375(33) 603-32-73\n▪️ Зам. начальника Болбас Р.А.: +375(17) 369-03-93, +375(29) 840-78-37\n▪ Инж. по БД Гузов А.П.: +375(17) 369-05-27, +375(29) 779-11-88\n");
        for (String s : dyn.getOrDefault(5, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n🔌 <b>Станционщики (магистраль / проключения)</b>\n▪️ Инженер Ивашкевич Дарья: +375(29) 555-38-48\n");
        for (String s : dyn.getOrDefault(6, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n📺 <b>Привязка СМЛ приставок</b>\n▪️ Дневное время (Ольга): +375(29) 788-84-98\n▪️ Вечернее время: +375(17) 334-56-24, +375(17) 328-46-56, +375(17) 252-44-55, +375(17) 359-41-10\n");
        for (String s : dyn.getOrDefault(7, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n🎧 <b>Диспетчера и админы (закрытие заявок)</b>\n▪ Диспетчеры ЦАБР: +375(17) 274-30-10\n▪ Инженер ЦАБР: +375(17) 274-28-16\n▪ Админы: +375(17) 306-29-56, +375(33) 603-38-81\n▪ После 20:00: +375(17) 306-29-59\n▪ VPN: +375(17) 203-66-86\n");
        for (String s : dyn.getOrDefault(8, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n📹 <b>Прочее</b>\n▪ Видеоконтроль (Андрей): +375(17) 359-40-30\n▪️ РСМОБ: +375(17) 357-96-30\n");
        for (String s : dyn.getOrDefault(9, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        return sb.toString();
    }

    private void sendDirectory(long chatId, String role) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createBtn("➕ Добавить контакт", "DIR_ADD_MENU")));
        if ("ADMIN".equals(role)) rows.add(List.of(createBtn("🗑 Удалить добавленный", "DIR_DEL_MENU")));
        rows.add(List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE")));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), getDirectoryText());
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendDirectoryAddMenu(long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(
                List.of(createBtn("🏢 Руководство", "DIR_CAT:1"), createBtn("👷‍♂ ИТР", "DIR_CAT:2")),
                List.of(createBtn("💻 Тех. отдел", "DIR_CAT:3"), createBtn("🗂 Админ.", "DIR_CAT:4")),
                List.of(createBtn("🚗 АТЦ", "DIR_CAT:5"), createBtn("🔌 Станционщики", "DIR_CAT:6")),
                List.of(createBtn("📺 Привязка", "DIR_CAT:7"), createBtn("🎧 Диспетчера", "DIR_CAT:8")),
                List.of(createBtn("📹 Прочее", "DIR_CAT:9")),
                List.of(createBtn("🔙 Назад", "DIR_BACK"))
        ));
        SendMessage msg = new SendMessage(String.valueOf(chatId), "➕ <b>В какой раздел добавить контакт?</b>\nВыберите категорию ниже:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendDirectoryDelMenu(long chatId) {
        List<String[]> contacts = DatabaseManager.getDynamicContactsList();
        if (contacts.isEmpty()) { sendMenu(chatId, "ADMIN", "ℹ️ В справочник еще не добавлено ни одного стороннего контакта."); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] c : contacts) rows.add(List.of(createBtn("🗑 " + (c[1].length() > 30 ? c[1].substring(0, 30) + "…" : c[1]), "DIR_DEL:" + c[0])));
        rows.add(List.of(createBtn("🔙 Назад", "DIR_BACK")));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗑 <b>Удаление контакта</b>\nНажмите на контакт, который хотите удалить:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void notifyAdminsForAction(long excludeChatId, String text) {
        for (Long adminId : DatabaseManager.getAdminIds()) {
            if (adminId == excludeChatId) continue;
            SendMessage msg = new SendMessage(String.valueOf(adminId), text);
            msg.setParseMode("HTML"); try { execute(msg); } catch (TelegramApiException e) {}
        }
    }

    private void sendNameBindingMenu(long chatId, List<String> names) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String name : names) rows.add(List.of(createBtn(name, "BIND_NAME:" + name)));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "👤 <b>Вы еще не привязаны к графику.</b>\nВыберите вашу фамилию:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendCancelKeyboard(long chatId, String text) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("❌ Отменить", "CANCEL_PROMPT"))));
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        message.setParseMode("HTML"); message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) {}
    }

    private void handleWriteOffStep(long chatId, String firstName, String role, String text) {
        WriteOffCartSession s = writeOffCartSessions.get(chatId);
        if (s == null || s.anchorMsgId == null) {
            sendCancelKeyboard(chatId, "❌ Ошибка сессии. Начните заново.");
            writeOffCartSessions.remove(chatId);
            return;
        }

        switch (s.step) {
            case "WAIT_QTY" -> {
                try {
                    double qty = Double.parseDouble(text.trim().replace(",", "."));
                    if (qty <= 0 || qty > s.tempItem.maxAvailable + 1e-9) {
                        updateWriteOffUI(chatId, s.anchorMsgId, "❌ Максимум: " + DatabaseManager.fmtQty(s.tempItem.maxAvailable) + "\n\nВведите заново:");
                        return;
                    }
                    s.tempItem.quantity = qty;
                    s.items.add(s.tempItem);
                    s.tempItem = null;
                    startWriteOffMenu(chatId, role, s.anchorMsgId, "✅ Материал добавлен в список!");
                } catch (Exception e) { updateWriteOffUI(chatId, s.anchorMsgId, "❌ Введите количество числом:"); }
            }
            case "WAIT_RECEIPT_NUM" -> { s.receiptNumber = text.trim(); s.step = "WAIT_PAID_PHONE"; updateWriteOffUI(chatId, s.anchorMsgId, "🧾 Введите <b>номер телефона</b>:"); }
            case "WAIT_PAID_PHONE" -> { s.phoneNumber = text.trim(); s.step = "WAIT_PAID_CONTRACT"; updateWriteOffUI(chatId, s.anchorMsgId, "🧾 Введите <b>номер договора</b> (или -):"); }
            case "WAIT_PAID_CONTRACT" -> { s.contractNumber = text.trim(); s.step = "WAIT_PAID_ADDRESS"; updateWriteOffUI(chatId, s.anchorMsgId, "🧾 Введите <b>адрес абонента</b>:"); }
            case "WAIT_PAID_ADDRESS" -> {
                s.address = text.trim();
                StringBuilder finalRes = new StringBuilder("🧾 <b>Результаты списания:</b>\n");
                for (WriteOffSession item : s.items) {
                    item.isPaidReceipt = true;
                    item.receiptNumber = s.receiptNumber;
                    item.phoneNumber = s.phoneNumber;
                    item.contractNumber = s.contractNumber;
                    item.address = s.address;
                    item.closingCode = "102";
                    item.reason = "Списание по квитанции";
                    finalRes.append(DatabaseManager.completeWriteOff(chatId, item)).append("\n");
                }
                writeOffCartSessions.remove(chatId);
                if (!"ADMIN".equals(role)) notifyAdminsForAction(chatId, "🧾 <b>ВНИМАНИЕ! Выбита квитанция:</b>\n" + finalRes.toString());
                startWriteOffMenu(chatId, role, s.anchorMsgId, finalRes.toString().trim());
            }
            case "WAIT_FREE_PHONE" -> { s.phoneNumber = text.trim(); s.step = "WAIT_FREE_CONTRACT"; updateWriteOffUI(chatId, s.anchorMsgId, "🛠 Введите <b>номер договора</b> (или -):"); }
            case "WAIT_FREE_CONTRACT" -> { s.contractNumber = text.trim(); s.step = "WAIT_FREE_ADDRESS"; updateWriteOffUI(chatId, s.anchorMsgId, "🛠 Введите <b>адрес</b>:"); }
            case "WAIT_FREE_ADDRESS" -> { s.address = text.trim(); s.step = "WAIT_CLOSING_CODE"; sendClosingCodeButtons(chatId, s.phoneNumber, s.contractNumber, s.anchorMsgId); }
        }
    }

    private void sendTypeButtons(long chatId, WriteOffCartSession s, int messageId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(createBtn("🧾 Списать по квитанции", "WO_TYPE:PAID")),
                List.of(createBtn("🛠 Техническое списание", "WO_TYPE:FREE")),
                List.of(createBtn("↩️ Вернуть на склад", "WO_TYPE:RETURN")),
                List.of(createBtn("🔙 Назад к списку", "WO_BACK_LIST"), createBtn("❌ Отменить", "CANCEL_PROMPT"))
        ));
        String text = String.format("Выбрано позиций: <b>%d</b>.\nЧто делаем с этими материалами?", s.items.size());
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText(text); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
        try { execute(edit); } catch (Exception e) {}
    }

    private void sendReturnApprovalToAdmins(int requestId, String text) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("✅ Принять на склад", "RET_APP:" + requestId), createBtn("❌ Отклонить", "RET_REJ:" + requestId))));
        String alertMsg = "🚨 <b>ТРЕБУЕТСЯ ВАШЕ РАЗРЕШЕНИЕ!</b> 🚨\n\n" + text;
        for (Long adminId : DatabaseManager.getAdminIds()) {
            SendMessage msg = new SendMessage(String.valueOf(adminId), alertMsg);
            msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
        }
    }

    private void sendDirectNotification(long targetChatId, String text) {
        SendMessage msg = new SendMessage(String.valueOf(targetChatId), text);
        msg.setParseMode("HTML"); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void startWriteOffMenu(long chatId, String role) {
        startWriteOffMenu(chatId, role, null, null);
    }

    private void startWriteOffMenu(long chatId, String role, Integer messageId) {
        startWriteOffMenu(chatId, role, messageId, null);
    }

    private void startWriteOffMenu(long chatId, String role, Integer messageId, String alertText) {
        List<String[]> userMats = DatabaseManager.getUserMaterialsForWriteOff(chatId);
        WriteOffCartSession cart = writeOffCartSessions.get(chatId);

        StringBuilder sb = new StringBuilder();
        if (alertText != null && !alertText.isEmpty()) {
            sb.append(alertText).append("\n\n➖➖➖➖➖➖➖➖➖➖\n");
        }

        boolean hasCart = (cart != null && !cart.items.isEmpty());
        if (hasCart) {
            sb.append("🛒 <b>К списанию/возврату:</b>\n");
            for (int i = 0; i < cart.items.size(); i++) {
                WriteOffSession item = cart.items.get(i);
                sb.append(i + 1).append(". ").append(item.materialName).append(" — ")
                        .append(DatabaseManager.fmtQty(item.quantity)).append(" ").append(item.unit).append("\n");
            }
            sb.append("\n");
        }

        if (userMats.isEmpty()) {
            if (!hasCart) sb.append("🧰 У вас на руках нет материалов.");
        } else {
            sb.append("📝 <b>Выберите материал для добавления в список:</b>");
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Если в корзине есть товары, показываем кнопки оформления
        if (hasCart) {
            rows.add(List.of(createBtn("✅ ПЕРЕЙТИ К ОФОРМЛЕНИЮ", "WO_CHECKOUT")));
            rows.add(List.of(createBtn("🗑 Очистить список", "WO_CLEAR_CART")));
        }

        for (String[] m : userMats) {
            rows.add(List.of(createBtn(String.format("👉 %s [%s] (%s %s)",
                    m[2].length() > 30 ? m[2].substring(0, 30) + "…" : m[2], m[1], m[4], m[3]), "WO_MAT:" + m[0])));
        }
        rows.add(List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE")));
        markup.setKeyboard(rows);

        try {
            if (messageId == null) {
                SendMessage msg = new SendMessage(String.valueOf(chatId), sb.toString());
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                execute(msg);
            } else {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(sb.toString()); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                execute(edit);
            }
        } catch (TelegramApiException e) {}
    }

    private void updateWriteOffUI(long chatId, Integer messageId, String text) {
        if (messageId == null) return;
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(createBtn("🔙 Назад к списку", "WO_BACK_LIST"), createBtn("❌ Отменить", "CANCEL_PROMPT"))
        ));
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText(text); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
        try { execute(edit); } catch (Exception e) {}
    }

    private void sendClosingCodeButtons(long chatId, String phone, String contract, Integer messageId) {
        String warning = DatabaseManager.checkCode212History(phone, contract);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(createBtn("212 — Участок ОРК–ОРА (не чаще 6 мес!)", "WO_CODE:212")),
                List.of(createBtn("227 — Выправление волокна (повтор)", "WO_CODE:227")),
                List.of(createBtn("215 — В ОРШ: выправление пигтейла", "WO_CODE:215")),
                List.of(createBtn("226 — В ОРШ: замена адаптера", "WO_CODE:226")),
                List.of(createBtn("214 — Участок ОРШ–ОРК: ремонт райзера", "WO_CODE:214")),
                List.of(createBtn("217 — Участок ОРШ–ОРК: запасной модуль", "WO_CODE:217")),
                List.of(createBtn("🔙 Назад к списку", "WO_BACK_LIST"), createBtn("❌ Отменить", "CANCEL_PROMPT"))
        ));
        String text = "🔢 <b>Шаг 4 из 5:</b> Выберите <b>код закрытия заявки</b>:" + warning;

        if (messageId != null) {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
            edit.setText(text); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
            try { execute(edit); } catch (Exception e) {}
        }
    }

    private void sendWarehouseList(long chatId, String role) {
        List<String[]> materials = DatabaseManager.getAvailableMaterials();
        if (materials.isEmpty()) { sendMenu(chatId, role, "📦 На складе сейчас нет материалов."); return; }
        StringBuilder sb = new StringBuilder();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        String currentAcc = ""; int num = 1, itemsInChunk = 0;
        for (String[] m : materials) {
            String account = m[1], name = m[3].replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            if ((!account.equals(currentAcc) && itemsInChunk > 0) || itemsInChunk >= 10) {
                sendWarehouseChunk(chatId, sb.toString(), rows);
                sb.setLength(0); rows.clear(); itemsInChunk = 0;
            }
            if (!account.equals(currentAcc)) { sb.append("📦 <b>СКЛАД — Счёт ").append(account).append(" (с НДС):</b>\n\n"); currentAcc = account; }
            else if (itemsInChunk == 0) sb.append("📦 <b>СКЛАД — Счёт ").append(account).append(" (продолжение):</b>\n\n");

            sb.append(String.format("%d. <b>%s</b>%s\n   • Инв. №: <code>%s</code>\n   • Остаток: <b>%s %s</b>\n   • Цена: <b>%s руб.</b>\n\n", num, name, (m[8] != null && !m[8].isEmpty()) ? " <i>(" + m[8] + ")</i>" : "", m[2], m[7], m[4], m[6]));
            rows.add(List.of(createBtn(String.format("➕ %d. %s (%s %s)", num, name.length() > 22 ? name.substring(0, 22) + "…" : name, m[7], m[4]), "TAKE_MAT:" + m[0])));
            num++; itemsInChunk++;
        }
        if (itemsInChunk > 0) { sb.append("👇 <b>Нажмите на кнопку, чтобы взять в подотчет:</b>"); sendWarehouseChunk(chatId, sb.toString(), rows); }
    }

    private void sendCartMenu(long chatId, String role, Integer messageId) {
        DatabaseManager.ReceiptSession s = receiptSessions.getOrDefault(chatId, new DatabaseManager.ReceiptSession());
        StringBuilder sb = new StringBuilder("🧾 <b>Калькулятор квитанции</b>\n\nВ квитанции позиций: <b>").append(s.items.size()).append("</b>\n\n");
        if (!s.items.isEmpty()) {
            for (int i = 0; i < s.items.size(); i++) sb.append(i + 1).append(". ").append(s.items.get(i).name).append(" (").append(DatabaseManager.fmtQty(s.items.get(i).quantity)).append(" ").append(s.items.get(i).unit).append(")\n");
            sb.append("\nЧто делаем дальше?");
        } else sb.append("Добавьте работы и материалы.");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createBtn("➕ Услугу", "CART_ADD_SRV"), createBtn("➕ Материал", "CART_ADD_MAT")));
        if (!s.items.isEmpty()) {
            rows.add(List.of(createBtn("✅ РАССЧИТАТЬ ИТОГИ", "CART_FINISH")));
            rows.add(List.of(createBtn("🗑 Очистить", "CART_CLEAR")));
        }
        rows.add(List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE")));
        markup.setKeyboard(rows);

        try {
            if (messageId == null) {
                SendMessage msg = new SendMessage(String.valueOf(chatId), sb.toString());
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                execute(msg);
            } else {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(sb.toString()); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                execute(edit);
            }
        } catch (TelegramApiException e) {}
    }

    private void sendServiceSelectionForCart(long chatId, int messageId) {
        List<String[]> services = DatabaseManager.getAllServicesForReceipt();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] srv : services) rows.add(List.of(createBtn("🛠 " + (srv[1].length() > 35 ? srv[1].substring(0, 35) + "…" : srv[1]), "CART_SEL_SRV:" + srv[0])));
        rows.add(List.of(createBtn("🔙 Назад в корзину", "CART_MENU")));
        markup.setKeyboard(rows);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("🛠 <b>Выберите выполненную услугу:</b>");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
        try { execute(edit); } catch (TelegramApiException e) {}
    }

    private void sendMaterialSelectionForCart(long chatId, String role, int messageId) {
        List<String[]> userMats = DatabaseManager.getUserMaterialsForWriteOff(chatId);
        if (userMats.isEmpty()) {
            sendClosableMessage(chatId, "🧰 У вас нет материалов в подотчете.");
            sendCartMenu(chatId, role, messageId);
            return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] m : userMats) rows.add(List.of(createBtn("📦 " + (m[2].length() > 30 ? m[2].substring(0, 30) + "…" : m[2]) + " (" + m[4] + " " + m[3] + ")", "CART_SEL_MAT:" + m[0])));
        rows.add(List.of(createBtn("🔙 Назад в корзину", "CART_MENU")));
        markup.setKeyboard(rows);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
        edit.setText("📦 <b>Выберите использованный материал:</b>");
        edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
        try { execute(edit); } catch (TelegramApiException e) {}
    }

    private void sendWarehouseChunk(long chatId, String text, List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup inlineMarkup = new InlineKeyboardMarkup(); inlineMarkup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), text); msg.setParseMode("HTML"); msg.setReplyMarkup(inlineMarkup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void handleUpdateService(long chatId, String role, String text) {
        try {
            String[] parts = text.substring(8).split(";");
            if (parts.length == 2 && DatabaseManager.updateServicePrice(parts[0].trim(), Double.parseDouble(parts[1].trim().replace(",", ".")))) {
                sendMenu(chatId, role, "✅ Цена успешно обновлена."); return;
            }
        } catch (Exception ignored) {}
        sendMenu(chatId, role, "❌ Ошибка формата.");
    }

    private void sendExcelReport(long chatId, String role) {
        sendMenu(chatId, role, "⏳ Формирую отчет...");
        File reportFile = ExcelReportGenerator.generateMonthlyReport();
        if (reportFile != null && reportFile.exists()) {
            SendDocument sendDoc = new SendDocument(); sendDoc.setChatId(String.valueOf(chatId)); sendDoc.setDocument(new InputFile(reportFile));
            try { execute(sendDoc); EmailSender.sendOrshReport("Отчет по складу", "Во вложении Excel.", reportFile); sendMenu(chatId, role, "✉️ Отправлено на почту!"); }
            catch (Exception e) { sendMenu(chatId, role, "⚠️ В Telegram отправлено, но на почту ошибка: " + e.getMessage()); }
        } else sendMenu(chatId, role, "❌ Ошибка формирования.");
    }

    private boolean canAccessTm(long chatId, String role) {
        if ("ADMIN".equals(role)) return true;
        String excelName = DatabaseManager.getUserExcelName(chatId);
        // Даем доступ если фамилия содержит Петрович ИЛИ Афанасьев
        return excelName != null && (excelName.contains("Петрович") || excelName.contains("Афанасьев") || excelName.contains("Прищепчик"));
    }

    // 1. Отправка новой панели управления
    private void sendToolManagementMenu(long chatId) {
        sendToolManagementMenu(chatId, null);
    }

    private void sendToolManagementMenu(long chatId, String alertText) {
        StringBuilder sb = new StringBuilder();
        if (alertText != null && !alertText.isEmpty()) {
            sb.append(alertText).append("\n\n➖➖➖➖➖➖➖➖➖➖\n");
        }
        sb.append("🧰 <b>Панель управления инструментом</b>\nВыберите нужное действие:");

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(sb.toString());
        message.setParseMode("HTML");
        message.setReplyMarkup(getToolMenuInlineMarkup());
        try { execute(message); } catch (Exception e) { e.printStackTrace(); }
    }

    // 2. Возврат в главное меню панели
    private void editToolManagementMenu(long chatId, int messageId) {
        editToolManagementMenu(chatId, messageId, null);
    }

    private void editToolManagementMenu(long chatId, int messageId, String alertText) {
        StringBuilder sb = new StringBuilder();
        if (alertText != null && !alertText.isEmpty()) {
            sb.append(alertText).append("\n\n➖➖➖➖➖➖➖➖➖➖\n");
        }
        sb.append("🧰 <b>Панель управления инструментом</b>\nВыберите нужное действие:");

        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText(sb.toString());
        edit.setParseMode("HTML");
        edit.setReplyMarkup(getToolMenuInlineMarkup());
        try { execute(edit); } catch (Exception e) { e.printStackTrace(); }
    }

    // 3. Генерация кнопок Единого окна (Все 8 функций)
    private InlineKeyboardMarkup getToolMenuInlineMarkup() {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Ряд 1: Сводки
        rows.add(List.of(
                createBtn("📊 Общая сводка", "tool_menu_audit"),
                createBtn("👤 Сводка по людям", "tool_menu_audit_users")
        ));

        // Ряд 2: Движение инструмента
        rows.add(List.of(
                createBtn("📤 Выдать", "tool_menu_give"),
                createBtn("📥 Вернуть", "tool_menu_return")
        ));

        // Ряд 3: Списание и восстановление
        rows.add(List.of(
                createBtn("🗑 Списать", "tool_menu_writeoff"),
                createBtn("♻️ Восстановить", "tool_menu_restore")
        ));

        // Ряд 4: Архив и рассылка
        rows.add(List.of(
                createBtn("🗄 Архив", "tool_menu_archive"),
                createBtn("📢 Рассылка: Аудит", "tool_menu_notify")
        ));

        // Ряд 5: Закрытие
        rows.add(List.of(createBtn("❌ Закрыть панель", "tool_menu_close")));

        markup.setKeyboard(rows);
        return markup;
    }

    // 4. Показ выбранного раздела с кнопкой Назад
    private void showToolMenuSection(long chatId, int messageId, String text) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText(text);
        edit.setParseMode("HTML");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(createBtn("🔙 Назад к управлению", "tool_menu_main"))));
        edit.setReplyMarkup(markup);

        try { execute(edit); } catch (Exception e) { e.printStackTrace(); }
    }

    private void sendClosableMessage(long chatId, String text) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE"))));
        try {
            if (text.length() <= 3900) {
                SendMessage msg = new SendMessage(String.valueOf(chatId), text); msg.setParseMode("HTML"); msg.setReplyMarkup(markup); execute(msg);
            } else {
                String remaining = text;
                while (remaining.length() > 3900) {
                    int split = remaining.lastIndexOf("\n\n", 3900); if (split == -1) split = 3900;
                    SendMessage msgPart = new SendMessage(String.valueOf(chatId), remaining.substring(0, split)); msgPart.setParseMode("HTML"); execute(msgPart);
                    remaining = remaining.substring(split).trim();
                }
                if (!remaining.isEmpty()) {
                    SendMessage msg = new SendMessage(String.valueOf(chatId), remaining); msg.setParseMode("HTML"); msg.setReplyMarkup(markup); execute(msg);
                }
            }
        } catch (TelegramApiException e) {}
    }

    private void sendMaterialSelectionForReport(long chatId, TmReportSession session, Integer messageId) {
        List<String[]> userMats = DatabaseManager.getUserMaterialsForWriteOff(chatId);

        StringBuilder sb = new StringBuilder("📦 <b>Списание материалов (Шаг 3)</b>\n\n");
        if (!session.usedMaterials.isEmpty()) {
            sb.append("✅ <b>Уже добавлено к списанию:</b>\n");
            for (DatabaseManager.ReceiptItem item : session.usedMaterials) {
                sb.append("▪ ").append(item.name).append(" — ").append(DatabaseManager.fmtQty(item.quantity)).append(" ").append(item.unit).append("\n");
            }
            sb.append("\n");
        }
        sb.append("👇 Выберите материал из вашего подотчета ИЛИ нажмите кнопку завершения:");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (String[] m : userMats) {
            // Кнопка для каждого материала
            rows.add(List.of(createBtn("📦 " + (m[2].length() > 25 ? m[2].substring(0, 25) + "…" : m[2]) + " (" + m[4] + " " + m[3] + ")", "TM_REP_MAT:" + m[0])));
        }

        // Динамическая кнопка завершения
        if (session.isEditing) {
            if (!session.usedMaterials.isEmpty()) {
                rows.add(List.of(createBtn("✅ Сохранить и вернуться в превью", "TM_REP_MAT_DONE")));
            } else {
                rows.add(List.of(createBtn("🚫 Без материалов (Вернуться в превью)", "TM_REP_MAT_DONE")));
            }
            rows.add(List.of(createBtn("🔙 Назад в превью", "TM_REP_PREVIEW")));
        } else {
            if (!session.usedMaterials.isEmpty()) {
                rows.add(List.of(createBtn("✅ Готово (Перейти к тексту)", "TM_REP_MAT_DONE")));
            } else {
                rows.add(List.of(createBtn("🚫 Материалы не использовались", "TM_REP_MAT_DONE")));
            }
            rows.add(List.of(
                    createBtn("🔙 Назад в начало", "TM_REPORT:" + session.taskId),
                    createBtn("❌ Отменить", "TM_REP_CANCEL")
            ));
        }
        markup.setKeyboard(rows);

        try {
            if (messageId == null) {
                SendMessage msg = new SendMessage(String.valueOf(chatId), sb.toString());
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                execute(msg);
            } else {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(sb.toString()); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                execute(edit);
            }
        } catch (TelegramApiException e) {}
    }

    private void showTmReportPreview(long chatId, TmReportSession session, Integer messageId) {
        // Собираем текст для отправки в ТМ (Умно склеиваем через запятую)
        List<String> tmParts = new ArrayList<>();

        if ("PON".equals(session.type) && !session.closingCode.isEmpty()) {
            tmParts.add("КОД " + session.closingCode);
        }
        if (session.hasReceipt && !session.receiptNumber.isEmpty()) {
            tmParts.add("квитанция #" + session.receiptNumber);
        }
        if (!session.reportText.isEmpty()) {
            tmParts.add(session.reportText);
        }
        if (!session.retailStatus.isEmpty()) {
            tmParts.add(session.retailStatus);
        }

        String finalTmText = String.join(", ", tmParts);

        // Собираем текст по материалам для склада
        StringBuilder matText = new StringBuilder();
        if (session.usedMaterials.isEmpty()) {
            matText.append("🚫 <i>Материалы не списываются</i>");
        } else {
            for (DatabaseManager.ReceiptItem item : session.usedMaterials) {
                matText.append("▪ ").append(item.name).append(" — ").append(DatabaseManager.fmtQty(item.quantity)).append(" ").append(item.unit).append("\n");
            }
        }

        String previewMsg = "📄 <b>Проверьте отчет перед отправкой:</b>\n\n" +
                "🌐 <b>Уйдет в ТМ:</b>\n<code>" + finalTmText + "</code>\n\n" +
                "📦 <b>Спишется со склада:</b>\n" + matText.toString();

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(createBtn("🚀 Отправить отчет в ТМ", "TM_REP_SEND")),
                List.of(createBtn("✏️ Изменить текст", "TM_REP_EDIT_TXT"), createBtn("📦 Изменить материалы", "TM_REP_EDIT_MAT")),
                List.of(createBtn("🔄 Начать заново", "TM_REPORT:" + session.taskId), createBtn("❌ Отменить", "TM_REP_CANCEL"))
        ));

        try {
            if (messageId == null) {
                SendMessage msg = new SendMessage(String.valueOf(chatId), previewMsg);
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup); execute(msg);
            } else {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(previewMsg); edit.setParseMode("HTML"); edit.setReplyMarkup(markup); execute(edit);
            }
        } catch (Exception e) {}
    }

    private void sendScheduleTextSection(long chatId, int messageId, String text) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(createBtn("🔙 Назад", "SCHED_MAIN")),
                List.of(createBtn("❌ Закрыть", "GENERIC_CLOSE"))
        ));
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(messageId);
        edit.setText(text);
        edit.setParseMode("HTML");
        edit.setReplyMarkup(markup);
        try { execute(edit); } catch (Exception e) {}
    }

    // --- УМНЫЙ ПЫЛЕСОС: ПАМЯТЬ СООБЩЕНИЙ ---
    private final Map<Long, List<Integer>> chatCleanupMemory = new ConcurrentHashMap<>();
    private final Map<Long, Integer> persistentAnchorIds = new ConcurrentHashMap<>();

    private void trackMessageForCleanup(long chatId, int messageId) {
        chatCleanupMemory.computeIfAbsent(chatId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(messageId);
    }

    private void clearChatHistory(long chatId) {
        List<Integer> messages = chatCleanupMemory.remove(chatId);
        if (messages != null) {
            for (Integer msgId : messages) {
                try {
                    // Удаляем всё, что бот успел написать с момента прошлого клика по главному меню
                    super.execute(new DeleteMessage(String.valueOf(chatId), msgId));
                } catch (Exception e) {
                    // Игнорируем, если вы уже закрыли это окно кнопкой "❌ Закрыть"
                }
            }
        }
    }

    // --- ПЕРЕХВАТЧИК: АВТОМАТИЧЕСКИ ЗАПОМИНАЕМ ВСЁ, ЧТО ПИШЕТ БОТ ---
    @Override
    public <T extends java.io.Serializable, Method extends org.telegram.telegrambots.meta.api.methods.BotApiMethod<T>> T execute(Method method) throws org.telegram.telegrambots.meta.exceptions.TelegramApiException {
        T response = super.execute(method);

        // Если бот отправляет новое текстовое сообщение
        if (method instanceof SendMessage && response instanceof org.telegram.telegrambots.meta.api.objects.Message) {
            SendMessage request = (SendMessage) method;
            org.telegram.telegrambots.meta.api.objects.Message sentMsg = (org.telegram.telegrambots.meta.api.objects.Message) response;

            if (request.getText() != null && request.getText().contains("Ваша роль в системе")) {
                // Удаляем старый якорь, если он висит выше в истории чата
                Integer oldAnchorId = persistentAnchorIds.get(sentMsg.getChatId());
                if (oldAnchorId != null) {
                    try {
                        super.execute(new DeleteMessage(String.valueOf(sentMsg.getChatId()), oldAnchorId));
                    } catch (Exception e) {}
                }
                // Запоминаем ID нового якоря
                persistentAnchorIds.put(sentMsg.getChatId(), sentMsg.getMessageId());
            } else {
                // Всё остальное заносим в список на удаление
                trackMessageForCleanup(sentMsg.getChatId(), sentMsg.getMessageId());
            }
        }
        return response;
    }
    private void updateReportUI(long chatId, TmReportSession session, String text, InlineKeyboardMarkup markup) {
        if (session == null || session.anchorMsgId == null) return;
        EditMessageText edit = new EditMessageText();
        edit.setChatId(String.valueOf(chatId));
        edit.setMessageId(session.anchorMsgId);
        edit.setText(text);
        edit.setParseMode("HTML");
        if (markup == null) {
            markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("❌ Отменить", "TM_REP_CANCEL"))));
        }
        edit.setReplyMarkup(markup);
        try { execute(edit); } catch (Exception e) {}
    }

    // --- ПАНЕЛЬ АДМИНИСТРИРОВАНИЯ (ПОЛЬЗОВАТЕЛИ И СИСТЕМА) ---
    private void sendAdminUsersControlPanel(long chatId, Integer messageId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(createBtn("📋 Список сотрудников", "ADM_PANEL_LIST")),
                List.of(createBtn("✏️ Переименовать (/fix_name)", "ADM_PANEL_RENAME"), createBtn("🗑 Удалить (/fire)", "ADM_PANEL_DELETE")),
                List.of(createBtn("🔗 Сбросить привязку (/unbind)", "ADM_PANEL_UNBIND")),
                List.of(createBtn("🧹 Очистить склад", "ADM_PANEL_CLR_STOCK"), createBtn("🛠 Сброс зависших заявок", "ADM_PANEL_FIX_REQ")),
                List.of(createBtn("❌ Закрыть панель", "GENERIC_CLOSE"))
        ));

        String text = "⚙️ <b>Панель управления персоналом и системой</b>\nВыберите необходимое действие:";

        try {
            if (messageId == null) {
                SendMessage msg = new SendMessage(String.valueOf(chatId), text);
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                execute(msg);
            } else {
                EditMessageText edit = new EditMessageText();
                edit.setChatId(String.valueOf(chatId)); edit.setMessageId(messageId);
                edit.setText(text); edit.setParseMode("HTML"); edit.setReplyMarkup(markup);
                execute(edit);
            }
        } catch (TelegramApiException e) {}
    }

    // Метод для уведомления ответственных за график (Козлов и Белевич)
    private void notifyManagersAboutSickLeave(String text) {
        List<String> allNames = DatabaseManager.getAllExcelNames();
        List<Long> admins = DatabaseManager.getAdminIds();

        for (String name : allNames) {
            // Ищем нужных людей по части фамилии
            if (name.contains("Козлов") || name.contains("Белевич")) {
                Long managerId = DatabaseManager.getUserIdByExcelName(name);
                // Отправляем им уведомление (если нашли ID и если они сами не являются админами, чтобы не дублировать)
                if (managerId != null && !admins.contains(managerId)) {
                    sendDirectNotification(managerId, text);
                }
            }
        }
    }

    // Умный парсер дат: понимает форматы "10.11", "10.11.26" и "10.11.2026"
    private java.time.LocalDate parseSmartDate(String input, int currentYear, int currentMonth) throws Exception {
        String cleanInput = input.trim();
        String[] parts = cleanInput.split("\\.");

        if (parts.length < 2) throw new Exception("Неверный формат даты");

        int day = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int year = currentYear;

        if (parts.length == 3) {
            year = Integer.parseInt(parts[2]);
            // Если ввели двузначный год (например, 26), превращаем в 2026
            if (year < 100) {
                year += 2000;
            }
        } else {
            // Если год не указан (ввели только "10.11"), проверяем переход через Новый год
            if (currentMonth == 12 && month == 1) {
                year++;
            }
        }

        return java.time.LocalDate.of(year, month, day);
    }

    public Map<Long, Integer> getForceWelderReturnIds() { return forceWelderReturnIds; }
    public Map<Long, LocalDate> getKeptWelderForTomorrow() { return keptWelderForTomorrow; }

    private String getModemErrorsText() {
        return "🌐 <b>Неисправности модемов:</b>\n\n" +
                "• Низкий уровень Wi-Fi\n• Не раздает Wi-Fi\n• Нестабильный уровень Wi-Fi\n" +
                "• Замена на двухдиапазонный\n• Нет услуг\n• Не регистрируется\n• Не работает телефония\n" +
                "• LOS\n• Постоянная перезагрузка\n• Не работает LAN\n• Горит только power\n" +
                "• Не включается\n• Посторонние звуки\n• Запах гари\n• Пропадает сессия\n" +
                "• Пропадает соединение по LAN\n• Отключается сеть 5G\n• Нужны гигабитные порты\n" +
                "• Горит LOS и PON, не работают услуги\n• Отключается";
    }

    private String getStbErrorsText() {
        return "📺 <b>Неисправности приставок:</b>\n\n" +
                "• Ошибка 1901\n• Ошибка 1305\n• Ошибка 1306\n• Зависание\n• Нет звука\n" +
                "• Нет питания\n• Постоянная перезагрузка\n• Не реагирует на пульт\n• Нет изображения\n" +
                "• Пропадает изображение\n• Мерцание\n• Нечёткое изображение\n• Нет авторизации\n" +
                "• Ошибка смарт-карты\n• Не работает LAN\n• Нет сигнала\n• Посторонние звуки\n" +
                "• Запах гари\n• Не работает разъем HDMI\n• Не работает разъем 3RCA\n• Замена на модель с HDMI";
    }
}