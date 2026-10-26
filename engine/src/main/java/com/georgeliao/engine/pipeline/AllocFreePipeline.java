package com.georgeliao.engine.pipeline;

import com.georgeliao.engine.Engine;
import com.georgeliao.engine.wire.Codec;
import com.georgeliao.engine.wire.FrameView;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Experiment A. Same threads and same {@link ArrayBlockingQueue} as {@link BaselinePipeline}, so
 * only allocation changes: the reader passes preallocated slots that carry a frame offset, and
 * the engine reads fields through a {@link FrameView} over the (read-only, in-memory) feed.
 *
 * <p>Slots are reused round-robin. With {@code capacity + 2} slots the reader can't overwrite a
 * slot the engine is still using: at most {@code capacity} are queued, one is being processed,
 * one is being filled. The queue's lock gives the happens-before for the slot write.
 *
 * <p>Not fully allocation-free: {@code ArrayBlockingQueue}'s lock allocates a node whenever a
 * thread has to wait. That goes away with the SPSC ring in experiment C.
 */
public final class AllocFreePipeline {

    private static final class Slot {
        int offset;
    }

    private static final Slot END = new Slot();

    private final int capacity;

    public AllocFreePipeline(int capacity) {
        this.capacity = capacity;
    }

    public RunResult run(ByteBuffer feed, Engine engine) throws InterruptedException {
        return run(feed, engine, Schedule.UNPACED, LatencyRecorder.NOOP);
    }

    public RunResult run(ByteBuffer feed, Engine engine, long rate, LatencyRecorder recorder)
            throws InterruptedException {
        Schedule schedule = new Schedule(System.nanoTime() + 1_000_000, rate);
        BlockingQueue<Slot> queue = new ArrayBlockingQueue<>(capacity);
        Slot[] slots = new Slot[capacity + 2];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = new Slot();
        }
        int start = feed.position();
        int end = feed.limit();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean timeService = recorder.wantsServiceTime();
        long[] counts = new long[2];

        Thread consumer = new Thread(() -> {
            try {
                Pinning.pinEngine();
                FrameView view = new FrameView();
                long messages = 0;
                long triggers = 0;
                Slot s;
                while ((s = queue.take()) != END) {
                    boolean triggered;
                    if (timeService) {
                        long t0 = System.nanoTime();
                        triggered = engine.onFrame(view.wrap(feed, s.offset));
                        long t1 = System.nanoTime();
                        recorder.recordWithService(messages, t1 - schedule.intendedNanos(messages), t1 - t0, triggered);
                    } else {
                        triggered = engine.onFrame(view.wrap(feed, s.offset));
                        recorder.record(messages, System.nanoTime() - schedule.intendedNanos(messages), triggered);
                    }
                    messages++;
                    if (triggered) {
                        triggers++;
                    }
                }
                counts[0] = messages;
                counts[1] = triggers;
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "engine");

        Thread producer = new Thread(() -> {
            try {
                Pinning.pinReader();
                long i = 0;
                for (int offset = start; offset + Codec.FRAME_SIZE <= end; offset += Codec.FRAME_SIZE) {
                    schedule.awaitSend(i);
                    Slot s = slots[(int) (i % slots.length)];
                    s.offset = offset;
                    queue.put(s);
                    i++;
                }
                queue.put(END);
            } catch (Throwable t) {
                failure.set(t);
                consumer.interrupt();
            }
        }, "reader");

        consumer.start();
        producer.start();
        producer.join();
        consumer.join();
        long elapsed = System.nanoTime() - schedule.startNanos();

        if (failure.get() != null) {
            throw new IllegalStateException("pipeline failed", failure.get());
        }
        return new RunResult(counts[0], counts[1], elapsed);
    }
}
