package com.georgeliao.engine.queue;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Single-producer single-consumer ring of ints (here: frame offsets, so nothing is allocated).
 *
 * <ul>
 *   <li>{@code tail} (written by the producer) and {@code head} (written by the consumer) sit on
 *       separate cache lines. Padding is 128 bytes per side because Apple M-series cores use
 *       128-byte lines; it's done with superclass fields so the JVM can't reorder them together.
 *   <li>Each side publishes its sequence with {@code setRelease} and reads the other side's with
 *       {@code getAcquire}. No locks, no CAS, no volatile full fences.
 *   <li>Each side caches the other's last seen sequence and only re-reads it when the ring looks
 *       full (producer) or empty (consumer).
 *   <li>The consumer drains in batches: one acquire of {@code tail} and one release of
 *       {@code head} per batch, not per element.
 * </ul>
 *
 * Exactly one thread may call {@link #offer}, and exactly one (other) thread {@link #drain}.
 */
public final class SpscIntRing extends SpscIntRingFields {

    private static final VarHandle HEAD;
    private static final VarHandle TAIL;

    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            HEAD = l.findVarHandle(HeadField.class, "head", long.class);
            TAIL = l.findVarHandle(TailField.class, "tail", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Receives drained values on the consumer thread. */
    @FunctionalInterface
    public interface IntHandler {
        void onValue(int value);
    }

    private final int[] buffer;
    private final int mask;
    private final int capacity;

    public SpscIntRing(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2: " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.buffer = new int[capacity];
    }

    public int capacity() {
        return capacity;
    }

    /** Producer only. Returns false if the ring is full. */
    public boolean offer(int value) {
        long t = tail; // only this thread writes tail, so a plain read is fine
        if (t - headCache >= capacity) {
            // Looks full from the cached head; re-read the real one.
            headCache = (long) HEAD.getAcquire(this);
            if (t - headCache >= capacity) {
                return false;
            }
        }
        buffer[(int) t & mask] = value;
        TAIL.setRelease(this, t + 1);
        return true;
    }

    /** Consumer only. Hands up to {@code limit} values to {@code handler}; returns how many. */
    public int drain(IntHandler handler, int limit) {
        long h = head; // only this thread writes head
        long available = tailCache - h;
        if (available < limit) {
            // Refresh once per batch so a batch takes everything published so far.
            tailCache = (long) TAIL.getAcquire(this);
            available = tailCache - h;
            if (available <= 0) {
                return 0;
            }
        }
        int n = (int) Math.min(available, limit);
        for (int i = 0; i < n; i++) {
            handler.onValue(buffer[(int) (h + i) & mask]);
        }
        HEAD.setRelease(this, h + n);
        return n;
    }

    /** Approximate; safe to call from any thread. */
    public int size() {
        return (int) ((long) TAIL.getAcquire(this) - (long) HEAD.getAcquire(this));
    }
}

// Layout: [pad][tail, headCache][pad][head, tailCache][pad]. Fields in a superclass are laid out
// before subclass fields, which keeps each hot pair on its own line.
abstract class PadA {
    long a00, a01, a02, a03, a04, a05, a06, a07, a08, a09, a10, a11, a12, a13, a14, a15;
}

abstract class TailField extends PadA {
    /** Next slot the producer writes. Written by producer (release), read by consumer (acquire). */
    long tail;
    /** Producer's cached copy of head. */
    long headCache;
}

abstract class PadB extends TailField {
    long b00, b01, b02, b03, b04, b05, b06, b07, b08, b09, b10, b11, b12, b13, b14, b15;
}

abstract class HeadField extends PadB {
    /** Next slot the consumer reads. Written by consumer (release), read by producer (acquire). */
    long head;
    /** Consumer's cached copy of tail. */
    long tailCache;
}

abstract class SpscIntRingFields extends HeadField {
    long c00, c01, c02, c03, c04, c05, c06, c07, c08, c09, c10, c11, c12, c13, c14, c15;
}
