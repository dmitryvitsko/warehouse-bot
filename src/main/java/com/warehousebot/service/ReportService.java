package com.warehousebot.service;

import com.warehousebot.infrastructure.excel.ExcelReportWriter;
import com.warehousebot.infrastructure.excel.ExcelReportWriter.SheetData;
import com.warehousebot.repository.BalanceRepository;
import com.warehousebot.repository.MaterialRepository;
import com.warehousebot.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Excel-отчёт по складу (кнопка «📑 Скачать отчет за месяц»).
 * Как и раньше, в отчёт попадают все списания за всё время, а не только за текущий месяц.
 */
public final class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private static final String FILE_NAME = "Otchet_Sklad.xlsx";

    private final TransactionRepository transactions;
    private final MaterialRepository materials;
    private final BalanceRepository balances;

    public ReportService(TransactionRepository transactions, MaterialRepository materials, BalanceRepository balances) {
        this.transactions = transactions;
        this.materials = materials;
        this.balances = balances;
    }

    /**
     * Формирует отчёт во временной папке (у каждого вызова своя, поэтому параллельные запросы
     * не мешают друг другу); имя файла прежнее — Otchet_Sklad.xlsx. {@code null} — ошибка.
     * После отправки файл нужно удалить через {@link #discard(File)}.
     */
    public File generate() {
        try {
            File dir = Files.createTempDirectory("report").toFile();
            File file = new File(dir, FILE_NAME);
            ExcelReportWriter.write(file, List.of(paidSheet(), freeSheet(), summarySheet(), balancesSheet()));
            return file;
        } catch (Exception e) {
            log.error("Ошибка формирования отчёта", e);
            return null;
        }
    }

    /** Удаляет файл отчёта и его временную папку (в отчёте телефоны и адреса абонентов). */
    public void discard(File report) {
        if (report == null) return;
        File dir = report.getParentFile();
        if (!report.delete() && report.exists()) log.warn("Не удалось удалить {}", report);
        if (dir != null) dir.delete();
    }

    private SheetData paidSheet() {
        List<List<Object>> rows = new ArrayList<>();
        for (TransactionRepository.PaidRow r : transactions.paidWriteOffs()) {
            rows.add(Arrays.asList(r.dateTime(), r.workerName(), r.code(), r.name(), r.quantity(), r.unit(),
                    r.totalWithVat(), r.receiptNumber(), r.phone(), r.contract(), r.address()));
        }
        return new SheetData("1. По квитанциям (Платные)", List.of("Дата", "ФИО Мастера", "Код", "Материал", "Кол-во", "Ед.",
                "Сумма с НДС (руб)", "№ Квитанции", "Телефон заявки", "№ Договора", "Адрес"), rows);
    }

    private SheetData freeSheet() {
        List<List<Object>> rows = new ArrayList<>();
        for (TransactionRepository.FreeRow r : transactions.freeWriteOffs()) {
            rows.add(Arrays.asList(r.dateTime(), r.workerName(), r.code(), r.name(), r.quantity(), r.unit(),
                    r.closingCode(), r.phone(), r.contract(), r.address(), r.reason()));
        }
        return new SheetData("2. Без квитанций (Тех.расход)", List.of("Дата", "ФИО Мастера", "Код", "Материал", "Кол-во", "Ед.",
                "Код закрытия", "Телефон заявки", "№ Договора", "Адрес", "Причина списания"), rows);
    }

    /** Сводная для бухгалтерии: рабочие единицы пересчитываются обратно в бухгалтерские (м → км, шт → тыс. шт). */
    private SheetData summarySheet() {
        List<List<Object>> rows = new ArrayList<>();
        for (TransactionRepository.SummaryRow r : transactions.writeOffSummary()) {
            rows.add(Arrays.asList(r.code(), r.name(), r.paidQty(), r.freeQty(), r.totalQty(), r.workUnit(),
                    r.totalQty() / r.convFactor(), r.originalUnit()));
        }
        return new SheetData("3. Сводная для бухгалтерии", List.of("Код", "Наименование", "По квитанциям", "Без квитанций",
                "ВСЕГО (раб. ед.)", "Раб. ед.", "ВСЕГО для бух. ведомости", "Бух. ед."), rows);
    }

    /** Остатки: сначала склад МОЛ, затем подотчёт каждого мастера. */
    private SheetData balancesSheet() {
        List<List<Object>> rows = new ArrayList<>();
        for (MaterialRepository.StockRow r : materials.stockForReport()) {
            rows.add(Arrays.asList("📦 СКЛАД МОЛ", r.code(), r.name(), r.quantity(), r.workUnit(), r.priceWithVat(),
                    r.quantity() / r.convFactor(), r.originalUnit()));
        }
        for (BalanceRepository.ReportRow r : balances.balancesForReport()) {
            rows.add(Arrays.asList("👤 " + r.workerName(), r.code(), r.name(), r.quantity(), r.workUnit(), r.priceWithVat(),
                    r.quantity() / r.convFactor(), r.originalUnit()));
        }
        return new SheetData("4. Остатки (Склад и Мастера)", List.of("Где числится", "Код", "Материал", "Остаток (раб. ед.)",
                "Ед.", "Цена с НДС (руб)", "Остаток в бух. ед.", "Бух. ед."), rows);
    }
}
