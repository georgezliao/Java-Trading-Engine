package com.georgeliao.engine.metrics;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Plain {@code key=value} file written next to each histogram, so every number can be traced
 * back to the JVM, flags, GC and machine that produced it.
 */
public final class RunMetadata {

    private final Map<String, String> values = new LinkedHashMap<>();

    public RunMetadata() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        put("timestamp", Instant.now());
        put("host", hostname());
        put("slurm.job", System.getenv().getOrDefault("SLURM_JOB_ID", ""));
        put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        put("cpus", Runtime.getRuntime().availableProcessors());
        put("java.version", System.getProperty("java.version"));
        put("java.vm", runtime.getVmName() + " " + runtime.getVmVersion() + " (" + runtime.getVmVendor() + ")");
        put("jvm.flags", String.join(" ", runtime.getInputArguments()));
        put("heap.max.mb", Runtime.getRuntime().maxMemory() >> 20);
        put("gc", gcBeans().stream().map(GarbageCollectorMXBean::getName).collect(Collectors.joining(", ")));
    }

    public RunMetadata put(String key, Object value) {
        values.put(key, String.valueOf(value));
        return this;
    }

    public Map<String, String> values() {
        return values;
    }

    /** Total GC collections and collection time (ms) so far, summed over all collectors. */
    public static long[] gcTotals() {
        long count = 0;
        long millis = 0;
        for (GarbageCollectorMXBean gc : gcBeans()) {
            count += Math.max(gc.getCollectionCount(), 0);
            millis += Math.max(gc.getCollectionTime(), 0);
        }
        return new long[] {count, millis};
    }

    public void write(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        values.forEach((k, v) -> sb.append(k).append('=').append(v.replace('\n', ' ')).append('\n'));
        Files.writeString(path, sb);
    }

    /** {@code runs/baseline.hgrm} → {@code runs/baseline.meta}. */
    public static Path pathFor(Path hgrm) {
        String name = hgrm.getFileName().toString();
        String stem = name.endsWith(".hgrm") ? name.substring(0, name.length() - 5) : name;
        return hgrm.resolveSibling(stem + ".meta");
    }

    private static List<GarbageCollectorMXBean> gcBeans() {
        return ManagementFactory.getGarbageCollectorMXBeans();
    }

    private static String hostname() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }
}
