package com.georgeliao.engine.pipeline;

import com.georgeliao.engine.Engine;
import com.georgeliao.engine.wire.Codec;
import com.georgeliao.engine.wire.Message;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reader thread decodes each frame into a new {@link Message} record and puts it on an
 * {@link ArrayBlockingQueue}; the engine thread takes and processes. Allocation per message, a
 * lock per handoff, and parking when the queue is empty or full. This is the thing to beat.
 */
public final class BaselinePipeline {

    public static final int DEFAULT_CAPACITY = 1024;

    /** End-of-feed marker, compared by identity. */
    private static final Message END = new Message.Tick(-1, 0, 0);

    private final int capacity;

    public BaselinePipeline(int capacity) {
        this.capacity = capacity;
    }

    /** Unpaced run, no latency recording. Used for correctness checks. */
    public RunResult run(ByteBuffer feed, Engine engine) throws InterruptedException {
        return run(feed, engine, Schedule.UNPACED, LatencyRecorder.NOOP);
    }

    /**
     * Replays {@code feed} (already in memory) open-loop at {@code rate} msg/s, or unpaced if rate
     * is {@link Schedule#UNPACED}.
     */
    public RunResult run(ByteBuffer feed, Engine engine, long rate, LatencyRecorder recorder)
            throws InterruptedException {
        // Small lead so both threads are running before the first message is due.
        Schedule schedule = new Schedule(System.nanoTime() + 1_000_000, rate);
        BlockingQueue<Message> queue = new ArrayBlockingQueue<>(capacity);
        ByteBuffer frames = feed.duplicate().order(feed.order());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long[] counts = new long[2];

        Thread consumer = new Thread(() -> {
            try {
                Pinning.pinEngine();
                long messages = 0;
                long triggers = 0;
                Message m;
                while ((m = queue.take()) != END) {
                    boolean triggered = engine.onMessage(m);
                    recorder.record(messages, System.nanoTime() - schedule.intendedNanos(messages), triggered);
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
                while (frames.hasRemaining()) {
                    schedule.awaitSend(i++);
                    queue.put(Codec.decode(frames));
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
