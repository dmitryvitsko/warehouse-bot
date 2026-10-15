package com.warehousebot.service;

import com.warehousebot.domain.SickLeaveRequest;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.repository.ScheduleRepository;
import com.warehousebot.repository.SickLeaveRepository;
import com.warehousebot.repository.UserRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Больничные: до 10 дней включительно вносятся в график сразу (статус «Б»),
 * более длинные ждут подтверждения администратора.
 */
public final class SickLeaveService {

    public static final int AUTO_APPROVE_MAX_DAYS = 10;
    public static final String SICK_CODE = "Б";
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final Database db;
    private final ScheduleRepository schedules;
    private final SickLeaveRepository requests;
    private final UserRepository users;

    public SickLeaveService(Database db, ScheduleRepository schedules, SickLeaveRepository requests, UserRepository users) {
        this.db = db;
        this.schedules = schedules;
        this.requests = requests;
        this.users = users;
    }

    /**
     * Результат сообщения о больничном.
     *
     * @param days      длительность периода в днях
     * @param requestId id запроса на подтверждение или {@code null}, если график уже обновлён
     */
    public record Report(long days, Long requestId) {
        public boolean needsApproval() {
            return requestId != null;
        }
    }

    /** Решение администратора по запросу. */
    public record Decision(SickLeaveRequest request, boolean approved) {
    }

    /** Больничный на период {@code from..to} (включительно). */
    public Report report(long chatId, String excelName, LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days <= AUTO_APPROVE_MAX_DAYS) {
            markSick(excelName, from, to);
            return new Report(days, null);
        }
        long id = requests.create(chatId, excelName, from.format(DATE), to.format(DATE));
        return new Report(days, id);
    }

    /**
     * Подтверждение/отклонение запроса. Статус меняется атомарно (только из PENDING), поэтому
     * два администратора не обработают один запрос дважды. Пусто — запрос не найден или уже обработан.
     */
    public Optional<Decision> decide(long requestId, boolean approve) {
        return db.inTransaction(() -> {
            Optional<SickLeaveRequest> found = requests.find(requestId);
            if (found.isEmpty() || !found.get().isPending()) return Optional.<Decision>empty();
            SickLeaveRequest req = found.get();
            LocalDate start = LocalDate.parse(req.startDate(), DATE);
            LocalDate end = LocalDate.parse(req.endDate(), DATE);

            if (!requests.resolveIfPending(requestId, approve ? "APPROVED" : "REJECTED")) return Optional.<Decision>empty();
            if (approve) markSick(req.excelName(), start, end);
            return Optional.of(new Decision(req, approve));
        });
    }

    private void markSick(String excelName, LocalDate from, LocalDate to) {
        db.inTransaction(() -> {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                schedules.upsert(excelName, d.getYear(), d.getMonthValue(), d.getDayOfMonth(), SICK_CODE, "", "");
            }
        });
    }

    /**
     * Ответственные за график (см. {@link UserService#isScheduleEditor}), которых нужно известить о больничном.
     * Администраторы исключаются — они получают своё уведомление.
     */
    public List<Long> managerIds() {
        List<Long> admins = users.adminIds();
        List<Long> ids = new ArrayList<>();
        for (String name : users.allExcelNames()) {
            if (!UserService.isScheduleEditor(name)) continue;
            users.findUserIdByExcelName(name).filter(id -> !admins.contains(id)).ifPresent(ids::add);
        }
        return ids;
    }
}
