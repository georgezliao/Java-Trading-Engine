package com.georgeliao.engine.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.georgeliao.engine.Engine;
import com.georgeliao.engine.book.HashingListener;
import com.georgeliao.engine.book.LadderBook;
import com.georgeliao.engine.feed.FeedGenerator;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class ArrivalsTest {

    private static final int COUNT = 1_000_000;
    private static final long RATE = 1_000_000;

    /** Coefficient of variation of the gaps: 0 for fixed, about 1 for Poisson, above 1 for bursty. */
    private static double gapCv(Arrivals a) {
        double sum = 0;
        double squares = 0;
        for (int i = 1; i < COUNT; i++) {
            double gap = a.offsetNanos(i) - a.offsetNanos(i - 1);
            sum += gap;
            squares += gap * gap;
        }
        double mean = sum / (COUNT - 1);
        return Math.sqrt(squares / (COUNT - 1) - mean * mean) / mean;
    }

    private static double meanRate(Arrivals a) {
        return (COUNT - 1) * 1e9 / a.offsetNanos(COUNT - 1);
    }

    @Test
    void fixedMatchesTheOldSchedule() {
        Arrivals a = Arrivals.fixed(200_000);
        assertEquals(0, a.offsetNanos(0));
        assertEquals(5_000, a.offsetNanos(1));
        assertEquals(1_000_000_000L, a.offsetNanos(200_000));
        assertEquals(0, Arrivals.fixed(Schedule.UNPACED).offsetNanos(99));
    }

    @Test
    void poissonHitsMeanRateWithUnitVariation() {
        Arrivals a = Arrivals.poisson(COUNT, RATE, 1);
        assertEquals(RATE, meanRate(a), RATE * 0.01);
        assertEquals(1.0, gapCv(a), 0.02);
    }

    @Test
    void burstyHitsMeanRateAndIsBurstierThanPoisson() {
        Arrivals a = Arrivals.bursty(COUNT, RATE, 1);
        assertEquals(RATE, meanRate(a), RATE * 0.05);
        assertTrue(gapCv(a) > 1.3, "cv " + gapCv(a));
    }

    @Test
    void sameSeedSameScheduleAndTimeNeverGoesBackwards() {
        for (Arrivals.Mode mode : new Arrivals.Mode[] {Arrivals.Mode.POISSON, Arrivals.Mode.BURSTY}) {
            Arrivals a = Arrivals.of(mode, 50_000, RATE, 7);
            Arrivals b = Arrivals.of(mode, 50_000, RATE, 7);
            Arrivals c = Arrivals.of(mode, 50_000, RATE, 8);
            boolean differs = false;
            for (int i = 1; i < 50_000; i++) {
                assertEquals(a.offsetNanos(i), b.offsetNanos(i));
                assertTrue(a.offsetNanos(i) >= a.offsetNanos(i - 1));
                differs |= a.offsetNanos(i) != c.offsetNanos(i);
            }
            assertTrue(differs);
        }
    }

    @Test
    void timingDoesNotChangeTheOutput() throws Exception {
        ByteBuffer feed = FeedGenerator.generate(FeedGenerator.Profile.BURST, 50_000, 3);
        String[] hashes = new String[3];
        Arrivals[] modes = {Arrivals.fixed(500_000), Arrivals.poisson(50_000, 500_000, 1), Arrivals.bursty(50_000, 500_000, 1)};
        for (int i = 0; i < modes.length; i++) {
            HashingListener out = new HashingListener();
            new SpscPipeline(1024).run(feed, new Engine(new LadderBook(out)), modes[i], LatencyRecorder.NOOP);
            hashes[i] = out.hex();
        }
        assertEquals(hashes[0], hashes[1]);
        assertEquals(hashes[0], hashes[2]);
    }
}
