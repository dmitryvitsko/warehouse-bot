package com.warehousebot.service;

import com.warehousebot.domain.OrshCabinet;
import com.warehousebot.domain.OrshProblem;
import com.warehousebot.domain.OrshStatistics;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.infrastructure.excel.ExcelParser;
import com.warehousebot.repository.OrshRepository;
import com.warehousebot.util.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Плановый осмотр шкафов ОРШ: план, отметки об осмотре, статистика. */
public final class OrshService {

    private static final Logger log = LoggerFactory.getLogger(OrshService.class);

    private final OrshRepository orsh;

    public OrshService(OrshRepository orsh) {
        this.orsh = orsh;
    }

    public List<OrshCabinet> pending() {
        return orsh.findPending();
    }

    public Optional<OrshCabinet> find(int id) {
        return orsh.findById(id);
    }

    /** Фото отправлено — шкаф вычёркивается из плана. */
    public void markCompleted(int id, String workerName) {
        orsh.markInspected(id, workerName, false, "ОК");
    }

    /** Осмотр невозможен — шкаф переводится в проблемные с причиной. */
    public void markProblem(int id, String workerName, String reason) {
        orsh.markInspected(id, workerName, true, reason);
    }

    public OrshStatistics statistics() {
        Map<String, Integer> counts = orsh.countByStatus();
        int total = 0;
        for (int c : counts.values()) total += c;
        int problem = counts.getOrDefault("PROBLEM", 0);
        List<OrshProblem> problems = problem > 0 ? orsh.findProblems() : List.of();
        return new OrshStatistics(total, counts.getOrDefault("COMPLETED", 0), counts.getOrDefault("PENDING", 0),
                problem, problems);
    }

    /** Загрузка плана из Excel (план заменяется целиком); возвращает HTML-отчёт, как прежний importOrshSheet. */
    public String importPlan(File file) {
        List<ExcelParser.OrshRow> rows;
        try {
            rows = ExcelParser.parseOrsh(file);
        } catch (Exception e) {
            log.error("Ошибка чтения плана ОРШ", e);
            return "❌ Ошибка при чтении файла ОРШ: " + Html.esc(String.valueOf(e.getMessage()));
        }
        List<OrshCabinet> cabinets = new ArrayList<>(rows.size());
        for (ExcelParser.OrshRow r : rows) cabinets.add(new OrshCabinet(0, r.number(), r.address(), r.location()));
        try {
            orsh.replacePlan(cabinets);
        } catch (DataAccessException e) {
            log.error("Ошибка сохранения плана ОРШ", e);
            return "❌ Ошибка при сохранении плана ОРШ.";
        }
        return "✅ <b>План осмотра ОРШ успешно загружен!</b>\nДобавлено шкафов: <b>" + cabinets.size() + "</b> шт.";
    }
}
