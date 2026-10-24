package com.georgeliao.bench;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Single-threaded offer+poll cost. Placeholder until the SPSC comparison in Phase 4. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Thread)
public class QueueHandoffBench {

    private final ArrayBlockingQueue<Long> queue = new ArrayBlockingQueue<>(1024);
    private final Long item = 42L;

    @Benchmark
    public Long arrayBlockingQueueOfferPoll() {
        queue.offer(item);
        return queue.poll();
    }
}
