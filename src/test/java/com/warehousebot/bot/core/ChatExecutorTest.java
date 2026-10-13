package com.warehousebot.bot.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatExecutorTest {

    @Test
    void sameChatIsProcessedInOrder() throws Exception {
        List<Integer> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(50);
        try (ChatExecutor ex = new ChatExecutor(8)) {
            for (int i = 0; i < 50; i++) {
                int n = i;
                ex.submit(1L, () -> {
                    order.add(n);
                    done.countDown();
                });
            }
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }
        for (int i = 0; i < 50; i++) assertEquals(i, order.get(i));
    }

    @Test
    void slowChatDoesNotBlockOtherChats() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        try (ChatExecutor ex = new ChatExecutor(4)) {
            ex.submit(1L, () -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            ex.submit(2L, fastDone::countDown);
            assertTrue(fastDone.await(2, TimeUnit.SECONDS), "чат 2 не должен ждать медленный чат 1");
            release.countDown();
        }
    }

    @Test
    void failingTaskDoesNotBreakQueue() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        try (ChatExecutor ex = new ChatExecutor(2)) {
            ex.submit(1L, () -> {
                throw new RuntimeException("boom");
            });
            ex.submit(1L, done::countDown);
            assertTrue(done.await(2, TimeUnit.SECONDS));
        }
    }
}
