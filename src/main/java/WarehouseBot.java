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
    // НОВЫЙ БЛОК: СВАРОЧНЫЕ АППАРАТЫ (ОТРИСОВКА ИНТЕРФЕЙСА)
    // =========================================================================================

    private void sendWeldersMenu(long chatId, String role) {
        List<String[]> welders = DatabaseManager.getWeldersStatus();

        StringBuilder sb = new StringBuilder();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        boolean hasBusy = false;
        String myBusyWelderId = null;
        String myBusyWelderName = null;

        for (String[] w : welders) {
            String wId = w[0];
            String wName = w[1];
            String status = w[2];
            String userName = w[3];
            // w[4] - time (здесь не выводим, чтобы не перегружать, но можно добавить)
            String assignedToId = w.length > 5 ? w[5] : "";

            if ("IN_USE".equals(status)) {
                if (!hasBusy) {
                    sb.append("🔴 <b>В работе у коллег:</b>\n");
                    hasBusy = true;
                }
                sb.append("▫️ ").append(wName).append(" 👉 у <b>").append(userName).append("</b>\n");

                if (String.valueOf(chatId).equals(assignedToId)) {
                    myBusyWelderId = wId;
                    myBusyWelderName = wName;
                }
            }
        }

        if (myBusyWelderId != null) {
            sb.insert(0, "⚠️ <b>На вас сейчас числится: " + myBusyWelderName + "</b>\n\n");

            InlineKeyboardButton retBtn = new InlineKeyboardButton("↩️ ВЕРНУТЬ " + myBusyWelderName.toUpperCase() + " НА БАЗУ");
            retBtn.setCallbackData("W_RET:" + myBusyWelderId);
            rows.add(List.of(retBtn));

            sb.append("\n👇 <b>Управление и свободные аппараты:</b>");
        } else {
            sb.append("\n👇 <b>Выберите свободный аппарат, который берете с базы:</b>");
        }

        // Кнопки для свободных аппаратов
        for (String[] w : welders) {
            if ("ON_BASE".equals(w[2])) {
                InlineKeyboardButton takeBtn = new InlineKeyboardButton("🔌 " + w[1]);
                takeBtn.setCallbackData("W_TAKE:" + w[0]);
                rows.add(List.of(takeBtn));
            }
        }

        // Админские кнопки принудительного управления
        if ("ADMIN".equals(role)) {
            InlineKeyboardButton histBtn = new InlineKeyboardButton("📜 История логов");
            histBtn.setCallbackData("W_HISTORY");

            InlineKeyboardButton forceTakeBtn = new InlineKeyboardButton("➕ Выдать принудительно");
            forceTakeBtn.setCallbackData("W_FORCE_TAKE_M");

            InlineKeyboardButton forceRetBtn = new InlineKeyboardButton("⚠️ Вернуть принудительно");
            forceRetBtn.setCallbackData("W_FORCE_RET_M");

            rows.add(List.of(histBtn));
            rows.add(List.of(forceTakeBtn, forceRetBtn));
        }

        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage(String.valueOf(chatId), sb.toString());
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (Exception e) { e.printStackTrace(); }
    }

    private void sendWeldersForceAssignMenu(long chatId) {
        List<String[]> welders = DatabaseManager.getWeldersStatus();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (String[] w : welders) {
            if ("ON_BASE".equals(w[2])) {
                rows.add(List.of(createBtn("🔌 " + w[1], "W_F_SEL:" + w[0])));
            }
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

        for (String[] u : users) {
            rows.add(List.of(createBtn("👤 " + u[1], "W_F_ASS:" + welderId + ":" + u[0])));
        }
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
            if ("IN_USE".equals(w[2])) {
                rows.add(List.of(createBtn("⚠️ Списать: " + w[1] + " (у " + w[3] + ")", "W_F_RET:" + w[0])));
            }
        }
        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage(String.valueOf(chatId), "⚠️ <b>Принудительный возврат на базу</b>\nВыберите аппарат, который хотите списать с сотрудника:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    // =========================================================================================

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

            InlineKeyboardButton btnApprove = new InlineKeyboardButton("✅ Одобрить (Мастер)");
            btnApprove.setCallbackData("NEW_USER_APP:" + newUserId);
            InlineKeyboardButton btnReject = new InlineKeyboardButton("❌ Заблокировать");
            btnReject.setCallbackData("NEW_USER_REJ:" + newUserId);
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(btnApprove, btnReject)));

            String text = String.format("👤 <b>Новый пользователь ждет доступа!</b>\n\nИмя: <b>%s</b>\nID: <code>%d</code>\n\nРазрешить доступ?", newUserName, newUserId);
            SendMessage msg = new SendMessage(String.valueOf(chatId), text);
            msg.setParseMode("HTML");
            msg.setReplyMarkup(markup);
            try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
        }

        for (String[] pr : pendingReturns) {
            int reqId = Integer.parseInt(pr[0]);
            String workerName = pr[1];
            String matName = pr[2];
            String code = pr[3];
            String qty = DatabaseManager.fmtQty(Double.parseDouble(pr[4]));
            String unit = pr[5];

            String alertMsg = String.format("🔔 <b>Запрос на возврат на склад (№%d)</b>\n\n👤 Сотрудник: <b>%s</b>\n📦 Материал: <b>%s</b>\n🔢 Инв. №: <code>%s</code>\n↩️ Количество к возврату: <b>%s %s</b>", reqId, workerName, matName, code, qty, unit);

            InlineKeyboardButton btnApprove = new InlineKeyboardButton("✅ Принять на склад");
            btnApprove.setCallbackData("RET_APP:" + reqId);
            InlineKeyboardButton btnReject = new InlineKeyboardButton("❌ Отклонить");
            btnReject.setCallbackData("RET_REJ:" + reqId);
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(btnApprove, btnReject)));

            SendMessage msg = new SendMessage(String.valueOf(chatId), alertMsg);
            msg.setParseMode("HTML");
            msg.setReplyMarkup(markup);
            try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
        }
    }

    // Метод-файрвол: блокирует все действия, пока сотрудник не выберет фамилию
    private boolean checkUnboundAndNotify(long chatId, String role) {
        // Админов не блокируем, иначе вы не сможете загрузить сам график с фамилиями
        if ("ADMIN".equals(role)) return false;

        String excelName = DatabaseManager.getUserExcelName(chatId);
        if (excelName == null) {
            List<String> availableNames = DatabaseManager.getAvailableExcelNames();
            if (availableNames.isEmpty()) {
                sendDirectNotification(chatId, "⚠ <b>Внимание!</b>\nВам необходимо идентифицировать себя, но администратор еще не загрузил график с новыми фамилиями. Пожалуйста, обратитесь к нему напрямую.");
            } else {
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                for (String name : availableNames) {
                    InlineKeyboardButton btn = new InlineKeyboardButton(name);
                    btn.setCallbackData("BIND_NAME:" + name);
                    rows.add(List.of(btn));
                }
                markup.setKeyboard(rows);

                String alertText = "⚠️ <b>ОГРАНИЧЕНИЕ ДОСТУПА!</b>\n\n" +
                        "Остальной функционал бота временно заблокирован.\n" +
                        "👇 <b>Пожалуйста, выберите СВОЮ настоящую фамилию из списка ниже, чтобы продолжить работу:</b>";

                SendMessage msg = new SendMessage(String.valueOf(chatId), alertText);
                msg.setParseMode("HTML");
                msg.setReplyMarkup(markup);
                try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
            }
            return true; // Возвращаем true -> блокируем выполнение остального кода
        }
        return false;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasDocument()) {
            long chatId = update.getMessage().getChatId();
            String firstName = update.getMessage().getFrom().getFirstName();
            String role = checkRoleAndNotify(chatId, firstName);

            if ("BANNED".equals(role) || "PENDING".equals(role)) return;
            if (checkUnboundAndNotify(chatId, role)) return; // <-- Файрвол документов

            if (!"ADMIN".equals(role)) {
                sendMenu(chatId, role, "❌ Загружать файлы может только Администратор (МОЛ).");
                return;
            }
            if (isAdminBlockedByPendingRequests(chatId, role)) return;

            String state = fileWaitState.getOrDefault(chatId, "");
            if (state.isEmpty()) {
                sendMenu(chatId, role, "Сначала нажмите кнопку «📥 Загрузить ведомость» или «📥 Загрузить график», а затем отправьте файл.");
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
                else if ("SCHEDULE".equals(state)) result = ExcelImporter.importScheduleSheet(localFile);
                else if ("TOOLS".equals(state)) result = ExcelImporter.importToolsSheet(localFile);
                else if ("ORSH".equals(state)) result = ExcelImporter.importOrshSheet(localFile);
                else result = "❌ Неизвестное состояние загрузки файла.";

                localFile.delete();
                fileWaitState.remove(chatId);
                sendMenu(chatId, role, result);
            } catch (Exception e) {
                e.printStackTrace();
                sendMenu(chatId, role, "❌ Ошибка при скачивании файла: " + e.getMessage());
            }
            return;
        }

        if (update.hasMessage() && update.getMessage().hasPhoto()) {
            long chatId = update.getMessage().getChatId();
            String firstName = update.getMessage().getFrom().getFirstName();
            String role = checkRoleAndNotify(chatId, firstName);

            if ("BANNED".equals(role) || "PENDING".equals(role)) return;
            if (checkUnboundAndNotify(chatId, role)) return; // <-- Файрвол для фото

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

        // =========================================================
        // ОБРАБОТКА ПОДЕЛИТЬСЯ КОНТАКТОМ (ИЗ ТЕЛЕФОННОЙ КНИГИ)
        // =========================================================
        if (update.hasMessage() && update.getMessage().hasContact()) {
            long chatId = update.getMessage().getChatId();
            String firstName = update.getMessage().getFrom().getFirstName();
            String role = checkRoleAndNotify(chatId, firstName);

            if ("BANNED".equals(role) || "PENDING".equals(role)) return;
            if (checkUnboundAndNotify(chatId, role)) return; // Файрвол

            if (waitingDirContactCat.containsKey(chatId)) {
                int category = waitingDirContactCat.remove(chatId);

                // Достаем объект контакта
                org.telegram.telegrambots.meta.api.objects.Contact contact = update.getMessage().getContact();

                // Склеиваем имя и фамилию
                String contactName = contact.getFirstName();
                if (contact.getLastName() != null && !contact.getLastName().isEmpty()) {
                    contactName += " " + contact.getLastName();
                }

                // Достаем телефон (Телеграм иногда отдает номер без плюса в начале, исправляем это)
                String contactPhone = contact.getPhoneNumber();
                if (!contactPhone.startsWith("+")) {
                    contactPhone = "+" + contactPhone;
                }

                // Формируем красивую строку
                String textToSave = contactName + ": " + contactPhone;

                if (DatabaseManager.addDirectoryContact(category, textToSave, chatId)) {
                    sendMenu(chatId, role, "✅ <b>Контакт успешно добавлен из телефонной книги!</b>\nОн теперь отображается в справочнике у всех сотрудников.");
                    sendDirectory(chatId, role); // Сразу показываем обновленный справочник
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

            if (data.equals("DIR_ADD_MENU")) {
                sendDirectoryAddMenu(chatId);
                return;
            }
            if (data.startsWith("DIR_CAT:")) {
                int category = Integer.parseInt(data.split(":")[1]);
                waitingDirContactCat.put(chatId, category);
                sendCancelKeyboard(chatId, "✍️️ <b>Отлично!</b>\n\nВы можете <b>поделиться контактом</b> из телефонной книги (📎 Скрепка ➔ Контакт)\n\n👇 ИЛИ напишите данные вручную одним сообщением:\n<i>Пример: Электрик Иванов Иван: +375(29)111-22-33</i>");
                return;
            }
            if (data.equals("DIR_DEL_MENU") && "ADMIN".equals(role)) {
                sendDirectoryDelMenu(chatId);
                return;
            }
            if (data.startsWith("DIR_DEL:") && "ADMIN".equals(role)) {
                int contactId = Integer.parseInt(data.split(":")[1]);
                DatabaseManager.deleteDirectoryContact(contactId);
                sendDirectoryDelMenu(chatId); // Обновляем список после удаления
                return;
            }
            if (data.equals("DIR_BACK")) {
                sendDirectory(chatId, role);
                return;
            }

            // =========================================================
            // СВАРОЧНЫЕ АППАРАТЫ: ОБРАБОТКА КНОПОК
            // =========================================================

            if (data.startsWith("W_TAKE:")) {
                int welderId = Integer.parseInt(data.split(":")[1]);
                if (DatabaseManager.takeWelder(welderId, chatId, null)) {
                    sendMenu(chatId, role, "✅ Вы успешно взяли сварочный аппарат!");
                    if (chatId != DatabaseManager.ADMIN_ID) {
                        String userName = DatabaseManager.getUserFullName(chatId);
                        String welderName = DatabaseManager.getWelderNameById(welderId);
                        sendDirectNotification(DatabaseManager.ADMIN_ID,
                                "ℹ️ <b>Сварочный аппарат взят!</b>\n👷‍♂ " + userName + " только что взял аппарат <b>" + welderName + "</b>.");
                    }
                    sendWeldersMenu(chatId, role);
                } else {
                    sendMenu(chatId, role, "❌ Ошибка: аппарат уже занят или не существует.");
                }
                return;
            }

            if (data.startsWith("W_RET:")) {
                int welderId = Integer.parseInt(data.split(":")[1]);
                if (DatabaseManager.returnWelder(welderId, chatId, null)) {
                    forceWelderReturnIds.remove(chatId); // Снимаем жесткую блокировку, если она была
                    sendMenu(chatId, role, "✅ Вы успешно вернули сварочный аппарат на базу!");
                    sendWeldersMenu(chatId, role);
                }
                return;
            }

            if (data.equals("W_KEEP_TOMORROW")) {
                forceWelderReturnIds.remove(chatId); // Снимаем жесткую блокировку
                sendMenu(chatId, role, "🌙 Вы оставили сварочный аппарат за собой на завтра. Блокировка снята.");
                return;
            }

            if (data.equals("W_HISTORY") && "ADMIN".equals(role)) {
                sendMenu(chatId, role, DatabaseManager.getWeldersHistoryText());
                return;
            }

            if (data.equals("W_FORCE_TAKE_M") && "ADMIN".equals(role)) {
                sendWeldersForceAssignMenu(chatId);
                return;
            }

            if (data.startsWith("W_F_SEL:") && "ADMIN".equals(role)) {
                sendWeldersForceAssignUsers(chatId, Integer.parseInt(data.split(":")[1]));
                return;
            }

            if (data.startsWith("W_F_ASS:") && "ADMIN".equals(role)) {
                String[] parts = data.split(":");
                int welderId = Integer.parseInt(parts[1]);
                long targetUserId = Long.parseLong(parts[2]);

                if (DatabaseManager.takeWelder(welderId, targetUserId, chatId)) {
                    String welderName = DatabaseManager.getWelderNameById(welderId);
                    sendMenu(chatId, role, "✅ Аппарат <b>" + welderName + "</b> принудительно выдан.");
                    sendDirectNotification(targetUserId, "🔔 <b>Администратор выдал вам сварочный аппарат!</b>\nЗа вами закреплен: <b>" + welderName + "</b>");
                    sendWeldersMenu(chatId, role);
                } else {
                    sendMenu(chatId, role, "❌ Ошибка при принудительной выдаче.");
                }
                return;
            }

            if (data.equals("W_FORCE_RET_M") && "ADMIN".equals(role)) {
                sendWeldersForceReturnMenu(chatId);
                return;
            }

            if (data.startsWith("W_F_RET:") && "ADMIN".equals(role)) {
                int welderId = Integer.parseInt(data.split(":")[1]);
                if (DatabaseManager.returnWelder(welderId, chatId, chatId)) { // chatId передаем дважды (и как userId, и как adminId)
                    String welderName = DatabaseManager.getWelderNameById(welderId);
                    sendMenu(chatId, role, "⚠️ Аппарат <b>" + welderName + "</b> принудительно списан на базу.");
                    sendWeldersMenu(chatId, role);
                } else {
                    sendMenu(chatId, role, "❌ Ошибка при принудительном возврате.");
                }
                return;
            }

            // =========================================================

            if (data.startsWith("F_ASS:") && "ADMIN".equals(role)) {
                String[] parts = data.split(":");
                int firstId = Integer.parseInt(parts[1]);
                long targetUserId = Long.parseLong(parts[2]);

                String[] res = DatabaseManager.assignAnyAvailableToolFast(firstId, targetUserId);

                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                answer.setText(res[1]);
                answer.setShowAlert(false);
                try { execute(answer); } catch (TelegramApiException e) { e.printStackTrace(); }

                if ("OK".equals(res[0])) {
                    sendDirectNotification(targetUserId, res[2]);
                }
                return;
            }

            if (data.startsWith("ORSH_SEL:")) {
                int orshId = Integer.parseInt(data.split(":")[1]);
                sendOrshDetails(chatId, orshId);
                return;
            }
            if (data.startsWith("ORSH_PROB:")) {
                int orshId = Integer.parseInt(data.split(":")[1]);
                waitingOrshPhoto.remove(chatId);
                waitingOrshProblemReason.put(chatId, orshId);
                sendCancelKeyboard(chatId, "⚠️ <b>Невозможно сделать фото!</b>\nНапишите причину (например: затоплен подвал, нет ключа, спилен замок):");
                return;
            }

            if (data.equals("TOOL_MENU_RETURN") && "ADMIN".equals(role)) {
                sendUsersForToolReturn(chatId);
                return;
            }
            if (data.equals("TOOL_MENU_ARCHIVE") && "ADMIN".equals(role)) {
                sendMenu(chatId, role, DatabaseManager.getWrittenOffToolsArchiveText());
                return;
            }
            if (data.equals("TOOL_MENU_RESTORE") && "ADMIN".equals(role)) {
                sendToolsForRestore(chatId);
                return;
            }

            if (data.startsWith("T_RES_DO:") && "ADMIN".equals(role)) {
                int toolId = Integer.parseInt(data.split(":")[1]);
                String result = DatabaseManager.restoreToolToStock(toolId);
                sendMenu(chatId, role, result);
                return;
            }
            if (data.equals("TOOL_MENU_WRITEOFF") && "ADMIN".equals(role)) {
                InlineKeyboardButton btnStock = new InlineKeyboardButton("📦 Со склада");
                btnStock.setCallbackData("T_WO_LOC:STOCK");
                InlineKeyboardButton btnUser = new InlineKeyboardButton("👤 У сотрудника");
                btnUser.setCallbackData("T_WO_LOC:ASSIGNED");
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(btnStock, btnUser)));
                SendMessage msg = new SendMessage(String.valueOf(chatId), "🗑 <b>Где сейчас находится инструмент, который нужно списать?</b>");
                msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
                try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
                return;
            }

            if (data.startsWith("T_WO_LOC:") && "ADMIN".equals(role)) {
                if (data.split(":")[1].equals("STOCK")) sendGroupsForToolWriteOff(chatId);
                else sendUsersForToolWriteOff(chatId);
                return;
            }
            if (data.startsWith("T_WO_U:") && "ADMIN".equals(role)) {
                sendUserToolsForWriteOff(chatId, Long.parseLong(data.split(":")[1]));
                return;
            }
            if (data.startsWith("T_WO_G:") && "ADMIN".equals(role)) {
                sendSpecificToolsInStockForWriteOff(chatId, Integer.parseInt(data.split(":")[1]));
                return;
            }
            if (data.startsWith("T_WO_DO:") && "ADMIN".equals(role)) {
                int toolId = Integer.parseInt(data.split(":")[1]);
                waitingToolWriteOffReason.put(chatId, toolId);
                sendCancelKeyboard(chatId, "✍️ Выбран инструмент: <b>" + DatabaseManager.getToolNameAndInvById(toolId) + "</b>\n\nВведите <b>причину списания</b> (например: утерян, сломался, акт №12):");
                return;
            }
            if (data.startsWith("T_RET_U:") && "ADMIN".equals(role)) {
                long targetUserId = Long.parseLong(data.split(":")[1]);
                sendUserToolsForReturn(chatId, targetUserId);
                return;
            }
            if (data.startsWith("T_RET_T:") && "ADMIN".equals(role)) {
                int toolId = Integer.parseInt(data.split(":")[1]);
                String result = DatabaseManager.returnToolToWarehouse(toolId);
                sendMenu(chatId, role, result);
                return;
            }
            if (data.equals("TOOL_MAIN_MENU") && "ADMIN".equals(role)) {
                sendToolAdminMenu(chatId);
                return;
            }
            if (data.equals("TOOL_MENU_AUDIT_ALL") && "ADMIN".equals(role)) {
                sendMenu(chatId, role, DatabaseManager.getToolsAuditText());
                return;
            }
            if (data.equals("TOOL_MENU_AUDIT_USERS") && "ADMIN".equals(role)) {
                sendUsersToolSummaryMenu(chatId);
                return;
            }
            if (data.equals("TOOL_MENU_AUDIT_NOTIFY") && "ADMIN".equals(role)) {
                sendMenu(chatId, role, "⏳ Начинаю рассылку уведомлений...");
                List<Long> workersWithMaterials = DatabaseManager.getUsersWithBalances();
                int successCount = 0;
                for (Long workerId : workersWithMaterials) {
                    String balanceText = DatabaseManager.getUserBalanceText(workerId);
                    String alertMsg = "⚠️ <b>ВНИМАНИЕ: АУДИТ ОСТАТКОВ!</b> ⚠️\n\n"
                            + "Напоминаем о необходимости закрыть подотчет до конца месяца. "
                            + "Пожалуйста, <b>спишите</b> использованные материалы в квитанции/заявки "
                            + "или <b>верните</b> остатки на склад!\n\n" + balanceText;
                    try {
                        SendMessage msg = new SendMessage(String.valueOf(workerId), alertMsg);
                        msg.setParseMode("HTML");
                        execute(msg);
                        successCount++;
                    } catch (TelegramApiException e) {}
                }
                sendMenu(chatId, role, "✅ Уведомления об аудите успешно доставлены <b>" + successCount + "</b> сотрудникам!");

                // Возвращаем админ-меню обратно после завершения
                sendToolAdminMenu(chatId);
                return;
            }
            if (data.startsWith("T_SUMM_U:") && "ADMIN".equals(role)) {
                long targetUserId = Long.parseLong(data.split(":")[1]);
                sendUserToolSummary(chatId, targetUserId);
                return;
            }
            if (data.startsWith("T_SUMM_RET:") && "ADMIN".equals(role)) {
                String[] parts = data.split(":");
                int toolId = Integer.parseInt(parts[1]);
                long targetUserId = Long.parseLong(parts[2]);

                DatabaseManager.returnToolToWarehouse(toolId); // Возвращаем на склад

                // Показываем всплывающее уведомление (Toast)
                AnswerCallbackQuery answer = new AnswerCallbackQuery();
                answer.setCallbackQueryId(update.getCallbackQuery().getId());
                answer.setText("✅ Инструмент возвращен!");
                answer.setShowAlert(false);
                try { execute(answer); } catch (TelegramApiException e) {}

                // МГНОВЕННО обновляем меню пользователя (чтобы кнопка инструмента пропала)
                sendUserToolSummary(chatId, targetUserId);
                return;
            }
            if (data.startsWith("T_SUMM_RALL:") && "ADMIN".equals(role)) {
                long targetUserId = Long.parseLong(data.split(":")[1]);
                String result = DatabaseManager.returnAllUserToolsToWarehouse(targetUserId);
                sendMenu(chatId, role, result);
                sendUsersToolSummaryMenu(chatId); // Возвращаем админа к списку людей
                return;
            }
            if (data.equals("TOOL_MENU_ASSIGN") && "ADMIN".equals(role)) {
                sendAvailableToolsForAssignment(chatId);
                return;
            }
            if (data.startsWith("T_SEL_N:") && "ADMIN".equals(role)) {
                int toolId = Integer.parseInt(data.split(":")[1]);
                sendUsersForToolAssignment(chatId, toolId);
                return;
            }
            if (data.startsWith("T_ASS_U:") && "ADMIN".equals(role)) {
                String[] parts = data.split(":");
                int toolId = Integer.parseInt(parts[1]);
                long targetUserId = Long.parseLong(parts[2]);
                String result = DatabaseManager.assignTool(toolId, targetUserId);
                sendMenu(chatId, role, result);

                if (result.startsWith("✅") && targetUserId != chatId) {
                    sendDirectNotification(targetUserId, "🔔 <b>Вам выдан новый инструмент!</b>\nМОЛ закрепил за вами новую позицию. Нажмите «🪛 Мой инструмент», чтобы проверить ваш список.");
                }
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

            if (data.equals("CART_MENU")) {
                sendCartMenu(chatId, role);
                return;
            }
            if (data.equals("CART_ADD_SRV")) {
                sendServiceSelectionForCart(chatId);
                return;
            }
            if (data.equals("CART_ADD_MAT")) {
                sendMaterialSelectionForCart(chatId, role);
                return;
            }
            if (data.startsWith("CART_SEL_SRV:")) {
                int srvId = Integer.parseInt(data.split(":")[1]);
                DatabaseManager.ReceiptSession s = receiptSessions.computeIfAbsent(chatId, k -> new DatabaseManager.ReceiptSession());
                DatabaseManager.ReceiptItem item = DatabaseManager.getServiceById(srvId);
                if (item != null) {
                    if (item.isSingle) {
                        item.quantity = 1.0;
                        s.items.add(item);
                        sendCartMenu(chatId, role);
                    } else {
                        s.waitingServiceId = srvId;
                        s.waitingMaterialId = -1;
                        sendCancelKeyboard(chatId, "✍️ <b>Введите количество</b> для выбранной услуги (например: 1 или 2):");
                    }
                }
                return;
            }
            if (data.startsWith("CART_SEL_MAT:")) {
                int matId = Integer.parseInt(data.split(":")[1]);
                DatabaseManager.ReceiptSession s = receiptSessions.computeIfAbsent(chatId, k -> new DatabaseManager.ReceiptSession());
                s.waitingMaterialId = matId;
                s.waitingServiceId = -1;
                sendCancelKeyboard(chatId, "✍️ <b>Введите количество</b> израсходованного материала (например: 15 или 0.02):");
                return;
            }
            if (data.equals("CART_FINISH")) {
                DatabaseManager.ReceiptSession s = receiptSessions.get(chatId);
                if (s == null || s.items.isEmpty()) {
                    sendMenu(chatId, role, "❌ Ваша квитанция пуста. Сначала добавьте услуги или материалы.");
                } else {
                    String receiptText = DatabaseManager.generateReceiptText(s);
                    receiptSessions.remove(chatId);
                    sendMenu(chatId, role, receiptText);
                }
                return;
            }
            if (data.equals("CART_CLEAR")) {
                receiptSessions.remove(chatId);
                sendMenu(chatId, role, "🗑 Корзина очищена.");
                return;
            }

            if (data.startsWith("BIND_NAME:")) {
                String excelName = data.substring(10);
                DatabaseManager.bindUserToExcelName(chatId, excelName);

                // Убрали автоматический вывод графика, оставили только подтверждение
                sendMenu(chatId, role, "✅ Отлично! Ваш профиль успешно привязан к: <b>" + excelName + "</b>.\n\nТеперь вам полностью доступны все функции системы.");
                return;
            }

            if (data.startsWith("VIEW_SCHED:")) {
                String excelName = data.substring(11);
                // Отправляем красивый график выбранного сотрудника
                sendMenu(chatId, role, DatabaseManager.getFormattedSchedule(excelName));
                return;
            }

            if (data.startsWith("TAKE_MAT:")) {
                writeOffSessions.remove(chatId);
                int materialId = Integer.parseInt(data.split(":")[1]);
                waitingTakeMaterialId.put(chatId, materialId);
                sendCancelKeyboard(chatId, "Подготовка к выдаче...");

                SendMessage msg = new SendMessage(String.valueOf(chatId), "✍ <b>Укажите количество:</b>\nНапишите число вручную (например: <code>15</code> или <code>0.5</code>)\n👇 ИЛИ нажмите на быструю кнопку ниже:");
                msg.setParseMode("HTML");

                InlineKeyboardMarkup inlineMarkup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                rows.add(List.of(
                        createBtn("1", "TAKE_Q:1"), createBtn("2", "TAKE_Q:2"),
                        createBtn("3", "TAKE_Q:3"), createBtn("4", "TAKE_Q:4"),
                        createBtn("5", "TAKE_Q:5")
                ));
                rows.add(List.of(
                        createBtn("10", "TAKE_Q:10"), createBtn("20", "TAKE_Q:20"),
                        createBtn("50", "TAKE_Q:50"), createBtn("100", "TAKE_Q:100")
                ));
                inlineMarkup.setKeyboard(rows);
                msg.setReplyMarkup(inlineMarkup);
                try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
                return;
            }

            if (data.startsWith("TAKE_Q:")) {
                if (waitingTakeMaterialId.containsKey(chatId)) {
                    try {
                        double qty = Double.parseDouble(data.split(":")[1]);
                        int materialId = waitingTakeMaterialId.get(chatId);
                        String result = DatabaseManager.takeMaterialFromWarehouse(chatId, materialId, qty);

                        if (result.startsWith("✅")) {
                            waitingTakeMaterialId.remove(chatId);
                            sendMenu(chatId, role, result);
                        } else {
                            SendMessage msg = new SendMessage(String.valueOf(chatId), result);
                            msg.setParseMode("HTML");
                            try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                } else {
                    sendMenu(chatId, role, "❌ Ошибка: вы не выбрали материал или сессия устарела.");
                }
                return;
            }

            if (data.startsWith("RET_APP:") && "ADMIN".equals(role)) {
                int reqId = Integer.parseInt(data.split(":")[1]);
                String[] res = DatabaseManager.approveReturnRequest(reqId);
                sendMenu(chatId, role, res[2]);
                if ("OK".equals(res[0])) {
                    long workerId = Long.parseLong(res[1]);
                    if (workerId != chatId) sendDirectNotification(workerId, res[3]);
                }
                return;
            }

            if (data.startsWith("RET_REJ:") && "ADMIN".equals(role)) {
                int reqId = Integer.parseInt(data.split(":")[1]);
                String[] res = DatabaseManager.rejectReturnRequest(reqId);
                sendMenu(chatId, role, res[2]);
                if ("OK".equals(res[0])) {
                    long workerId = Long.parseLong(res[1]);
                    if (workerId != chatId) sendDirectNotification(workerId, res[3]);
                }
                return;
            }

            if (data.startsWith("WO_MAT:")) {
                waitingTakeMaterialId.remove(chatId);
                int materialId = Integer.parseInt(data.split(":")[1]);
                WriteOffSession session = DatabaseManager.createWriteOffSession(chatId, materialId);
                if (session == null) {
                    sendMenu(chatId, role, "❌ Этот материал больше не числится у вас в подотчете.");
                    return;
                }
                writeOffSessions.put(chatId, session);
                sendCancelKeyboard(chatId, "Подготовка к списанию...");

                SendMessage msg = new SendMessage(String.valueOf(chatId), String.format(
                        "Выбрано: <b>%s</b>\nДоступно у вас: <b>%s %s</b>\n\n✍️ Напишите количество вручную ИЛИ выберите быстрый вариант:",
                        session.materialName, DatabaseManager.fmtQty(session.maxAvailable), session.unit));
                msg.setParseMode("HTML");

                InlineKeyboardMarkup inlineMarkup = new InlineKeyboardMarkup();
                List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                rows.add(List.of(
                        createBtn("1", "WO_Q:1"), createBtn("2", "WO_Q:2"),
                        createBtn("5", "WO_Q:5"), createBtn("10", "WO_Q:10")
                ));
                rows.add(List.of(
                        createBtn("Всё (" + DatabaseManager.fmtQty(session.maxAvailable) + " " + session.unit + ")", "WO_Q:" + session.maxAvailable)
                ));
                inlineMarkup.setKeyboard(rows);
                msg.setReplyMarkup(inlineMarkup);
                try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
                return;
            }

            if (data.startsWith("WO_Q:") && writeOffSessions.containsKey(chatId)) {
                WriteOffSession s = writeOffSessions.get(chatId);
                try {
                    double qty = Double.parseDouble(data.split(":")[1]);
                    if (qty <= 0 || qty > s.maxAvailable + 1e-9) {
                        sendCancelKeyboard(chatId, String.format("❌ Ошибка: доступно не более %s %s.", DatabaseManager.fmtQty(s.maxAvailable), s.unit));
                        return;
                    }
                    s.quantity = qty;
                    s.step = "WAIT_TYPE";
                    sendTypeButtons(chatId, s);
                } catch (Exception e) { e.printStackTrace(); }
                return;
            }

            if (data.startsWith("WO_TYPE:") && writeOffSessions.containsKey(chatId)) {
                WriteOffSession session = writeOffSessions.get(chatId);
                String type = data.split(":")[1];

                if ("RETURN".equals(type)) {
                    DatabaseManager.ReturnRequestInfo req = DatabaseManager.createReturnRequest(chatId, session.materialId, session.quantity);
                    if (req.success) {
                        if ("ADMIN".equals(role)) {
                            String[] res = DatabaseManager.approveReturnRequest(req.requestId);
                            sendMenu(chatId, role, "⚡️ <b>Автоматический возврат МОЛ:</b>\n\n" + res[2]);
                        } else {
                            sendMenu(chatId, role, req.messageForWorker);
                            sendReturnApprovalToAdmins(req.requestId, req.messageForAdmin);
                        }
                    } else {
                        sendMenu(chatId, role, req.messageForWorker);
                    }
                    writeOffSessions.remove(chatId);
                } else if ("PAID".equals(type)) {
                    session.isPaidReceipt = true;
                    session.step = "WAIT_RECEIPT_NUM";
                    sendCancelKeyboard(chatId, "🧾 <b>Списание по квитанции (шаг 1 из 4)</b>\nВведите <b>номер квитанции</b>:");
                } else {
                    session.isPaidReceipt = false;
                    session.step = "WAIT_FREE_PHONE";
                    sendCancelKeyboard(chatId, "🛠 <b>Техническое списание (шаг 1 из 5)</b>\nВведите <b>номер телефона</b>, на который оформлена заявка:");
                }
                return;
            }

            if (data.startsWith("WO_CODE:") && writeOffSessions.containsKey(chatId)) {
                WriteOffSession session = writeOffSessions.remove(chatId);
                session.closingCode = data.split(":")[1];
                session.reason = switch (session.closingCode) {
                    case "212" -> "Ремонт ВОК на участке ОРК-ОРА";
                    case "227" -> "Выправление волокна";
                    case "215" -> "В ОРШ: выправление пигтейла/волокна";
                    case "226" -> "В ОРШ: замена пигтейла / адаптера";
                    case "214" -> "Участок ОРШ-ОРК: ремонт/замена райзера";
                    case "217" -> "Участок ОРШ-ОРК: запасной модуль";
                    default -> "Тех. списание (Код " + session.closingCode + ")";
                };
                String res = DatabaseManager.completeWriteOff(chatId, session);
                sendMenu(chatId, role, res);

                if (!"ADMIN".equals(role)) {
                    notifyAdminsForAction(chatId, "🔔 <b>ВНИМАНИЕ! Списание материала:</b>\nМастер <b>" + firstName + "</b> только что выполнил техническое списание:\n\n" + res);
                }
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
                if (text.equals("/start")) {
                    sendDirectNotification(chatId, "⏳ Ваша заявка все еще находится на рассмотрении администратора.");
                }
                return;
            }

            if (checkUnboundAndNotify(chatId, role)) return;

            if (waitingDirContactCat.containsKey(chatId)) {
                int category = waitingDirContactCat.remove(chatId);
                if (DatabaseManager.addDirectoryContact(category, text.trim(), chatId)) {
                    sendMenu(chatId, role, "✅ <b>Контакт успешно добавлен!</b>\nОн теперь отображается в справочнике у всех сотрудников.");
                    sendDirectory(chatId, role); // Сразу показываем обновленный справочник
                } else {
                    sendMenu(chatId, role, "❌ Ошибка при сохранении контакта.");
                }
                return;
            }

            if (text.startsWith("/unbind_") && "ADMIN".equals(role)) {
                long targetId = Long.parseLong(text.replace("/unbind_", ""));

                // --- НОВЫЙ БЛОК: Запрашиваем у Телеграма реальное имя ---
                String realTgName = "Сотрудник";
                try {
                    org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat getChat =
                            new org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat(String.valueOf(targetId));
                    org.telegram.telegrambots.meta.api.objects.Chat targetChat = execute(getChat);

                    if (targetChat.getFirstName() != null) {
                        realTgName = targetChat.getFirstName();
                        if (targetChat.getLastName() != null) {
                            realTgName += " " + targetChat.getLastName();
                        }
                    } else if (targetChat.getUserName() != null) {
                        realTgName = targetChat.getUserName();
                    }
                } catch (Exception e) {
                    // Если Телеграм не ответил (например, скрытый профиль), оставляем ID
                    realTgName = "ID " + targetId;
                }
                // --------------------------------------------------------

                // Вызываем отвязку с передачей реального имени
                if (DatabaseManager.unbindUser(targetId, realTgName)) {
                    sendMenu(chatId, role, "✅ Пользователь сброшен. Ему отправлено меню выбора ФИО!");

                    // Получаем список свободных фамилий
                    List<String> availableNames = DatabaseManager.getAvailableExcelNames();

                    if (availableNames.isEmpty()) {
                        sendDirectNotification(targetId, "⚠ <b>Внимание! Требование Администратора:</b>\nВам необходимо идентифицировать себя, но администратор еще не загрузил график с новыми фамилиями. Пожалуйста, обратитесь к нему напрямую.");
                    } else {
                        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
                        for (String name : availableNames) {
                            InlineKeyboardButton btn = new InlineKeyboardButton(name);
                            btn.setCallbackData("BIND_NAME:" + name);
                            rows.add(List.of(btn));
                        }
                        markup.setKeyboard(rows);

                        String alertText = "⚠️ <b>Внимание! Требование Администратора:</b>\n\n" +
                                "Вам необходимо идентифицировать себя в системе, чтобы ваш аккаунт не был заблокирован.\n\n" +
                                "👇 <b>Пожалуйста, выберите СВОЮ настоящую фамилию из списка ниже:</b>";

                        SendMessage msg = new SendMessage(String.valueOf(targetId), alertText);
                        msg.setParseMode("HTML");
                        msg.setReplyMarkup(markup);
                        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
                    }
                } else {
                    sendMenu(chatId, role, "❌ Ошибка: пользователь не найден в базе данных.");
                }
                return;
            }

            // =========================================================
            // ЖЕСТКАЯ БЛОКИРОВКА БОТОМ-НАДЗИРАТЕЛЕМ
            // =========================================================
            if (forceWelderReturnIds.containsKey(chatId)) {
                int welderId = forceWelderReturnIds.get(chatId);
                String welderName = DatabaseManager.getWelderNameById(welderId);

                SendMessage blockMsg = new SendMessage(String.valueOf(chatId),
                        "⚠️ <b>ДОСТУП ЗАБЛОКИРОВАН!</b>\n\nУ вас висит необработанный запрос по возврату сварочного аппарата: <b>" + welderName + "</b>.\n\nПоднимитесь чуть выше в истории чата и нажмите одну из кнопок под сообщением-напоминанием!");
                blockMsg.setParseMode("HTML");
                try { execute(blockMsg); } catch (TelegramApiException e) {}
                return; // Полностью прерываем обработку текста
            }
            // =========================================================

            if (text.equals("❌ Отменить") || text.equals("🔙 Назад")) {
                waitingTakeMaterialId.remove(chatId);
                writeOffSessions.remove(chatId);
                fileWaitState.remove(chatId);
                waitingToolWriteOffReason.remove(chatId);
                waitingOrshPhoto.remove(chatId);
                waitingOrshProblemReason.remove(chatId);
                waitingDirContactCat.remove(chatId);

                if (text.equals("❌ Отменить")) sendMenu(chatId, role, "🚫 <b>Действие отменено.</b>");
                else sendMenu(chatId, role, "Вы вернулись в главное меню:");
                return;
            }

            if (!text.equals("/start") && !text.equals("/fix") && !text.startsWith("/take_") && !text.startsWith("/give_")) {
                if (isAdminBlockedByPendingRequests(chatId, role)) {
                    return;
                }
            }

            if (text.startsWith("📦") || text.startsWith("🧰") || text.startsWith("📝") ||
                    text.startsWith("📋") || text.startsWith("🔢") || text.startsWith("🤝") ||
                    text.startsWith("📊") || text.startsWith("📥") || text.startsWith("📑") ||
                    text.startsWith("🗓") || text.startsWith("🔍") || text.startsWith("📢") ||
                    text.startsWith("👥") || text.startsWith("🪛") || text.startsWith("🛠") ||
                    text.startsWith("📞") || text.startsWith("🔌") || text.startsWith("👁") || text.equals("/start")) {
                waitingTakeMaterialId.remove(chatId);
                waitingDirContactCat.remove(chatId);

                writeOffSessions.remove(chatId);
                fileWaitState.remove(chatId);
                waitingToolWriteOffReason.remove(chatId);
                waitingOrshPhoto.remove(chatId);
                waitingOrshProblemReason.remove(chatId);
            }

            if (text.startsWith("/take_") && "ADMIN".equals(role)) {
                try {
                    int toolId = Integer.parseInt(text.replace("/take_", ""));
                    String result = DatabaseManager.returnToolToWarehouse(toolId);
                    sendMenu(chatId, role, result);
                } catch (Exception e) {}
                return;
            }

            if (text.startsWith("/give_") && "ADMIN".equals(role)) {
                try {
                    int firstId = Integer.parseInt(text.replace("/give_", ""));
                    String toolName = DatabaseManager.getToolNameById(firstId);

                    List<String[]> users = DatabaseManager.getUsersForToolAssignment();
                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
                    List<List<InlineKeyboardButton>> rows = new ArrayList<>();

                    for (String[] u : users) {
                        String roleIcon = "ADMIN".equals(u[2]) ? "👑" : "👷‍♂️";
                        InlineKeyboardButton btn = new InlineKeyboardButton(roleIcon + " " + u[1]);
                        btn.setCallbackData("F_ASS:" + firstId + ":" + u[0]);
                        rows.add(List.of(btn));
                    }
                    markup.setKeyboard(rows);

                    SendMessage msg = new SendMessage(String.valueOf(chatId), String.format("🪛 Быстрая выдача: <b>%s</b>\n\n👤 <b>Нажимайте на фамилии сотрудников</b>, чтобы выдать им этот инструмент со склада (можно нажать несколько раз подряд на разных людей):", toolName));
                    msg.setParseMode("HTML");
                    msg.setReplyMarkup(markup);
                    execute(msg);
                } catch (Exception e) {}
                return;
            }

            if (waitingTakeMaterialId.containsKey(chatId)) {
                try {
                    double qty = Double.parseDouble(text.trim().replace(",", "."));
                    int materialId = waitingTakeMaterialId.get(chatId);
                    String result = DatabaseManager.takeMaterialFromWarehouse(chatId, materialId, qty);
                    if (result.startsWith("✅")) {
                        waitingTakeMaterialId.remove(chatId);
                        sendMenu(chatId, role, result);
                    } else {
                        SendMessage msg = new SendMessage(String.valueOf(chatId), result);
                        msg.setParseMode("HTML");
                        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
                    }
                } catch (NumberFormatException e) {
                    sendCancelKeyboard(chatId, "❌ Введите корректное число (например: <code>5</code> или <code>0.05</code>).");
                }
                return;
            }
            if (waitingToolWriteOffReason.containsKey(chatId)) {
                int toolId = waitingToolWriteOffReason.remove(chatId);
                String result = DatabaseManager.writeOffTool(toolId, text);
                sendMenu(chatId, role, result);
                return;
            }
            if (writeOffSessions.containsKey(chatId)) {
                handleWriteOffStep(chatId, firstName, role, text);
                return;
            }

            DatabaseManager.ReceiptSession cartSession = receiptSessions.get(chatId);
            if (cartSession != null && (cartSession.waitingServiceId != -1 || cartSession.waitingMaterialId != -1)) {
                try {
                    double qty = Double.parseDouble(text.trim().replace(",", "."));
                    if (qty <= 0) throw new NumberFormatException();

                    if (cartSession.waitingServiceId != -1) {
                        DatabaseManager.ReceiptItem item = DatabaseManager.getServiceById(cartSession.waitingServiceId);
                        if (item != null) {
                            item.quantity = qty;
                            cartSession.items.add(item);
                        }
                        cartSession.waitingServiceId = -1;
                    } else if (cartSession.waitingMaterialId != -1) {
                        DatabaseManager.ReceiptItem item = DatabaseManager.getMaterialFromBalanceById(chatId, cartSession.waitingMaterialId);
                        if (item != null) {
                            item.quantity = qty;
                            cartSession.items.add(item);
                        }
                        cartSession.waitingMaterialId = -1;
                    }
                    sendCartMenu(chatId, role);
                } catch (NumberFormatException e) {
                    sendCancelKeyboard(chatId, "❌ Введите корректное число больше нуля:");
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
                    try {
                        SendMessage msg = new SendMessage(String.valueOf(userId), broadcastMsg);
                        msg.setParseMode("HTML");
                        execute(msg);
                        successCount++;
                    } catch (TelegramApiException e) {}
                }
                sendMenu(chatId, role, "✅ Рассылка успешно доставлена <b>" + successCount + "</b> сотрудникам!");
                return;
            }

            if (text.startsWith("/ban_") && "ADMIN".equals(role)) {
                long targetId = Long.parseLong(text.replace("/ban_", ""));
                sendMenu(chatId, role, DatabaseManager.setBanStatus(targetId, true));
                return;
            }

            if (text.startsWith("/unban_") && "ADMIN".equals(role)) {
                long targetId = Long.parseLong(text.replace("/unban_", ""));
                sendMenu(chatId, role, DatabaseManager.setBanStatus(targetId, false));
                return;
            }

            if (waitingOrshProblemReason.containsKey(chatId)) {
                int orshId = waitingOrshProblemReason.remove(chatId);
                DatabaseManager.markOrshCompleted(orshId, firstName, true, text);
                sendMenu(chatId, role, "⚠️ Причина зафиксирована. Этот ОРШ переведен в статус проблемных.");
                return;
            }

            switch (text) {
                case "/start" -> {
                    String roleTitle = role.equals("ADMIN") ? "Администратор (МОЛ)" : "Мастер ЦБР УЛКС №2 ЛКЦ";
                    sendMenu(chatId, role, "Привет, <b>" + firstName + "</b>! 👋\n"
                            + "Ваша роль в системе: <b>" + roleTitle + "</b>.\n\n"
                            + "Выберите нужное действие на кнопках внизу экрана:");
                }
                case "/fix" -> {
                    if ("ADMIN".equals(role)) {
                        try (java.sql.Connection conn = DatabaseManager.getConnection();
                             java.sql.Statement stmt = conn.createStatement()) {
                            stmt.execute("DELETE FROM return_requests");
                            sendMenu(chatId, role, "✅ Зависшие заявки очищены!");
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                }
                case "🔌 Сварочные аппараты" -> sendWeldersMenu(chatId, role);
                case "📞 Справочник" -> sendDirectory(chatId, role);
                case "🗓 График работ" -> sendScheduleMenu(chatId);
                case "🗓 Мой график" -> {
                    String excelName = DatabaseManager.getUserExcelName(chatId);
                    if (excelName != null) {
                        sendMenu(chatId, role, DatabaseManager.getFormattedSchedule(excelName));
                    } else {
                        List<String> names = DatabaseManager.getAvailableExcelNames();
                        if (names.isEmpty()) sendMenu(chatId, role, "ℹ График работ еще не загружен администратором.");
                        else sendNameBindingMenu(chatId, names);
                    }
                }
                case "👁 График коллеги" -> {
                    List<String> names = DatabaseManager.getAllExcelNames();
                    if (names.isEmpty()) {
                        sendMenu(chatId, role, "ℹ График работ еще не загружен администратором.");
                    } else {
                        sendColleagueSelectionMenu(chatId, names);
                    }
                }
                case "🤝 С кем я в смене?" -> {
                    String excelName = DatabaseManager.getUserExcelName(chatId);
                    if (excelName != null) {
                        String result = DatabaseManager.getShiftPartners(excelName);
                        sendMenu(chatId, role, result);
                    } else {
                        List<String> names = DatabaseManager.getAvailableExcelNames();
                        if (names.isEmpty()) sendMenu(chatId, role, "ℹ Ваш профиль еще не привязан к графику. Сначала привяжите его через кнопку «🗓 Мой график».");
                        else sendNameBindingMenu(chatId, names);
                    }
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
                case "📊 Статистика ОРШ" -> {
                    if (role.equals("ADMIN")) sendMenu(chatId, role, DatabaseManager.getOrshStatistics());
                }
                case "📥 Загрузить план ОРШ (Excel)" -> {
                    if (role.equals("ADMIN")) {
                        fileWaitState.put(chatId, "ORSH");
                        sendCancelKeyboard(chatId, "📁 Отправьте файл ПЛАНА ОРШ (Excel) прямо в этот чат.\n\nКолонки:\n• A — Номер ОРШ\n• B — Адрес\n• C — Местоположение");
                    }
                }
                case "📥 Загрузки (Excel)" -> {
                    if (role.equals("ADMIN")) sendUploadsMenu(chatId, "📥 <b>Меню загрузки файлов (Excel):</b>\nВыберите базу для обновления:");
                }
                case "📥 Загрузить ведомость (Excel)" -> {
                    if (role.equals("ADMIN")) {
                        fileWaitState.put(chatId, "TURNOVER");
                        sendCancelKeyboard(chatId, "📎 Отправьте файл ОБОРОТНОЙ ВЕДОМОСТИ прямо в этот чат.");
                    }
                }
                case "📥 Загрузить график (Excel)" -> {
                    if (role.equals("ADMIN")) {
                        fileWaitState.put(chatId, "SCHEDULE");
                        sendCancelKeyboard(chatId, "🗓 Отправьте файл ГРАФИКА РАБОТ прямо в этот чат.");
                    }
                }
                case "📥 Загрузить инструмент (Excel)" -> {
                    if (role.equals("ADMIN")) {
                        fileWaitState.put(chatId, "TOOLS");
                        sendCancelKeyboard(chatId, "🪛 Отправьте файл базы ИНСТРУМЕНТА прямо в этот чат.");
                    }
                }
                case "📊 У кого что на руках" -> {
                    if (role.equals("ADMIN")) sendMenu(chatId, role, DatabaseManager.getAllWorkersBalancesText());
                }
                case "📑 Скачать отчет за месяц" -> {
                    if (role.equals("ADMIN")) sendExcelReport(chatId, role);
                }
                case "📢 Сделать рассылку" -> {
                    if (role.equals("ADMIN")) {
                        fileWaitState.put(chatId, "WAIT_BROADCAST_TEXT");
                        sendCancelKeyboard(chatId, "📢 <b>Режим массовой рассылки</b>\n\nВведите текст сообщения, которое хотите отправить <b>всем сотрудникам</b>. Вы можете использовать смайлы, переносы строк и ссылки.\n\n<i>Для отмены нажмите кнопку «❌ Отменить».</i>");
                    }
                }
                case "👥 Пользователи" -> {
                    if (role.equals("ADMIN")) sendMenu(chatId, role, DatabaseManager.getUsersListText());
                }
                case "🛠 Управление инструментом" -> {
                    if (role.equals("ADMIN")) sendToolAdminMenu(chatId);
                }
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
        sb.append("\n");

        sb.append("👷‍♂ <b>ИТР УЛКС №2</b>\n▪️ Нач. УЛКС №2 Лазуко С.В.: +375(17) 200-02-98, +375(29) 501-01-11\n▪ Зам. нач. УЛКС №2 Журко А.Н.: +375(17) 264-93-74, +375(29) 231-69-23\n▪ Рук. каб. группы Прохорчик В.Ю.: +375(17) 200-40-40, +375(29) 176-58-88\n▪️ Рук. гр. технадзора Снитко А.В.: +375(33) 347-28-85\n▪️ Рук. гр. малопарщиков Никитин Д.Д.: +375(44) 752-02-69\n▪ Рук. изм. группы Лабуза С.И.: +375(29) 701-88-29\n");
        for (String s : dyn.getOrDefault(2, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n");

        sb.append("💻 <b>Технический отдел</b>\n▪ Рук. гр. технадзора Шашков В.П.: +375(29) 373-35-55\n▪️ Инж. технадзора Кононова Светлана: +375(29) 577-57-46\n▪ Магистральщики (Протасевич Юлия): +375(29) 560-80-82\n▪️ Профсоюзные дела (Луговцова Оксана): +375(29) 317-19-56\n");
        for (String s : dyn.getOrDefault(3, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n");

        sb.append("🗂 <b>Администрация</b>\n▪️ Профком (Нестерова Е.Ю.): +375(17) 359-45-70\n▪️ Бухгалтерия по ЗП (Ромашко Ю.Г.): +375(17) 359-45-20\n▪️ Специалист по кадрам (Субач Е.В.): +375(17) 359-45-03\n");
        for (String s : dyn.getOrDefault(4, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n");

        sb.append("🚗 <b>АТЦ</b>\n▪️ Начальник Савицкий А.В.: +375(17) 369-05-25, +375(33) 603-32-73\n▪️ Зам. начальника Болбас Р.А.: +375(17) 369-03-93, +375(29) 840-78-37\n▪️ Инж. по БД Гузов А.П.: +375(17) 369-05-27, +375(29) 779-11-88\n");
        for (String s : dyn.getOrDefault(5, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n");

        sb.append("🔌 <b>Станционщики (магистраль / проключения)</b>\n▪️ Инженер Ивашкевич Дарья: +375(29) 555-38-48\n");
        for (String s : dyn.getOrDefault(6, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n");

        sb.append("📺 <b>Привязка СМЛ приставок</b>\n▪️ Дневное время (Ольга): +375(29) 788-84-98\n▪️ Вечернее время: +375(17) 334-56-24, +375(17) 328-46-56, +375(17) 252-44-55, +375(17) 359-41-10\n");
        for (String s : dyn.getOrDefault(7, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n");

        sb.append("🎧 <b>Диспетчера и админы (закрытие заявок)</b>\n▪ Диспетчеры ЦАБР: +375(17) 288-47-10\n▪️ Инженер ЦАБР: +375(17) 268-46-26\n▪ Админы: +375(17) 306-29-56, +375(33) 603-38-81\n▪ После 20:00: +375(17) 306-29-59\n▪ VPN: +375(17) 203-66-86\n");
        for (String s : dyn.getOrDefault(8, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");
        sb.append("\n");

        sb.append("📹 <b>Прочее</b>\n▪ Видеоконтроль (Андрей): +375(17) 359-40-30\n▪️ РСМОБ: +375(17) 357-96-30\n");
        for (String s : dyn.getOrDefault(9, new ArrayList<>())) sb.append("▪️ ").append(s).append("\n");

        return sb.toString();
    }

    private void sendDirectory(long chatId, String role) {
        String text = getDirectoryText();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        rows.add(List.of(createBtn("➕ Добавить контакт", "DIR_ADD_MENU")));
        if ("ADMIN".equals(role)) {
            rows.add(List.of(createBtn("🗑 Удалить добавленный", "DIR_DEL_MENU")));
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendDirectoryAddMenu(long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createBtn("🏢 Руководство ЛКЦ", "DIR_CAT:1"), createBtn("👷‍♂ ИТР УЛКС №2", "DIR_CAT:2")));
        rows.add(List.of(createBtn("💻 Технический отдел", "DIR_CAT:3"), createBtn("🗂 Администрация", "DIR_CAT:4")));
        rows.add(List.of(createBtn("🚗 АТЦ", "DIR_CAT:5"), createBtn("🔌 Станционщики", "DIR_CAT:6")));
        rows.add(List.of(createBtn("📺 Привязка СМЛ приставок", "DIR_CAT:7"), createBtn("🎧 Диспетчера", "DIR_CAT:8")));
        rows.add(List.of(createBtn("📹 Прочее", "DIR_CAT:9")));
        rows.add(List.of(createBtn("🔙 Назад к справочнику", "DIR_BACK")));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "➕ <b>В какой раздел добавить контакт?</b>\nВыберите категорию ниже:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendDirectoryDelMenu(long chatId) {
        List<String[]> contacts = DatabaseManager.getDynamicContactsList();
        if (contacts.isEmpty()) {
            sendMenu(chatId, "ADMIN", "ℹ️ В справочник еще не добавлено ни одного стороннего контакта.");
            return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] c : contacts) {
            String shortText = c[1].length() > 30 ? c[1].substring(0, 30) + "…" : c[1];
            rows.add(List.of(createBtn("🗑 " + shortText, "DIR_DEL:" + c[0])));
        }
        rows.add(List.of(createBtn("🔙 Назад к справочнику", "DIR_BACK")));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗑 <b>Удаление контакта</b>\nНажмите на контакт, который хотите удалить из базы:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void notifyAdminsForAction(long excludeChatId, String text) {
        List<Long> adminIds = DatabaseManager.getAdminIds();
        for (Long adminId : adminIds) {
            if (adminId == excludeChatId) continue;
            SendMessage msg = new SendMessage(String.valueOf(adminId), text);
            msg.setParseMode("HTML");
            try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
        }
    }

    private void sendNameBindingMenu(long chatId, List<String> names) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (String name : names) {
            InlineKeyboardButton btn = new InlineKeyboardButton(name);
            btn.setCallbackData("BIND_NAME:" + name);
            rows.add(List.of(btn));
        }

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "👤 <b>Вы еще не привязаны к графику.</b>\n\nВыберите вашу фамилию из списка сотрудников:");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendCancelKeyboard(long chatId, String text) {
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        message.setParseMode("HTML");
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        KeyboardRow row = new KeyboardRow();
        row.add("🔙 Назад");
        row.add("❌ Отменить");
        markup.setKeyboard(List.of(row));
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void handleWriteOffBack(long chatId, String role) {
        WriteOffSession s = writeOffSessions.get(chatId);
        switch (s.step) {
            case "WAIT_QTY":
                writeOffSessions.remove(chatId);
                startWriteOffMenu(chatId, role);
                break;
            case "WAIT_TYPE":
                s.step = "WAIT_QTY";
                sendCancelKeyboard(chatId, String.format("Выбрано: <b>%s</b>\nДоступно у вас: <b>%s %s</b>\n\n✍️ Введите количество:",
                        s.materialName, DatabaseManager.fmtQty(s.maxAvailable), s.unit));
                break;
            case "WAIT_RECEIPT_NUM":
            case "WAIT_FREE_PHONE":
                s.step = "WAIT_TYPE";
                sendTypeButtons(chatId, s);
                break;
            case "WAIT_PAID_PHONE":
                s.step = "WAIT_RECEIPT_NUM";
                sendCancelKeyboard(chatId, "🧾 <b>Списание по квитанции (шаг 1 из 4)</b>\nВведите <b>номер квитанции</b>:");
                break;
            case "WAIT_PAID_CONTRACT":
                s.step = "WAIT_PAID_PHONE";
                sendCancelKeyboard(chatId, "🧾 <b>Шаг 2 из 4:</b> Введите <b>номер телефона</b>, на который оформлена заявка:");
                break;
            case "WAIT_PAID_ADDRESS":
                s.step = "WAIT_PAID_CONTRACT";
                sendCancelKeyboard(chatId, "🧾 <b>Шаг 3 из 4:</b> Введите <b>номер договора</b>:");
                break;
            case "WAIT_FREE_CONTRACT":
                s.step = "WAIT_FREE_PHONE";
                sendCancelKeyboard(chatId, "🛠 <b>Шаг 1 из 5:</b> Введите <b>номер телефона</b>, на который оформлена заявка:");
                break;
            case "WAIT_FREE_ADDRESS":
                s.step = "WAIT_FREE_CONTRACT";
                sendCancelKeyboard(chatId, "🛠 <b>Шаг 2 из 5:</b> Введите <b>номер договора</b> (или поставьте прочерк <code>-</code>, если нет):");
                break;
            case "WAIT_CLOSING_CODE":
                s.step = "WAIT_FREE_ADDRESS";
                sendCancelKeyboard(chatId, "🛠 <b>Шаг 3 из 5:</b> Введите <b>адрес</b>:");
                break;
        }
    }

    private void handleWriteOffStep(long chatId, String firstName, String role, String text) {
        WriteOffSession s = writeOffSessions.get(chatId);
        switch (s.step) {
            case "WAIT_QTY" -> {
                try {
                    double qty = Double.parseDouble(text.trim().replace(",", "."));
                    if (qty <= 0 || qty > s.maxAvailable + 1e-9) {
                        sendCancelKeyboard(chatId, String.format("❌ Введите число больше 0 и не более %s %s:",
                                DatabaseManager.fmtQty(s.maxAvailable), s.unit));
                        return;
                    }
                    s.quantity = qty;
                    s.step = "WAIT_TYPE";
                    sendTypeButtons(chatId, s);
                } catch (Exception e) {
                    sendCancelKeyboard(chatId, "❌ Введите количество числом (например: <code>2</code> или <code>0.02</code>):");
                }
            }
            case "WAIT_RECEIPT_NUM" -> {
                s.receiptNumber = text.trim();
                s.step = "WAIT_PAID_PHONE";
                sendCancelKeyboard(chatId, "🧾 <b>Шаг 2 из 4:</b> Введите <b>номер телефона</b>, на который оформлена заявка:");
            }
            case "WAIT_PAID_PHONE" -> {
                s.phoneNumber = text.trim();
                s.step = "WAIT_PAID_CONTRACT";
                sendCancelKeyboard(chatId, "🧾 <b>Шаг 3 из 4:</b> Введите <b>номер договора</b>:");
            }
            case "WAIT_PAID_CONTRACT" -> {
                s.contractNumber = text.trim();
                s.step = "WAIT_PAID_ADDRESS";
                sendCancelKeyboard(chatId, "🧾 <b>Шаг 4 из 4:</b> Введите <b>адрес абонента</b>:");
            }
            case "WAIT_PAID_ADDRESS" -> {
                s.address = text.trim();
                writeOffSessions.remove(chatId);
                String res = DatabaseManager.completeWriteOff(chatId, s);
                sendMenu(chatId, role, res);

                if (!"ADMIN".equals(role)) {
                    notifyAdminsForAction(chatId, "🧾 <b>ВНИМАНИЕ! Выбита квитанция:</b>\nМастер <b>" + firstName + "</b> только что отчитался по квитанции:\n\n" + res);
                }
            }
            case "WAIT_FREE_PHONE" -> {
                s.phoneNumber = text.trim();
                s.step = "WAIT_FREE_CONTRACT";
                sendCancelKeyboard(chatId, "🛠 <b>Шаг 2 из 5:</b> Введите <b>номер договора</b> (или поставьте прочерк <code>-</code>, если нет):");
            }
            case "WAIT_FREE_CONTRACT" -> {
                s.contractNumber = text.trim();
                s.step = "WAIT_FREE_ADDRESS";
                sendCancelKeyboard(chatId, "🛠 <b>Шаг 3 из 5:</b> Введите <b>адрес</b>:");
            }
            case "WAIT_FREE_ADDRESS" -> {
                s.address = text.trim();
                s.step = "WAIT_CLOSING_CODE";
                sendClosingCodeButtons(chatId, s.phoneNumber, s.contractNumber);
            }
        }
    }

    private void sendTypeButtons(long chatId, WriteOffSession s) {
        InlineKeyboardButton btnPaid = new InlineKeyboardButton("🧾 Списать по квитанции");
        btnPaid.setCallbackData("WO_TYPE:PAID");
        InlineKeyboardButton btnFree = new InlineKeyboardButton("🛠 Техническое списание");
        btnFree.setCallbackData("WO_TYPE:FREE");
        InlineKeyboardButton btnReturn = new InlineKeyboardButton("↩️ Вернуть на склад");
        btnReturn.setCallbackData("WO_TYPE:RETURN");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(btnPaid),
                List.of(btnFree),
                List.of(btnReturn)
        ));

        SendMessage msg = new SendMessage(String.valueOf(chatId),
                String.format("Количество: <b>%s %s</b>.\nЧто делаем с материалом?",
                        DatabaseManager.fmtQty(s.quantity), s.unit));
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendReturnApprovalToAdmins(int requestId, String text) {
        InlineKeyboardButton btnApprove = new InlineKeyboardButton("✅ Принять на склад");
        btnApprove.setCallbackData("RET_APP:" + requestId);
        InlineKeyboardButton btnReject = new InlineKeyboardButton("❌ Отклонить возврат");
        btnReject.setCallbackData("RET_REJ:" + requestId);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(btnApprove, btnReject)));

        String alertMsg = "🚨 <b>ТРЕБУЕТСЯ ВАШЕ РАЗРЕШЕНИЕ!</b> 🚨\n\n" + text + "\n\n<i>❗️ Мастер ждет подтверждения, чтобы вернуть этот материал на склад.</i>";

        List<Long> adminIds = DatabaseManager.getAdminIds();
        for (Long adminId : adminIds) {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(adminId));
            msg.setText(alertMsg);
            msg.setParseMode("HTML");
            msg.setReplyMarkup(markup);
            try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
        }
    }

    private void sendDirectNotification(long targetChatId, String text) {
        SendMessage msg = new SendMessage(String.valueOf(targetChatId), text);
        msg.setParseMode("HTML");
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void startWriteOffMenu(long chatId, String role) {
        List<String[]> userMats = DatabaseManager.getUserMaterialsForWriteOff(chatId);
        if (userMats.isEmpty()) {
            sendMenu(chatId, role, "🧰 У вас на руках нет материалов для работы. Сначала возьмите их в разделе «📦 Склад».");
            return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] m : userMats) {
            String shortBtnName = m[2].length() > 30 ? m[2].substring(0, 30) + "…" : m[2];
            InlineKeyboardButton btn = new InlineKeyboardButton();
            btn.setText(String.format("👉 %s [%s] (%s %s)", shortBtnName, m[1], m[4], m[3]));
            btn.setCallbackData("WO_MAT:" + m[0]);
            rows.add(List.of(btn));
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "📝 <b>Выберите материал из вашего подотчета (для списания или возврата):</b>");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendClosingCodeButtons(long chatId, String phone, String contract) {
        String warning = DatabaseManager.checkCode212History(phone, contract);

        InlineKeyboardButton c212 = new InlineKeyboardButton("212 — Участок ОРК–ОРА (ВОК-1, короб) [не чаще 6 мес!]");
        c212.setCallbackData("WO_CODE:212");

        InlineKeyboardButton c227 = new InlineKeyboardButton("227 — Выправление волокна / повтор (без ограничений)");
        c227.setCallbackData("WO_CODE:227");

        InlineKeyboardButton c215 = new InlineKeyboardButton("215 — В ОРШ: выправление пигтейла/волокна");
        c215.setCallbackData("WO_CODE:215");

        InlineKeyboardButton c226 = new InlineKeyboardButton("226 — В ОРШ: замена пигтейла / адаптера");
        c226.setCallbackData("WO_CODE:226");

        InlineKeyboardButton c214 = new InlineKeyboardButton("214 — Участок ОРШ–ОРК: ремонт/замена райзера");
        c214.setCallbackData("WO_CODE:214");

        InlineKeyboardButton c217 = new InlineKeyboardButton("217 — Участок ОРШ–ОРК: запасной модуль (КДЗС)");
        c217.setCallbackData("WO_CODE:217");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(
                List.of(c212),
                List.of(c227),
                List.of(c215),
                List.of(c226),
                List.of(c214),
                List.of(c217)
        ));

        SendMessage msg = new SendMessage(String.valueOf(chatId), "🔢 <b>Шаг 4 из 5:</b> Выберите <b>код закрытия заявки</b> из списка ниже:" + warning);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendWarehouseList(long chatId, String role) {
        List<String[]> materials = DatabaseManager.getAvailableMaterials();
        if (materials.isEmpty()) {
            sendMenu(chatId, role, "📦 На складе сейчас нет доступных материалов.\nЗагрузите оборотную ведомость через кнопку «📥 Загрузить ведомость (Excel)».");
            return;
        }
        StringBuilder sb = new StringBuilder();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        String currentAcc = "";
        int num = 1;
        int itemsInChunk = 0;

        for (String[] m : materials) {
            String account = m[1];
            String name = m[3].replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");

            if ((!account.equals(currentAcc) && itemsInChunk > 0) || itemsInChunk >= 10) {
                sendWarehouseChunk(chatId, sb.toString(), rows);
                sb.setLength(0);
                rows = new ArrayList<>();
                itemsInChunk = 0;
            }
            if (!account.equals(currentAcc)) {
                sb.append("📦 <b>СКЛАД — Счёт ").append(account).append(" (цены с НДС +20%):</b>\n\n");
                currentAcc = account;
            } else if (itemsInChunk == 0) {
                sb.append("📦 <b>СКЛАД — Счёт ").append(account).append(" (продолжение, с НДС +20%):</b>\n\n");
            }
            String batchInfo = (m[8] != null && !m[8].isEmpty()) ? " <i>(" + m[8] + ")</i>" : "";
            sb.append(String.format("%d. <b>%s</b>%s\n   • Инв. №: <code>%s</code>\n   • Ед. изм.: <b>%s</b> | Остаток: <b>%s %s</b>\n   • Цена (с НДС +20%%): <b>%s руб.</b>\n\n",
                    num, name, batchInfo, m[2], m[4], m[7], m[4], m[6]));

            String shortBtnName = m[3].length() > 22 ? m[3].substring(0, 22) + "…" : m[3];
            InlineKeyboardButton btn = new InlineKeyboardButton();
            btn.setText(String.format("➕ %d. %s (%s %s | %s р.)", num, shortBtnName, m[7], m[4], m[6]));
            btn.setCallbackData("TAKE_MAT:" + m[0]);
            rows.add(List.of(btn));
            num++;
            itemsInChunk++;
        }
        if (itemsInChunk > 0) {
            sb.append("👇 <b>Нажмите на кнопку нужной позиции под списком, чтобы взять её в подотчет:</b>");
            sendWarehouseChunk(chatId, sb.toString(), rows);
        }
    }

    private void sendCartMenu(long chatId, String role) {
        DatabaseManager.ReceiptSession s = receiptSessions.getOrDefault(chatId, new DatabaseManager.ReceiptSession());

        StringBuilder sb = new StringBuilder("🧾 <b>Калькулятор квитанции</b>\n\n");
        sb.append("В квитанции сейчас позиций: <b>").append(s.items.size()).append("</b>\n\n");
        if (!s.items.isEmpty()) {
            for (int i = 0; i < s.items.size(); i++) {
                sb.append(i + 1).append(". ").append(s.items.get(i).name)
                        .append(" (").append(DatabaseManager.fmtQty(s.items.get(i).quantity)).append(" ").append(s.items.get(i).unit).append(")\n");
            }
            sb.append("\nЧто делаем дальше?");
        } else {
            sb.append("Добавьте выполненные работы и материалы.");
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        InlineKeyboardButton btnSrv = new InlineKeyboardButton("➕ Услугу");
        btnSrv.setCallbackData("CART_ADD_SRV");

        InlineKeyboardButton btnMat = new InlineKeyboardButton("➕ Материал");
        btnMat.setCallbackData("CART_ADD_MAT");

        rows.add(List.of(btnSrv, btnMat));

        if (!s.items.isEmpty()) {
            InlineKeyboardButton btnFinish = new InlineKeyboardButton("✅ РАССЧИТАТЬ ИТОГИ");
            btnFinish.setCallbackData("CART_FINISH");
            rows.add(List.of(btnFinish));

            InlineKeyboardButton btnClear = new InlineKeyboardButton("🗑 Очистить");
            btnClear.setCallbackData("CART_CLEAR");
            rows.add(List.of(btnClear));
        }

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), sb.toString());
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendServiceSelectionForCart(long chatId) {
        List<String[]> services = DatabaseManager.getAllServicesForReceipt();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (String[] srv : services) {
            String shortName = srv[1].length() > 35 ? srv[1].substring(0, 35) + "…" : srv[1];
            InlineKeyboardButton btn = new InlineKeyboardButton("🛠 " + shortName);
            btn.setCallbackData("CART_SEL_SRV:" + srv[0]);
            rows.add(List.of(btn));
        }

        InlineKeyboardButton btnBack = new InlineKeyboardButton("🔙 Назад в корзину");
        btnBack.setCallbackData("CART_MENU");
        rows.add(List.of(btnBack));

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🛠 <b>Выберите выполненную услугу:</b>");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendMaterialSelectionForCart(long chatId, String role) {
        List<String[]> userMats = DatabaseManager.getUserMaterialsForWriteOff(chatId);
        if (userMats.isEmpty()) {
            sendMenu(chatId, role, "🧰 У вас нет материалов в подотчете для добавления в квитанцию.");
            sendCartMenu(chatId, role);
            return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (String[] m : userMats) {
            String shortName = m[2].length() > 30 ? m[2].substring(0, 30) + "…" : m[2];
            InlineKeyboardButton btn = new InlineKeyboardButton("📦 " + shortName + " (" + m[4] + " " + m[3] + ")");
            btn.setCallbackData("CART_SEL_MAT:" + m[0]);
            rows.add(List.of(btn));
        }

        InlineKeyboardButton btnBack = new InlineKeyboardButton("🔙 Назад в корзину");
        btnBack.setCallbackData("CART_MENU");
        rows.add(List.of(btnBack));

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "📦 <b>Выберите использованный материал:</b>");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendWarehouseChunk(long chatId, String text, List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup inlineMarkup = new InlineKeyboardMarkup();
        inlineMarkup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(inlineMarkup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void handleUpdateService(long chatId, String role, String text) {
        try {
            String[] parts = text.substring(8).split(";");
            if (parts.length == 2) {
                String name = parts[0].trim();
                double price = Double.parseDouble(parts[1].trim().replace(",", "."));

                if (DatabaseManager.updateServicePrice(name, price)) {
                    sendMenu(chatId, role, "✅ Цена для услуги <b>" + name + "</b> успешно обновлена на <b>" + price + " руб.</b>");
                    return;
                } else {
                    sendMenu(chatId, role, "❌ Услуга с таким названием не найдена. Проверьте правильность написания в разделе «Тарифы услуг».");
                    return;
                }
            }
        } catch (Exception ignored) {}
        sendMenu(chatId, role, "❌ Неверный формат. Пример:\n<code>+услуга Вызов мастера ; 10.50</code>");
    }

    private void sendExcelReport(long chatId, String role) {
        sendMenu(chatId, role, "⏳ Формирую Excel-отчет по складу и списаниям...");
        File reportFile = ExcelReportGenerator.generateMonthlyReport();

        if (reportFile != null && reportFile.exists()) {
            SendDocument sendDoc = new SendDocument();
            sendDoc.setChatId(String.valueOf(chatId));
            sendDoc.setDocument(new InputFile(reportFile));
            sendDoc.setCaption("📊 Итоговый отчет по складу и списаниям.");
            try {
                execute(sendDoc);
            } catch (TelegramApiException e) {
                e.printStackTrace();
            }

            try {
                String subject = "Итоговый отчет по складу (Сводка)";
                String body = "Добрый день!\n\nВо вложении находится сгенерированный Excel-отчет по складу (Остатки, платные квитанции, техническое списание).";
                EmailSender.sendOrshReport(subject, body, reportFile);
                sendMenu(chatId, role, "✉️ Отчет также успешно отправлен на рабочую почту!");
            } catch (Exception e) {
                e.printStackTrace();
                sendMenu(chatId, role, "⚠️ В Telegram отчет отправлен, но при отправке на почту произошла ошибка: " + e.getMessage());
            }
        } else {
            sendMenu(chatId, role, "❌ Не удалось сформировать отчет.");
        }
    }

    public void sendMenu(long chatId, String role, String text) {
        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);
        List<KeyboardRow> keyboard = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("📦 Склад (Наличие и цены)");
        row1.add("🧰 Мой подотчет");
        keyboard.add(row1);

        KeyboardRow row2 = new KeyboardRow();
        row2.add("📝 Списать / Вернуть");
        row2.add("🔌 Сварочные аппараты");
        keyboard.add(row2);

        KeyboardRow row3 = new KeyboardRow();
        row3.add("🧾 Калькулятор квитанции");
        row3.add("📋 Тарифы услуг");
        keyboard.add(row3);

        KeyboardRow row4 = new KeyboardRow();
        row4.add("📸 Плановый осмотр ОРШ");
        row4.add("🗓 График работ");
        keyboard.add(row4);

        KeyboardRow row5 = new KeyboardRow();
        row5.add("🔢 Коды закрытия");
        row5.add("📞 Справочник");
        keyboard.add(row5);

        if ("ADMIN".equals(role)) {
            KeyboardRow adminRow1 = new KeyboardRow();
            adminRow1.add("📊 У кого что на руках");
            adminRow1.add("🛠 Управление инструментом");
            keyboard.add(adminRow1);

            KeyboardRow adminRow2 = new KeyboardRow();
            adminRow2.add("📑 Скачать отчет за месяц");
            adminRow2.add("📊 Статистика ОРШ");
            keyboard.add(adminRow2);

            KeyboardRow adminRow3 = new KeyboardRow();
            adminRow3.add("📢 Сделать рассылку");
            adminRow3.add("👥 Пользователи");
            keyboard.add(adminRow3);

            KeyboardRow adminRow4 = new KeyboardRow();
            adminRow4.add("📥 Загрузки (Excel)");
            keyboard.add(adminRow4);
        }

        keyboardMarkup.setKeyboard(keyboard);

        int maxLength = 3900;
        try {
            if (text.length() <= maxLength) {
                SendMessage message = new SendMessage(String.valueOf(chatId), text);
                message.setParseMode("HTML");
                message.setReplyMarkup(keyboardMarkup);
                execute(message);
            } else {
                while (text.length() > maxLength) {
                    int splitIndex = text.lastIndexOf("\n\n", maxLength);
                    if (splitIndex == -1) {
                        splitIndex = text.lastIndexOf('\n', maxLength);
                    }
                    if (splitIndex == -1) {
                        splitIndex = maxLength;
                    }

                    String part = text.substring(0, splitIndex);
                    text = text.substring(splitIndex).trim();

                    SendMessage msgPart = new SendMessage(String.valueOf(chatId), part);
                    msgPart.setParseMode("HTML");
                    execute(msgPart);
                }

                if (!text.isEmpty()) {
                    SendMessage message = new SendMessage(String.valueOf(chatId), text);
                    message.setParseMode("HTML");
                    message.setReplyMarkup(keyboardMarkup);
                    execute(message);
                }
            }
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendPendingOrshList(long chatId) {
        List<String[]> list = DatabaseManager.getPendingOrshList();
        if (list.isEmpty()) {
            sendMenu(chatId, "WORKER", "🎉 <b>План осмотра пуст!</b>\nВсе шкафы проверены или новый план еще не загружен.");
            return;
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        int count = 0;
        for (String[] o : list) {
            if (count >= 30) break;
            String btnText = "📍 " + (o[2].length() > 35 ? o[2].substring(0, 35) + "…" : o[2]);
            InlineKeyboardButton btn = new InlineKeyboardButton(btnText);
            btn.setCallbackData("ORSH_SEL:" + o[0]);
            rows.add(List.of(btn));
            count++;
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "📸 <b>Осталось проверить: " + list.size() + " шт.</b>\nВыберите адрес из списка (показаны ближайшие " + count + "):");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendOrshDetails(long chatId, int orshId) {
        String[] orsh = DatabaseManager.getOrshById(orshId);
        if (orsh == null) {
            sendMenu(chatId, "WORKER", "❌ Шкаф не найден или уже был проверен.");
            return;
        }

        waitingOrshPhoto.put(chatId, orshId);
        sendCancelKeyboard(chatId, "Подготовка к осмотру...");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        InlineKeyboardButton btnProb = new InlineKeyboardButton("⚠ Невозможно сделать фото");
        btnProb.setCallbackData("ORSH_PROB:" + orshId);
        markup.setKeyboard(List.of(List.of(btnProb)));

        String text = String.format("📸 <b>Выбран ОРШ-%s</b>\n\n📍 Адрес: %s\n🧭 Местоположение: %s\n\n👇 <b>Отправьте фото шкафа прямо в этот чат!</b>",
                orsh[0], orsh[1], orsh[2]);

        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private String checkRoleAndNotify(long chatId, String firstName) {
        String role = DatabaseManager.getUserRole(chatId, firstName);
        if ("NEW_PENDING".equals(role)) {
            sendNewUserRequestToAdmins(chatId, firstName);
            SendMessage msg = new SendMessage(String.valueOf(chatId), "⏳ <b>Ваша заявка отправлена.</b>\nОжидайте подтверждения доступа администратором.");
            msg.setParseMode("HTML");
            try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
            return "PENDING";
        }
        return role;
    }

    private void sendNewUserRequestToAdmins(long newUserId, String newUserName) {
        InlineKeyboardButton btnApprove = new InlineKeyboardButton("✅ Одобрить (Мастер)");
        btnApprove.setCallbackData("NEW_USER_APP:" + newUserId);
        InlineKeyboardButton btnReject = new InlineKeyboardButton("❌ Заблокировать");
        btnReject.setCallbackData("NEW_USER_REJ:" + newUserId);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(btnApprove, btnReject)));

        String text = String.format("👤 <b>Новый пользователь хочет получить доступ к боту!</b>\n\nИмя: <b>%s</b>\nID: <code>%d</code>\n\nРазрешить доступ?", newUserName, newUserId);

        for (Long adminId : DatabaseManager.getAdminIds()) {
            SendMessage msg = new SendMessage(String.valueOf(adminId), text);
            msg.setParseMode("HTML");
            msg.setReplyMarkup(markup);
            try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
        }
    }

    private void sendToolAdminMenu(long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createBtn("🤝 Выдать мастеру", "TOOL_MENU_ASSIGN")));
        rows.add(List.of(createBtn("↩️ Забрать на склад", "TOOL_MENU_RETURN")));
        rows.add(List.of(createBtn("🗑 Списать (поломка)", "TOOL_MENU_WRITEOFF")));
        rows.add(List.of(createBtn("📊 Общая сводка", "TOOL_MENU_AUDIT_ALL")));
        rows.add(List.of(createBtn("👤 Сводка по людям", "TOOL_MENU_AUDIT_USERS")));
        rows.add(List.of(createBtn("📢 Рассылка: Аудит остатков", "TOOL_MENU_AUDIT_NOTIFY")));
        rows.add(List.of(createBtn("🗄 Архив списанного", "TOOL_MENU_ARCHIVE")));
        rows.add(List.of(createBtn("♻️ Восстановить из архива", "TOOL_MENU_RESTORE")));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🛠 <b>УПРАВЛЕНИЕ ИНСТРУМЕНТОМ</b>\nВыберите действие:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUsersToolSummaryMenu(long chatId) {
        List<String[]> users = DatabaseManager.getUsersWithToolCounts();
        if (users.isEmpty()) {
            sendMenu(chatId, "ADMIN", "ℹ️ Сейчас ни у кого из сотрудников нет инструмента на руках.");
            return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) {
            String btnText = String.format("👤 %s (%s шт.)", u[1], u[2]);
            rows.add(List.of(createBtn(btnText, "T_SUMM_U:" + u[0])));
        }
        rows.add(List.of(createBtn("🔙 Назад в меню управления", "TOOL_MAIN_MENU")));
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "👤 <b>Сводка по людям</b>\nВыберите сотрудника, чтобы просмотреть и управлять его инструментом:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUserToolSummary(long chatId, long targetUserId) {
        List<String[]> tools = DatabaseManager.getUserAssignedToolsForReturn(targetUserId);
        String userName = DatabaseManager.getUserFullName(targetUserId);

        if (tools.isEmpty()) {
            sendMenu(chatId, "ADMIN", "ℹ️ У сотрудника <b>" + userName + "</b> больше нет инструмента на руках.");
            sendUsersToolSummaryMenu(chatId); // Автоматически возвращаем к списку людей
            return;
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) {
            String inv = (t[2] != null && !t[2].isEmpty() && !t[2].equals("null")) ? " [" + t[2] + "]" : "";
            // Обрезаем длинные названия, чтобы влезли в кнопку
            String btnText = String.format("↩️ Забрать: %s%s", t[1].length() > 15 ? t[1].substring(0, 15) + "…" : t[1], inv);
            rows.add(List.of(createBtn(btnText, "T_SUMM_RET:" + t[0] + ":" + targetUserId)));
        }

        rows.add(List.of(createBtn("🚨 Вернуть ВСЁ на склад", "T_SUMM_RALL:" + targetUserId)));
        rows.add(List.of(createBtn("🔙 Назад к списку людей", "TOOL_MENU_AUDIT_USERS")));

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "👤 <b>Инструмент: " + userName + "</b>\n\nНажмите на кнопку, чтобы вернуть конкретный прибор на склад, или заберите всё разом:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendAvailableToolsForAssignment(long chatId) {
        List<String[]> groups = DatabaseManager.getAvailableToolGroups();
        if (groups.isEmpty()) {
            sendMenu(chatId, "ADMIN", "📦 На складе сейчас нет свободного инструмента для выдачи.");
            return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] g : groups) {
            String shortName = g[1].length() > 30 ? g[1].substring(0, 30) + "…" : g[1];
            rows.add(List.of(createBtn(shortName + " (в наличии: " + g[2] + " шт)", "T_SEL_N:" + g[0])));
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🤝 <b>Шаг 1 из 2: Выберите инструмент для выдачи:</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUsersForToolAssignment(long chatId, int toolId) {
        String fullToolNameInfo = DatabaseManager.getToolNameAndInvById(toolId);
        List<String[]> users = DatabaseManager.getUsersForToolAssignment();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) {
            String roleIcon = "ADMIN".equals(u[2]) ? "👑" : "👷‍♂️";
            rows.add(List.of(createBtn(roleIcon + " " + u[1], "T_ASS_U:" + toolId + ":" + u[0])));
        }
        markup.setKeyboard(rows);
        String text = String.format("🪛 Вы выбрали: <b>%s</b>\n\n👤 <b>Шаг 2 из 2: Кому выдать этот инструмент?</b>\n\n<i>(Если инструмент выбран неверно, просто проигнорируйте это меню и нажмите кнопку «🤝 Выдать мастеру» заново)</i>", fullToolNameInfo);
        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUsersForToolReturn(long chatId) {
        List<String[]> users = DatabaseManager.getUsersWithAssignedTools();
        if (users.isEmpty()) {
            sendMenu(chatId, "ADMIN", "ℹ️ Сейчас ни у кого из сотрудников нет инструмента на руках.");
            return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) {
            rows.add(List.of(createBtn("👤 " + u[1], "T_RET_U:" + u[0])));
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "↩️ <b>Шаг 1 из 2: У кого забираем инструмент?</b>\nВыберите сотрудника:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUserToolsForReturn(long chatId, long targetUserId) {
        List<String[]> tools = DatabaseManager.getUserAssignedToolsForReturn(targetUserId);
        if (tools.isEmpty()) {
            sendMenu(chatId, "ADMIN", "У этого сотрудника больше нет инструмента.");
            return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) {
            String btnText = String.format("%s (Инв: %s)", t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1], t[2]);
            rows.add(List.of(createBtn("🪛 " + btnText, "T_RET_T:" + t[0])));
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "↩️ <b>Шаг 2 из 2: Какой инструмент возвращаем на склад?</b>\nНажмите на нужную позицию:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUsersForToolWriteOff(long chatId) {
        List<String[]> users = DatabaseManager.getUsersWithAssignedTools();
        if (users.isEmpty()) {
            sendMenu(chatId, "ADMIN", "ℹ️ Сейчас ни у кого из сотрудников нет инструмента на руках.");
            return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] u : users) {
            rows.add(List.of(createBtn("👤 " + u[1], "T_WO_U:" + u[0])));
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗑 <b>У кого списываем инструмент?</b>\nВыберите сотрудника:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendUserToolsForWriteOff(long chatId, long targetUserId) {
        List<String[]> tools = DatabaseManager.getUserAssignedToolsForReturn(targetUserId);
        if (tools.isEmpty()) {
            sendMenu(chatId, "ADMIN", "У этого сотрудника нет инструмента.");
            return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) {
            String btnText = String.format("%s (Инв: %s)", t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1], t[2]);
            rows.add(List.of(createBtn("🪛 " + btnText, "T_WO_DO:" + t[0])));
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗑 <b>Какой инструмент списываем?</b>\nНажмите на нужную позицию:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendGroupsForToolWriteOff(long chatId) {
        List<String[]> groups = DatabaseManager.getAvailableToolGroups();
        if (groups.isEmpty()) {
            sendMenu(chatId, "ADMIN", "📦 На складе нет инструмента.");
            return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] g : groups) {
            String shortName = g[1].length() > 30 ? g[1].substring(0, 30) + "…" : g[1];
            rows.add(List.of(createBtn(shortName + " (" + g[2] + " шт)", "T_WO_G:" + g[0])));
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗑 <b>Выберите категорию инструмента на складе для списания:</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendSpecificToolsInStockForWriteOff(long chatId, int firstId) {
        List<String[]> tools = DatabaseManager.getToolsInStockByGroup(String.valueOf(firstId));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) {
            String btnText = String.format("%s (Инв: %s)", t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1], t[2]);
            rows.add(List.of(createBtn("🪛 " + btnText, "T_WO_DO:" + t[0])));
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🗑 <b>Выберите конкретную единицу для списания:</b>");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendToolsForRestore(long chatId) {
        List<String[]> tools = DatabaseManager.getWrittenOffToolsForRestore();
        if (tools.isEmpty()) {
            sendMenu(chatId, "ADMIN", "🗄 В архиве пока нет списанного инструмента.");
            return;
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (String[] t : tools) {
            String btnText = String.format("%s (Инв: %s)", t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1], t[2]);
            rows.add(List.of(createBtn("♻ " + btnText, "T_RES_DO:" + t[0])));
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "♻️ <b>Какой инструмент восстановить на склад?</b>\nНажмите на нужную позицию:");
        msg.setParseMode("HTML"); msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) {}
    }

    private void sendMyInventoryMenu(long chatId, String text) {
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        message.setParseMode("HTML");
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📦 Мои материалы");
        row1.add("🪛 Мой инструмент");
        KeyboardRow row2 = new KeyboardRow();
        row2.add("🔙 Назад");
        markup.setKeyboard(List.of(row1, row2));
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) {}
    }

    private void sendScheduleMenu(long chatId) {
        SendMessage message = new SendMessage(String.valueOf(chatId), "🗓 <b>Графики и смены:</b>\nВыберите, что хотите посмотреть:");
        message.setParseMode("HTML");
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        KeyboardRow row1 = new KeyboardRow();
        row1.add("🗓 Мой график");
        row1.add("🤝 С кем я в смене?");
        KeyboardRow row2 = new KeyboardRow();
        row2.add("👁 График коллеги");
        row2.add("🔙 Назад");
        markup.setKeyboard(List.of(row1, row2));
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) {}
    }

    private void sendColleagueSelectionMenu(long chatId, List<String> names) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (String name : names) {
            InlineKeyboardButton btn = new InlineKeyboardButton("👤 " + name);
            btn.setCallbackData("VIEW_SCHED:" + name);
            rows.add(List.of(btn));
        }

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "👁 <b>График коллеги</b>\n\nВыберите фамилию сотрудника из списка:");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendUploadsMenu(long chatId, String text) {
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        message.setParseMode("HTML");
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);
        KeyboardRow row1 = new KeyboardRow(); row1.add("📥 Загрузить ведомость (Excel)");
        KeyboardRow row2 = new KeyboardRow(); row2.add("📥 Загрузить инструмент (Excel)");
        KeyboardRow row3 = new KeyboardRow(); row3.add("📥 Загрузить график (Excel)");
        KeyboardRow row4 = new KeyboardRow(); row4.add("📥 Загрузить план ОРШ (Excel)");
        KeyboardRow row5 = new KeyboardRow(); row5.add("🔙 Назад");
        markup.setKeyboard(List.of(row1, row2, row3, row4, row5));
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) {}
    }

    // Метод-геттер для Бота-надзирателя (из Main.java)
    public Map<Long, Integer> getForceWelderReturnIds() {
        return forceWelderReturnIds;
    }
}