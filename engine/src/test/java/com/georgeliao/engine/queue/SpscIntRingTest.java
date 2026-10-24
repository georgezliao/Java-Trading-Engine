package com.georgeliao.engine.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SpscIntRingTest {

    @Test
    void fifoFullAndEmpty() {
        SpscIntRing ring = new SpscIntRing(4);
        List<Integer> out = new ArrayList<>();
        assertEquals(0, ring.drain(out::add, 10));
        for (int i = 0; i < 4; i++) {
            assertTrue(ring.offer(i));
        }
        assertFalse(ring.offer(99));
        assertEquals(2, ring.drain(out::add, 2));
        assertTrue(ring.offer(4));
        assertTrue(ring.offer(5));
        assertEquals(4, ring.drain(out::add, 10));
        assertEquals(List.of(0, 1, 2, 3, 4, 5), out);
        assertEquals(0, ring.size());
    }

    @Test
    void rejectsBadCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new SpscIntRing(3));
        assertThrows(IllegalArgumentException.class, () -> new SpscIntRing(1));
    }

    /**
     * Two threads, small ring so it wraps and hits full/empty constantly. The consumer checks every
     * value arrives exactly once and in order.
     */
    @Test
    void concurrentStressPreservesOrder() throws Exception {
        for (int capacity : new int[] {2, 64, 1024}) {
            stress(capacity, 3_000_000);
        }
    }

    private static void stress(int capacity, int count) throws Exception {
        SpscIntRing ring = new SpscIntRing(capacity);
        AtomicReference<String> error = new AtomicReference<>();
        Thread producer = new Thread(() -> {
            for (int i = 0; i < count; i++) {
                while (!ring.offer(i)) {
                    Thread.onSpinWait();
                }
            }
        });
        int[] expected = {0};
        SpscIntRing.IntHandler check = v -> {
            if (v != expected[0] && error.get() == null) {
                error.set("capacity " + capacity + ": expected " + expected[0] + " got " + v);
            }
            expected[0]++;
        };
        producer.start();
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (expected[0] < count && System.nanoTime() < deadline) {
            if (ring.drain(check, 256) == 0) {
                Thread.onSpinWait();
            }
        }
        producer.join(5_000);
        assertNull(error.get());
        assertEquals(count, expected[0], "capacity " + capacity + ": timed out");
        assertEquals(0, ring.size());
    }
}
