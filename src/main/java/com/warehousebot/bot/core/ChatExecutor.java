package com.warehousebot.bot.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Обработка апдейтов: последовательно внутри одного чата, параллельно между чатами.
 *
 * <p>Раньше все апдейты шли в одном потоке, и медленный запрос (сервер ТМ, почта, импорт Excel)
 * у одного пользователя замораживал бота для всех. Теперь медленная операция задерживает только
 * свой чат, а порядок сообщений внутри чата сохраняется.
 */
public final class ChatExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChatExecutor.class);

    private final ExecutorService pool;
    private final Map<Long, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    public ChatExecutor(int threads) {
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "update-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.pool = Executors.newFixedThreadPool(threads, tf);
    }

    public void submit(long chatId, Runnable task) {
        CompletableFuture<Void> next = tails.compute(chatId, (id, tail) -> {
            CompletableFuture<Void> prev = tail == null ? CompletableFuture.completedFuture(null) : tail;
            return prev.thenRunAsync(() -> runSafely(task), pool);
        });
        // Убираем хвост очереди, когда чат «простаивает», чтобы карта не росла бесконечно.
        next.whenComplete((v, e) -> tails.remove(chatId, next));
    }

    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            log.error("❌ Ошибка обработки апдейта", t);
        }
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
