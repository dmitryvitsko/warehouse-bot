package com.warehousebot.service;

import com.warehousebot.domain.Tool;
import com.warehousebot.domain.ToolGroup;
import com.warehousebot.domain.ToolHolder;
import com.warehousebot.domain.ToolPosition;
import com.warehousebot.domain.UserRef;
import com.warehousebot.domain.WrittenOffTool;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.excel.ExcelParser;
import com.warehousebot.infrastructure.excel.ExcelParser.ToolRow;
import com.warehousebot.repository.ToolRepository;
import com.warehousebot.util.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Учёт инструмента: выдача, возврат, списание, восстановление и загрузка базы из Excel.
 *
 * <p>Каждая смена статуса выполняется в транзакции и с проверкой текущего статуса в UPDATE,
 * поэтому два администратора не могут одновременно выдать одну и ту же единицу.
 */
public final class ToolService implements ToolFacade {

    private static final Logger log = LoggerFactory.getLogger(ToolService.class);

    private final Database db;
    private final ToolRepository tools;

    public ToolService(Database db, ToolRepository tools) {
        this.db = db;
        this.tools = tools;
    }

    /** Выдача конкретной единицы. */
    public record Assignment(Tool tool, String userName) {
    }

    /** Массовый возврат инструмента сотрудника. */
    public record BulkReturn(String userName, int count) {
    }

    /** Итог загрузки базы инструмента. */
    public record ImportResult(int added, int updated) {
    }

    /** Результат быстрой выдачи «любой свободной единицы из группы». */
    public sealed interface FastAssignment {
        record GroupNotFound() implements FastAssignment {
        }

        record OutOfStock(String toolName) implements FastAssignment {
        }

        record Assigned(Tool tool, String userName) implements FastAssignment {
        }
    }

    // ------------------------------------------------------------------ чтение

    /** Текущий статус единицы (IN_STOCK / ASSIGNED / WRITTEN_OFF), если она существует. */
    public Optional<String> status(int toolId) {
        return tools.findStatus(toolId);
    }

    public Optional<Tool> find(int toolId) {
        return tools.findById(toolId);
    }

    public List<ToolPosition> auditPositions() {
        return tools.activeByName();
    }

    /** Поиск несписанного инструмента по части названия или инв. номера (без учёта регистра). */
    public List<ToolPosition> search(String query) {
        String q = query.toLowerCase();
        List<ToolPosition> found = new ArrayList<>();
        for (ToolPosition p : tools.activeByStatus()) {
            String name = p.tool().name();
            String inv = p.tool().invNumber();
            boolean matchName = name != null && name.toLowerCase().contains(q);
            boolean matchInv = inv != null && inv.toLowerCase().contains(q);
            if (matchName || matchInv) found.add(p);
        }
        return found;
    }

    public List<Tool> assignedTo(long userId) {
        return tools.assignedTo(userId);
    }

    public List<ToolGroup> inStockGroups() {
        return tools.inStockGroups();
    }

    /** Свободные единицы той же группы, что и {@code firstId} (группа = название). */
    public List<Tool> inStockOfGroup(int firstId) {
        return tools.findById(firstId).map(t -> tools.inStockByName(t.name())).orElse(List.of());
    }

    public List<UserRef> holders() {
        return tools.holders();
    }

    public List<ToolHolder> holdersWithCounts() {
        return tools.holdersWithCounts();
    }

    public List<Tool> writtenOff() {
        return tools.writtenOff();
    }

    public List<WrittenOffTool> archive() {
        return tools.writtenOffArchive();
    }

    // ------------------------------------------------------------------ движение

    /** Выдаёт единицу со склада. Пусто — инструмент уже выдан или списан. */
    public Optional<Assignment> assign(int toolId, long userId) {
        return db.inTransaction(() -> {
            Optional<Tool> tool = tools.findById(toolId);
            if (tool.isEmpty() || tools.assign(toolId, userId) == 0) return Optional.<Assignment>empty();
            String userName = tools.holderName(userId).orElse("Неизвестный сотрудник");
            return Optional.of(new Assignment(tool.get(), userName));
        });
    }

    /** Выдаёт первую свободную единицу с тем же названием, что и {@code firstId}. */
    public FastAssignment assignAnyFromGroup(int firstId, long userId) {
        return db.inTransaction(() -> {
            Optional<Tool> group = tools.findById(firstId);
            if (group.isEmpty()) return new FastAssignment.GroupNotFound();
            String toolName = group.get().name();
            Optional<Tool> free = tools.firstInStockByName(toolName);
            if (free.isEmpty() || tools.assign(free.get().id(), userId) == 0) return new FastAssignment.OutOfStock(toolName);
            String userName = tools.holderName(userId).orElse("Сотрудник");
            return new FastAssignment.Assigned(free.get(), userName);
        });
    }

    /** Возвращает выданную единицу на склад. Пусто — инструмент не найден или не выдан. */
    public Optional<Tool> returnToStock(int toolId) {
        return db.inTransaction(() -> {
            Optional<Tool> tool = tools.findById(toolId);
            if (tool.isEmpty() || tools.returnToStock(toolId) == 0) return Optional.<Tool>empty();
            return tool;
        });
    }

    public BulkReturn returnAll(long userId) {
        return db.inTransaction(() -> {
            String userName = tools.holderName(userId).orElse("Неизвестный сотрудник");
            return new BulkReturn(userName, tools.returnAllOf(userId));
        });
    }

    /** Списывает единицу в архив. Пусто — инструмент не найден или уже списан. */
    public Optional<Tool> writeOff(int toolId, String reason) {
        return db.inTransaction(() -> {
            Optional<Tool> tool = tools.findById(toolId);
            if (tool.isEmpty() || tools.writeOff(toolId, reason) == 0) return Optional.<Tool>empty();
            return tool;
        });
    }

    /** Восстанавливает единицу из архива. Пусто — инструмент не найден или не списан. */
    public Optional<Tool> restore(int toolId) {
        return db.inTransaction(() -> {
            Optional<Tool> tool = tools.findById(toolId);
            if (tool.isEmpty() || tools.restore(toolId) == 0) return Optional.<Tool>empty();
            return tool;
        });
    }

    /** Полностью удаляет единицу инструмента из базы (передача в другое подразделение). */
    public Optional<Tool> deletePermanently(int toolId) {
        return db.inTransaction(() -> {
            Optional<Tool> tool = tools.findById(toolId);
            if (tool.isEmpty() || tools.deleteById(toolId) == 0) return Optional.<Tool>empty();
            return tool;
        });
    }

    // ------------------------------------------------------------------ импорт

    /**
     * Синхронизирует базу с ведомостью: новые инв. номера добавляются на склад,
     * у существующих обновляется только название (статус не трогается).
     *
     * <p>Позиция с количеством N даёт N единиц: «инв (i)»; без инв. номера — «Б/Н (название) - i».
     */
    public ImportResult saveImported(List<ToolRow> rows) {
        return db.inTransaction(() -> {
            Set<String> existing = tools.allInvNumbers();
            List<Object[]> inserts = new ArrayList<>();
            List<Object[]> renames = new ArrayList<>();
            for (ToolRow t : rows) {
                int qty = t.quantity() > 0 ? t.quantity() : 1;
                for (int i = 1; i <= qty; i++) {
                    String inv = (t.invNumber() == null || t.invNumber().trim().isEmpty())
                            ? "Б/Н (" + t.name() + ") - " + i
                            : (qty > 1 ? t.invNumber() + " (" + i + ")" : t.invNumber());
                    if (existing.add(inv)) inserts.add(new Object[]{t.name(), inv});
                    else renames.add(new Object[]{t.name(), inv});
                }
            }
            // Вставки раньше переименований: итоговое название каждого номера то же, что при построчной обработке.
            tools.insertAll(inserts);
            tools.renameAll(renames);
            return new ImportResult(inserts.size(), renames.size());
        });
    }

    @Override
    public String importTools(File file) {
        List<ToolRow> rows;
        try {
            rows = ExcelParser.parseTools(file);
        } catch (Exception e) {
            log.error("Ошибка чтения Excel с инструментом", e);
            return "❌ Ошибка при чтении Excel файла с инструментом: " + e.getMessage();
        }
        try {
            ImportResult r = saveImported(rows);
            return "✅ <b>База инструмента успешно синхронизирована!</b>\nДобавлено новых: <b>" + r.added()
                    + "</b> шт.\nОбновлено существующих: <b>" + r.updated() + "</b> шт.";
        } catch (DataAccessException e) {
            log.error("Ошибка сохранения инструмента", e);
            return "❌ Ошибка базы данных при сохранении инструмента: " + e.getMessage();
        }
    }

    /**
     * Ручное добавление инструмента текстом: «Название; Инв. номер; Количество» (можно несколько строк).
     */
    public String addToolsManual(String rawText) {
        String[] lines = rawText.split("\\r?\\n");
        List<ToolRow> rows = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split(";");
            if (parts.length < 3) {
                return "❌ <b>Неверный формат!</b>\nНужно 3 параметра через точку с запятой:\n"
                        + "<code>Название инструмента; Инв. номер; Количество</code>\n\n"
                        + "Пример: <code>Визуальный локатор VFL; 004512; 2</code>";
            }
            String name = parts[0].trim();
            String inv = parts[1].trim();
            int qty;
            try {
                qty = Integer.parseInt(parts[2].trim());
                if (name.isEmpty() || qty <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                return "❌ <b>Ошибка в данных!</b> Проверьте название и количество (должно быть целое число больше 0).";
            }
            rows.add(new ToolRow(name, inv, qty));
        }
        if (rows.isEmpty()) {
            return "❌ Пустой ввод.";
        }
        try {
            ImportResult r = saveImported(rows);
            return "✅ <b>Инструмент успешно добавлен в базу!</b>\n"
                    + "Добавлено новых единиц: <b>" + r.added() + "</b> шт.\n"
                    + (r.updated() > 0 ? "Обновлено существующих: <b>" + r.updated() + "</b> шт." : "");
        } catch (DataAccessException e) {
            log.error("Ошибка ручного добавления инструмента", e);
            return "❌ Ошибка БД при сохранении инструмента: " + Html.esc(String.valueOf(e.getMessage()));
        }
    }

    @Override
    public String userToolsText(long userId) {
        List<Tool> list;
        try {
            list = tools.assignedTo(userId);
        } catch (DataAccessException e) {
            log.error("Ошибка чтения инструмента сотрудника {}", userId, e);
            list = List.of();
        }
        if (list.isEmpty()) return "🪛 За вами пока не закреплен инструмент.";
        StringBuilder sb = new StringBuilder("🪛 <b>Ваш закрепленный инструмент:</b>\n\n");
        int counter = 1;
        for (Tool t : list) {
            String inv = t.invNumber();
            String displayName = (inv != null && !inv.isEmpty() && !inv.equals("null"))
                    ? "[" + inv + "] " + t.name()
                    : t.name();
            sb.append(String.format("%d. <b>%s</b>\n", counter++, Html.esc(displayName)));
        }
        return sb.toString();
    }
}
