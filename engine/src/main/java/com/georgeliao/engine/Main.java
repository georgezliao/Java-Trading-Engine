package com.georgeliao.engine;

import com.georgeliao.engine.book.HashingListener;
import com.georgeliao.engine.book.LadderBook;
import com.georgeliao.engine.book.PooledOrderBook;
import com.georgeliao.engine.feed.FeedGenerator;
import com.georgeliao.engine.pipeline.AllocFreePipeline;
import com.georgeliao.engine.pipeline.BaselinePipeline;
import com.georgeliao.engine.pipeline.LatencyRecorder;
import com.georgeliao.engine.pipeline.Pinning;
import com.georgeliao.engine.metrics.Annotate;
import com.georgeliao.engine.metrics.HistogramRecorder;
import com.georgeliao.engine.metrics.OutlierRecorder;
import com.georgeliao.engine.metrics.RunMetadata;
import com.georgeliao.engine.pipeline.RunResult;
import com.georgeliao.engine.pipeline.SpscPipeline;
import java.lang.management.ManagementFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length > 0 && args[0].equals("annotate")) {
            Annotate.run(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && args[0].equals("gen")) {
            try {
                FeedGenerator.run(Arrays.copyOfRange(args, 1, args.length));
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                System.exit(2);
            }
            return;
        }
        Config config;
        try {
            config = Config.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(Config.USAGE);
            System.exit(2);
            return;
        }
        // Whole feed in memory before timing starts; no I/O on the hot path.
        ByteBuffer feed = ByteBuffer.wrap(Files.readAllBytes(config.feed())).order(ByteOrder.LITTLE_ENDIAN);
        Pinning.configure(config.readerCpu(), config.engineCpu());
        Variant variant = variant(config, feed);
        if (variant == null) {
            System.err.println("unknown variant " + config.variant());
            System.exit(2);
            return;
        }

        // Warm the JIT with full unmeasured replays (fresh engine, same code paths and types,
        // including the recorder's warmup cutoff), so most compilation and deopts happen here and
        // not inside the measured run. The measured run still starts from an empty book, which
        // trips a few deopts in its first ~25 ms; a small --warmup cutoff drops those.
        for (int pass = 1; pass <= config.warmupPasses(); pass++) {
            RunResult warm = variant.run(new HashingListener(), recorder(config, new HistogramRecorder(config.warmup())));
            System.out.printf("warmup pass %d: %.1f ms%n", pass, warm.elapsedNanos() / 1e6);
        }

        HashingListener output = new HashingListener();
        HistogramRecorder latency = new HistogramRecorder(config.warmup());
        LatencyRecorder measured = recorder(config, latency);
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long[] gcBefore = RunMetadata.gcTotals();
        long cpuBefore = os.getProcessCpuTime();
        RunResult result = variant.run(output, measured);
        long cpuNanos = os.getProcessCpuTime() - cpuBefore;
        long[] gcAfter = RunMetadata.gcTotals();
        System.out.printf("%s: %d messages, %d triggers, %.1f ms, %.0f msg/s, %.2f cores, output %s (%d events)%n",
                config.variant(), result.messages(), result.triggers(), result.elapsedNanos() / 1e6,
                result.messagesPerSecond(), (double) cpuNanos / result.elapsedNanos(), output.hex(), output.events());
        System.out.println(HistogramRecorder.summary("all", latency.all()));
        System.out.println(HistogramRecorder.summary("triggered", latency.triggered()));
        if (measured instanceof OutlierRecorder o) {
            o.writeCsv(config.outliers());
            System.out.printf("wrote %d outliers to %s (%d more not stored)%n", o.stored(), config.outliers(), o.dropped());
        }
        if (config.out() != null) {
            latency.writeHgrm(config.out());
            Path meta = RunMetadata.pathFor(config.out());
            new RunMetadata()
                    .put("variant", config.variant())
                    .put("feed", config.feed())
                    .put("rate", config.rate())
                    .put("warmup", config.warmup())
                    .put("warmup.passes", config.warmupPasses())
                    .put("pinning", Pinning.describe())
                    .put("wait", config.waitStrategy())
                    .put("cpu.ms", cpuNanos / 1e6)
                    .put("messages", result.messages())
                    .put("triggers", result.triggers())
                    .put("elapsed.ms", result.elapsedNanos() / 1e6)
                    .put("achieved.rate", Math.round(result.messagesPerSecond()))
                    .put("output.hash", output.hex())
                    .put("output.events", output.events())
                    .put("gc.collections", gcAfter[0] - gcBefore[0])
                    .put("gc.time.ms", gcAfter[1] - gcBefore[1])
                    .write(meta);
            System.out.println("wrote " + config.out() + ", " + HistogramRecorder.triggeredPath(config.out()) + ", " + meta);
        }
    }

    /**
     * Same recorder type for warmup and measured runs, so the JIT doesn't see a new type at the
     * start of the measured run.
     */
    private static LatencyRecorder recorder(Config config, HistogramRecorder histograms) {
        if (config.outliers() == null) {
            return histograms;
        }
        return new OutlierRecorder(histograms, config.warmup(), config.outlierLatencyMicros() * 1_000,
                config.outlierServiceMicros() * 1_000, 500_000);
    }

    /** One measured or warmup replay with a fresh engine. */
    private interface Variant {
        RunResult run(HashingListener output, LatencyRecorder latency) throws InterruptedException;
    }

    private static Variant variant(Config config, ByteBuffer feed) {
        int capacity = BaselinePipeline.DEFAULT_CAPACITY;
        long rate = config.rate();
        return switch (config.variant()) {
            case "baseline" -> (out, lat) -> new BaselinePipeline(capacity).run(feed, new Engine(out), rate, lat);
            case "alloc-free" -> (out, lat) ->
                    new AllocFreePipeline(capacity).run(feed, new Engine(new PooledOrderBook(out)), rate, lat);
            case "ladder-book" -> (out, lat) ->
                    new AllocFreePipeline(capacity).run(feed, new Engine(new LadderBook(out)), rate, lat);
            case "spsc" -> (out, lat) ->
                    new SpscPipeline(capacity, config.waitStrategy()).run(feed, new Engine(new LadderBook(out)), rate, lat);
            default -> null;
        };
    }
}
