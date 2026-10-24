package com.georgeliao.engine.metrics;

import com.georgeliao.engine.pipeline.LatencyRecorder;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;

/**
 * Two HdrHistograms: every message, and only ticks that triggered a strategy order. The first
 * {@code warmup} messages are dropped so JIT compilation doesn't land in the numbers. Histograms
 * are preallocated, so recording doesn't allocate.
 */
public final class HistogramRecorder implements LatencyRecorder {

    static final long MAX_TRACKABLE_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final long warmup;
    private final Histogram all = new Histogram(MAX_TRACKABLE_NANOS, 3);
    private final Histogram triggered = new Histogram(MAX_TRACKABLE_NANOS, 3);

    public HistogramRecorder(long warmup) {
        this.warmup = warmup;
    }

    @Override
    public void record(long index, long latencyNanos, boolean wasTrigger) {
        if (index < warmup) {
            return;
        }
        long v = Math.min(Math.max(latencyNanos, 0), MAX_TRACKABLE_NANOS);
        all.recordValue(v);
        if (wasTrigger) {
            triggered.recordValue(v);
        }
    }

    public Histogram all() {
        return all;
    }

    public Histogram triggered() {
        return triggered;
    }

    /** Writes {@code out} (all messages) and {@code out-triggered.hgrm}, values in microseconds. */
    public void writeHgrm(Path out) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        write(all, out);
        write(triggered, triggeredPath(out));
    }

    public static Path triggeredPath(Path out) {
        String name = out.getFileName().toString();
        String stem = name.endsWith(".hgrm") ? name.substring(0, name.length() - 5) : name;
        return out.resolveSibling(stem + "-triggered.hgrm");
    }

    private static void write(Histogram h, Path path) throws IOException {
        try (PrintStream ps = new PrintStream(Files.newOutputStream(path))) {
            h.outputPercentileDistribution(ps, 1000.0);
        }
    }

    public static String summary(String label, Histogram h) {
        return String.format("%-9s n=%d p50=%.1fus p99=%.1fus p99.9=%.1fus p99.99=%.1fus max=%.1fus",
                label, h.getTotalCount(), h.getValueAtPercentile(50) / 1e3, h.getValueAtPercentile(99) / 1e3,
                h.getValueAtPercentile(99.9) / 1e3, h.getValueAtPercentile(99.99) / 1e3, h.getMaxValue() / 1e3);
    }
}
