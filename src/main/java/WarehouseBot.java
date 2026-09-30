import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
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

    private final Map<Long, Integer> waitingTakeMaterialId = new HashMap<>();
    private final Map<Long, WriteOffSession> writeOffSessions = new HashMap<>();

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
        // 1. Обработка нажатий на Inline-кнопки под сообщениями
        if (update.hasCallbackQuery()) {
            long chatId = update.getCallbackQuery().getMessage().getChatId();
            String data = update.getCallbackQuery().getData();
            String firstName = update.getCallbackQuery().getFrom().getFirstName();
            String role = DatabaseManager.getUserRole(chatId, firstName);

            if (data.startsWith("TAKE_MAT:")) {
                writeOffSessions.remove(chatId);
                int materialId = Integer.parseInt(data.split(":")[1]);
                waitingTakeMaterialId.put(chatId, materialId);
                sendMenu(chatId, role, "✍️️ Введите количество, которое вы берете со склада (например: <code>50</code>):");
                return;
            }

            if (data.startsWith("WO_MAT:")) {
                String[] parts = data.split(":");
                WriteOffSession session = new WriteOffSession();
                session.materialId = Integer.parseInt(parts[1]);
                session.materialName = parts[2];
                session.unit = parts[3];
                session.priceWithVat = Double.parseDouble(parts[4]);
                session.maxAvailable = Double.parseDouble(parts[5]);
                session.step = "WAIT_QTY";
                writeOffSessions.put(chatId, session);

                sendMenu(chatId, role, String.format(
                        "Выбрано: <b>%s</b> (у вас на руках: <b>%.0f %s</b>)\n\n✍️ Введите количество для списания:",
                        session.materialName, session.maxAvailable, session.unit));
                return;
            }

            if (data.startsWith("WO_TYPE:") && writeOffSessions.containsKey(chatId)) {
                WriteOffSession session = writeOffSessions.get(chatId);
                String type = data.split(":")[1];
                if ("PAID".equals(type)) {
                    session.isPaidReceipt = true;
                    session.step = "WAIT_RECEIPT_NUM";
                    sendMenu(chatId, role, "🧾 <b>Списание по квитанции (шаг 1 из 4)</b>\nВведите <b>номер квитанции</b>:");
                } else {
                    session.isPaidReceipt = false;
                    session.step = "WAIT_FREE_PHONE";
                    sendMenu(chatId, role, "🛠 <b>Техническое списание (шаг 1 из 5)</b>\nВведите <b>номер телефона</b>, на который оформлена заявка:");
                }
                return;
            }

            // Выбор КОДА ЗАКРЫТИЯ заявки (212, 227, 215, 226, 214, 217)
            if (data.startsWith("WO_CODE:") && writeOffSessions.containsKey(chatId)) {
                WriteOffSession session = writeOffSessions.get(chatId);
                session.closingCode = data.split(":")[1];
                session.step = "WAIT_REASON";
                sendReasonButtons(chatId, session.closingCode);
                return;
            }

            // Выбор причины при списании без квитанции (короткие коды до 64 байт)
            if (data.startsWith("WO_REASON:") && writeOffSessions.containsKey(chatId)) {
                WriteOffSession session = writeOffSessions.remove(chatId);
                String reasonCode = data.split(":")[1];
                session.reason = switch (reasonCode) {
                    case "1" -> "Повреждение грызунами";
                    case "2" -> "Обрыв / перемонтаж линии";
                    case "3" -> "Замена пигтейла / адаптера / КДЗС";
                    default -> reasonCode;
                };
                String res = DatabaseManager.completeWriteOff(chatId, session);
                sendMenu(chatId, role, res);
                return;
            }
            return;
        }

        // 2. Обработка текстовых сообщений
        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String firstName = update.getMessage().getFrom().getFirstName();
            String text = update.getMessage().getText();
            String role = DatabaseManager.getUserRole(chatId, firstName);

            // Если нажата кнопка главного меню — сбрасываем незаконченные диалоги
            if (text.startsWith("📦") || text.startsWith("🧰") || text.startsWith("📝") ||
                    text.startsWith("📋") || text.startsWith("🔢") || text.startsWith("📊") ||
                    text.startsWith("📥") || text.startsWith("📑") || text.equals("/start")) {
                waitingTakeMaterialId.remove(chatId);
                writeOffSessions.remove(chatId);
            }

            if (waitingTakeMaterialId.containsKey(chatId)) {
                try {
                    double qty = Double.parseDouble(text.trim().replace(",", "."));
                    int materialId = waitingTakeMaterialId.remove(chatId);
                    String result = DatabaseManager.takeMaterialFromWarehouse(chatId, materialId, qty);
                    sendMenu(chatId, role, result);
                } catch (NumberFormatException e) {
                    sendMenu(chatId, role, "❌ Введите число (например: <code>20</code>) или выберите другой пункт меню.");
                }
                return;
            }

            if (writeOffSessions.containsKey(chatId)) {
                handleWriteOffStep(chatId, role, text);
                return;
            }

            switch (text) {
                case "/start" -> {
                    String roleTitle = role.equals("ADMIN") ? "Администратор (МОЛ)" : "Мастер бюро ремонта";
                    sendMenu(chatId, role, "Привет, <b>" + firstName + "</b>! 👋\n"
                            + "Ваша роль в системе: <b>" + roleTitle + "</b>.\n\n"
                            + "Выберите нужное действие на кнопках внизу экрана:");
                }

                case "📦 Склад (Наличие и цены)" -> sendWarehouseList(chatId, role);

                case "🧰 Мой подотчет" -> sendMenu(chatId, role, DatabaseManager.getUserBalanceText(chatId));

                case "📝 Списать материал" -> startWriteOffMenu(chatId, role);

                case "📋 Тарифы услуг" -> sendMenu(chatId, role, DatabaseManager.getServiceTariffsText());

                case "🔢 Коды закрытия" -> sendMenu(chatId, role, DatabaseManager.getClosingCodesText());

                case "📊 У кого что на руках" -> {
                    if (role.equals("ADMIN")) {
                        sendMenu(chatId, role, DatabaseManager.getAllWorkersBalancesText());
                    }
                }

                case "📥 Загрузить ведомость (Excel)" -> {
                    if (role.equals("ADMIN")) {
                        sendMenu(chatId, role, "📎 Отправьте файл оборотной ведомости <b>.xlsx</b> в этот чат.");
                    }
                }

                case "📑 Скачать отчет за месяц" -> {
                    if (role.equals("ADMIN")) {
                        sendExcelReport(chatId, role);
                    }
                }

                default -> {
                    if (text.startsWith("+услуга ") && role.equals("ADMIN")) {
                        handleAddService(chatId, role, text);
                    } else {
                        sendMenu(chatId, role, "Используйте кнопки меню внизу экрана 👇");
                    }
                }
            }
        }
    }

    private void startWriteOffMenu(long chatId, String role) {
        List<String[]> userMats = DatabaseManager.getUserMaterialsForWriteOff(chatId);
        if (userMats.isEmpty()) {
            sendMenu(chatId, role, "🧰 У вас на руках нет материалов для списания. Сначала возьмите их в разделе «📦 Склад».");
            return;
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (String[] m : userMats) {
            String id = m[0];
            String name = m[1];
            String unit = m[2];
            String priceVat = m[3];
            double qty = Double.parseDouble(m[4]);

            InlineKeyboardButton btn = new InlineKeyboardButton();
            btn.setText(String.format("📝 %s (доступно: %.0f %s)", name, qty, unit));
            btn.setCallbackData(String.format("WO_MAT:%s:%s:%s:%s:%.0f", id, name, unit, priceVat, qty));
            rows.add(List.of(btn));
        }

        markup.setKeyboard(rows);
        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText("📝 <b>Выберите материал из вашего подотчета, который нужно списать:</b>");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleWriteOffStep(long chatId, String role, String text) {
        WriteOffSession s = writeOffSessions.get(chatId);

        switch (s.step) {
            case "WAIT_QTY" -> {
                try {
                    double qty = Double.parseDouble(text.trim().replace(",", "."));
                    if (qty <= 0 || qty > s.maxAvailable) {
                        sendMenu(chatId, role, String.format("❌ Введите число от 1 до %.0f %s:", s.maxAvailable, s.unit));
                        return;
                    }
                    s.quantity = qty;
                    s.step = "WAIT_TYPE";

                    InlineKeyboardButton btnPaid = new InlineKeyboardButton("🧾 По квитанции (вина абонента)");
                    btnPaid.setCallbackData("WO_TYPE:PAID");

                    InlineKeyboardButton btnFree = new InlineKeyboardButton("🛠 Без квитанции (крысы / износ)");
                    btnFree.setCallbackData("WO_TYPE:FREE");

                    InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(btnPaid), List.of(btnFree)));
                    SendMessage msg = new SendMessage(String.valueOf(chatId),
                            String.format("Списываем: <b>%s — %.0f %s</b>.\nВыберите тип списания:", s.materialName, s.quantity, s.unit));
                    msg.setParseMode("HTML");
                    msg.setReplyMarkup(markup);
                    execute(msg);
                } catch (Exception e) {
                    sendMenu(chatId, role, "❌ Введите количество числом (например: <code>15</code>):");
                }
            }

            // Ветка 1: ПО КВИТАНЦИИ
            case "WAIT_RECEIPT_NUM" -> {
                s.receiptNumber = text.trim();
                s.step = "WAIT_PAID_PHONE";
                sendMenu(chatId, role, "🧾 <b>Шаг 2 из 4:</b> Введите <b>номер телефона</b>, на который оформлена заявка:");
            }
            case "WAIT_PAID_PHONE" -> {
                s.phoneNumber = text.trim();
                s.step = "WAIT_PAID_CONTRACT";
                sendMenu(chatId, role, "🧾 <b>Шаг 3 из 4:</b> Введите <b>номер договора</b>:");
            }
            case "WAIT_PAID_CONTRACT" -> {
                s.contractNumber = text.trim();
                s.step = "WAIT_PAID_ADDRESS";
                sendMenu(chatId, role, "🧾 <b>Шаг 4 из 4:</b> Введите <b>адрес абонента</b>:");
            }
            case "WAIT_PAID_ADDRESS" -> {
                s.address = text.trim();
                writeOffSessions.remove(chatId);
                String res = DatabaseManager.completeWriteOff(chatId, s);
                sendMenu(chatId, role, res);
            }

            // Ветка 2: БЕЗ КВИТАНЦИИ (Телефон -> Договор -> Адрес -> Код закрытия -> Причина)
            case "WAIT_FREE_PHONE" -> {
                s.phoneNumber = text.trim();
                s.step = "WAIT_FREE_CONTRACT";
                sendMenu(chatId, role, "🛠 <b>Шаг 2 из 5:</b> Введите <b>номер договора</b> (или поставьте прочерк <code>-</code>, если нет):");
            }
            case "WAIT_FREE_CONTRACT" -> {
                s.contractNumber = text.trim();
                s.step = "WAIT_FREE_ADDRESS";
                sendMenu(chatId, role, "🛠 <b>Шаг 3 из 5:</b> Введите <b>адрес</b>:");
            }
            case "WAIT_FREE_ADDRESS" -> {
                s.address = text.trim();
                s.step = "WAIT_CLOSING_CODE";
                sendClosingCodeButtons(chatId, s.phoneNumber, s.contractNumber);
            }
            case "WAIT_REASON" -> {
                s.reason = text.trim();
                writeOffSessions.remove(chatId);
                String res = DatabaseManager.completeWriteOff(chatId, s);
                sendMenu(chatId, role, res);
            }
        }
    }

    // Выпадающий список (Inline-кнопки) выбора кода закрытия заявки
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

        SendMessage msg = new SendMessage(String.valueOf(chatId),
                "🔢 <b>Шаг 4 из 5:</b> Выберите <b>код закрытия заявки</b> из списка ниже:" + warning);
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Кнопки выбора причины после выбора кода закрытия (с коротким CallbackData)
    private void sendReasonButtons(long chatId, String chosenCode) {
        InlineKeyboardButton b1 = new InlineKeyboardButton("🐀 Повреждение грызунами");
        b1.setCallbackData("WO_REASON:1");
        InlineKeyboardButton b2 = new InlineKeyboardButton("⚡ Обрыв / перемонтаж линии");
        b2.setCallbackData("WO_REASON:2");
        InlineKeyboardButton b3 = new InlineKeyboardButton("🔧 Замена пигтейла / адаптера / КДЗС");
        b3.setCallbackData("WO_REASON:3");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(b1), List.of(b2), List.of(b3)));
        SendMessage msg = new SendMessage(String.valueOf(chatId),
                "Выбран код: <b>" + chosenCode + "</b>.\n🛠 <b>Шаг 5 из 5:</b> Выберите причину списания кнопкой или напишите свою текстом:");
        msg.setParseMode("HTML");
        msg.setReplyMarkup(markup);
        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendWarehouseList(long chatId, String role) {
        List<String[]> materials = DatabaseManager.getAvailableMaterials();
        if (materials.isEmpty()) {
            sendMenu(chatId, role, "📦 На складе сейчас нет доступных материалов.");
            return;
        }

        StringBuilder sb = new StringBuilder("📦 <b>Доступно на складе (цены с НДС +20%):</b>\n\n");
        InlineKeyboardMarkup inlineMarkup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        for (String[] m : materials) {
            String id = m[0];
            String name = m[1];
            String unit = m[2];
            String priceVat = m[3];
            String qty = m[4];

            sb.append(String.format("• <b>%s</b> — остаток: <b>%s %s</b> <i>(по %s руб/%s)</i>\n",
                    name, qty, unit, priceVat, unit));

            InlineKeyboardButton btn = new InlineKeyboardButton();
            btn.setText("➕ Взять: " + name + " (" + qty + " " + unit + ")");
            btn.setCallbackData("TAKE_MAT:" + id);
            rows.add(List.of(btn));
        }

        sb.append("\n👇 <b>Нажмите на кнопку нужного материала ниже, чтобы взять его себе в подотчет:</b>");
        inlineMarkup.setKeyboard(rows);

        SendMessage msg = new SendMessage();
        msg.setChatId(String.valueOf(chatId));
        msg.setText(sb.toString());
        msg.setParseMode("HTML");
        msg.setReplyMarkup(inlineMarkup);

        try {
            execute(msg);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleAddService(long chatId, String role, String text) {
        try {
            String data = text.substring(8).trim();
            String[] parts = data.split(";");
            if (parts.length == 4) {
                double price = Double.parseDouble(parts[3].trim().replace(",", "."));
                boolean ok = DatabaseManager.addServiceTariff(parts[0], parts[1], parts[2], price);
                if (ok) {
                    sendMenu(chatId, role, "✅ Услуга успешно добавлена в шпаргалку!\nНажмите «📋 Тарифы услуг», чтобы проверить.");
                    return;
                }
            }
        } catch (Exception ignored) {}
        sendMenu(chatId, role, "❌ Неверный формат. Пример:\n<code>+услуга Монтаж ; Прокладка кабеля UTP ; 1 м ; 0.65</code>");
    }

    private void sendExcelReport(long chatId, String role) {
        sendMenu(chatId, role, "⏳ Формирую Excel-отчет по складу и списаниям...");
        File reportFile = ExcelReportGenerator.generateMonthlyReport();
        if (reportFile != null && reportFile.exists()) {
            SendDocument sendDoc = new SendDocument();
            sendDoc.setChatId(String.valueOf(chatId));
            sendDoc.setDocument(new InputFile(reportFile));
            sendDoc.setCaption("📊 Итоговый отчет по складу и списаниям (4 вкладки внутри файла).");
            try {
                execute(sendDoc);
            } catch (TelegramApiException e) {
                e.printStackTrace();
                sendMenu(chatId, role, "❌ Ошибка при отправке файла в Telegram.");
            }
        } else {
            sendMenu(chatId, role, "❌ Не удалось сформировать отчет.");
        }
    }

    public void sendMenu(long chatId, String role, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("HTML");

        ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
        keyboardMarkup.setResizeKeyboard(true);
        List<KeyboardRow> keyboard = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("📦 Склад (Наличие и цены)");
        row1.add("🧰 Мой подотчет");
        keyboard.add(row1);

        KeyboardRow row2 = new KeyboardRow();
        row2.add("📝 Списать материал");
        row2.add("📋 Тарифы услуг");
        row2.add("🔢 Коды закрытия");
        keyboard.add(row2);

        if ("ADMIN".equals(role)) {
            KeyboardRow adminRow1 = new KeyboardRow();
            adminRow1.add("📊 У кого что на руках");
            adminRow1.add("📥 Загрузить ведомость (Excel)");
            keyboard.add(adminRow1);

            KeyboardRow adminRow2 = new KeyboardRow();
            adminRow2.add("📑 Скачать отчет за месяц");
            keyboard.add(adminRow2);
        }

        keyboardMarkup.setKeyboard(keyboard);
        message.setReplyMarkup(keyboardMarkup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}