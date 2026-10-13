package com.warehousebot.service;

import com.warehousebot.domain.ReturnRequest;
import com.warehousebot.infrastructure.db.DataAccessException;
import com.warehousebot.infrastructure.db.Database;
import com.warehousebot.repository.BalanceRepository;
import com.warehousebot.repository.BalanceRepository.Holding;
import com.warehousebot.repository.MaterialRepository;
import com.warehousebot.repository.ReturnRequestRepository;
import com.warehousebot.repository.TransactionRepository;
import com.warehousebot.util.Html;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static com.warehousebot.util.Formats.qty;

/**
 * Возврат материалов на склад: мастер создаёт заявку, МОЛ принимает или отклоняет её.
 * Каждая операция выполняется в одной транзакции, а смена статуса заявки защищена условием
 * {@code status = 'PENDING'} — два администратора не могут обработать одну заявку дважды.
 */
public final class ReturnService {

    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

    /** Результат создания заявки: тексты для мастера и для администраторов. */
    public record Created(boolean success, int requestId, String workerMessage, String adminMessage) {

        static Created failed(String workerMessage) {
            return new Created(false, 0, workerMessage, null);
        }
    }

    /**
     * Решение по заявке.
     *
     * @param ok       решение сохранено
     * @param workerId сотрудник, которому нужно отправить {@code workerMessage} (0, если неизвестен)
     */
    public record Decision(boolean ok, long workerId, String adminMessage, String workerMessage) {

        static Decision error(long workerId, String adminMessage) {
            return new Decision(false, workerId, adminMessage, null);
        }
    }

    /** Откат транзакции с готовым ответом (если условное обновление не затронуло ни одной строки). */
    private static final class Abort extends RuntimeException {
        private final transient Decision decision;

        Abort(Decision decision) {
            super(null, null, false, false);
            this.decision = decision;
        }
    }

    private final Database db;
    private final ReturnRequestRepository requests;
    private final BalanceRepository balances;
    private final MaterialRepository materials;
    private final TransactionRepository transactions;

    public ReturnService(Database db, ReturnRequestRepository requests, BalanceRepository balances,
                         MaterialRepository materials, TransactionRepository transactions) {
        this.db = db;
        this.requests = requests;
        this.balances = balances;
        this.materials = materials;
        this.transactions = transactions;
    }

    /** Заявка на возврат: нельзя вернуть больше, чем на руках за вычетом уже заявленного. */
    public Created create(long userId, int materialId, double qtyToReturn) {
        if (!(qtyToReturn > 0)) return Created.failed("❌ Количество для возврата должно быть больше нуля!");
        try {
            return db.inTransaction(() -> {
                Optional<Holding> found = balances.findHolding(userId, materialId);
                if (found.isEmpty()) return Created.failed("❌ Этот материал не числится у вас в подотчете.");
                Holding h = found.get();
                String unit = Html.esc(h.unit());

                double pendingQty = requests.pendingQuantity(userId, materialId);
                if (qtyToReturn > h.quantity() - pendingQty + 1e-9) {
                    return Created.failed(String.format(
                            "❌ Нельзя вернуть больше, чем есть на руках!\nЧислится за вами: <b>%s %s</b> (в ожидании подтверждения МОЛ: <b>%s %s</b>).",
                            qty(h.quantity()), unit, qty(pendingQty), unit));
                }

                int requestId = requests.insertPending(userId, materialId, qtyToReturn);
                String name = Html.esc(h.name());
                String code = Html.esc(h.code());
                String worker = String.format("""
                                ⏳ <b>Заявка на возврат №%d отправлена МОЛ!</b>
                                
                                • Материал: <b>%s</b> [<code>%s</code>]
                                • Количество к возврату: <b>%s %s</b>
                                
                                <i>⚠️ Материал числится за вами до тех пор, пока МОЛ не подтвердит приемку на склад.</i>""",
                        requestId, name, code, qty(qtyToReturn), unit);
                String admin = String.format("""
                                🔔 <b>Запрос на возврат материала на склад (№%d)</b>
                                
                                👤 Сотрудник: <b>%s</b>
                                📦 Материал: <b>%s</b>
                                🔢 Инв. №: <code>%s</code>
                                ↩️ Количество к возврату: <b>%s %s</b>
                                
                                Подтверждаете приемку этого материала обратно на склад?""",
                        requestId, Html.esc(h.workerName()), name, code, qty(qtyToReturn), unit);
                return new Created(true, requestId, worker, admin);
            });
        } catch (DataAccessException e) {
            log.error("Ошибка создания заявки на возврат", e);
            return Created.failed("❌ Ошибка базы данных при создании заявки на возврат.");
        }
    }

    /** МОЛ принимает материал: подотчёт уменьшается, склад увеличивается, возврат пишется в журнал. */
    public Decision approve(int requestId) {
        try {
            return db.inTransaction(() -> {
                Optional<ReturnRequest> found = requests.findById(requestId);
                if (found.isEmpty()) return Decision.error(0, "❌ Заявка №" + requestId + " не найдена.");
                ReturnRequest r = found.get();
                if (!r.isPending()) return Decision.error(0, "ℹ️ Эта заявка уже обработана.");

                Decision notEnough = Decision.error(r.userId(), "❌ Ошибка: у сотрудника " + Html.esc(r.userName())
                        + " на руках уже меньше материала, чем указано в заявке.");
                Optional<Double> onHands = balances.quantity(r.userId(), r.materialId());
                if (onHands.isEmpty() || onHands.get() + 1e-9 < r.quantity()) return notEnough;

                if (!requests.resolvePending(requestId, ReturnRequest.APPROVED)) {
                    return Decision.error(0, "ℹ️ Эта заявка уже обработана.");
                }
                if (!balances.decrease(r.userId(), r.materialId(), r.quantity())) throw new Abort(notEnough);
                materials.increaseStock(r.materialId(), r.quantity());
                transactions.logReturn(r.userId(), r.materialId(), r.quantity(), r.quantity() * r.priceWithVat());

                String name = Html.esc(r.name());
                String code = Html.esc(r.code());
                String unit = Html.esc(r.unit());
                String admin = String.format("✅ <b>Возврат №%d подтвержден!</b>\n• Сотрудник: <b>%s</b>\n• Материал: <b>%s</b> [<code>%s</code>]\n• Возвращено на склад: <b>%s %s</b>",
                        requestId, Html.esc(r.userName()), name, code, qty(r.quantity()), unit);
                String worker = String.format("✅ <b>МОЛ подтвердил ваш возврат (заявка №%d)!</b>\n• Материал: <b>%s</b> [<code>%s</code>]\n• Списано на склад: <b>%s %s</b>",
                        requestId, name, code, qty(r.quantity()), unit);
                return new Decision(true, r.userId(), admin, worker);
            });
        } catch (Abort a) {
            return a.decision;
        } catch (DataAccessException e) {
            log.error("Ошибка подтверждения возврата №{}", requestId, e);
            return Decision.error(0, "❌ Ошибка базы данных при подтверждении возврата.");
        }
    }

    /** МОЛ отклоняет заявку: материал остаётся в подотчёте. */
    public Decision reject(int requestId) {
        String alreadyProcessed = "ℹ Эта заявка (№" + requestId + ") уже обработана.";
        try {
            return db.inTransaction(() -> {
                Optional<ReturnRequest> found = requests.findById(requestId);
                if (found.isEmpty()) return Decision.error(0, "❌ Заявка №" + requestId + " не найдена.");
                ReturnRequest r = found.get();
                if (!r.isPending() || !requests.resolvePending(requestId, ReturnRequest.REJECTED)) {
                    return Decision.error(0, alreadyProcessed);
                }

                String name = Html.esc(r.name());
                String unit = Html.esc(r.unit());
                String admin = String.format("❌ <b>Вы отклонили заявку на возврат №%d.</b>\nМатериал <b>%s</b> (%s %s) остался в подотчете за сотрудником <b>%s</b>.",
                        requestId, name, qty(r.quantity()), unit, Html.esc(r.userName()));
                String worker = String.format("❌ <b>МОЛ отклонил вашу заявку на возврат №%d.</b>\nМатериал <b>%s</b> [<code>%s</code>] в количестве <b>%s %s</b> остается в вашем подотчете.",
                        requestId, name, Html.esc(r.code()), qty(r.quantity()), unit);
                return new Decision(true, r.userId(), admin, worker);
            });
        } catch (DataAccessException e) {
            log.error("Ошибка отклонения возврата №{}", requestId, e);
            return Decision.error(0, "❌ Ошибка БД при отклонении возврата.");
        }
    }
}
