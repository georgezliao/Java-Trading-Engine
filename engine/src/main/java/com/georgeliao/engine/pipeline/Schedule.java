package com.georgeliao.engine.pipeline;

/**
 * Open-loop send schedule: message {@code i} (0-based) is due at {@code start + i / rate}. The
 * producer never waits for the consumer to catch up before computing the next due time, and
 * latency is measured from the due time, not the actual send. So when the engine stalls, every
 * message queued behind the stall gets charged for it (no coordinated omission).
 */
public record Schedule(long startNanos, long rate) {

    /** No pacing: every message is due at start. Latency numbers are meaningless in this mode. */
    public static final long UNPACED = 0;

    public long intendedNanos(long i) {
        return rate == UNPACED ? startNanos : startNanos + i * 1_000_000_000L / rate;
    }

    /** Spin until message {@code i} is due. Returns immediately if we're already behind. */
    public void awaitSend(long i) {
        long due = intendedNanos(i);
        while (System.nanoTime() < due) {
            Thread.onSpinWait();
        }
    }
}
