package com.georgeliao.engine.feed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.georgeliao.engine.feed.FeedGenerator.Profile;
import com.georgeliao.engine.wire.Codec;
import com.georgeliao.engine.wire.Message;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeedGeneratorTest {

    private static byte[] bytes(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return out;
    }

    @Test
    void sameSeedGivesIdenticalBytes() {
        for (Profile p : Profile.values()) {
            assertArrayEquals(bytes(FeedGenerator.generate(p, 50_000, 7)), bytes(FeedGenerator.generate(p, 50_000, 7)));
        }
    }

    @Test
    void seedAndProfileChangeTheFeed() {
        byte[] base = bytes(FeedGenerator.generate(Profile.BURST, 50_000, 7));
        assertFalse(Arrays.equals(base, bytes(FeedGenerator.generate(Profile.BURST, 50_000, 8))));
        assertFalse(Arrays.equals(base, bytes(FeedGenerator.generate(Profile.STEADY, 50_000, 7))));
    }

    @Test
    void framesDecodeWithSequentialSeqAndValidCancels() {
        ByteBuffer feed = FeedGenerator.generate(Profile.BURST, 20_000, 3);
        assertEquals(20_000 * Codec.FRAME_SIZE, feed.remaining());
        Set<Long> live = new HashSet<>();
        long seq = 0;
        while (feed.hasRemaining()) {
            Message m = Codec.decode(feed);
            assertEquals(++seq, m.seq());
            switch (m) {
                case Message.NewOrder o -> assertTrue(live.add(o.orderId()));
                case Message.Cancel c -> assertTrue(live.remove(c.orderId()), "cancel of unknown id " + c.orderId());
                case Message.Tick t -> assertTrue(t.price() > 0);
            }
        }
    }

    @Test
    void writesFileMatchingBuffer(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("feeds/f.bin");
        FeedGenerator.run(new String[] {"--profile", "burst", "--count", "1000", "--seed", "1", "--out", out.toString()});
        assertArrayEquals(bytes(FeedGenerator.generate(Profile.BURST, 1000, 1)), Files.readAllBytes(out));
    }
}
