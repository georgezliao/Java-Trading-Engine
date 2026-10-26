package com.georgeliao.engine.metrics;

import com.georgeliao.engine.pipeline.LatencyRecorder;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Wraps a {@link HistogramRecorder} and keeps the individual messages that were slow end to end
 * or expensive to process, so tail events can be traced back to specific feed positions. Asks
 * the pipeline for per-message service time. Storage is preallocated; once full, further
 * outliers are counted but not stored.
 */
public final class OutlierRecorder implements LatencyRecorder {

    private final HistogramRecorder histograms;
    private final long warmup;
    private final long latencyThreshold;
    private final long serviceThreshold;
    private final long[] index;
    private final long[] latency;
    private final long[] service;
    private final boolean[] triggered;
    private int stored;
    private long dropped;

    public OutlierRecorder(HistogramRecorder histograms, long warmup, long latencyThresholdNanos,
                           long serviceThresholdNanos, int capacity) {
        this.histograms = histograms;
        this.warmup = warmup;
        this.latencyThreshold = latencyThresholdNanos;
        this.serviceThreshold = serviceThresholdNanos;
        this.index = new long[capacity];
        this.latency = new long[capacity];
        this.service = new long[capacity];
        this.triggered = new boolean[capacity];
    }

    @Override
    public boolean wantsServiceTime() {
        return true;
    }

    @Override
    public void record(long i, long latencyNanos, boolean wasTrigger) {
        histograms.record(i, latencyNanos, wasTrigger);
    }

    @Override
    public void recordWithService(long i, long latencyNanos, long serviceNanos, boolean wasTrigger) {
        histograms.record(i, latencyNanos, wasTrigger);
        if (i < warmup || (latencyNanos < latencyThreshold && serviceNanos < serviceThreshold)) {
            return;
        }
        if (stored == index.length) {
            dropped++;
            return;
        }
        index[stored] = i;
        latency[stored] = latencyNanos;
        service[stored] = serviceNanos;
        triggered[stored] = wasTrigger;
        stored++;
    }

    public HistogramRecorder histograms() {
        return histograms;
    }

    public int stored() {
        return stored;
    }

    public long dropped() {
        return dropped;
    }

    /** CSV: index (0-based feed position), latency_ns, service_ns, triggered. */
    public void writeCsv(Path out) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("index,latency_ns,service_ns,triggered");
            for (int k = 0; k < stored; k++) {
                w.println(index[k] + "," + latency[k] + "," + service[k] + "," + (triggered[k] ? 1 : 0));
            }
        }
    }
}
