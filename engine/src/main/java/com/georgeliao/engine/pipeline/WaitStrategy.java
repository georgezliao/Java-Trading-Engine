package com.georgeliao.engine.pipeline;

import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * What the {@code spsc} engine thread does when the ring is empty. Each one is a single behavior (no
 * spin-then-yield-then-park ladder like Disruptor's SleepingWaitStrategy) so each one's cost shows
 * up on its own.
 */
public enum WaitStrategy {

    /** Burn the core. Lowest wake-up latency, 100% of a CPU even when idle. */
    SPIN {
        @Override
        public void idle() {
            Thread.onSpinWait();
        }
    },

    /** Give the core to the scheduler, run again when it decides. */
    YIELD {
        @Override
        public void idle() {
            Thread.yield();
        }
    },

    /**
     * Sleep ~1 us. The reader never unparks us, so the real sleep is the OS timer resolution
     * (tens of us on Linux by default) and every message that lands while we sleep waits for it.
     */
    PARK {
        @Override
        public void idle() {
            LockSupport.parkNanos(1_000);
        }
    };

    public abstract void idle();

    public static WaitStrategy parse(String s) {
        return valueOf(s.toUpperCase(Locale.ROOT));
    }
}
