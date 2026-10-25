package com.georgeliao.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.georgeliao.engine.book.HashingListener;
import com.georgeliao.engine.book.LadderBook;
import com.georgeliao.engine.book.PooledOrderBook;
import com.georgeliao.engine.feed.FeedGenerator;
import com.georgeliao.engine.pipeline.AllocFreePipeline;
import com.georgeliao.engine.pipeline.BaselinePipeline;
import com.georgeliao.engine.pipeline.RunResult;
import com.georgeliao.engine.pipeline.SpscPipeline;
import com.georgeliao.engine.wire.Codec;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * The oracle. Every variant must reproduce this hash on the committed feed. If the hash changes,
 * engine behaviour changed: only update the constants on purpose, in their own commit.
 */
class GoldenOutputTest {

    static final String FEED = "/feeds/golden-burst-5k.bin";
    static final long HASH = 0xe6ae302edc4501ddL;
    static final long EVENTS = 3720;
    static final long TRIGGERS = 834;

    static ByteBuffer goldenFeed() throws Exception {
        try (InputStream in = GoldenOutputTest.class.getResourceAsStream(FEED)) {
            return ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    @Test
    void generatorStillProducesTheCommittedFeed() throws Exception {
        ByteBuffer regenerated = FeedGenerator.generate(FeedGenerator.Profile.BURST, 5000, 2026);
        byte[] expected = goldenFeed().array();
        byte[] actual = new byte[regenerated.remaining()];
        regenerated.get(actual);
        assertArrayEquals(expected, actual);
    }

    @Test
    void singleThreadedReplayMatchesGoldenHash() throws Exception {
        ByteBuffer feed = goldenFeed();
        HashingListener out = new HashingListener();
        Engine engine = new Engine(out);
        long triggers = 0;
        while (feed.hasRemaining()) {
            if (engine.onMessage(Codec.decode(feed))) {
                triggers++;
            }
        }
        assertEquals(TRIGGERS, triggers);
        assertEquals(EVENTS, out.events());
        assertEquals(HASH, out.hash(), "got " + out.hex());
    }

    @Test
    void baselinePipelineMatchesGoldenHash() throws Exception {
        HashingListener out = new HashingListener();
        RunResult result = new BaselinePipeline(BaselinePipeline.DEFAULT_CAPACITY).run(goldenFeed(), new Engine(out));
        assertEquals(TRIGGERS, result.triggers());
        assertEquals(EVENTS, out.events());
        assertEquals(HASH, out.hash(), "got " + out.hex());
    }

    @Test
    void allocFreePipelineMatchesGoldenHash() throws Exception {
        HashingListener out = new HashingListener();
        RunResult result = new AllocFreePipeline(BaselinePipeline.DEFAULT_CAPACITY)
                .run(goldenFeed(), new Engine(new PooledOrderBook(out)));
        assertEquals(TRIGGERS, result.triggers());
        assertEquals(EVENTS, out.events());
        assertEquals(HASH, out.hash(), "got " + out.hex());
    }

    @Test
    void ladderBookMatchesGoldenHash() throws Exception {
        HashingListener out = new HashingListener();
        RunResult result = new AllocFreePipeline(BaselinePipeline.DEFAULT_CAPACITY)
                .run(goldenFeed(), new Engine(new LadderBook(out)));
        assertEquals(TRIGGERS, result.triggers());
        assertEquals(EVENTS, out.events());
        assertEquals(HASH, out.hash(), "got " + out.hex());
    }

    @Test
    void spscPipelineMatchesGoldenHash() throws Exception {
        for (int capacity : new int[] {2, 1024}) {
            HashingListener out = new HashingListener();
            RunResult result = new SpscPipeline(capacity).run(goldenFeed(), new Engine(new LadderBook(out)));
            assertEquals(5000, result.messages());
            assertEquals(TRIGGERS, result.triggers());
            assertEquals(EVENTS, out.events());
            assertEquals(HASH, out.hash(), "capacity " + capacity + " got " + out.hex());
        }
    }
}
