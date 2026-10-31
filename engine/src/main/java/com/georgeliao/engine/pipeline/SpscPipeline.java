package com.georgeliao.engine.pipeline;

import com.georgeliao.engine.Engine;
import com.georgeliao.engine.queue.SlotSignalRing;
import com.georgeliao.engine.queue.SpscIntRing;
import com.georgeliao.engine.wire.Codec;
import com.georgeliao.engine.wire.FrameView;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The {@code spsc} variant. Frame offsets go through a {@link SlotSignalRing} instead of an
 * {@code ArrayBlockingQueue}: no lock, no parking, no allocation on the handoff. (The slot-signalled
 * ring measured ~28 ns/handoff vs ~65 ns for the index-based {@link SpscIntRing} in HandoffBench.) Both threads
 * busy-spin by default (reader when the ring is full, engine when it's empty), so this burns two
 * cores. {@link WaitStrategy} changes what the engine does when the ring is empty.
 *
 * <p>Offsets are non-negative, so {@code -1} marks the end of the feed.
 */
public final class SpscPipeline {

    private static final int END = -1;
    private static final int BATCH = 256;

    private final int capacity;
    private final WaitStrategy wait;

    public SpscPipeline(int capacity) {
        this(capacity, WaitStrategy.SPIN);
    }

    public SpscPipeline(int capacity, WaitStrategy wait) {
        this.capacity = capacity;
        this.wait = wait;
    }

    public RunResult run(ByteBuffer feed, Engine engine) throws InterruptedException {
        return run(feed, engine, Schedule.UNPACED, LatencyRecorder.NOOP);
    }

    public RunResult run(ByteBuffer feed, Engine engine, long rate, LatencyRecorder recorder)
            throws InterruptedException {
        Schedule schedule = new Schedule(System.nanoTime() + 1_000_000, rate);
        SlotSignalRing ring = new SlotSignalRing(capacity);
        int start = feed.position();
        int end = feed.limit();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Consumer consumer = new Consumer(ring, feed, engine, recorder, schedule, wait, failure);

        Thread engineThread = new Thread(consumer, "engine");
        Thread producer = new Thread(() -> {
            try {
                Pinning.pinReader();
                long i = 0;
                for (int offset = start; offset + Codec.FRAME_SIZE <= end; offset += Codec.FRAME_SIZE) {
                    schedule.awaitSend(i++);
                    while (!ring.offer(offset)) {
                        if (failure.get() != null) {
                            return;
                        }
                        Thread.onSpinWait();
                    }
                }
                while (!ring.offer(END)) {
                    Thread.onSpinWait();
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "reader");

        engineThread.start();
        producer.start();
        producer.join();
        engineThread.join();
        long elapsed = System.nanoTime() - schedule.startNanos();

        if (failure.get() != null) {
            throw new IllegalStateException("pipeline failed", failure.get());
        }
        return new RunResult(consumer.messages, consumer.triggers, elapsed);
    }

    /** Engine-thread loop. A class rather than a lambda so the drain handler is a plain method. */
    private static final class Consumer implements Runnable, SpscIntRing.IntHandler {
        private final SlotSignalRing ring;
        private final ByteBuffer feed;
        private final Engine engine;
        private final LatencyRecorder recorder;
        private final Schedule schedule;
        private final WaitStrategy wait;
        private final AtomicReference<Throwable> failure;
        private final FrameView view = new FrameView();
        private final boolean timeService;
        private boolean done;
        long messages;
        long triggers;

        Consumer(SlotSignalRing ring, ByteBuffer feed, Engine engine, LatencyRecorder recorder, Schedule schedule,
                 WaitStrategy wait, AtomicReference<Throwable> failure) {
            this.ring = ring;
            this.feed = feed;
            this.engine = engine;
            this.recorder = recorder;
            this.schedule = schedule;
            this.wait = wait;
            this.failure = failure;
            this.timeService = recorder.wantsServiceTime();
        }

        @Override
        public void run() {
            try {
                Pinning.pinEngine();
                while (!done) {
                    if (ring.drain(this, BATCH) == 0) {
                        if (failure.get() != null) {
                            return;
                        }
                        wait.idle();
                    }
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }

        @Override
        public void onValue(int offset) {
            if (offset == END) {
                done = true;
                return;
            }
            boolean triggered;
            if (timeService) {
                long t0 = System.nanoTime();
                triggered = engine.onFrame(view.wrap(feed, offset));
                long t1 = System.nanoTime();
                recorder.recordWithService(messages, t1 - schedule.intendedNanos(messages), t1 - t0, triggered);
            } else {
                triggered = engine.onFrame(view.wrap(feed, offset));
                recorder.record(messages, System.nanoTime() - schedule.intendedNanos(messages), triggered);
            }
            messages++;
            if (triggered) {
                triggers++;
            }
        }
    }
}
