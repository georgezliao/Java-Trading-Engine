package com.georgeliao.bench;

import com.georgeliao.engine.queue.SlotSignalRing;
import com.georgeliao.engine.queue.SpscIntRing;
import com.lmax.disruptor.EventPoller;
import com.lmax.disruptor.RingBuffer;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Cross-thread handoff cost of {@code ArrayBlockingQueue}, both in-house SPSC rings, Agrona's
 * {@code OneToOneRingBuffer} and the Disruptor. Each invocation sends
 * {@value #BURST} ints from the benchmark thread to a busy-spinning consumer thread and waits
 * until all of them have been consumed, so the score is ns per handoff including the core-to-core
 * cache traffic. Both sides spin; nobody parks except inside ArrayBlockingQueue's lock.
 *
 * <p>Run: {@code java -jar benchmarks/target/benchmarks.jar HandoffBench}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@State(Scope.Benchmark)
public class HandoffBench {

    static final int BURST = 1000;
    static final int CAPACITY = 1024;

    @Param({"abq", "spsc", "spsc-slot", "agrona", "disruptor"})
    public String queue;

    private Handoff handoff;
    private long sent;

    @Setup(Level.Trial)
    public void setup() {
        handoff = switch (queue) {
            case "abq" -> new AbqHandoff();
            case "spsc" -> new SpscHandoff();
            case "spsc-slot" -> new SlotHandoff();
            case "agrona" -> new AgronaHandoff();
            case "disruptor" -> new DisruptorHandoff();
            default -> throw new IllegalArgumentException(queue);
        };
        handoff.start();
    }

    @TearDown(Level.Trial)
    public void tearDown() throws InterruptedException {
        handoff.stop();
    }

    @Benchmark
    @OperationsPerInvocation(BURST)
    public void burst() {
        for (int i = 0; i < BURST; i++) {
            handoff.send(i);
        }
        sent += BURST;
        while (handoff.consumed() < sent) {
            Thread.onSpinWait();
        }
    }

    /** Producer side runs on the benchmark thread; consumer side on its own spinning thread. */
    abstract static class Handoff implements Runnable {
        final AtomicLong consumed = new AtomicLong();
        volatile boolean running = true;
        private Thread thread;
        long count;

        abstract void send(int v);

        /** Consume whatever is available; return how many. */
        abstract int poll();

        long consumed() {
            return consumed.get();
        }

        void start() {
            thread = new Thread(this, "consumer");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() throws InterruptedException {
            running = false;
            thread.join(1000);
        }

        @Override
        public void run() {
            while (running) {
                int n = poll();
                if (n == 0) {
                    Thread.onSpinWait();
                } else {
                    count += n;
                    consumed.lazySet(count);
                }
            }
        }
    }

    static final class AbqHandoff extends Handoff {
        private final ArrayBlockingQueue<Integer> q = new ArrayBlockingQueue<>(CAPACITY);
        private static final Integer ITEM = 42;
        long sink;

        @Override
        void send(int v) {
            while (!q.offer(ITEM)) {
                Thread.onSpinWait();
            }
        }

        @Override
        int poll() {
            Integer v = q.poll();
            if (v == null) {
                return 0;
            }
            sink += v;
            return 1;
        }
    }

    static final class SpscHandoff extends Handoff {
        private final SpscIntRing ring = new SpscIntRing(CAPACITY);
        long sink;
        private final SpscIntRing.IntHandler handler = v -> sink += v;

        @Override
        void send(int v) {
            while (!ring.offer(v)) {
                Thread.onSpinWait();
            }
        }

        @Override
        int poll() {
            return ring.drain(handler, 256);
        }
    }

    static final class SlotHandoff extends Handoff {
        private final SlotSignalRing ring = new SlotSignalRing(CAPACITY);
        long sink;
        private final SpscIntRing.IntHandler handler = v -> sink += v;

        @Override
        void send(int v) {
            while (!ring.offer(v)) {
                Thread.onSpinWait();
            }
        }

        @Override
        int poll() {
            return ring.drain(handler, 256);
        }
    }

    static final class AgronaHandoff extends Handoff {
        private final OneToOneRingBuffer ring = new OneToOneRingBuffer(new UnsafeBuffer(
                ByteBuffer.allocateDirect(CAPACITY * 16 + RingBufferDescriptor.TRAILER_LENGTH)));
        private final UnsafeBuffer msg = new UnsafeBuffer(ByteBuffer.allocateDirect(8));
        long sink;
        private final org.agrona.concurrent.MessageHandler handler =
                (type, buffer, index, length) -> sink += buffer.getInt(index);

        @Override
        void send(int v) {
            msg.putInt(0, v);
            while (!ring.write(1, msg, 0, 4)) {
                Thread.onSpinWait();
            }
        }

        @Override
        int poll() {
            return ring.read(handler, 256);
        }
    }

    static final class DisruptorHandoff extends Handoff {
        static final class IntEvent {
            int value;
        }

        private final RingBuffer<IntEvent> ring = RingBuffer.createSingleProducer(IntEvent::new, CAPACITY);
        private final EventPoller<IntEvent> poller = ring.newPoller();
        long sink;
        private int polled;
        private final EventPoller.Handler<IntEvent> handler = (event, sequence, endOfBatch) -> {
            sink += event.value;
            polled++;
            return true;
        };

        DisruptorHandoff() {
            ring.addGatingSequences(poller.getSequence());
        }

        @Override
        void send(int v) {
            long seq;
            while (true) {
                try {
                    seq = ring.tryNext();
                    break;
                } catch (com.lmax.disruptor.InsufficientCapacityException e) {
                    Thread.onSpinWait();
                }
            }
            ring.get(seq).value = v;
            ring.publish(seq);
        }

        @Override
        int poll() {
            polled = 0;
            try {
                poller.poll(handler);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return polled;
        }
    }
}
