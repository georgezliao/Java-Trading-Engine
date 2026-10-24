package com.georgeliao.bench;

import com.georgeliao.engine.feed.FeedGenerator;
import com.georgeliao.engine.wire.Codec;
import com.georgeliao.engine.wire.Message;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Does decoding to a {@link Message} record allocate? Two cases:
 *
 * <ul>
 *   <li>{@code decodeEscaping}: the record goes to a Blackhole, like it goes onto the queue in the
 *       baseline pipeline. It must be heap-allocated.
 *   <li>{@code decodeNonEscaping}: only a field sum leaves the method, so escape analysis is free
 *       to scalar-replace the record.
 * </ul>
 *
 * Run: {@code java -jar benchmarks/target/benchmarks.jar DecodeBench -prof gc} and compare
 * {@code gc.alloc.rate.norm} (bytes per op).
 *
 * <p>First run (M4 MacBook Air, JDK 25.0.2, G1, 1 fork, 3x1 s): both cases allocate 35.7 B/op,
 * about 12-13 ns/op. Escape analysis did not remove the record even when it doesn't escape. My
 * guess is that {@code decode} returns one of three record types through a single switch, so the
 * allocations merge into one value and C2 gives up on scalar replacement. Not verified with
 * {@code -XX:+PrintEscapeAnalysis} yet. 35.7 B matches the feed mix: 32 B Tick/Cancel, 48 B
 * NewOrder.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class DecodeBench {

    private static final int FRAMES = 4096;

    private ByteBuffer feed;

    @Setup
    public void setup() {
        feed = FeedGenerator.generate(FeedGenerator.Profile.BURST, FRAMES, 1);
    }

    private ByteBuffer next() {
        if (!feed.hasRemaining()) {
            feed.rewind();
        }
        return feed;
    }

    @Benchmark
    public void decodeEscaping(Blackhole bh) {
        bh.consume(Codec.decode(next()));
    }

    @Benchmark
    public long decodeNonEscaping() {
        return switch (Codec.decode(next())) {
            case Message.NewOrder o -> o.orderId() + o.price() + o.qty();
            case Message.Cancel c -> c.orderId();
            case Message.Tick t -> t.price() + t.qty();
        };
    }
}
