package com.georgeliao.engine.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.georgeliao.engine.Engine;
import com.georgeliao.engine.book.BookListener;
import com.georgeliao.engine.feed.FeedGenerator;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class OpenLoopTest {

    private static final ByteBuffer FEED = FeedGenerator.generate(FeedGenerator.Profile.STEADY, 20_000, 5);

    @Test
    void scheduleSpacesMessagesByRate() {
        Schedule s = new Schedule(1_000, 200_000);
        assertEquals(1_000, s.intendedNanos(0));
        assertEquals(1_000 + 5_000, s.intendedNanos(1));
        assertEquals(1_000 + 1_000_000_000L, s.intendedNanos(200_000));
        assertEquals(1_000, new Schedule(1_000, Schedule.UNPACED).intendedNanos(99));
    }

    @Test
    void pacedRunTakesAboutCountOverRate() throws Exception {
        long[] recorded = new long[2];
        RunResult r = new BaselinePipeline(1024).run(FEED, new Engine(BookListener.NOOP), 200_000,
                (i, latency, triggered) -> {
                    recorded[0]++;
                    if (triggered) {
                        recorded[1]++;
                    }
                });
        assertEquals(20_000, recorded[0]);
        assertEquals(r.triggers(), recorded[1]);
        // 20k messages at 200k/s is 100 ms of schedule.
        assertTrue(r.elapsedNanos() >= 99_000_000L, "finished too early: " + r.elapsedNanos());
    }

    @Test
    void stallIsChargedToMessagesQueuedBehindIt() throws Exception {
        long[] latencies = new long[20_000];
        new BaselinePipeline(1 << 15).run(FEED, new Engine(BookListener.NOOP), 200_000,
                (i, latency, triggered) -> {
                    latencies[(int) i] = latency;
                    if (i == 1_000) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
        // Message 1001 was due 5 us after 1000 but couldn't be processed until the 20 ms stall
        // ended. A closed-loop harness would report ~nothing for it; open loop reports the wait.
        assertTrue(latencies[1_001] > 15_000_000L, "latency after stall: " + latencies[1_001]);
        // Messages due well after the stall have caught up again.
        assertTrue(latencies[19_999] < 15_000_000L, "never recovered: " + latencies[19_999]);
    }
}
