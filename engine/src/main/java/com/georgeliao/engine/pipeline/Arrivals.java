package com.georgeliao.engine.pipeline;

import java.util.Locale;
import java.util.SplittableRandom;

/**
 * When each message is due, as nanoseconds after the start of the run.
 *
 * <p>{@code fixed} spaces messages evenly. That's easy to reason about but hides queueing, and it
 * can phase-lock with periodic events like the kernel timer tick. {@code poisson} uses exponential
 * gaps. {@code bursty} is a two-state process: on average 20% of the time at 4x the rate and 80% at
 * 0.25x, which works out to the requested mean rate.
 *
 * <p>Random schedules are generated up front from a seed, so the hot path only reads an array and
 * two runs with the same seed see identical arrivals. Only timing differs between modes, so the
 * output hash doesn't change.
 */
public final class Arrivals {

    public enum Mode {
        FIXED, POISSON, BURSTY;

        public static Mode parse(String s) {
            return valueOf(s.toUpperCase(Locale.ROOT));
        }
    }

    private static final double BURST_FRACTION = 0.2;
    private static final double BURST_MULTIPLIER = 4.0;
    private static final double QUIET_MULTIPLIER = 0.25;
    /** Mean length of one on/off cycle. Long enough for a backlog to build at high rates. */
    private static final double MEAN_CYCLE_NANOS = 1_000_000;

    private final long rate;
    private final long[] offsets;

    private Arrivals(long rate, long[] offsets) {
        this.rate = rate;
        this.offsets = offsets;
    }

    /** Evenly spaced, or all due at the start if {@code rate} is {@link Schedule#UNPACED}. */
    public static Arrivals fixed(long rate) {
        return new Arrivals(rate, null);
    }

    public static Arrivals of(Mode mode, int count, long rate, long seed) {
        return switch (mode) {
            case FIXED -> fixed(rate);
            case POISSON -> poisson(count, rate, seed);
            case BURSTY -> bursty(count, rate, seed);
        };
    }

    public static Arrivals poisson(int count, long rate, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        double meanGap = 1e9 / rate;
        long[] offsets = new long[count];
        double t = 0;
        for (int i = 0; i < count; i++) {
            offsets[i] = (long) t;
            t += exponential(rng, meanGap);
        }
        return new Arrivals(rate, offsets);
    }

    public static Arrivals bursty(int count, long rate, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        double burstGap = 1e9 / (rate * BURST_MULTIPLIER);
        double quietGap = 1e9 / (rate * QUIET_MULTIPLIER);
        double meanBurst = MEAN_CYCLE_NANOS * BURST_FRACTION;
        double meanQuiet = MEAN_CYCLE_NANOS * (1 - BURST_FRACTION);
        long[] offsets = new long[count];
        double t = 0;
        boolean burst = true;
        double stateEnds = exponential(rng, meanBurst);
        for (int i = 0; i < count; i++) {
            offsets[i] = (long) t;
            t += exponential(rng, burst ? burstGap : quietGap);
            while (t >= stateEnds) {
                burst = !burst;
                stateEnds += exponential(rng, burst ? meanBurst : meanQuiet);
            }
        }
        return new Arrivals(rate, offsets);
    }

    private static double exponential(SplittableRandom rng, double mean) {
        return -mean * Math.log(1 - rng.nextDouble());
    }

    public long offsetNanos(long i) {
        if (offsets != null) {
            return offsets[(int) i];
        }
        return rate == Schedule.UNPACED ? 0 : i * 1_000_000_000L / rate;
    }
}
