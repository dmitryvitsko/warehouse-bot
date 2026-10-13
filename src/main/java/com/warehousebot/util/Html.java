package com.warehousebot.util;

/** Экранирование для parse_mode=HTML в Telegram. */
public final class Html {

    private Html() {
    }

    /**
     * Экранирует пользовательский текст, вставляемый в HTML-шаблон сообщения.
     * Без этого символы {@code < > &} в имени или адресе приводят к отказу Telegram отправить сообщение.
     */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep = switch (c) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                default -> null;
            };
            if (rep != null) {
                if (sb == null) {
                    sb = new StringBuilder(s.length() + 16);
                    sb.append(s, 0, i);
                }
                sb.append(rep);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }
}
