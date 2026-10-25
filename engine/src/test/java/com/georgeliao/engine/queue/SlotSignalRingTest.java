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

class SlotSignalRingTest {

    @Test
    void fifoFullEmptyAndSpecialValues() {
        SlotSignalRing ring = new SlotSignalRing(4);
        List<Integer> out = new ArrayList<>();
        assertEquals(0, ring.drain(out::add, 10));
        for (int v : new int[] {0, -1, Integer.MAX_VALUE, 7}) {
            assertTrue(ring.offer(v));
        }
        assertFalse(ring.offer(8));
        assertEquals(2, ring.drain(out::add, 2));
        assertTrue(ring.offer(8));
        assertEquals(3, ring.drain(out::add, 10));
        assertEquals(List.of(0, -1, Integer.MAX_VALUE, 7, 8), out);
        assertThrows(IllegalArgumentException.class, () -> ring.offer(Integer.MIN_VALUE));
    }

    @Test
    void concurrentStressPreservesOrder() throws Exception {
        for (int capacity : new int[] {2, 64, 1024}) {
            SlotSignalRing ring = new SlotSignalRing(capacity);
            int count = 3_000_000;
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
        }
    }
}
