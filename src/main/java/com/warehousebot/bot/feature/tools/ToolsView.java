package com.warehousebot.bot.feature.tools;

import com.warehousebot.bot.core.Keyboards;
import com.warehousebot.domain.ActiveUser;
import com.warehousebot.domain.Tool;
import com.warehousebot.domain.ToolGroup;
import com.warehousebot.domain.ToolHolder;
import com.warehousebot.domain.ToolPosition;
import com.warehousebot.domain.UserRef;
import com.warehousebot.domain.WrittenOffTool;
import com.warehousebot.service.ToolService.Assignment;
import com.warehousebot.service.ToolService.BulkReturn;
import com.warehousebot.util.Formats;
import com.warehousebot.util.Html;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.warehousebot.bot.core.Keyboards.btn;
import static com.warehousebot.bot.core.Keyboards.row;

/** Тексты и клавиатуры панели управления инструментом. */
final class ToolsView {

    static final String PANEL = "🧰 <b>Панель управления инструментом</b>\nВыберите нужное действие:";
    static final String SEPARATOR = "\n\n➖➖➖➖➖➖➖➖➖➖\n";

    static final String NOT_FOUND = "❌ Инструмент не найден.";

    /**
     * Почему действие не выполнено: единица уже в нужном состоянии (повторное нажатие, старая кнопка)
     * или её нет. Прежняя версия в таких случаях молча меняла статус.
     */
    static String unchanged(java.util.Optional<String> status) {
        if (status.isEmpty()) return NOT_FOUND;
        return switch (status.get()) {
            case "IN_STOCK" -> "ℹ️ Инструмент уже на складе.";
            case "ASSIGNED" -> "ℹ️ Инструмент уже выдан сотруднику.";
            case "WRITTEN_OFF" -> "ℹ️ Инструмент уже списан в архив.";
            default -> NOT_FOUND;
        };
    }
    static final String ALREADY_TAKEN = "❌ Этот инструмент уже выдан или списан. Попробуйте выбрать заново.";
    static final String UNKNOWN_TOOL = "Неизвестный инструмент";

    static final String ERR_ASSIGN = "❌ Ошибка при выдаче инструмента.";
    static final String ERR_FAST_ASSIGN = "❌ Ошибка базы данных.";
    static final String ERR_RETURN = "❌ Ошибка базы данных при возврате инструмента.";
    static final String ERR_RETURN_ALL = "❌ Ошибка базы данных при массовом возврате инструмента.";
    static final String ERR_WRITE_OFF = "❌ Ошибка БД при списании.";
    static final String ERR_RESTORE = "❌ Ошибка базы данных при восстановлении.";
    static final String ERR_AUDIT = "❌ Ошибка при формировании аудита инструмента.";
    static final String ERR_SEARCH = "❌ Ошибка при поиске инструмента.";

    static final String AUDIT_NOTICE = "⚠ <b>ВНИМАНИЕ: АУДИТ ОСТАТКОВ!</b> ⚠️\n\nНапоминаем о необходимости закрыть подотчет до конца месяца. Пожалуйста, <b>спишите</b> или <b>верните</b> материалы.\n\n";

    private ToolsView() {
    }

    /** Значение из базы для HTML; {@code null} выводится как «null» — так же, как в прежней версии. */
    private static String h(String value) {
        return Html.esc(String.valueOf(value));
    }

    private static boolean hasInv(String inv) {
        return inv != null && !inv.isEmpty() && !inv.equals("null");
    }

    /** Уведомление над основным текстом, отделённое чертой. */
    static String withAlert(String alert, String body) {
        if (alert == null || alert.isEmpty()) return body;
        return alert + SEPARATOR + body;
    }

    // ------------------------------------------------------------------ панель

    static InlineKeyboardMarkup panelKeyboard() {
        return Keyboards.of(
                row(btn("📊 Общая сводка", "tool_menu_audit"), btn("👤 Сводка по людям", "tool_menu_audit_users")),
                row(btn("📤 Выдать", "tool_menu_give"), btn("📥 Вернуть", "tool_menu_return")),
                row(btn("🗑 Списать", "tool_menu_writeoff"), btn("♻️ Восстановить", "tool_menu_restore")),
                row(btn("📦 Передать (Удалить из базы)", "tool_menu_transfer")),
                row(btn("🗄 Архив", "tool_menu_archive"), btn("📢 Рассылка: Аудит", "tool_menu_notify")),
                row(btn("❌ Закрыть панель", "tool_menu_close")));
    }

    static final String ERR_DELETE = "❌ Ошибка БД при удалении инструмента.";

    static InlineKeyboardMarkup transferLocationKeyboard() {
        return Keyboards.of(
                row(btn("📦 Со склада", "T_TR_LOC:STOCK"), btn("👤 У сотрудника", "T_TR_LOC:ASSIGNED")),
                row(btn("🔙 Назад", "tool_menu_main")));
    }

    static InlineKeyboardMarkup groupsForTransfer(List<ToolGroup> groups) {
        Keyboards.Rows rows = Keyboards.rows();
        for (ToolGroup g : groups) rows.add(btn(Formats.cut(g.name(), 30) + " (" + g.count() + " шт)", "T_TR_G:" + g.firstId()));
        return rows.add(btn("🔙 Назад", "tool_menu_transfer")).build();
    }

    static String confirmTransferText(Tool tool) {
        return "⚠️ <b>Подтверждение передачи по накладной</b>\n\n"
                + "Выбран инструмент: <b>" + nameAndInv(tool) + "</b>\n\n"
                + "Он будет <b>полностью удален из вашей базы инструмента</b> (без помещения в архив). Продолжить?";
    }

    static InlineKeyboardMarkup confirmTransferKeyboard(int toolId) {
        return Keyboards.of(
                row(btn("🔥 Да, удалить из базы", "T_TR_YES:" + toolId)),
                row(btn("🔙 Отмена", "tool_menu_transfer")));
    }

    static String transferredOut(Tool tool) {
        return String.format("✅ <b>Инструмент передан и удален из базы!</b>\n\n🪛 <b>%s</b>\n🔢 Инв. №: <code>%s</code>",
                h(tool.name()), h(tool.invNumber()));
    }

    static InlineKeyboardMarkup backToPanel() {
        return Keyboards.single("🔙 Назад к управлению", "tool_menu_main");
    }

    static InlineKeyboardMarkup writeOffLocationKeyboard() {
        return Keyboards.of(
                row(btn("📦 Со склада", "T_WO_LOC:STOCK"), btn("👤 У сотрудника", "T_WO_LOC:ASSIGNED")),
                row(btn("🔙 Назад", "tool_menu_main")));
    }

    static InlineKeyboardMarkup writeOffReasonKeyboard() {
        return Keyboards.single("❌ Отменить", "tool_menu_main");
    }

    // ------------------------------------------------------------------ сводка по людям

    static InlineKeyboardMarkup holdersSummaryKeyboard(List<ToolHolder> holders) {
        Keyboards.Rows rows = Keyboards.rows();
        for (ToolHolder u : holders) {
            rows.add(btn(String.format("👤 %s (%s шт.)", u.fullName(), u.toolCount()), "T_SUMM_U:" + u.userId()));
        }
        return rows.add(btn("🔙 Назад", "tool_menu_main")).build();
    }

    static InlineKeyboardMarkup userSummaryKeyboard(List<Tool> list, long userId) {
        Keyboards.Rows rows = Keyboards.rows();
        for (Tool t : list) {
            String inv = hasInv(t.invNumber()) ? " [" + t.invNumber() + "]" : "";
            rows.add(btn(String.format("↩️ Забрать: %s%s", Formats.cut(t.name(), 15), inv), "T_SUMM_RET:" + t.id() + ":" + userId));
        }
        rows.add(btn("🚨 Вернуть ВСЁ на склад", "T_SUMM_RALL:" + userId));
        return rows.add(btn("🔙 Назад к списку", "tool_menu_audit_users")).build();
    }

    // ------------------------------------------------------------------ списки выбора

    /** Кнопки «🪛 название (Инв: …)» со своим префиксом и кнопкой «Назад». */
    static InlineKeyboardMarkup toolList(List<Tool> list, String icon, String prefix, String backData) {
        Keyboards.Rows rows = Keyboards.rows();
        for (Tool t : list) rows.add(btn(icon + " " + Formats.cut(t.name(), 20) + " (Инв: " + t.invNumber() + ")", prefix + t.id()));
        return rows.add(btn("🔙 Назад", backData)).build();
    }

    static InlineKeyboardMarkup groupsForAssignment(List<ToolGroup> groups) {
        Keyboards.Rows rows = Keyboards.rows();
        for (ToolGroup g : groups) rows.add(btn(Formats.cut(g.name(), 30) + " (в наличии: " + g.count() + " шт)", "T_SEL_N:" + g.firstId()));
        return rows.add(btn("🔙 Назад", "tool_menu_main")).build();
    }

    static InlineKeyboardMarkup groupsForWriteOff(List<ToolGroup> groups) {
        Keyboards.Rows rows = Keyboards.rows();
        for (ToolGroup g : groups) rows.add(btn(Formats.cut(g.name(), 30) + " (" + g.count() + " шт)", "T_WO_G:" + g.firstId()));
        return rows.add(btn("🔙 Назад", "tool_menu_writeoff")).build();
    }

    static InlineKeyboardMarkup usersForAssignment(List<ActiveUser> users, int toolId) {
        Keyboards.Rows rows = Keyboards.rows();
        for (ActiveUser u : users) rows.add(btn((u.role().isAdmin() ? "👑 " : "👷‍♂️ ") + u.fullName(), "T_ASS_U:" + toolId + ":" + u.id()));
        return rows.add(btn("🔙 Назад", "tool_menu_give")).build();
    }

    static InlineKeyboardMarkup holdersList(List<UserRef> holders, String prefix, String backData) {
        Keyboards.Rows rows = Keyboards.rows();
        for (UserRef u : holders) rows.add(btn("👤 " + u.fullName(), prefix + u.id()));
        return rows.add(btn("🔙 Назад", backData)).build();
    }

    /** Быстрая выдача (/give_): кнопки сотрудников без «Назад». */
    static InlineKeyboardMarkup fastAssignKeyboard(List<ActiveUser> users, int firstId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (ActiveUser u : users) rows.add(row(btn((u.role().isAdmin() ? "👑" : "👷‍♂️") + " " + u.fullName(), "F_ASS:" + firstId + ":" + u.id())));
        return Keyboards.of(rows);
    }

    // ------------------------------------------------------------------ тексты

    /** «название (Инв. №: …)» или «Неизвестный инструмент». */
    static String nameAndInv(Tool tool) {
        if (tool == null) return UNKNOWN_TOOL;
        return h(tool.name()) + " (Инв. №: " + h(tool.invNumber()) + ")";
    }

    static String assignStep2(Tool tool) {
        return "🪛 Вы выбрали: <b>" + nameAndInv(tool) + "</b>\n\n👤 <b>Шаг 2 из 2: Кому выдать?</b>";
    }

    static String writeOffReasonPrompt(Tool tool) {
        return "✍️ Выбран инструмент: <b>" + nameAndInv(tool) + "</b>\n\nВведите <b>причину списания</b> (например: утерян, сломался):";
    }

    static String fastAssignPrompt(String toolName) {
        return String.format("🪛 Быстрая выдача: <b>%s</b>\n\n👤 <b>Нажимайте на фамилии сотрудников</b>, чтобы выдать им этот инструмент со склада:", Html.esc(toolName));
    }

    static String assigned(Assignment a) {
        return String.format("✅ <b>Успешно выдано!</b>\n\n🪛 Инструмент: <b>%s</b>\n🔢 Инв. №: <code>%s</code>\n👤 Выдано сотруднику: <b>%s</b>",
                h(a.tool().name()), h(a.tool().invNumber()), h(a.userName()));
    }

    static String fastAssignedNotice(Tool tool) {
        return String.format("🔔 <b>Вам выдан новый инструмент!</b>\n\n🪛 <b>%s</b>\n🔢 Инв. №: <code>%s</code>", h(tool.name()), h(tool.invNumber()));
    }

    static final String ASSIGNED_NOTICE = "🔔 <b>Вам выдан новый инструмент!</b>\nМОЛ закрепил за вами новую позицию.";

    static String returned(Tool tool) {
        return String.format("✅ <b>Инструмент возвращен на склад!</b>\n\n🪛 <b>%s</b>\n🔢 Инв. №: <code>%s</code>", h(tool.name()), h(tool.invNumber()));
    }

    static String returnedAll(BulkReturn r) {
        if (r.count() > 0) {
            return "✅ <b>Успешно!</b>\nВсе инструменты (<b>" + r.count() + " шт.</b>) списаны с сотрудника <b>" + h(r.userName()) + "</b> и возвращены на склад.";
        }
        return "ℹ️ У данного сотрудника нет инструмента для возврата.";
    }

    static String writtenOff(Tool tool, String reason) {
        return String.format("✅ <b>Инструмент успешно СПИСАН!</b>\n\n🪛 <b>%s</b>\n🔢 Инв. №: <code>%s</code>\n📝 Причина: <i>%s</i>",
                h(tool.name()), h(tool.invNumber()), h(reason));
    }

    static String restored(Tool tool) {
        return String.format("✅ <b>Инструмент успешно восстановлен из архива на склад!</b>\n\n🪛 <b>%s</b>\n🔢 Инв. №: <code>%s</code>\n📦 Теперь он снова доступен для выдачи.",
                h(tool.name()), h(tool.invNumber()));
    }

    static String userSummaryEmpty(String userName) {
        return "ℹ️ У <b>" + h(userName) + "</b> нет инструмента.";
    }

    static String userSummaryTitle(String userName) {
        return "👤 <b>Инструмент: " + h(userName) + "</b>";
    }

    /** Общая сводка: шапка с итогами и группы одноимённого инструмента (сначала склад, затем на руках). */
    static String audit(List<ToolPosition> positions) {
        final class Group {
            int total;
            int inStock;
            int assigned;
            final List<String> inStockLines = new ArrayList<>();
            final List<String> assignedLines = new ArrayList<>();
        }
        Map<String, Group> groups = new LinkedHashMap<>();
        int overallTotal = 0;
        int overallInStock = 0;
        for (ToolPosition p : positions) {
            Tool t = p.tool();
            Group g = groups.computeIfAbsent(t.name(), k -> new Group());
            g.total++;
            overallTotal++;
            String inv = hasInv(t.invNumber()) ? "[" + h(t.invNumber()) + "] " : "";
            if ("IN_STOCK".equals(p.status())) {
                g.inStock++;
                overallInStock++;
                g.inStockLines.add("   📦 <b>На складе</b> 👉 " + inv + h(t.name()) + " (/give_" + t.id() + ")");
            } else if ("ASSIGNED".equals(p.status())) {
                g.assigned++;
                g.assignedLines.add("   ✅ <b>" + h(p.holderName()) + "</b> 👉 " + inv + h(t.name()) + " (/take_" + t.id() + ")");
            }
        }

        StringBuilder sb = new StringBuilder("📊 <b>Аудит инструмента:</b>\n\n");
        sb.append("🏢 <b>Всего числится инструмента:</b> ").append(overallTotal).append(" шт.\n");
        sb.append("📦 <b>Из них ждет на складе:</b> ").append(overallInStock).append(" шт.\n\n");
        sb.append("➖➖➖➖➖➖➖➖➖➖\n\n");
        if (groups.isEmpty()) {
            sb.append("<i>База инструмента пока пуста. Загрузите файл Excel.</i>");
            return sb.toString();
        }
        for (Map.Entry<String, Group> e : groups.entrySet()) {
            Group g = e.getValue();
            sb.append("🔧 <b>").append(h(e.getKey())).append("</b>\n");
            sb.append("   <i>Всего: ").append(g.total)
                    .append(" | На складе: ").append(g.inStock)
                    .append(" | На руках: ").append(g.assigned).append("</i>\n");
            for (String line : g.inStockLines) sb.append(line).append("\n");
            for (String line : g.assignedLines) sb.append(line).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Результаты поиска; {@code null}, если ничего не найдено. */
    static String searchResults(String query, List<ToolPosition> found) {
        if (found.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("🔍 <b>Результаты поиска по запросу «" + Html.esc(query) + "»:</b>\n\n");
        for (ToolPosition p : found) {
            Tool t = p.tool();
            String inv = hasInv(t.invNumber()) ? "[" + h(t.invNumber()) + "] " : "";
            if ("IN_STOCK".equals(p.status())) {
                sb.append("📦 <b>На складе</b> 👉 ").append(inv).append(h(t.name())).append(" (/give_").append(t.id()).append(")\n\n");
            } else if ("ASSIGNED".equals(p.status())) {
                sb.append("✅ <b>").append(h(p.holderName())).append("</b> 👉 ").append(inv).append(h(t.name())).append(" (/take_").append(t.id()).append(")\n\n");
            }
        }
        return sb.toString().trim();
    }

    static String searchNotFound(String text) {
        return "ℹ️ По запросу «" + Html.esc(text) + "» инструмент не найден. Попробуйте использовать кнопки меню 👇";
    }

    static String archive(List<WrittenOffTool> list) {
        if (list.isEmpty()) return "🗄 В архиве списанного инструмента пока пусто.";
        StringBuilder sb = new StringBuilder("🗄 <b>Архив списанного инструмента:</b>\n\n");
        int counter = 1;
        for (WrittenOffTool t : list) {
            String reason = t.reason() == null || t.reason().isEmpty() ? "Не указана" : t.reason();
            String date = t.date() == null ? "Дата неизвестна" : t.date();
            sb.append(String.format("%d. <b>%s</b>\n   • Инв. №: <code>%s</code>\n   • Дата списания: <b>%s</b>\n   • Причина: <i>%s</i>\n\n",
                    counter++, h(t.name()), h(t.invNumber()), date, Html.esc(reason)));
        }
        return sb.toString();
    }
}
