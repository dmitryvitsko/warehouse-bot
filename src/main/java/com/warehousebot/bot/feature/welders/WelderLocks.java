package com.warehousebot.bot.feature.welders;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Состояние сварочников, общее для разных чатов: блокировки конца смены и передачи между коллегами.
 *
 * <p>Читается и меняется из потоков разных чатов и из бота-надзирателя, поэтому все карты —
 * ConcurrentHashMap, а изменения выполняются атомарными операциями.
 */
final class WelderLocks {

    /** Передача аппарата, ожидающая решения получателя. */
    static final class Transfer {
        final int welderId;
        final long senderId;
        final long receiverId;
        final int senderMsgId;
        /** Сообщение с кнопками «Принять»/«Отказаться» у получателя (известно после отправки). */
        volatile Integer receiverMsgId;

        Transfer(int welderId, long senderId, long receiverId, int senderMsgId) {
            this.welderId = welderId;
            this.senderId = senderId;
            this.receiverId = receiverId;
            this.senderMsgId = senderMsgId;
        }
    }

    /** Сотрудник → аппарат, за который он должен отчитаться в конце смены (меню заблокировано). */
    private final Map<Long, Integer> returnLocks = new ConcurrentHashMap<>();
    /** Сотрудник → день, на который он оставил аппарат за собой до завтра. */
    private final Map<Long, LocalDate> keptForTomorrow = new ConcurrentHashMap<>();
    /** Получатель → ожидающая его решения передача. */
    private final Map<Long, Transfer> pendingByReceiver = new ConcurrentHashMap<>();
    /** Передающий → получатель его активной передачи. */
    private final Map<Long, Long> activeBySender = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ конец смены

    boolean isReturnLocked(long userId) {
        return returnLocks.containsKey(userId);
    }

    /** Аппарат, за который должен отчитаться сотрудник, или {@code null}. */
    Integer lockedWelder(long userId) {
        return returnLocks.get(userId);
    }

    void lockForReturn(long userId, int welderId) {
        returnLocks.put(userId, welderId);
    }

    void releaseReturnLock(long userId) {
        returnLocks.remove(userId);
    }

    void keepForTomorrow(long userId, LocalDate day) {
        keptForTomorrow.put(userId, day);
    }

    boolean keptOn(long userId, LocalDate day) {
        return day.equals(keptForTomorrow.get(userId));
    }

    // ------------------------------------------------------------------ передачи

    boolean hasPendingTransfer(long receiverId) {
        return pendingByReceiver.containsKey(receiverId);
    }

    /** Регистрирует передачу, если получатель свободен. {@code false} — он уже рассматривает другой запрос. */
    boolean offer(Transfer t) {
        if (pendingByReceiver.putIfAbsent(t.receiverId, t) != null) return false;
        activeBySender.put(t.senderId, t.receiverId);
        return true;
    }

    /** Получатель текущей незавершённой передачи от сотрудника или {@code null}. */
    Long activeReceiver(long senderId) {
        return activeBySender.get(senderId);
    }

    /** Снимает ожидающую передачу получателя, если она от {@code senderId}; ссылку у передающего не трогает. */
    Transfer withdrawPending(long receiverId, long senderId) {
        return removeIfFrom(receiverId, senderId, null);
    }

    /** Отмена передачи передающим. Возвращает отменённую передачу или {@code null}. */
    Transfer cancelBySender(long senderId) {
        Long receiverId = activeBySender.remove(senderId);
        if (receiverId == null) return null;
        return removeIfFrom(receiverId, senderId, null);
    }

    /**
     * Решение получателя: снимает его ожидающую передачу, если она от {@code senderId}
     * (и для указанного аппарата, если {@code welderId != null}).
     *
     * @return снятая передача или {@code null}, если такой передачи уже нет
     */
    Transfer resolve(long receiverId, long senderId, Integer welderId) {
        Transfer t = removeIfFrom(receiverId, senderId, welderId);
        activeBySender.remove(senderId, receiverId);
        return t;
    }

    private Transfer removeIfFrom(long receiverId, long senderId, Integer welderId) {
        Transfer t = pendingByReceiver.get(receiverId);
        if (t == null || t.senderId != senderId) return null;
        if (welderId != null && t.welderId != welderId) return null;
        return pendingByReceiver.remove(receiverId, t) ? t : null;
    }
}
