import org.telegram.telegrambots.bots.TelegramLongPollingBot;
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

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasDocument()) {
            long chatId = update.getMessage().getChatId();
            String firstName = update.getMessage().getFrom().getFirstName();
            String role = checkRoleAndNotify(chatId, firstName);
            if ("BANNED".equals(role) || "PENDING".equals(role)) return;

            if (!"ADMIN".equals(role)) {
                sendMenu(chatId, role, "❌ Загружать файлы может только Администратор (МОЛ).");
                return;
            }

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
                if ("TURNOVER".equals(state)) {
                    result = ExcelImporter.importTurnoverSheet(localFile);
                } else if ("SCHEDULE".equals(state)) {
                    result = ExcelImporter.importScheduleSheet(localFile);
                } else if ("TOOLS".equals(state)) {
                    result = ExcelImporter.importToolsSheet(localFile);
                } else if ("ORSH".equals(state)) {
                    result = ExcelImporter.importOrshSheet(localFile);
                } else {
                    result = "❌ Неизвестное состояние загрузки файла.";
                }

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

            if (waitingOrshPhoto.containsKey(chatId)) {
                int orshId = waitingOrshPhoto.remove(chatId);
                sendMenu(chatId, role, "⏳ Скачиваю фото и отправляю на почту руководству...");

                try {
                    // Берем фото максимального качества (оно всегда последнее в списке)
                    var photos = update.getMessage().getPhoto();
                    String fileId = photos.get(photos.size() - 1).getFileId();
                    GetFile getFile = new GetFile(fileId);
                    org.telegram.telegrambots.meta.api.objects.File tgFile = execute(getFile);
                    File localFile = downloadFile(tgFile);

                    // Достаем данные шкафа из базы
                    String[] orsh = DatabaseManager.getOrshById(orshId);
                    String subject = "Осмотр ОРШ-" + orsh[0] + " (" + firstName + ")";
                    String textBody = String.format("Плановый осмотр ОРШ\n\nНомер: %s\nАдрес: %s\nМестоположение: %s\n\nВыполнил: %s",
                            orsh[0], orsh[1], orsh[2], firstName);

                    // Переименовываем файл для удобства
                    File renamedFile = new File(localFile.getParent(), "ORSH_" + orsh[0] + "_" + firstName + ".jpg");
                    localFile.renameTo(renamedFile);

                    // Отправляем письмо!
                    EmailSender.sendOrshReport(subject, textBody, renamedFile);

                    // Удаляем файл с компьютера и закрываем шкаф в базе
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

        if (update.hasCallbackQuery()) {
            long chatId = update.getCallbackQuery().getMessage().getChatId();
            String data = update.getCallbackQuery().getData();
            String firstName = update.getCallbackQuery().getFrom().getFirstName();
            String role = checkRoleAndNotify(chatId, firstName);

            if (data.startsWith("ORSH_SEL:")) {
                int orshId = Integer.parseInt(data.split(":")[1]);
                sendOrshDetails(chatId, orshId);
                return;
            }
            if (data.startsWith("ORSH_PROB:")) {
                int orshId = Integer.parseInt(data.split(":")[1]);
                waitingOrshPhoto.remove(chatId); // Отменяем режим ожидания фото
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

            if (data.equals("TOOL_MENU_AUDIT") && "ADMIN".equals(role)) {
                sendMenu(chatId, role, DatabaseManager.getToolsAuditText());
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

                // Оповещаем сотрудника в ЛС о том, что ему выдали инструмент
                if (result.startsWith("✅") && targetUserId != chatId) {
                    sendDirectNotification(targetUserId, "🔔 <b>Вам выдан новый инструмент!</b>\nМОЛ закрепил за вами новую позицию. Нажмите «🪛 Мой инструмент», чтобы проверить ваш список.");
                }
                return;
            }
            // Обработка кнопок одобрения/отклонения от МОЛ
            if (data.startsWith("NEW_USER_APP:") && "ADMIN".equals(role)) {
                long targetId = Long.parseLong(data.split(":")[1]);
                DatabaseManager.setBanStatus(targetId, false); // Одобряем (переводим в WORKER)
                sendMenu(chatId, role, "✅ Пользователь " + targetId + " одобрен и получил доступ.");
                sendDirectNotification(targetId, "✅ <b>Администратор одобрил ваш доступ!</b>\nТеперь вы можете пользоваться ботом. Нажмите /start для обновления меню.");
                return;
            }
            if (data.startsWith("NEW_USER_REJ:") && "ADMIN".equals(role)) {
                long targetId = Long.parseLong(data.split(":")[1]);
                DatabaseManager.setBanStatus(targetId, true); // Блокируем
                sendMenu(chatId, role, "❌ Пользователь " + targetId + " заблокирован.");
                return;
            }

            if ("BANNED".equals(role) || "PENDING".equals(role)) return;

            // --- КАЛЬКУЛЯТОР КВИТАНЦИЙ ---
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
                        // Это разовая услуга, сразу ставим количество 1 и добавляем в корзину
                        item.quantity = 1.0;
                        s.items.add(item);
                        sendCartMenu(chatId, role);
                    } else {
                        // Услуга требует ввода количества (метры, штуки)
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
                    receiptSessions.remove(chatId); // Очищаем корзину после расчета
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
                sendMenu(chatId, role, "✅ Отлично! Ваш профиль привязан к: <b>" + excelName + "</b>\n\n" + DatabaseManager.getFormattedSchedule(excelName));
                return;
            }

            if (data.startsWith("TAKE_MAT:")) {
                writeOffSessions.remove(chatId);
                int materialId = Integer.parseInt(data.split(":")[1]);
                waitingTakeMaterialId.put(chatId, materialId);
                sendCancelKeyboard(chatId, "✍️ Введите количество, которое вы берете со склада (например: <code>5</code> или <code>0.02</code>):");
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
                sendCancelKeyboard(chatId, String.format(
                        "Выбрано: <b>%s</b>\nДоступно у вас: <b>%s %s</b>\n\n✍️ Введите количество:",
                        session.materialName, DatabaseManager.fmtQty(session.maxAvailable), session.unit));
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
                // Извлекаем сессию и сразу удаляем её из ожиданий, так как это финальный шаг
                WriteOffSession session = writeOffSessions.remove(chatId);
                session.closingCode = data.split(":")[1];

                // Автоматически прописываем причину для базы данных на основе выбранного кода
                session.reason = switch (session.closingCode) {
                    case "212" -> "Ремонт ВОК на участке ОРК-ОРА";
                    case "227" -> "Выправление волокна";
                    case "215" -> "В ОРШ: выправление пигтейла/волокна";
                    case "226" -> "В ОРШ: замена пигтейла / адаптера";
                    case "214" -> "Участок ОРШ-ОРК: ремонт/замена райзера";
                    case "217" -> "Участок ОРШ-ОРК: запасной модуль";
                    default -> "Тех. списание (Код " + session.closingCode + ")";
                };

                // Сразу завершаем списание
                String res = DatabaseManager.completeWriteOff(chatId, session);
                sendMenu(chatId, role, res);
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

            if (text.equals("❌ Отменить")) {
                waitingTakeMaterialId.remove(chatId);
                writeOffSessions.remove(chatId);
                fileWaitState.remove(chatId);
                sendMenu(chatId, role, "🚫 <b>Действие отменено.</b>");
                waitingToolWriteOffReason.remove(chatId);
                waitingOrshPhoto.remove(chatId);
                waitingOrshProblemReason.remove(chatId);
                return;
            }

            if (text.equals("🔙 Назад")) {
                if (waitingTakeMaterialId.containsKey(chatId)) {
                    waitingTakeMaterialId.remove(chatId);
                    sendWarehouseList(chatId, role);
                    return;
                }
                if (writeOffSessions.containsKey(chatId)) {
                    handleWriteOffBack(chatId, role);
                    return;
                }
                sendMenu(chatId, role, "Вы вернулись в главное меню:");
                return;
            }

            if (text.startsWith("📦") || text.startsWith("🧰") || text.startsWith("📝") ||
                    text.startsWith("📋") || text.startsWith("🔢") ||
                    text.startsWith("📊") || text.startsWith("📥") || text.startsWith("📑") ||
                    text.startsWith("🗓") || text.startsWith("🔍") || text.startsWith("📢") ||
                    text.startsWith("👥") || text.startsWith("🪛") || text.startsWith("🛠") || text.equals("/start")) {
                waitingTakeMaterialId.remove(chatId);
                writeOffSessions.remove(chatId);
                fileWaitState.remove(chatId);
                waitingToolWriteOffReason.remove(chatId);
                waitingOrshPhoto.remove(chatId);
                waitingOrshProblemReason.remove(chatId);
            }

            if (waitingTakeMaterialId.containsKey(chatId)) {
                try {
                    double qty = Double.parseDouble(text.trim().replace(",", "."));
                    int materialId = waitingTakeMaterialId.remove(chatId);
                    String result = DatabaseManager.takeMaterialFromWarehouse(chatId, materialId, qty);
                    sendMenu(chatId, role, result);
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
                handleWriteOffStep(chatId, role, text);
                return;
            }

            // Обработка ввода количества для калькулятора квитанций
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

            // Обработка текста для массовой рассылки
            if ("WAIT_BROADCAST_TEXT".equals(fileWaitState.get(chatId))) {
                fileWaitState.remove(chatId);
                sendMenu(chatId, role, "⏳ Отправляю сообщение всем сотрудникам...");

                List<Long> allUsers = DatabaseManager.getAllUserIds();
                int successCount = 0;
                String broadcastMsg = "📢 <b>ИНФОРМАЦИЯ ОТ РУКОВОДИТЕЛЯ:</b>\n\n" + text;

                for (Long userId : allUsers) {
                    if (userId == chatId) continue; // Себе не отправляем
                    try {
                        SendMessage msg = new SendMessage(String.valueOf(userId), broadcastMsg);
                        msg.setParseMode("HTML");
                        execute(msg);
                        successCount++;
                    } catch (TelegramApiException e) {
                        // Пользователь мог заблокировать бота
                    }
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

                case "🗓 Мой график" -> {
                    String excelName = DatabaseManager.getUserExcelName(chatId);
                    if (excelName != null) {
                        sendMenu(chatId, role, DatabaseManager.getFormattedSchedule(excelName));
                    } else {
                        List<String> names = DatabaseManager.getAvailableExcelNames();
                        if (names.isEmpty()) {
                            sendMenu(chatId, role, "ℹ️ График работ еще не загружен администратором.");
                        } else {
                            sendNameBindingMenu(chatId, names);
                        }
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

                // --- ГРУППА: ЗАГРУЗКИ ---
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
                case "🔍 Аудит остатков" -> {
                    if (role.equals("ADMIN")) {
                        sendMenu(chatId, role, "⏳ Начинаю рассылку уведомлений...");
                        List<Long> workersWithMaterials = DatabaseManager.getUsersWithBalances();
                        int successCount = 0;

                        for (Long workerId : workersWithMaterials) {
                            String balanceText = DatabaseManager.getUserBalanceText(workerId);
                            String alertMsg = "⚠️ <b>ВНИМАНИЕ: АУДИТ ОСТАТКОВ!</b> ⚠️\n\n"
                                    + "Напоминаем о необходимости закрыть подотчет до конца месяца. "
                                    + "Пожалуйста, <b>спишите</b> использованные материалы в квитанции/заявки "
                                    + "или <b>верните</b> остатки на склад!\n\n"
                                    + balanceText;
                            try {
                                SendMessage msg = new SendMessage(String.valueOf(workerId), alertMsg);
                                msg.setParseMode("HTML");
                                execute(msg);
                                successCount++;
                            } catch (TelegramApiException e) {
                                // Пользователь мог заблокировать бота, просто пропускаем
                            }
                        }
                        sendMenu(chatId, role, "✅ Уведомления об аудите успешно доставлены <b>" + successCount + "</b> сотрудникам!");
                    }
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

    private void handleWriteOffStep(long chatId, String role, String text) {
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

        List<Long> adminIds = DatabaseManager.getAdminIds();
        for (Long adminId : adminIds) {
            SendMessage msg = new SendMessage();
            msg.setChatId(String.valueOf(adminId));
            msg.setText(text);
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
            // Ожидаемый формат: +услуга Вызов мастера ; 10.50
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
            try { execute(sendDoc); } catch (TelegramApiException e) { e.printStackTrace(); }
        } else {
            sendMenu(chatId, role, "❌ Не удалось сформировать отчет.");
        }
    }

    public void sendMenu(long chatId, String role, String text) {
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        message.setParseMode("HTML");

        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);
        List<KeyboardRow> keyboard = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("📦 Склад (Наличие и цены)");
        row1.add("🧰 Мой подотчет"); // Теперь это папка, убрали отдельную кнопку инструмента
        keyboard.add(row1);

        KeyboardRow row2 = new KeyboardRow();
        row2.add("📝 Списать / Вернуть");
        row2.add("📸 Плановый осмотр ОРШ");
        keyboard.add(row2);

        // Чтобы 4 кнопки не слипались, разобьем их на 2 ряда
        KeyboardRow row3 = new KeyboardRow();
        row3.add("🧾 Калькулятор квитанции");
        row3.add("📋 Тарифы услуг");
        keyboard.add(row3);

        KeyboardRow row4 = new KeyboardRow();
        row4.add("🔢 Коды закрытия");
        row4.add("🗓 Мой график");
        keyboard.add(row4);

        if ("ADMIN".equals(role)) {
            KeyboardRow adminRow1 = new KeyboardRow();
            adminRow1.add("📊 У кого что на руках");
            adminRow1.add("🛠 Управление инструментом");
            keyboard.add(adminRow1);

            KeyboardRow adminRow2 = new KeyboardRow();
            adminRow2.add("🔍 Аудит остатков");
            adminRow2.add("📑 Скачать отчет за месяц");
            adminRow2.add("📊 Статистика ОРШ");
            keyboard.add(adminRow2);

            KeyboardRow adminRow3 = new KeyboardRow();
            adminRow3.add("📢 Сделать рассылку");
            adminRow3.add("👥 Пользователи");
            keyboard.add(adminRow3);

            KeyboardRow adminRow4 = new KeyboardRow();
            adminRow4.add("📥 Загрузки (Excel)"); // Объединенная кнопка-папка!
            keyboard.add(adminRow4);
        }

        keyboardMarkup.setKeyboard(keyboard);
        message.setReplyMarkup(keyboardMarkup);

        try { execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
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

        InlineKeyboardButton btnAssign = new InlineKeyboardButton("🤝 Выдать мастеру");
        btnAssign.setCallbackData("TOOL_MENU_ASSIGN");

        InlineKeyboardButton btnReturn = new InlineKeyboardButton("↩️ Забрать на склад");
        btnReturn.setCallbackData("TOOL_MENU_RETURN");

        InlineKeyboardButton btnWriteOff = new InlineKeyboardButton("🗑 Списать (поломка)");
        btnWriteOff.setCallbackData("TOOL_MENU_WRITEOFF");

        InlineKeyboardButton btnAudit = new InlineKeyboardButton("📊 Сводка (Аудит)");
        btnAudit.setCallbackData("TOOL_MENU_AUDIT");

        // Создаем кнопку архива
        InlineKeyboardButton btnArchive = new InlineKeyboardButton("🗄 Архив списанного");
        btnArchive.setCallbackData("TOOL_MENU_ARCHIVE");

        // Создаем кнопку восстановления
        InlineKeyboardButton btnRestore = new InlineKeyboardButton("♻️ Восстановить из архива");
        btnRestore.setCallbackData("TOOL_MENU_RESTORE");

        rows.add(List.of(btnAssign));
        rows.add(List.of(btnReturn));
        rows.add(List.of(btnWriteOff));
        rows.add(List.of(btnAudit));
        rows.add(List.of(btnArchive));
        rows.add(List.of(btnRestore));

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🛠 <b>УПРАВЛЕНИЕ ИНСТРУМЕНТОМ</b>\nВыберите действие:");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
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
            InlineKeyboardButton btn = new InlineKeyboardButton(String.format("%s (в наличии: %s шт)", shortName, g[2]));
            btn.setCallbackData("T_SEL_N:" + g[0]);
            rows.add(List.of(btn));
        }

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "🤝 <b>Шаг 1 из 2: Выберите инструмент для выдачи:</b>");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendUsersForToolAssignment(long chatId, int toolId) {
        // Достаем полное название и номер из базы
        String fullToolNameInfo = DatabaseManager.getToolNameAndInvById(toolId);

        List<String[]> users = DatabaseManager.getUsersForToolAssignment();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (String[] u : users) {
            String roleIcon = "ADMIN".equals(u[2]) ? "👑" : "👷‍♂️";
            InlineKeyboardButton btn = new InlineKeyboardButton(roleIcon + " " + u[1]);
            btn.setCallbackData("T_ASS_U:" + toolId + ":" + u[0]);
            rows.add(List.of(btn));
        }

        markup.setKeyboard(rows);

        String text = String.format("🪛 Вы выбрали: <b>%s</b>\n\n👤 <b>Шаг 2 из 2: Кому выдать этот инструмент?</b>\n\n<i>(Если инструмент выбран неверно, просто проигнорируйте это меню и нажмите кнопку «🤝 Выдать мастеру» заново)</i>", fullToolNameInfo);

        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
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
            InlineKeyboardButton btn = new InlineKeyboardButton("👤 " + u[1]);
            btn.setCallbackData("T_RET_U:" + u[0]);
            rows.add(List.of(btn));
        }

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "↩️ <b>Шаг 1 из 2: У кого забираем инструмент?</b>\nВыберите сотрудника:");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
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
            // Формируем текст кнопки: Название (Инв: 123)
            String btnText = String.format("%s (Инв: %s)", t[1].length() > 20 ? t[1].substring(0, 20) + "…" : t[1], t[2]);
            InlineKeyboardButton btn = new InlineKeyboardButton("🪛 " + btnText);
            btn.setCallbackData("T_RET_T:" + t[0]);
            rows.add(List.of(btn));
        }

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "↩️ <b>Шаг 2 из 2: Какой инструмент возвращаем на склад?</b>\nНажмите на нужную позицию:");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
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
            InlineKeyboardButton btn = new InlineKeyboardButton("👤 " + u[1]);
            btn.setCallbackData("T_WO_U:" + u[0]);
            rows.add(List.of(btn));
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
            InlineKeyboardButton btn = new InlineKeyboardButton("🪛 " + btnText);
            btn.setCallbackData("T_WO_DO:" + t[0]);
            rows.add(List.of(btn));
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
            InlineKeyboardButton btn = new InlineKeyboardButton(shortName + " (" + g[2] + " шт)");
            btn.setCallbackData("T_WO_G:" + g[0]);
            rows.add(List.of(btn));
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
            InlineKeyboardButton btn = new InlineKeyboardButton("🪛 " + btnText);
            btn.setCallbackData("T_WO_DO:" + t[0]);
            rows.add(List.of(btn));
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
            InlineKeyboardButton btn = new InlineKeyboardButton("♻️️ " + btnText);
            btn.setCallbackData("T_RES_DO:" + t[0]);
            rows.add(List.of(btn));
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
        try { execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
    }

    private void sendUploadsMenu(long chatId, String text) {
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        message.setParseMode("HTML");
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);

        KeyboardRow row1 = new KeyboardRow();
        row1.add("📥 Загрузить ведомость (Excel)");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("📥 Загрузить инструмент (Excel)");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("📥 Загрузить график (Excel)");

        KeyboardRow row4 = new KeyboardRow();
        row4.add("📥 Загрузить план ОРШ (Excel)"); // <-- НОВОЕ

        KeyboardRow row5 = new KeyboardRow();
        row5.add("🔙 Назад"); // Бывший ряд 4 стал 5-м

        markup.setKeyboard(List.of(row1, row2, row3, row4, row5));
        message.setReplyMarkup(markup);
        try { execute(message); } catch (TelegramApiException e) { e.printStackTrace(); }
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
            if (count >= 30) break; // Показываем максимум 30 шкафов за раз
            String btnText = "ОРШ-" + o[1] + " (" + (o[2].length() > 20 ? o[2].substring(0, 20) + "…" : o[2]) + ")";
            InlineKeyboardButton btn = new InlineKeyboardButton(btnText);
            btn.setCallbackData("ORSH_SEL:" + o[0]);
            rows.add(List.of(btn));
            count++;
        }
        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage(String.valueOf(chatId), "📸 <b>Осталось проверить: " + list.size() + " шт.</b>\nВыберите шкаф из списка (показаны ближайшие " + count + "):");
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
        InlineKeyboardButton btnProb = new InlineKeyboardButton("⚠️ Невозможно сделать фото");
        btnProb.setCallbackData("ORSH_PROB:" + orshId);
        markup.setKeyboard(List.of(List.of(btnProb)));

        String text = String.format("📸 <b>Выбран ОРШ-%s</b>\n\n📍 Адрес: %s\n🧭 Местоположение: %s\n\n👇 <b>Отправьте фото шкафа прямо в этот чат!</b>",
                orsh[0], orsh[1], orsh[2]);

        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try { execute(msg); } catch (TelegramApiException e) { e.printStackTrace(); }
    }
}