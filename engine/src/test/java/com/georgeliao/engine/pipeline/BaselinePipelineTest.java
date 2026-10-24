package com.georgeliao.engine.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.georgeliao.engine.Engine;
import com.georgeliao.engine.book.BookListener;
import com.georgeliao.engine.feed.FeedGenerator;
import com.georgeliao.engine.wire.Codec;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BaselinePipelineTest {

    /** Records book events as strings; fine for a test-sized feed. */
    static final class Recorder implements BookListener {
        final List<String> events = new ArrayList<>();
        @Override public void onRest(long id, long price, int qty) { events.add("R" + id + ":" + qty + "@" + price); }
        @Override public void onFill(long a, long r, long price, int qty) { events.add("F" + a + "x" + r + ":" + qty + "@" + price); }
        @Override public void onCancel(long id, int qty) { events.add("C" + id + ":" + qty); }
        @Override public void onCancelReject(long id) { events.add("X" + id); }
    }

    @Test
    void matchesSingleThreadedReplay() throws Exception {
        ByteBuffer feed = FeedGenerator.generate(FeedGenerator.Profile.BURST, 50_000, 11);

        Recorder direct = new Recorder();
        Engine reference = new Engine(direct);
        ByteBuffer frames = feed.duplicate().order(feed.order());
        long triggers = 0;
        while (frames.hasRemaining()) {
            if (reference.onMessage(Codec.decode(frames))) {
                triggers++;
            }
        }

        Recorder piped = new Recorder();
        // Tiny queue so the threads actually block on each other.
        RunResult result = new BaselinePipeline(4).run(feed, new Engine(piped));

        assertEquals(50_000, result.messages());
        assertEquals(triggers, result.triggers());
        assertTrue(triggers > 0);
        assertEquals(direct.events, piped.events);
        assertEquals(0, feed.position(), "run must not consume the caller's buffer");
    }
}
