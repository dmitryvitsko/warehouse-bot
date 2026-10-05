import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class WarehouseBot extends TelegramLongPollingBot {

    private final Map<Long, DatabaseManager.ReceiptSession> receiptSessions = new HashMap<>();
    private final Map<Long, Integer> waitingTakeMaterialId = new HashMap<>();
    private final Map<Long, WriteOffSession> writeOffSessions = new HashMap<>();
    private final Map<Long, String> fileWaitState = new HashMap<>();
    private final Map<Long, Integer> waitingToolWriteOffReason = new HashMap<>();
    private final Map<Long, Integer> waitingOrshPhoto = new HashMap<>();
    private final Map<Long, Integer> waitingOrshProblemReason = new HashMap<>();
    private final Map<Long, Integer> waitingDirContactCat = new HashMap<>();

    // Для карманного редактора смен
    private final Map<Long, String> waitingScheduleEditUser = new HashMap<>();
    private final Map<Long, String> waitingScheduleEditDate = new HashMap<>();

    // Блокировка меню сварочником (для бота-надзирателя)
    private final Map<Long, Integer> forceWelderReturnIds = new HashMap<>();

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

    // =========================================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ (МЕНЮ И ОТРИСОВКА)
    // =========================================================================================

    private String getMonthName(int month) {
        String[] monthNames = {"", "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь", "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"};
        return monthNames[month];
    }

    private void sendMonthSelectionForSchedule(long chatId, String excelName, boolean isMine) {
        LocalDate now = LocalDate.now();
        LocalDate next = now.plusMonths(1);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        String prefix = isMine ? "MY_SCHED:" : ("COL_SCHED:" + excelName + ":");
        rows.add(List.of(createBtn("📅 " + getMonthName(now.getMonthValue()), prefix + now.getYear() + ":" + now.getMonthValue())));
        rows.add(List.of(createBtn("📅 " + getMonthName(next.getMonthValue()), prefix + next.getYear() + ":" + next.getMonthValue())));

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗓 <b>График: " + excelName + "</b>\nВыберите месяц:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (Exception e){}
    }

    private void sendScheduleMenu(long chatId, String role) {
        SendMessage message = new SendMessage(String.valueOf(chatId), "🗓 <b>Графики и смены:</b>\nВыберите, что хотите посмотреть:");
        message.setParseMode("HTML");
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);

        List<KeyboardRow> keyboard = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("🗓 Мой график");
        row1.add("🤝 С кем я в смене?");
        keyboard.add(row1);

        KeyboardRow row2 = new KeyboardRow();
        row2.add("👁 График коллеги");
        row2.add("👥 Кто сегодня работает?"); // <--- НАША НОВАЯ КНОПКА
        keyboard.add(row2);

        KeyboardRow row3 = new KeyboardRow();
        if ("ADMIN".equals(role)) {
            row3.add("✏️ Изменить смену");
        }
        row3.add("🔙 Назад");
        keyboard.add(row3);

        markup.setKeyboard(keyboard);
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) {}
    }

    private void sendWeldersMenu(long chatId, String role) {
        List<String[]> welders = DatabaseManager.getWeldersStatus();
        StringBuilder sb = new StringBuilder();
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
                sb.append("▫️ ").append(wName).append(" 👉 у <b>").append(userName).append("</b>\n");

                if (String.valueOf(chatId).equals(assignedToId)) {
                    myBusyWelderId = wId; myBusyWelderName = wName;
                }
            }
        }

        if (myBusyWelderId != null) {
            sb.insert(0, "⚠️ <b>На вас сейчас числится: " + myBusyWelderName + "</b>\n\n");
            rows.add(List.of(createBtn("↩️ ВЕРНУТЬ " + myBusyWelderName.toUpperCase() + " НА БАЗУ", "W_RET:" + myBusyWelderId)));
            sb.append("\n👇 <b>Управление и свободные аппараты:</b>");
        } else {
            sb.append("\n👇 <b>Выберите свободный аппарат, который берете с базы:</b>");
        }

        for (String[] w : welders) {
            if ("ON_BASE".equals(w[2])) rows.add(List.of(createBtn("🔌 " + w[1], "W_TAKE:" + w[0])));
        }

        // Кнопка истории теперь добавляется для всех пользователей
        rows.add(List.of(createBtn("📜 История логов", "W_HISTORY")));

        if ("ADMIN".equals(role)) {
            rows.add(List.of(createBtn("➕ Выдать принудительно", "W_FORCE_TAKE_M"), createBtn("⚠️ Вернуть принудительно", "W_FORCE_RET_M")));
        }

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), sb.toString());
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (Exception e) { e.printStackTrace(); }
    }

    private void sendWeldersForceAssignMenu(long chatId) {
        List<String[]> welders = DatabaseManager.getWeldersStatus();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] w : welders) {
            if ("ON_BASE".equals(w[2])) rows.add(List.of(createBtn("🔌 " + w[1], "W_F_SEL:" + w[0])));
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "➕ <b>Принудительная выдача (Шаг 1)</b>\nВыберите свободный сварочник:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendWeldersForceAssignUsers(long chatId, int welderId) {
        String wName = DatabaseManager.getWelderNameById(welderId);
        List<String[]> users = DatabaseManager.getUsersForToolAssignment();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) rows.add(List.of(createBtn("👤 " + u[1], "W_F_ASS:" + welderId + ":" + u[0])));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "➕ Принудительная выдача: <b>" + wName + "</b>\n\n👤 <b>Шаг 2: Выберите сотрудника</b>, на которого нужно повесить аппарат:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendWeldersForceReturnMenu(long chatId) {
        List<String[]> welders = DatabaseManager.getWeldersStatus();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] w : welders) {
            if ("IN_USE".equals(w[2])) rows.add(List.of(createBtn("⚠️ Списать: " + w[1] + " (у " + w[3] + ")", "W_F_RET:" + w[0])));
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "⚠️ <b>Принудительный возврат на базу</b>\nВыберите аппарат, который хотите списать с сотрудника:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
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

    // =========================================================================================
    // ГЛАВНЫЙ МЕТОД ОБРАБОТКИ
    // =========================================================================================

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

            if ("BANNED".equals(role) || "PENDING".equals(role)) return;

            if (!data.startsWith("BIND_NAME:") && checkUnboundAndNotify(chatId, role)) {
                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                answer.setText("⚠️ Сначала выберите фамилию!");
                answer.setShowAlert(true);
                try { execute(answer); } catch (TelegramApiException e) {}
                return;
            }

            if (data.startsWith("UPL_SCHED:") && "ADMIN".equals(role)) {
                String[] p = data.split(":");
                fileWaitState.put(chatId, "SCHEDULE:" + p[1] + ":" + p[2]);
                sendCancelKeyboard(chatId, "🗓 Отправьте файл <b>ГРАФИКА РАБОТ</b> (Excel) на <b>" + getMonthName(Integer.parseInt(p[2])) + " " + p[1] + "</b> прямо в этот чат.");
                return;
            }

            if (data.startsWith("MY_SCHED:")) {
                String[] p = data.split(":");
                String excelName = DatabaseManager.getUserExcelName(chatId);
                sendMenu(chatId, role, DatabaseManager.getFormattedSchedule(excelName, Integer.parseInt(p[1]), Integer.parseInt(p[2])));
                return;
            }

            if (data.startsWith("COL_SCHED:")) {
                String[] p = data.split(":");
                sendMenu(chatId, role, DatabaseManager.getFormattedSchedule(p[1], Integer.parseInt(p[2]), Integer.parseInt(p[3])));
                return;
            }

            if (data.startsWith("ED_SCH_U:") && "ADMIN".equals(role)) {
                String excelName = data.substring(9);
                waitingScheduleEditUser.put(chatId, excelName);
                sendCancelKeyboard(chatId, "✏️ Выбран сотрудник: <b>" + excelName + "</b>\n\nНапишите дату ИЛИ период дат, которые нужно изменить.\n\n<i>Пример 1:</i> <code>15.11.2026</code>\n<i>Пример 2:</i> <code>01.11.2026-07.11.2026</code>");                return;
            }

            if (data.startsWith("ED_SCH_S:") && "ADMIN".equals(role)) {
                String payload = data.substring(9);
                String savedData = waitingScheduleEditDate.remove(chatId);
                if (savedData == null) { sendMenu(chatId, role, "❌ Ошибка сессии редактирования. Начните заново."); return; }

                String[] mainParts = savedData.split(":", 2);
                String targetUser = mainParts[0];
                String dateRangeStr = mainParts[1];

                String statusCode = ""; String start = ""; String end = "";
                if (payload.equals("В") || payload.equals("О") || payload.equals("Д") || payload.equals("Б") || payload.equals("А") || payload.equals("Г") || payload.equals("П")) {                    statusCode = payload;
                } else {
                    String[] times = payload.split("-");
                    start = times[0]; end = times[1];
                }

                java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
                java.time.LocalDate startDate;
                java.time.LocalDate endDate;
                String displayDate;

                try {
                    if (dateRangeStr.contains("-")) {
                        String[] dParts = dateRangeStr.split("-");
                        startDate = java.time.LocalDate.parse(dParts[0], dtf);
                        endDate = java.time.LocalDate.parse(dParts[1], dtf);
                        displayDate = "период с " + dateRangeStr.replace("-", " по ");
                    } else {
                        startDate = java.time.LocalDate.parse(dateRangeStr, dtf);
                        endDate = startDate;
                        displayDate = dateRangeStr;
                    }

                    // Запускаем цикл: от первого дня периода до последнего
                    java.time.LocalDate current = startDate;
                    while (!current.isAfter(endDate)) {
                        DatabaseManager.updateSingleShift(targetUser, current.getYear(), current.getMonthValue(), current.getDayOfMonth(), statusCode, start, end);
                        current = current.plusDays(1);
                    }

                    sendMenu(chatId, role, "✅ График сотрудника " + targetUser + " успешно изменен (" + displayDate + ")!");

                    Long targetUserId = DatabaseManager.getUserIdByExcelName(targetUser);
                    if (targetUserId != null) {
                        sendDirectNotification(targetUserId, "⚠️️ <b>ВНИМАНИЕ!</b>\nАдминистратор изменил ваш график!\nДата: <b>" + displayDate + "</b>\nНовая смена/статус: <b>" + (statusCode.isEmpty() ? payload : statusCode) + "</b>");
                    }
                } catch (Exception e) {
                    sendMenu(chatId, role, "❌ Ошибка при обработке дат.");
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

            if (data.startsWith("W_TAKE:")) {
                int welderId = Integer.parseInt(data.split(":")[1]);
                if (DatabaseManager.takeWelder(welderId, chatId, null)) {
                    sendMenu(chatId, role, "✅ Вы успешно взяли сварочный аппарат!");
                    if (chatId != DatabaseManager.ADMIN_ID) {
                        sendDirectNotification(DatabaseManager.ADMIN_ID, "ℹ️ <b>Сварочный аппарат взят!</b>\n👷‍♂ " + DatabaseManager.getUserFullName(chatId) + " взял аппарат <b>" + DatabaseManager.getWelderNameById(welderId) + "</b>.");
                    }
                    sendWeldersMenu(chatId, role);
                } else sendMenu(chatId, role, "❌ Ошибка: аппарат уже занят.");
                return;
            }

            if (data.startsWith("W_RET:")) {
                int welderId = Integer.parseInt(data.split(":")[1]);
                if (DatabaseManager.returnWelder(welderId, chatId, null)) {
                    forceWelderReturnIds.remove(chatId);
                    sendMenu(chatId, role, "✅ Вы успешно вернули сварочный аппарат на базу!");
                    sendWeldersMenu(chatId, role);
                }
                return;
            }

            if (data.equals("W_KEEP_TOMORROW")) {
                forceWelderReturnIds.remove(chatId);
                sendMenu(chatId, role, "🌙 Вы оставили сварочный аппарат за собой на завтра. Блокировка снята.");
                return;
            }

            if (data.equals("W_HISTORY")) {
                sendMenu(chatId, role, DatabaseManager.getWeldersHistoryText()); return;
            }
            if (data.equals("W_FORCE_TAKE_M") && "ADMIN".equals(role)) {
                sendWeldersForceAssignMenu(chatId); return;
            }
            if (data.startsWith("W_F_SEL:") && "ADMIN".equals(role)) {
                sendWeldersForceAssignUsers(chatId, Integer.parseInt(data.split(":")[1])); return;
            }
            if (data.startsWith("W_F_ASS:") && "ADMIN".equals(role)) {
                String[] parts = data.split(":");
                int welderId = Integer.parseInt(parts[1]);
                long targetUserId = Long.parseLong(parts[2]);
                if (DatabaseManager.takeWelder(welderId, targetUserId, chatId)) {
                    sendMenu(chatId, role, "✅ Аппарат <b>" + DatabaseManager.getWelderNameById(welderId) + "</b> принудительно выдан.");
                    sendDirectNotification(targetUserId, "🔔 <b>Администратор выдал вам сварочный аппарат!</b>\nЗа вами закреплен: <b>" + DatabaseManager.getWelderNameById(welderId) + "</b>");
                    sendWeldersMenu(chatId, role);
                }
                return;
            }
            if (data.equals("W_FORCE_RET_M") && "ADMIN".equals(role)) {
                sendWeldersForceReturnMenu(chatId); return;
            }
            if (data.startsWith("W_F_RET:") && "ADMIN".equals(role)) {
                int welderId = Integer.parseInt(data.split(":")[1]);
                if (DatabaseManager.returnWelder(welderId, chatId, chatId)) {
                    sendMenu(chatId, role, "⚠️ Аппарат <b>" + DatabaseManager.getWelderNameById(welderId) + "</b> принудительно списан на базу.");
                    sendWeldersMenu(chatId, role);
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

            if (data.equals("TOOL_MENU_RETURN") && "ADMIN".equals(role)) { sendUsersForToolReturn(chatId); return; }
            if (data.equals("TOOL_MENU_ARCHIVE") && "ADMIN".equals(role)) { sendMenu(chatId, role, DatabaseManager.getWrittenOffToolsArchiveText()); return; }
            if (data.equals("TOOL_MENU_RESTORE") && "ADMIN".equals(role)) { sendToolsForRestore(chatId); return; }
            if (data.startsWith("T_RES_DO:") && "ADMIN".equals(role)) { sendMenu(chatId, role, DatabaseManager.restoreToolToStock(Integer.parseInt(data.split(":")[1]))); return; }

            if (data.equals("TOOL_MENU_WRITEOFF") && "ADMIN".equals(role)) {
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("📦 Со склада", "T_WO_LOC:STOCK"), createBtn("👤 У сотрудника", "T_WO_LOC:ASSIGNED"))));
                SendMessage msg = new SendMessage(String.valueOf(chatId), "🗑 <b>Где сейчас находится инструмент, который нужно списать?</b>");
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                try { execute(msg); } catch (TelegramApiException e) {} return;
            }

            if (data.startsWith("T_WO_LOC:") && "ADMIN".equals(role)) {
                if (data.split(":")[1].equals("STOCK")) sendGroupsForToolWriteOff(chatId);
                else sendUsersForToolWriteOff(chatId);
                return;
            }
            if (data.startsWith("T_WO_U:") && "ADMIN".equals(role)) { sendUserToolsForWriteOff(chatId, Long.parseLong(data.split(":")[1])); return; }
            if (data.startsWith("T_WO_G:") && "ADMIN".equals(role)) { sendSpecificToolsInStockForWriteOff(chatId, Integer.parseInt(data.split(":")[1])); return; }
            if (data.startsWith("T_WO_DO:") && "ADMIN".equals(role)) {
                int toolId = Integer.parseInt(data.split(":")[1]);
                waitingToolWriteOffReason.put(chatId, toolId);
                sendCancelKeyboard(chatId, "✍️ Выбран инструмент: <b>" + DatabaseManager.getToolNameAndInvById(toolId) + "</b>\n\nВведите <b>причину списания</b> (например: утерян, сломался):");
                return;
            }
            if (data.startsWith("T_RET_U:") && "ADMIN".equals(role)) { sendUserToolsForReturn(chatId, Long.parseLong(data.split(":")[1])); return; }
            if (data.startsWith("T_RET_T:") && "ADMIN".equals(role)) { sendMenu(chatId, role, DatabaseManager.returnToolToWarehouse(Integer.parseInt(data.split(":")[1]))); return; }
            if (data.equals("TOOL_MAIN_MENU") && "ADMIN".equals(role)) { sendToolAdminMenu(chatId); return; }
            if (data.equals("TOOL_MENU_AUDIT_ALL") && "ADMIN".equals(role)) { sendMenu(chatId, role, DatabaseManager.getToolsAuditText()); return; }
            if (data.equals("TOOL_MENU_AUDIT_USERS") && "ADMIN".equals(role)) { sendUsersToolSummaryMenu(chatId); return; }

            if (data.equals("TOOL_MENU_AUDIT_NOTIFY") && "ADMIN".equals(role)) {
                sendMenu(chatId, role, "⏳ Начинаю рассылку уведомлений...");
                List<Long> workersWithMaterials = DatabaseManager.getUsersWithBalances();
                int successCount = 0;
                for (Long workerId : workersWithMaterials) {
                    try {
                        SendMessage msg = new SendMessage(String.valueOf(workerId), "⚠️ <b>ВНИМАНИЕ: АУДИТ ОСТАТКОВ!</b> ⚠️\n\nНапоминаем о необходимости закрыть подотчет до конца месяца. Пожалуйста, <b>спишите</b> использованные материалы в квитанции или <b>верните</b> остатки на склад!\n\n" + DatabaseManager.getUserBalanceText(workerId));
                        msg.setParseMode("HTML"); execute(msg); successCount++;
                    } catch (TelegramApiException e) {}
                }
                sendMenu(chatId, role, "✅ Уведомления об аудите успешно доставлены <b>" + successCount + "</b> сотрудникам!");
                sendToolAdminMenu(chatId);
                return;
            }

            if (data.startsWith("T_SUMM_U:") && "ADMIN".equals(role)) { sendUserToolSummary(chatId, Long.parseLong(data.split(":")[1])); return; }
            if (data.startsWith("T_SUMM_RET:") && "ADMIN".equals(role)) {
                String[] parts = data.split(":");
                DatabaseManager.returnToolToWarehouse(Integer.parseInt(parts[1]));
                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                answer.setText("✅ Инструмент возвращен!"); answer.setShowAlert(false);
                try { execute(answer); } catch (TelegramApiException e) {}
                sendUserToolSummary(chatId, Long.parseLong(parts[2])); return;
            }
            if (data.startsWith("T_SUMM_RALL:") && "ADMIN".equals(role)) {
                sendMenu(chatId, role, DatabaseManager.returnAllUserToolsToWarehouse(Long.parseLong(data.split(":")[1])));
                sendUsersToolSummaryMenu(chatId); return;
            }
            if (data.equals("TOOL_MENU_ASSIGN") && "ADMIN".equals(role)) { sendAvailableToolsForAssignment(chatId); return; }
            if (data.startsWith("T_SEL_N:") && "ADMIN".equals(role)) { sendUsersForToolAssignment(chatId, Integer.parseInt(data.split(":")[1])); return; }
            if (data.startsWith("T_ASS_U:") && "ADMIN".equals(role)) {
                String[] parts = data.split(":");
                long targetUserId = Long.parseLong(parts[2]);
                String result = DatabaseManager.assignTool(Integer.parseInt(parts[1]), targetUserId);
                sendMenu(chatId, role, result);
                if (result.startsWith("✅") && targetUserId != chatId) sendDirectNotification(targetUserId, "🔔 <b>Вам выдан новый инструмент!</b>\nМОЛ закрепил за вами новую позицию.");
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

            if (data.equals("CART_MENU")) { sendCartMenu(chatId, role); return; }
            if (data.equals("CART_ADD_SRV")) { sendServiceSelectionForCart(chatId); return; }
            if (data.equals("CART_ADD_MAT")) { sendMaterialSelectionForCart(chatId, role); return; }
            if (data.startsWith("CART_SEL_SRV:")) {
                int srvId = Integer.parseInt(data.split(":")[1]);
                DatabaseManager.ReceiptSession s = receiptSessions.computeIfAbsent(chatId, k -> new DatabaseManager.ReceiptSession());
                DatabaseManager.ReceiptItem item = DatabaseManager.getServiceById(srvId);
                if (item != null) {
                    if (item.isSingle) {
                        item.quantity = 1.0; s.items.add(item); sendCartMenu(chatId, role);
                    } else {
                        s.waitingServiceId = srvId; s.waitingMaterialId = -1;
                        sendCancelKeyboard(chatId, "✍️ <b>Введите количество</b> для выбранной услуги (например: 1 или 2):");
                    }
                }
                return;
            }
            if (data.startsWith("CART_SEL_MAT:")) {
                int matId = Integer.parseInt(data.split(":")[1]);
                DatabaseManager.ReceiptSession s = receiptSessions.computeIfAbsent(chatId, k -> new DatabaseManager.ReceiptSession());
                s.waitingMaterialId = matId; s.waitingServiceId = -1;
                sendCancelKeyboard(chatId, "✍️ <b>Введите количество</b> израсходованного материала (например: 15 или 0.02):");
                return;
            }
            if (data.equals("CART_FINISH")) {
                DatabaseManager.ReceiptSession s = receiptSessions.get(chatId);
                if (s == null || s.items.isEmpty()) sendMenu(chatId, role, "❌ Ваша квитанция пуста.");
                else { sendMenu(chatId, role, DatabaseManager.generateReceiptText(s)); receiptSessions.remove(chatId); }
                return;
            }
            if (data.equals("CART_CLEAR")) { receiptSessions.remove(chatId); sendMenu(chatId, role, "🗑 Корзина очищена."); return; }

            if (data.startsWith("BIND_NAME:")) {
                String excelName = data.substring(10);
                DatabaseManager.bindUserToExcelName(chatId, excelName);
                sendMenu(chatId, role, "✅ Отлично! Ваш профиль успешно привязан к: <b>" + excelName + "</b>.\n\nТеперь вам полностью доступны все функции системы.");
                return;
            }

            if (data.startsWith("VIEW_SCHED:")) {
                sendMonthSelectionForSchedule(chatId, data.substring(11), false);
                return;
            }

            if (data.startsWith("TAKE_MAT:")) {
                writeOffSessions.remove(chatId);
                waitingTakeMaterialId.put(chatId, Integer.parseInt(data.split(":")[1]));
                sendCancelKeyboard(chatId, "Подготовка к выдаче...");
                InlineKeyboardMarkup inlineMarkup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("1", "TAKE_Q:1"), createBtn("2", "TAKE_Q:2"), createBtn("3", "TAKE_Q:3"), createBtn("4", "TAKE_Q:4"), createBtn("5", "TAKE_Q:5")),
                        List.of(createBtn("10", "TAKE_Q:10"), createBtn("20", "TAKE_Q:20"), createBtn("50", "TAKE_Q:50"), createBtn("100", "TAKE_Q:100"))
                ));
                SendMessage msg = new SendMessage(String.valueOf(chatId), "✍ <b>Укажите количество:</b>\nНапишите число вручную (например: <code>15</code>) ИЛИ нажмите на кнопку:");
                msg.setParseMode("HTML"); msg.setReplyMarkup(inlineMarkup);
                try { execute(msg); } catch (TelegramApiException e) {} return;
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

            if (data.startsWith("WO_MAT:")) {
                waitingTakeMaterialId.remove(chatId);
                WriteOffSession session = DatabaseManager.createWriteOffSession(chatId, Integer.parseInt(data.split(":")[1]));
                if (session == null) { sendMenu(chatId, role, "❌ Материал не числится в подотчете."); return; }
                writeOffSessions.put(chatId, session);
                sendCancelKeyboard(chatId, "Подготовка к списанию...");
                InlineKeyboardMarkup inlineMarkup = new InlineKeyboardMarkup(List.of(
                        List.of(createBtn("1", "WO_Q:1"), createBtn("2", "WO_Q:2"), createBtn("5", "WO_Q:5"), createBtn("10", "WO_Q:10")),
                        List.of(createBtn("Всё (" + DatabaseManager.fmtQty(session.maxAvailable) + " " + session.unit + ")", "WO_Q:" + session.maxAvailable))
                ));
                SendMessage msg = new SendMessage(String.valueOf(chatId), String.format("Выбрано: <b>%s</b>\nДоступно у вас: <b>%s %s</b>\n\n✍️ Напишите количество вручную ИЛИ выберите быстрый вариант:", session.materialName, DatabaseManager.fmtQty(session.maxAvailable), session.unit));
                msg.setParseMode("HTML"); msg.setReplyMarkup(inlineMarkup);
                try { execute(msg); } catch (TelegramApiException e) {} return;
            }

            if (data.startsWith("WO_Q:") && writeOffSessions.containsKey(chatId)) {
                WriteOffSession s = writeOffSessions.get(chatId);
                try {
                    double qty = Double.parseDouble(data.split(":")[1]);
                    if (qty <= 0 || qty > s.maxAvailable + 1e-9) { sendCancelKeyboard(chatId, String.format("❌ Ошибка: доступно не более %s %s.", DatabaseManager.fmtQty(s.maxAvailable), s.unit)); return; }
                    s.quantity = qty; s.step = "WAIT_TYPE"; sendTypeButtons(chatId, s);
                } catch (Exception e) {} return;
            }

            if (data.startsWith("WO_TYPE:") && writeOffSessions.containsKey(chatId)) {
                WriteOffSession session = writeOffSessions.get(chatId);
                String type = data.split(":")[1];
                if ("RETURN".equals(type)) {
                    DatabaseManager.ReturnRequestInfo req = DatabaseManager.createReturnRequest(chatId, session.materialId, session.quantity);
                    if (req.success) {
                        if ("ADMIN".equals(role)) {
                            sendMenu(chatId, role, "⚡️ <b>Автоматический возврат МОЛ:</b>\n\n" + DatabaseManager.approveReturnRequest(req.requestId)[2]);
                        } else {
                            sendMenu(chatId, role, req.messageForWorker); sendReturnApprovalToAdmins(req.requestId, req.messageForAdmin);
                        }
                    } else sendMenu(chatId, role, req.messageForWorker);
                    writeOffSessions.remove(chatId);
                } else if ("PAID".equals(type)) {
                    session.isPaidReceipt = true; session.step = "WAIT_RECEIPT_NUM";
                    sendCancelKeyboard(chatId, "🧾 <b>Списание по квитанции (шаг 1 из 4)</b>\nВведите <b>номер квитанции</b>:");
                } else {
                    session.isPaidReceipt = false; session.step = "WAIT_FREE_PHONE";
                    sendCancelKeyboard(chatId, "🛠 <b>Техническое списание (шаг 1 из 5)</b>\nВведите <b>номер телефона</b>, на который оформлена заявка:");
                }
                return;
            }

            if (data.startsWith("WO_CODE:") && writeOffSessions.containsKey(chatId)) {
                WriteOffSession session = writeOffSessions.remove(chatId);
                session.closingCode = data.split(":")[1];
                session.reason = switch (session.closingCode) {
                    case "212" -> "Ремонт ВОК на участке ОРК-ОРА"; case "227" -> "Выправление волокна";
                    case "215" -> "В ОРШ: выправление пигтейла/волокна"; case "226" -> "В ОРШ: замена пигтейла / адаптера";
                    case "214" -> "Участок ОРШ-ОРК: ремонт/замена райзера"; case "217" -> "Участок ОРШ-ОРК: запасной модуль";
                    default -> "Тех. списание (Код " + session.closingCode + ")";
                };
                String res = DatabaseManager.completeWriteOff(chatId, session);
                sendMenu(chatId, role, res);
                if (!"ADMIN".equals(role)) notifyAdminsForAction(chatId, "🔔 <b>ВНИМАНИЕ! Списание материала:</b>\nМастер <b>" + firstName + "</b> только что выполнил техническое списание:\n\n" + res);
                return;
            }
            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String firstName = update.getMessage().getFrom().getFirstName();
            String text = update.getMessage().getText();
            String role = checkRoleAndNotify(chatId, firstName);

            if ("BANNED".equals(role)) return;
            if ("PENDING".equals(role)) {
                if (text.equals("/start")) sendDirectNotification(chatId, "⏳ Ваша заявка все еще находится на рассмотрении администратора.");
                return;
            }

            if (checkUnboundAndNotify(chatId, role)) return;

            if (waitingDirContactCat.containsKey(chatId)) {
                int category = waitingDirContactCat.remove(chatId);
                if (DatabaseManager.addDirectoryContact(category, text.trim(), chatId)) {
                    sendMenu(chatId, role, "✅ <b>Контакт успешно добавлен!</b>\nОн теперь отображается в справочнике у всех сотрудников.");
                    sendDirectory(chatId, role);
                } else sendMenu(chatId, role, "❌ Ошибка при сохранении контакта.");
                return;
            }

            if (waitingScheduleEditUser.containsKey(chatId)) {
                String targetUser = waitingScheduleEditUser.remove(chatId);
                try {
                    String input = text.trim();
                    java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
                    String savedDateRange;
                    String displayDate;

                    if (input.contains("-")) {
                        String[] parts = input.split("-");
                        java.time.LocalDate start = java.time.LocalDate.parse(parts[0].trim(), dtf);
                        java.time.LocalDate end = java.time.LocalDate.parse(parts[1].trim(), dtf);
                        if (start.isAfter(end)) throw new Exception("Ошибка: Начальная дата больше конечной");
                        savedDateRange = start.format(dtf) + "-" + end.format(dtf);
                        displayDate = "с " + start.format(dtf) + " по " + end.format(dtf);
                    } else {
                        java.time.LocalDate date = java.time.LocalDate.parse(input, dtf);
                        savedDateRange = date.format(dtf);
                        displayDate = date.format(dtf);
                    }

                    waitingScheduleEditDate.put(chatId, targetUser + ":" + savedDateRange);

                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                            List.of(createBtn("В (Выходной)", "ED_SCH_S:В"), createBtn("О (Отпуск)", "ED_SCH_S:О"), createBtn("Д (Дежурство)", "ED_SCH_S:Д")),
                            List.of(createBtn("Б (Больничный)", "ED_SCH_S:Б"), createBtn("А (За свой счет)", "ED_SCH_S:А"), createBtn("Г (Военкомат)", "ED_SCH_S:Г")),
                            List.of(createBtn("П (Другое подразделение)", "ED_SCH_S:П")),
                            List.of(createBtn("08:30 - 17:30", "ED_SCH_S:08:30-17:30"), createBtn("12:00 - 21:00", "ED_SCH_S:12:00-21:00"))
                    ));
                    SendMessage msg = new SendMessage(String.valueOf(chatId), "⚙️ Устанавливаем смену на <b>" + displayDate + "</b> для <b>" + targetUser + "</b>.\n\nВыберите тип смены из кнопок:");
                    msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                    execute(msg);
                } catch (Exception e) {
                    sendCancelKeyboard(chatId, "❌ Неверный формат даты.\nПример правильного ввода: <code>15.11.2026</code> ИЛИ <code>01.11.2026-07.11.2026</code>");
                    // Возвращаем пользователя в сессию, чтобы он мог ввести дату заново
                    waitingScheduleEditUser.put(chatId, targetUser);
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

            if (text.equals("❌ Отменить") || text.equals("🔙 Назад")) {
                waitingTakeMaterialId.remove(chatId); writeOffSessions.remove(chatId); fileWaitState.remove(chatId);
                waitingToolWriteOffReason.remove(chatId); waitingOrshPhoto.remove(chatId); waitingOrshProblemReason.remove(chatId);
                waitingDirContactCat.remove(chatId); waitingScheduleEditUser.remove(chatId); waitingScheduleEditDate.remove(chatId);
                if (text.equals("❌ Отменить")) sendMenu(chatId, role, "🚫 <b>Действие отменено.</b>");
                else sendMenu(chatId, role, "Вы вернулись в главное меню:"); return;
            }

            if (!text.equals("/start") && !text.equals("/fix") && !text.startsWith("/take_") && !text.startsWith("/give_")) {
                if (isAdminBlockedByPendingRequests(chatId, role)) return;
            }

            if (text.startsWith("📦") || text.startsWith("🧰") || text.startsWith("📝") || text.startsWith("📋") || text.startsWith("🔢") || text.startsWith("🤝") || text.startsWith("📊") || text.startsWith("📥") || text.startsWith("📑") || text.startsWith("🗓") || text.startsWith("🔍") || text.startsWith("📢") || text.startsWith("👥") || text.startsWith("🪛") || text.startsWith("🛠") || text.startsWith("📞") || text.startsWith("🔌") || text.startsWith("👁") || text.startsWith("✏️") || text.equals("/start")) {
                waitingTakeMaterialId.remove(chatId); waitingDirContactCat.remove(chatId); writeOffSessions.remove(chatId);
                fileWaitState.remove(chatId); waitingToolWriteOffReason.remove(chatId); waitingOrshPhoto.remove(chatId);
                waitingOrshProblemReason.remove(chatId); waitingScheduleEditUser.remove(chatId); waitingScheduleEditDate.remove(chatId);
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
                sendMenu(chatId, role, DatabaseManager.writeOffTool(waitingToolWriteOffReason.remove(chatId), text)); return;
            }
            if (writeOffSessions.containsKey(chatId)) { handleWriteOffStep(chatId, firstName, role, text); return; }

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
                    sendCartMenu(chatId, role);
                } catch (NumberFormatException e) { sendCancelKeyboard(chatId, "❌ Введите корректное число больше нуля:"); }
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
                sendMenu(chatId, role, DatabaseManager.removeWorkerFromSchedule(nameToRemove));
                return;
            }

            if (waitingOrshProblemReason.containsKey(chatId)) {
                DatabaseManager.markOrshCompleted(waitingOrshProblemReason.remove(chatId), firstName, true, text);
                sendMenu(chatId, role, "⚠️ Причина зафиксирована. Этот ОРШ переведен в статус проблемных."); return;
            }

            switch (text) {
                case "/start" -> {
                    String roleTitle = role.equals("ADMIN") ? "Администратор (МОЛ)" : "Мастер ЦБР УЛКС №2 ЛКЦ";
                    sendMenu(chatId, role, "Привет, <b>" + firstName + "</b>! 👋\nВаша роль в системе: <b>" + roleTitle + "</b>.\n\nВыберите нужное действие на кнопках внизу экрана:");
                }
                case "/fix" -> {
                    if ("ADMIN".equals(role)) {
                        try (java.sql.Connection conn = DatabaseManager.getConnection(); java.sql.Statement stmt = conn.createStatement()) {
                            stmt.execute("DELETE FROM return_requests"); sendMenu(chatId, role, "✅ Зависшие заявки очищены!");
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                }
                case "🔌 Сварочные аппараты" -> sendWeldersMenu(chatId, role);
                case "📞 Справочник" -> sendDirectory(chatId, role);
                case "🗓 График работ" -> sendScheduleMenu(chatId, role);
                case "🗓 Мой график" -> {
                    String excelName = DatabaseManager.getUserExcelName(chatId);
                    if (excelName != null) sendMonthSelectionForSchedule(chatId, excelName, true);
                    else {
                        List<String> names = DatabaseManager.getAvailableExcelNames();
                        if (names.isEmpty()) sendMenu(chatId, role, "ℹ График работ еще не загружен администратором.");
                        else sendNameBindingMenu(chatId, names);
                    }
                }
                case "👁 График коллеги" -> {
                    List<String> names = DatabaseManager.getAllExcelNames();
                    if (names.isEmpty()) sendMenu(chatId, role, "ℹ График работ еще не загружен администратором.");
                    else {
                        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                        for (String name : names) rows.add(List.of(createBtn("👤 " + name, "VIEW_SCHED:" + name)));
                        markup.setKeyboard(rows);
                        SendMessage msg = new SendMessage(String.valueOf(chatId), "👁 <b>График коллеги</b>\n\nВыберите фамилию сотрудника из списка:");
                        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
                    }
                }
                case "✏️ Изменить смену" -> {
                    if ("ADMIN".equals(role)) {
                        List<String> names = DatabaseManager.getAllExcelNames();
                        if (names.isEmpty()) sendMenu(chatId, role, "ℹ График работ еще не загружен.");
                        else {
                            InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                            List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                            for (String name : names) rows.add(List.of(createBtn("✏️ " + name, "ED_SCH_U:" + name)));
                            markup.setKeyboard(rows);
                            SendMessage msg = new SendMessage(String.valueOf(chatId), "✏️ <b>Редактор графиков</b>\nВыберите сотрудника, смену которого нужно изменить:");
                            msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
                        }
                    }
                }
                case "🤝 С кем я в смене?" -> {
                    String excelName = DatabaseManager.getUserExcelName(chatId);
                    if (excelName != null) sendMenu(chatId, role, DatabaseManager.getShiftPartners(excelName));
                    else {
                        List<String> names = DatabaseManager.getAvailableExcelNames();
                        if (names.isEmpty()) sendMenu(chatId, role, "ℹ Ваш профиль еще не привязан к графику.");
                        else sendNameBindingMenu(chatId, names);
                    }
                }
                case "👥 Кто сегодня работает?" -> {
                    sendMenu(chatId, role, DatabaseManager.getTodayRoster());
                }
                case "📦 Склад (Наличие и цены)" -> sendWarehouseList(chatId, role);
                case "📝 Списать / Вернуть" -> startWriteOffMenu(chatId, role);
                case "📋 Тарифы услуг" -> sendMenu(chatId, role, DatabaseManager.getServiceTariffsText());
                case "🔢 Коды закрытия" -> sendMenu(chatId, role, DatabaseManager.getClosingCodesText());
                case "🧾 Калькулятор квитанции" -> sendCartMenu(chatId, role);
                case "🧰 Мой подотчет" -> sendMyInventoryMenu(chatId, "🧰 <b>Ваш подотчет:</b>\nВыберите, что хотите посмотреть:");
                case "📦 Мои материалы" -> sendMyInventoryMenu(chatId, DatabaseManager.getUserBalanceText(chatId));
                case "🪛 Мой инструмент" -> sendMyInventoryMenu(chatId, DatabaseManager.getUserToolsText(chatId));
                case "📸 Плановый осмотр ОРШ" -> sendPendingOrshList(chatId);
                case "📊 Статистика ОРШ" -> { if (role.equals("ADMIN")) sendMenu(chatId, role, DatabaseManager.getOrshStatistics()); }
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
                        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                                List.of(createBtn("📅 " + getMonthName(now.getMonthValue()) + " " + now.getYear(), "UPL_SCHED:" + now.getYear() + ":" + now.getMonthValue())),
                                List.of(createBtn("📅 " + getMonthName(next.getMonthValue()) + " " + next.getYear(), "UPL_SCHED:" + next.getYear() + ":" + next.getMonthValue()))
                        ));
                        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗓 <b>На какой месяц вы загружаете график?</b>\nВыберите из списка:");
                        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
                    }
                }
                case "📊 У кого что на руках" -> { if (role.equals("ADMIN")) sendMenu(chatId, role, DatabaseManager.getAllWorkersBalancesText()); }
                case "📑 Скачать отчет за месяц" -> { if (role.equals("ADMIN")) sendExcelReport(chatId, role); }
                case "📢 Сделать рассылку" -> { if (role.equals("ADMIN")) { fileWaitState.put(chatId, "WAIT_BROADCAST_TEXT"); sendCancelKeyboard(chatId, "📢 <b>Режим массовой рассылки</b>\n\nВведите текст сообщения:"); } }
                case "👥 Пользователи" -> { if (role.equals("ADMIN")) sendMenu(chatId, role, DatabaseManager.getUsersListText()); }
                case "🛠 Управление инструментом" -> { if (role.equals("ADMIN")) sendToolAdminMenu(chatId); }
                default -> {
                    if (text.startsWith("+услуга ") && role.equals("ADMIN")) handleUpdateService(chatId, role, text);
                    else sendMenu(chatId, role, "Используйте кнопки меню внизу экрана 👇");
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
        sb.append("🏢 <b>Руководство ЛКЦ</b>\n▪️ Нач. цеха Богино А.Е.: +375(17) 335-24-44\n▪️ Зам. нач. цеха Жук С.Г.: +375(17) 334-44-45\n");
        for (String s : dyn.getOrDefault(1, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n👷‍♂ <b>ИТР УЛКС №2</b>\n▪️ Нач. УЛКС №2 Лазуко С.В.: +375(17) 200-02-98, +375(29) 501-01-11\n▪ Зам. нач. УЛКС №2 Журко А.Н.: +375(17) 264-93-74, +375(29) 231-69-23\n▪ Рук. каб. группы Прохорчик В.Ю.: +375(17) 200-40-40, +375(29) 176-58-88\n▪️ Рук. гр. технадзора Снитко А.В.: +375(33) 347-28-85\n▪️ Рук. гр. малопарщиков Никитин Д.Д.: +375(44) 752-02-69\n▪ Рук. изм. группы Лабуза С.И.: +375(29) 701-88-29\n");
        for (String s : dyn.getOrDefault(2, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n💻 <b>Технический отдел</b>\n▪ Рук. гр. технадзора Шашков В.П.: +375(29) 373-35-55\n▪️ Инж. технадзора Кононова Светлана: +375(29) 577-57-46\n▪ Магистральщики (Протасевич Юлия): +375(29) 560-80-82\n▪️ Профсоюзные дела (Луговцова Оксана): +375(29) 317-19-56\n");
        for (String s : dyn.getOrDefault(3, new ArrayList<>())) sb.append("▪️️ ").append(s).append("\n");
        sb.append("\n🗂 <b>Администрация</b>\n▪️ Профком (Нестерова Е.Ю.): +375(17) 359-45-70\n▪️ Бухгалтерия по ЗП (Ромашко Ю.Г.): +375(17) 359-45-20\n▪️ Специалист по кадрам (Субач Е.В.): +375(17) 359-45-03\n");
        for (String s : dyn.getOrDefault(4, new ArrayList<>())) sb.append("▪️️ ").append(s).append("\n");
        sb.append("\n🚗 <b>АТЦ</b>\n▪️ Начальник Савицкий А.В.: +375(17) 369-05-25, +375(33) 603-32-73\n▪️ Зам. начальника Болбас Р.А.: +375(17) 369-03-93, +375(29) 840-78-37\n▪️ Инж. по БД Гузов А.П.: +375(17) 369-05-27, +375(29) 779-11-88\n");
        for (String s : dyn.getOrDefault(5, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n🔌 <b>Станционщики (магистраль / проключения)</b>\n▪️ Инженер Ивашкевич Дарья: +375(29) 555-38-48\n");
        for (String s : dyn.getOrDefault(6, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n📺 <b>Привязка СМЛ приставок</b>\n▪️ Дневное время (Ольга): +375(29) 788-84-98\n▪️ Вечернее время: +375(17) 334-56-24, +375(17) 328-46-56, +375(17) 252-44-55, +375(17) 359-41-10\n");
        for (String s : dyn.getOrDefault(7, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n🎧 <b>Диспетчера и админы (закрытие заявок)</b>\n▪ Диспетчеры ЦАБР: +375(17) 288-47-10\n▪️ Инженер ЦАБР: +375(17) 268-46-26\n▪ Админы: +375(17) 306-29-56, +375(33) 603-38-81\n▪ После 20:00: +375(17) 306-29-59\n▪ VPN: +375(17) 203-66-86\n");
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
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        message.setParseMode("HTML");
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup(); markup.setResizeKeyboard(true);
        KeyboardRow row = new KeyboardRow(); row.add("🔙 Назад"); row.add("❌ Отменить");
        markup.setKeyboard(List.of(row)); message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) {}
    }

    private void handleWriteOffStep(long chatId, String firstName, String role, String text) {
        WriteOffSession s = writeOffSessions.get(chatId);
        switch (s.step) {
            case "WAIT_QTY" -> {
                try {
                    double qty = Double.parseDouble(text.trim().replace(",", "."));
                    if (qty <= 0 || qty > s.maxAvailable + 1e-9) { sendCancelKeyboard(chatId, "❌ Недопустимое количество."); return; }
                    s.quantity = qty; s.step = "WAIT_TYPE"; sendTypeButtons(chatId, s);
                } catch (Exception e) { sendCancelKeyboard(chatId, "❌ Введите количество числом."); }
            }
            case "WAIT_RECEIPT_NUM" -> { s.receiptNumber = text.trim(); s.step = "WAIT_PAID_PHONE"; sendCancelKeyboard(chatId, "🧾 Введите <b>номер телефона</b>:"); }
            case "WAIT_PAID_PHONE" -> { s.phoneNumber = text.trim(); s.step = "WAIT_PAID_CONTRACT"; sendCancelKeyboard(chatId, "🧾 Введите <b>номер договора</b>:"); }
            case "WAIT_PAID_CONTRACT" -> { s.contractNumber = text.trim(); s.step = "WAIT_PAID_ADDRESS"; sendCancelKeyboard(chatId, "🧾 Введите <b>адрес абонента</b>:"); }
            case "WAIT_PAID_ADDRESS" -> {
                s.address = text.trim(); writeOffSessions.remove(chatId);
                String res = DatabaseManager.completeWriteOff(chatId, s); sendMenu(chatId, role, res);
                if (!"ADMIN".equals(role)) notifyAdminsForAction(chatId, "🧾 <b>ВНИМАНИЕ! Выбита квитанция:</b>\n" + res);
            }
            case "WAIT_FREE_PHONE" -> { s.phoneNumber = text.trim(); s.step = "WAIT_FREE_CONTRACT"; sendCancelKeyboard(chatId, "🛠 Введите <b>номер договора</b> (или -):"); }
            case "WAIT_FREE_CONTRACT" -> { s.contractNumber = text.trim(); s.step = "WAIT_FREE_ADDRESS"; sendCancelKeyboard(chatId, "🛠 Введите <b>адрес</b>:"); }
            case "WAIT_FREE_ADDRESS" -> { s.address = text.trim(); s.step = "WAIT_CLOSING_CODE"; sendClosingCodeButtons(chatId, s.phoneNumber, s.contractNumber); }
        }
    }

    private void sendTypeButtons(long chatId, WriteOffSession s) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(createBtn("🧾 Списать по квитанции", "WO_TYPE:PAID")),
                List.of(createBtn("🛠 Техническое списание", "WO_TYPE:FREE")),
                List.of(createBtn("↩️ Вернуть на склад", "WO_TYPE:RETURN"))
        ));
        SendMessage msg = new SendMessage(String.valueOf(chatId), String.format("Количество: <b>%s %s</b>.\nЧто делаем с материалом?", DatabaseManager.fmtQty(s.quantity), s.unit));
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
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
        List<String[]> userMats = DatabaseManager.getUserMaterialsForWriteOff(chatId);
        if (userMats.isEmpty()) { sendMenu(chatId, role, "🧰 У вас на руках нет материалов."); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] m : userMats) rows.add(List.of(createBtn(String.format("👉 %s [%s] (%s %s)", m[2].length() > 30 ? m[2].substring(0, 30) + "…" : m[2], m[1], m[4], m[3]), "WO_MAT:" + m[0])));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "📝 <b>Выберите материал из вашего подотчета:</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendClosingCodeButtons(long chatId, String phone, String contract) {
        String warning = DatabaseManager.checkCode212History(phone, contract);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(createBtn("212 — Участок ОРК–ОРА (не чаще 6 мес!)", "WO_CODE:212")),
                List.of(createBtn("227 — Выправление волокна (повтор)", "WO_CODE:227")),
                List.of(createBtn("215 — В ОРШ: выправление пигтейла", "WO_CODE:215")),
                List.of(createBtn("226 — В ОРШ: замена адаптера", "WO_CODE:226")),
                List.of(createBtn("214 — Участок ОРШ–ОРК: ремонт райзера", "WO_CODE:214")),
                List.of(createBtn("217 — Участок ОРШ–ОРК: запасной модуль", "WO_CODE:217"))
        ));
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🔢 <b>Шаг 4 из 5:</b> Выберите <b>код закрытия заявки</b>:" + warning);
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
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

    private void sendCartMenu(long chatId, String role) {
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
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), sb.toString()); msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendServiceSelectionForCart(long chatId) {
        List<String[]> services = DatabaseManager.getAllServicesForReceipt();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] srv : services) rows.add(List.of(createBtn("🛠 " + (srv[1].length() > 35 ? srv[1].substring(0, 35) + "…" : srv[1]), "CART_SEL_SRV:" + srv[0])));
        rows.add(List.of(createBtn("🔙 Назад в корзину", "CART_MENU")));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🛠 <b>Выберите выполненную услугу:</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendMaterialSelectionForCart(long chatId, String role) {
        List<String[]> userMats = DatabaseManager.getUserMaterialsForWriteOff(chatId);
        if (userMats.isEmpty()) { sendMenu(chatId, role, "🧰 У вас нет материалов в подотчете."); sendCartMenu(chatId, role); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] m : userMats) rows.add(List.of(createBtn("📦 " + (m[2].length() > 30 ? m[2].substring(0, 30) + "…" : m[2]) + " (" + m[4] + " " + m[3] + ")", "CART_SEL_MAT:" + m[0])));
        rows.add(List.of(createBtn("🔙 Назад в корзину", "CART_MENU")));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "📦 <b>Выберите использованный материал:</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
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

    public void sendMenu(long chatId, String role, String text) {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup(); keyboardMarkup.setResizeKeyboard(true);
        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow r1 = new KeyboardRow(); r1.add("📦 Склад (Наличие и цены)"); r1.add("🧰 Мой подотчет"); keyboard.add(r1);
        KeyboardRow r2 = new KeyboardRow(); r2.add("📝 Списать / Вернуть"); r2.add("🔌 Сварочные аппараты"); keyboard.add(r2);
        KeyboardRow r3 = new KeyboardRow(); r3.add("🧾 Калькулятор квитанции"); r3.add("📋 Тарифы услуг"); keyboard.add(r3);
        KeyboardRow r4 = new KeyboardRow(); r4.add("📸 Плановый осмотр ОРШ"); r4.add("🗓 График работ"); keyboard.add(r4);
        KeyboardRow r5 = new KeyboardRow(); r5.add("🔢 Коды закрытия"); r5.add("📞 Справочник"); keyboard.add(r5);
        if ("ADMIN".equals(role)) {
            KeyboardRow a1 = new KeyboardRow(); a1.add("📊 У кого что на руках"); a1.add("🛠 Управление инструментом"); keyboard.add(a1);
            KeyboardRow a2 = new KeyboardRow(); a2.add("📑 Скачать отчет за месяц"); a2.add("📊 Статистика ОРШ"); keyboard.add(a2);
            KeyboardRow a3 = new KeyboardRow(); a3.add("📢 Сделать рассылку"); a3.add("👥 Пользователи"); keyboard.add(a3);
            KeyboardRow a4 = new KeyboardRow(); a4.add("📥 Загрузки (Excel)"); keyboard.add(a4);
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
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "📸 <b>Осталось проверить: " + list.size() + " шт.</b>\nВыберите адрес:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendOrshDetails(long chatId, int orshId) {
        String[] orsh = DatabaseManager.getOrshById(orshId);
        if (orsh == null) { sendMenu(chatId, "WORKER", "❌ Шкаф не найден."); return; }
        waitingOrshPhoto.put(chatId, orshId);
        sendCancelKeyboard(chatId, "Подготовка к осмотру...");
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(createBtn("⚠ Невозможно сделать фото", "ORSH_PROB:" + orshId))));
        SendMessage msg = new SendMessage(String.valueOf(chatId), String.format("📸 <b>Выбран ОРШ-%s</b>\n📍 Адрес: %s\n🧭 Местоположение: %s\n\n👇 <b>Отправьте фото!</b>", orsh[0], orsh[1], orsh[2]));
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
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

    private void sendToolAdminMenu(long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(
                List.of(createBtn("🤝 Выдать мастеру", "TOOL_MENU_ASSIGN")),
                List.of(createBtn("↩️ Забрать на склад", "TOOL_MENU_RETURN")),
                List.of(createBtn("🗑 Списать (поломка)", "TOOL_MENU_WRITEOFF")),
                List.of(createBtn("📊 Общая сводка", "TOOL_MENU_AUDIT_ALL")),
                List.of(createBtn("👤 Сводка по людям", "TOOL_MENU_AUDIT_USERS")),
                List.of(createBtn("📢 Рассылка: Аудит остатков", "TOOL_MENU_AUDIT_NOTIFY")),
                List.of(createBtn("🗄 Архив списанного", "TOOL_MENU_ARCHIVE")),
                List.of(createBtn("♻️ Восстановить из архива", "TOOL_MENU_RESTORE"))
        ));
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🛠 <b>УПРАВЛЕНИЕ ИНСТРУМЕНТОМ</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUsersToolSummaryMenu(long chatId) {
        List<String[]> users = DatabaseManager.getUsersWithToolCounts();
        if (users.isEmpty()) { sendMenu(chatId, "ADMIN", "ℹ️ Инструмента на руках нет."); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) rows.add(List.of(createBtn(String.format("👤 %s (%s шт.)", u[1], u[2]), "T_SUMM_U:" + u[0])));
        rows.add(List.of(createBtn("🔙 Назад", "TOOL_MAIN_MENU")));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "👤 <b>Сводка по людям</b>\nВыберите сотрудника:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUserToolSummary(long chatId, long targetUserId) {
        List<String[]> tools = DatabaseManager.getUserAssignedToolsForReturn(targetUserId);
        String userName = DatabaseManager.getUserFullName(targetUserId);
        if (tools.isEmpty()) { sendMenu(chatId, "ADMIN", "ℹ️ У <b>" + userName + "</b> нет инструмента."); sendUsersToolSummaryMenu(chatId); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) {
            String inv = (t[2] != null && !t[2].isEmpty() && !t[2].equals("null")) ? " [" + t[2] + "]" : "";
            rows.add(List.of(createBtn(String.format("↩️ Забрать: %s%s", t[1].length() > 15 ? t[1].substring(0, 15) + "…" : t[1], inv), "T_SUMM_RET:" + t[0] + ":" + targetUserId)));
        }
        rows.add(List.of(createBtn("🚨 Вернуть ВСЁ на склад", "T_SUMM_RALL:" + targetUserId)));
        rows.add(List.of(createBtn("🔙 Назад к списку", "TOOL_MENU_AUDIT_USERS")));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "👤 <b>Инструмент: " + userName + "</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendAvailableToolsForAssignment(long chatId) {
        List<String[]> groups = DatabaseManager.getAvailableToolGroups();
        if (groups.isEmpty()) { sendMenu(chatId, "ADMIN", "📦 На складе нет свободного инструмента."); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] g : groups) rows.add(List.of(createBtn((g[1].length() > 30 ? g[1].substring(0, 30) + "…" : g[1]) + " (в наличии: " + g[2] + " шт)", "T_SEL_N:" + g[0])));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🤝 <b>Шаг 1 из 2: Выберите инструмент:</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUsersForToolAssignment(long chatId, int toolId) {
        List<String[]> users = DatabaseManager.getUsersForToolAssignment();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) rows.add(List.of(createBtn(("ADMIN".equals(u[2]) ? "👑 " : "👷‍♂️ ") + u[1], "T_ASS_U:" + toolId + ":" + u[0])));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🪛 Вы выбрали: <b>" + DatabaseManager.getToolNameAndInvById(toolId) + "</b>\n\n👤 <b>Шаг 2 из 2: Кому выдать?</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUsersForToolReturn(long chatId) {
        List<String[]> users = DatabaseManager.getUsersWithAssignedTools();
        if (users.isEmpty()) { sendMenu(chatId, "ADMIN", "ℹ️ Нет инструмента на руках."); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) rows.add(List.of(createBtn("👤 " + u[1], "T_RET_U:" + u[0])));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "↩️ <b>У кого забираем инструмент?</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUserToolsForReturn(long chatId, long targetUserId) {
        List<String[]> tools = DatabaseManager.getUserAssignedToolsForReturn(targetUserId);
        if (tools.isEmpty()) { sendMenu(chatId, "ADMIN", "У сотрудника нет инструмента."); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) rows.add(List.of(createBtn("🪛 " + (t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1]) + " (Инв: " + t[2] + ")", "T_RET_T:" + t[0])));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "↩️ <b>Какой инструмент возвращаем?</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUsersForToolWriteOff(long chatId) {
        List<String[]> users = DatabaseManager.getUsersWithAssignedTools();
        if (users.isEmpty()) { sendMenu(chatId, "ADMIN", "ℹ️ Нет инструмента на руках."); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) rows.add(List.of(createBtn("👤 " + u[1], "T_WO_U:" + u[0])));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗑 <b>У кого списываем инструмент?</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUserToolsForWriteOff(long chatId, long targetUserId) {
        List<String[]> tools = DatabaseManager.getUserAssignedToolsForReturn(targetUserId);
        if (tools.isEmpty()) { sendMenu(chatId, "ADMIN", "У сотрудника нет инструмента."); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) rows.add(List.of(createBtn("🪛 " + (t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1]) + " (Инв: " + t[2] + ")", "T_WO_DO:" + t[0])));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗑 <b>Какой инструмент списываем?</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendGroupsForToolWriteOff(long chatId) {
        List<String[]> groups = DatabaseManager.getAvailableToolGroups();
        if (groups.isEmpty()) { sendMenu(chatId, "ADMIN", "📦 На складе нет инструмента."); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] g : groups) rows.add(List.of(createBtn((g[1].length() > 30 ? g[1].substring(0, 30) + "…" : g[1]) + " (" + g[2] + " шт)", "T_WO_G:" + g[0])));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗑 <b>Выберите категорию на складе:</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendSpecificToolsInStockForWriteOff(long chatId, int firstId) {
        List<String[]> tools = DatabaseManager.getToolsInStockByGroup(String.valueOf(firstId));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) rows.add(List.of(createBtn("🪛 " + (t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1]) + " (Инв: " + t[2] + ")", "T_WO_DO:" + t[0])));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗑 <b>Выберите единицу для списания:</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendToolsForRestore(long chatId) {
        List<String[]> tools = DatabaseManager.getWrittenOffToolsForRestore();
        if (tools.isEmpty()) { sendMenu(chatId, "ADMIN", "🗄 В архиве пусто."); return; }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) rows.add(List.of(createBtn("♻ " + (t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1]) + " (Инв: " + t[2] + ")", "T_RES_DO:" + t[0])));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "♻️ <b>Какой инструмент восстановить?</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendMyInventoryMenu(long chatId, String text) {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📦 Мои материалы");
        row1.add("🪛 Мой инструмент");
        KeyboardRow row2 = new KeyboardRow();
        row2.add("🔙 Назад");
        markup.setKeyboard(List.of(row1, row2));
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        message.setParseMode("HTML");
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) {}
    }

    private void sendColleagueSelectionMenu(long chatId, List<String> names) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String name : names) rows.add(List.of(createBtn("👤 " + name, "VIEW_SCHED:" + name)));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "👁 <b>График коллеги</b>\nВыберите сотрудника:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup); try { execute(msg); } catch (TelegramApiException e) {}
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

    public Map<Long, Integer> getForceWelderReturnIds() { return forceWelderReturnIds; }
}