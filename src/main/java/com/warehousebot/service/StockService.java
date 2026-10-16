package com.warehousebot.service;

import com.warehousebot.domain.HeldMaterial;
import com.warehousebot.domain.Material;
import com.warehousebot.domain.WriteOffItem;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.infrastructure.excel.ExcelParser;
import com.warehousebot.infrastructure.excel.ExcelParser.MaterialRow;
import com.warehousebot.repository.BalanceRepository;
import com.warehousebot.repository.BalanceRepository.Holding;
import com.warehousebot.repository.MaterialRepository;
import com.warehousebot.repository.MaterialRepository.ImportRow;
import com.warehousebot.repository.TransactionRepository;
import com.warehousebot.util.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.warehousebot.util.Formats.price;
import static com.warehousebot.util.Formats.qty;

/** Склад и подотчёт: выдача со склада, списание, остатки, загрузка оборотной ведомости. */
public final class StockService implements StockFacade {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);
    private static final int MAX_IMPORT_ERRORS = 20;

    /** Результат выдачи со склада: {@code ok} — материал выдан; {@code text} — сообщение пользователю. */
    public record TakeResult(boolean ok, String text) {
    }

    private final Database db;
    private final MaterialRepository materials;
    private final BalanceRepository balances;
    private final TransactionRepository transactions;

    public StockService(Database db, MaterialRepository materials, BalanceRepository balances,
                        TransactionRepository transactions) {
        this.db = db;
        this.materials = materials;
        this.balances = balances;
        this.transactions = transactions;
    }

    // ------------------------------------------------------------------ склад

    /** Материалы, которые есть на складе. */
    public List<Material> availableMaterials() {
        return materials.findAvailable();
    }

    /** Материал, если он ещё есть на складе. */
    public Optional<Material> availableMaterial(int materialId) {
        return materials.findAvailable().stream().filter(m -> m.id() == materialId).findFirst();
    }

    /** Выдача материала со склада в подотчёт сотрудника (атомарно: проверка остатка и списание в одной транзакции). */
    public TakeResult take(long userId, int materialId, double qtyToTake) {
        // !(x > 0) отсекает и NaN: раньше «NaN» доходил до UPDATE и портил остаток.
        if (!(qtyToTake > 0)) return new TakeResult(false, "❌ Количество должно быть больше нуля!");
        try {
            return db.inTransaction(() -> {
                Optional<Material> found = materials.findById(materialId);
                if (found.isEmpty()) return new TakeResult(false, "❌ Материал не найден.");
                Material m = found.get();
                if (qtyToTake > m.warehouseQty() + 1e-9 || !materials.decreaseStock(materialId, qtyToTake)) {
                    return new TakeResult(false, String.format("❌ На складе недостаточно материала! Доступно всего: <b>%s %s</b>",
                            qty(m.warehouseQty()), Html.esc(m.unit())));
                }
                balances.increase(userId, materialId, qtyToTake);
                double totalSum = qtyToTake * m.priceWithVat();
                transactions.logIssue(userId, materialId, qtyToTake, totalSum);
                String unit = Html.esc(m.unit());
                return new TakeResult(true, String.format("""
                                ✅ <b>Вы взяли со склада в подотчет:</b>
                                • Наименование: <b>%s</b>
                                • Инв. номер: <code>%s</code>
                                • Количество: <b>%s %s</b>
                                • Сумма (с НДС): <b>%s руб.</b> <i>(по %s за 1 %s)</i>""",
                        Html.esc(m.name()), Html.esc(m.code()), qty(qtyToTake), unit, price(totalSum), price(m.priceWithVat()), unit));
            });
        } catch (DataAccessException e) {
            log.error("Ошибка выдачи материала {} сотруднику {}", materialId, userId, e);
            return new TakeResult(false, "❌ Ошибка базы данных при выдаче.");
        }
    }

    /** Принудительная выдача материала со склада в подотчёт выбранного сотрудника (выполняется администратором). */
    public TakeResult forceAssign(long targetUserId, String targetUserName, int materialId, double qtyToTake) {
        if (!(qtyToTake > 0)) return new TakeResult(false, "❌ Количество должно быть больше нуля!");
        try {
            return db.inTransaction(() -> {
                Optional<Material> found = materials.findById(materialId);
                if (found.isEmpty()) return new TakeResult(false, "❌ Материал не найден.");
                Material m = found.get();
                if (qtyToTake > m.warehouseQty() + 1e-9 || !materials.decreaseStock(materialId, qtyToTake)) {
                    return new TakeResult(false, String.format("❌ На складе недостаточно материала! Доступно всего: <b>%s %s</b>",
                            qty(m.warehouseQty()), Html.esc(m.unit())));
                }
                balances.increase(targetUserId, materialId, qtyToTake);
                double totalSum = qtyToTake * m.priceWithVat();
                transactions.logIssue(targetUserId, materialId, qtyToTake, totalSum);
                String unit = Html.esc(m.unit());
                return new TakeResult(true, String.format("""
                                ✅ <b>Принудительно выдано в подотчет:</b>
                                👤 Сотрудник: <b>%s</b>
                                • Наименование: <b>%s</b>
                                • Инв. номер: <code>%s</code>
                                • Количество: <b>%s %s</b>
                                • Сумма (с НДС): <b>%s руб.</b>""",
                        Html.esc(targetUserName), Html.esc(m.name()), Html.esc(m.code()), qty(qtyToTake), unit, price(totalSum)));
            });
        } catch (DataAccessException e) {
            log.error("Ошибка принудительной выдачи материала {} сотруднику {}", materialId, targetUserId, e);
            return new TakeResult(false, "❌ Ошибка базы данных при принудительной выдаче.");
        }
    }

    /** Полная очистка склада (таблица materials). */
    @Override
    public void clearWarehouse() {
        materials.deleteAll();
    }

    /** Очистка истории списаний/выдач для начала нового отчетного месяца. */
    @Override
    public int clearTransactionsHistory() {
        return transactions.clearAllTransactions();
    }

    // ------------------------------------------------------------------ подотчёт

    @Override
    public List<HeldMaterial> heldMaterials(long userId) {
        return balances.findHeld(userId);
    }

    @Override
    public Optional<HeldMaterial> heldMaterial(long userId, int materialId) {
        return balances.findHeld(userId, materialId);
    }

    /** Материал из подотчёта с любым положительным остатком — для выбора позиции к списанию. */
    public Optional<HeldMaterial> heldForWriteOff(long userId, int materialId) {
        return balances.findPositive(userId, materialId);
    }

    @Override
    public List<Long> usersWithBalances() {
        return balances.usersWithBalances();
    }

    /** Материалы на руках у всех сотрудников (для администратора). */
    public List<Holding> allHoldings() {
        return balances.findAllHoldings();
    }

    @Override
    public String balanceText(long userId) {
        List<HeldMaterial> held = balances.findHeld(userId);
        if (held.isEmpty()) return "🧰 За вами сейчас не числится материалов. Возьмите нужные позиции в разделе «📦 Склад».";
        StringBuilder sb = new StringBuilder("🧰 <b>Числится за вами (в подотчете):</b>\n\n");
        for (HeldMaterial m : held) {
            double totalSum = m.quantity() * m.priceWithVat();
            String unit = Html.esc(m.unit());
            sb.append(String.format("🔹 <b>%s</b>\n   • Инв. №: <code>%s</code>\n   • В наличии: <b>%s %s</b>\n   • Сумма (с НДС): <b>%s руб.</b> <i>(по %s за 1 %s)</i>\n\n",
                    Html.esc(m.name()), Html.esc(m.code()), qty(m.quantity()), unit, price(totalSum), price(m.priceWithVat()), unit));
        }
        return sb.toString();
    }

    /** Списание позиции из подотчёта (атомарно: проверка остатка, списание и запись в журнал). */
    @Override
    public String writeOff(long userId, WriteOffItem item) {
        String notEnough = "❌ Ошибка: у вас на руках недостаточно этого материала.";
        if (!Double.isFinite(item.quantity())) return notEnough;
        Optional<Double> totalSumVat;
        try {
            totalSumVat = db.inTransaction(() -> {
                Optional<Double> onHands = balances.quantity(userId, item.materialId());
                if (onHands.isEmpty() || onHands.get() + 1e-9 < item.quantity()) return Optional.<Double>empty();
                if (!balances.decrease(userId, item.materialId(), item.quantity())) return Optional.<Double>empty();
                double total = Math.round(item.quantity() * item.priceWithVat() * 100.0) / 100.0;
                transactions.logWriteOff(userId, item, total);
                return Optional.of(total);
            });
        } catch (DataAccessException e) {
            log.error("Ошибка списания материала {} сотрудником {}", item.materialId(), userId, e);
            return "❌ Ошибка базы данных при сохранении списания.";
        }
        if (totalSumVat.isEmpty()) return notEnough;
        return writeOffText(item, totalSumVat.get());
    }

    private static String writeOffText(WriteOffItem s, double totalSumVat) {
        String unit = Html.esc(s.unit());
        if (s.paidReceipt()) {
            return String.format("""
                            ✅ <b>Списание ПО КВИТАНЦИИ сохранено!</b>
                            
                            📦 Материал: <b>%s — %s %s</b>
                            🧾 Квитанция: <b>%s</b>
                            ☎️ Телефон заявки: <b>%s</b>
                            📄 Номер договора: <b>%s</b>
                            🏠 Адрес: <b>%s</b>
                            
                            💰 <b>Сумма материала для квитанции (с НДС): %.2f руб.</b>
                            <i>(%s %s × %s руб.)</i>""",
                    Html.esc(s.materialName()), qty(s.quantity()), unit,
                    Html.esc(s.receiptNumber()), Html.esc(s.phoneNumber()), Html.esc(s.contractNumber()), Html.esc(s.address()),
                    totalSumVat, qty(s.quantity()), unit, price(s.priceWithVat()));
        }
        return String.format("""
                        ✅ <b>Техническое списание (БЕЗ квитанции) сохранено!</b>
                        
                        📦 Материал: <b>%s — %s %s</b>
                        🔢 Код закрытия: <b>%s</b>
                        ☎️ Телефон заявки: <b>%s</b>
                        📄 Номер договора: <b>%s</b>
                        🏠 Адрес: <b>%s</b>
                        🛠 Причина: <b>%s</b>""",
                Html.esc(s.materialName()), qty(s.quantity()), unit,
                Html.esc(s.closingCode()), Html.esc(s.phoneNumber()), Html.esc(s.contractNumber()), Html.esc(s.address()),
                Html.esc(s.reason()));
    }

    /**
     * Предупреждение для кода 212: если по этому телефону/договору менее полугода назад уже было
     * техническое закрытие (кроме 227). Пустая строка — предупреждения нет.
     */
    public String code212Warning(String phone, String contract) {
        if ((phone == null || phone.isEmpty() || phone.equals("-")) &&
                (contract == null || contract.isEmpty() || contract.equals("-"))) return "";
        try {
            return transactions.findRecentClosing(phone, contract)
                    .map(c -> "\n\n⚠️ <b>ОСТОРОЖНО:</b> По этому абоненту менее 6 мес. назад уже закрывалась заявка (код "
                            + Html.esc(c.closingCode()) + " от " + c.date() + "). <b>Вы обязаны использовать код 227 (любой другой запрещен)!</b>")
                    .orElse("");
        } catch (DataAccessException e) {
            // Как и раньше: без предупреждения, но списание не блокируем.
            log.error("Ошибка проверки истории кода 212", e);
            return "";
        }
    }

    // ------------------------------------------------------------------ оборотная ведомость

    @Override
    public String importTurnover(File file) {
        List<MaterialRow> rows;
        try {
            rows = ExcelParser.parseTurnover(file);
        } catch (Exception e) {
            log.error("Ошибка чтения оборотной ведомости", e);
            return "❌ Ошибка при чтении Excel-файла оборотной ведомости: " + Html.esc(e.getMessage());
        }
        if (rows.isEmpty()) {
            return "❌ В файле не найдены позиции по счетам 10.01 и 10.05. Проверьте структуру таблицы.";
        }
        try {
            return db.inTransaction(() -> saveImported(rows));
        } catch (DataAccessException e) {
            log.error("Ошибка сохранения оборотной ведомости", e);
            return "❌ Ошибка базы данных при сохранении ведомости: " + Html.esc(e.getMessage());
        }
    }

    /**
     * Сверяет ведомость с подотчётом и сохраняет её. Выполняется в одной транзакции:
     * подотчёт не может измениться между сверкой и записью.
     */
    private String saveImported(List<MaterialRow> rows) {
        // Шаг 1: что сейчас на руках у мастеров.
        Map<String, Double> onHands = new HashMap<>();
        Map<String, String> whoHolds = new HashMap<>();
        for (Holding h : balances.findAllHoldingsUnordered()) {
            onHands.merge(h.code(), h.quantity(), Double::sum);
            String holder = Html.esc(h.workerName()) + " (" + qty(h.quantity()) + ")";
            whoHolds.merge(h.code(), holder, (a, b) -> a + ", " + b);
        }

        // Шаг 2: строки с одинаковым инв. № (несколько партий) складываются: количества и суммы суммируются,
        // текстовые поля и цена берутся из последней строки.
        Map<String, Aggregate> byCode = new LinkedHashMap<>();
        for (MaterialRow r : rows) {
            byCode.computeIfAbsent(r.code(), k -> new Aggregate()).add(r);
        }

        StringBuilder errors = new StringBuilder();
        int errorCount = 0;
        for (Map.Entry<String, Double> e : onHands.entrySet()) {
            String code = e.getKey();
            double handsQty = e.getValue();
            Aggregate agg = byCode.get(code);
            double excelQty = agg == null ? 0.0 : agg.qty;
            if (handsQty > excelQty + 1e-9) {
                if (errorCount < MAX_IMPORT_ERRORS) {
                    errors.append("🚨 Инв. № <code>").append(Html.esc(code)).append("</code>\n")
                            .append("По новой ведомости: <b>").append(qty(excelQty)).append("</b>\n")
                            .append("У мастеров на руках: <b>").append(qty(handsQty)).append("</b>\n")
                            .append("Кто держит: <i>").append(whoHolds.get(code)).append("</i>\n\n");
                }
                errorCount++;
            }
        }
        if (errorCount > 0) {
            String header = "❌ <b>ОШИБКА ИМПОРТА: НЕДОСТАЧА ПО БУХГАЛТЕРИИ!</b>\n\n" +
                    "Материалы ниже числятся у мастеров, но в новой ведомости их меньше (или вообще нет). " +
                    "Заставьте мастеров списать эти материалы перед загрузкой ведомости:\n\n";
            if (errorCount > MAX_IMPORT_ERRORS) {
                errors.append("\n<i>... и еще ").append(errorCount - MAX_IMPORT_ERRORS).append(" проблемных позиций.</i>");
            }
            return header + errors.toString().trim();
        }

        // Шаг 3: обнуляем склад и записываем свободные остатки (ведомость минус то, что на руках).
        materials.resetBeforeImport();
        List<ImportRow> toSave = new ArrayList<>();
        for (Aggregate agg : byCode.values()) {
            MaterialRow r = agg.last;
            double warehouseQty = Math.max(0, agg.qty - onHands.getOrDefault(r.code(), 0.0));
            // batch_info в прежней версии всегда сохранялся пустым.
            toSave.add(new ImportRow(r.account(), r.code(), r.name(), r.originalUnit(), r.workUnit(), r.convFactor(),
                    r.priceWithVat(), warehouseQty, "", agg.startQty, agg.startSum, agg.inQty, agg.outQty, agg.endSum));
        }
        materials.upsert(toSave);
        return "✅ <b>Оборотная ведомость успешно загружена! (Умный импорт)</b>\nКоличество материалов на руках у сотрудников было проверено и автоматически вычтено из свободных остатков на складе.";
    }

    /** Строки ведомости с одним инв. №: суммы по всем партиям и последняя строка. */
    private static final class Aggregate {
        private double qty;
        private double startQty;
        private double startSum;
        private double inQty;
        private double outQty;
        private double endSum;
        private MaterialRow last;

        void add(MaterialRow r) {
            qty += r.qty();
            startQty += r.startQty();
            startSum += r.startSum();
            inQty += r.inQty();
            outQty += r.outQty();
            endSum += r.endSum();
            last = r;
        }
    }
}
