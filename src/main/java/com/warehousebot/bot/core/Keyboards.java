package com.warehousebot.bot.core;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;

/** Построение inline-клавиатур. */
public final class Keyboards {

    public static final String CANCEL_PROMPT = "CANCEL_PROMPT";
    public static final String GENERIC_CLOSE = "GENERIC_CLOSE";

    private Keyboards() {
    }

    public static InlineKeyboardButton btn(String text, String callbackData) {
        InlineKeyboardButton b = new InlineKeyboardButton(text);
        b.setCallbackData(callbackData);
        return b;
    }

    public static List<InlineKeyboardButton> row(InlineKeyboardButton... buttons) {
        return List.of(buttons);
    }

    @SafeVarargs
    public static InlineKeyboardMarkup of(List<InlineKeyboardButton>... rows) {
        return new InlineKeyboardMarkup(List.of(rows));
    }

    public static InlineKeyboardMarkup of(List<List<InlineKeyboardButton>> rows) {
        return new InlineKeyboardMarkup(rows);
    }

    public static InlineKeyboardMarkup single(String text, String callbackData) {
        return of(row(btn(text, callbackData)));
    }

    /** Раскладывает кнопки по {@code perRow} в ряд (последний ряд может быть неполным). */
    public static List<List<InlineKeyboardButton>> grid(List<InlineKeyboardButton> buttons, int perRow) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> current = new ArrayList<>();
        for (InlineKeyboardButton b : buttons) {
            current.add(b);
            if (current.size() == perRow) {
                rows.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) rows.add(current);
        return rows;
    }

    /** Кнопка «❌ Отменить», сбрасывающая текущий диалог. */
    public static InlineKeyboardMarkup cancelPrompt() {
        return single("❌ Отменить", CANCEL_PROMPT);
    }

    /** Кнопка «❌ Закрыть», убирающая окно. */
    public static InlineKeyboardMarkup close() {
        return single("❌ Закрыть", GENERIC_CLOSE);
    }

    /** Изменяемый список рядов для пошаговой сборки клавиатуры. */
    public static final class Rows {
        private final List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        public Rows add(InlineKeyboardButton... buttons) {
            rows.add(List.of(buttons));
            return this;
        }

        public Rows addRow(List<InlineKeyboardButton> row) {
            if (!row.isEmpty()) rows.add(row);
            return this;
        }

        public Rows addAll(List<List<InlineKeyboardButton>> more) {
            rows.addAll(more);
            return this;
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }

        public InlineKeyboardMarkup build() {
            return new InlineKeyboardMarkup(rows);
        }
    }

    public static Rows rows() {
        return new Rows();
    }
}
