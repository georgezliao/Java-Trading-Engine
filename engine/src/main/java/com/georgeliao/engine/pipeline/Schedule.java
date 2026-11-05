package com.georgeliao.engine.pipeline;

/**
 * Open-loop send schedule: message {@code i} (0-based) is due at {@code start + arrivals(i)}. The
 * producer never waits for the consumer to catch up before computing the next due time, and
 * latency is measured from the due time, not the actual send. So when the engine stalls, every
 * message queued behind the stall gets charged for it (no coordinated omission).
 */
public record Schedule(long startNanos, Arrivals arrivals) {

    /** No pacing: every message is due at start. Latency numbers are meaningless in this mode. */
    public static final long UNPACED = 0;

    public Schedule(long startNanos, long rate) {
        this(startNanos, Arrivals.fixed(rate));
    }

    public long intendedNanos(long i) {
        return startNanos + arrivals.offsetNanos(i);
    }

    /** Spin until message {@code i} is due. Returns immediately if we're already behind. */
    public void awaitSend(long i) {
        long due = intendedNanos(i);
        while (System.nanoTime() < due) {
            Thread.onSpinWait();
        }
    }
}
