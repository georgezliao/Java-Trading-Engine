package com.georgeliao.engine.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * SPSC int ring where the slots themselves carry the full/empty signal (FastFlow / JCTools
 * style), so neither side ever reads the other side's index:
 *
 * <ul>
 *   <li>a slot holding 0 is empty. Values are stored as {@code value ^ Integer.MIN_VALUE}, so any
 *       int except {@code Integer.MIN_VALUE} can be sent (including -1);
 *   <li>the producer checks its next slot is 0 (acquire), then writes the value (release);
 *   <li>the consumer reads its next slot (acquire); non-zero means data. It clears the slot to 0
 *       (release) after reading, which is what tells the producer the slot is free.
 * </ul>
 *
 * Compared with {@link SpscIntRing}, the only shared cache lines are the data lines both sides
 * already touch. Same single-producer/single-consumer contract and the same API.
 */
public final class SlotSignalRing {

    private static final VarHandle SLOT = MethodHandles.arrayElementVarHandle(int[].class);
    private static final int EMPTY = 0;

    private final int[] buffer;
    private final int mask;
    // Each index is touched by one thread only. Padded apart anyway so they don't share a line.
    private long p00, p01, p02, p03, p04, p05, p06, p07, p08, p09, p10, p11, p12, p13, p14, p15;
    private long tail;
    private long q00, q01, q02, q03, q04, q05, q06, q07, q08, q09, q10, q11, q12, q13, q14, q15;
    private long head;
    private long r00, r01, r02, r03, r04, r05, r06, r07, r08, r09, r10, r11, r12, r13, r14, r15;

    public SlotSignalRing(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2: " + capacity);
        }
        this.buffer = new int[capacity];
        this.mask = capacity - 1;
    }

    /** Producer only. Returns false if the ring is full. */
    public boolean offer(int value) {
        if (value == Integer.MIN_VALUE) {
            throw new IllegalArgumentException("Integer.MIN_VALUE is reserved");
        }
        int i = (int) tail & mask;
        if ((int) SLOT.getAcquire(buffer, i) != EMPTY) {
            return false;
        }
        SLOT.setRelease(buffer, i, value ^ Integer.MIN_VALUE);
        tail++;
        return true;
    }

    /** Consumer only. Hands up to {@code limit} values to {@code handler}; returns how many. */
    public int drain(SpscIntRing.IntHandler handler, int limit) {
        int n = 0;
        while (n < limit) {
            int i = (int) head & mask;
            int stored = (int) SLOT.getAcquire(buffer, i);
            if (stored == EMPTY) {
                break;
            }
            SLOT.setRelease(buffer, i, EMPTY);
            head++;
            n++;
            handler.onValue(stored ^ Integer.MIN_VALUE);
        }
        return n;
    }
}
