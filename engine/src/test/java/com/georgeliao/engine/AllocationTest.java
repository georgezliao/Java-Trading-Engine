package com.georgeliao.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.georgeliao.engine.book.HashingListener;
import com.georgeliao.engine.book.PooledOrderBook;
import com.georgeliao.engine.feed.FeedGenerator;
import com.georgeliao.engine.metrics.HistogramRecorder;
import com.georgeliao.engine.wire.Codec;
import com.georgeliao.engine.wire.FrameView;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/** The alloc-free engine path (frame view, pooled book, strategy, hash, histogram) once warm. */
class AllocationTest {

    @Test
    void warmFramePathDoesNotAllocate() {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        ByteBuffer feed = FeedGenerator.generate(FeedGenerator.Profile.BURST, 400_000, 9);
        Engine engine = new Engine(new PooledOrderBook(new HashingListener()));
        HistogramRecorder latency = new HistogramRecorder(0);
        FrameView view = new FrameView();
        int frames = feed.limit() / Codec.FRAME_SIZE;
        int warm = frames / 4;

        for (int i = 0; i < warm; i++) {
            latency.record(i, 1_000 + i % 5_000, engine.onFrame(view.wrap(feed, i * Codec.FRAME_SIZE)));
        }
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = warm; i < frames; i++) {
            latency.record(i, 1_000 + i % 5_000, engine.onFrame(view.wrap(feed, i * Codec.FRAME_SIZE)));
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;

        // 300k messages. The baseline path allocates ~36 B/message just decoding (~10 MB).
        assertTrue(allocated < 64 * 1024, allocated + " bytes allocated over " + (frames - warm) + " messages");
        System.out.println("alloc-free path: " + allocated + " bytes over " + (frames - warm) + " messages");
    }
}
