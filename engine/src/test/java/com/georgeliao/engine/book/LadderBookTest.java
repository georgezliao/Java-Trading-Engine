package com.georgeliao.engine.book;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.georgeliao.engine.Engine;
import com.georgeliao.engine.feed.FeedGenerator;
import com.georgeliao.engine.wire.Codec;
import com.georgeliao.engine.wire.FrameView;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class LadderBookTest extends BookContractTest {

    @Override
    Book create(BookListener listener) {
        // Tiny arrays and window so tests also cover growth and re-centring.
        return new LadderBook(listener, 2, 4);
    }

    @Test
    void matchesTreeMapBookOnGeneratedFeeds() {
        for (long seed = 1; seed <= 3; seed++) {
            ByteBuffer feed = FeedGenerator.generate(FeedGenerator.Profile.BURST, 200_000, seed);

            HashingListener expected = new HashingListener();
            Engine reference = new Engine(expected);
            ByteBuffer frames = feed.duplicate().order(feed.order());
            while (frames.hasRemaining()) {
                reference.onMessage(Codec.decode(frames));
            }

            HashingListener actual = new HashingListener();
            Engine ladder = new Engine(new LadderBook(actual, 16, 64));
            FrameView view = new FrameView();
            for (int at = 0; at < feed.limit(); at += Codec.FRAME_SIZE) {
                ladder.onFrame(view.wrap(feed, at));
            }

            assertEquals(expected.events(), actual.events(), "seed " + seed);
            assertEquals(expected.hex(), actual.hex(), "seed " + seed);
            assertEquals(reference.book().restingOrders(), ladder.book().restingOrders());
            assertEquals(reference.book().bestBid(), ladder.book().bestBid());
            assertEquals(reference.book().bestAsk(), ladder.book().bestAsk());
        }
    }
}
