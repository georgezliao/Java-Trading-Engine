package com.georgeliao.engine;

import com.georgeliao.engine.pipeline.WaitStrategy;
import java.nio.file.Path;

/** Command-line options for a single run. */
public record Config(String variant, Path feed, long rate, long warmup, int warmupPasses, int readerCpu, int engineCpu,
        WaitStrategy waitStrategy, Path outliers, long outlierLatencyMicros, long outlierServiceMicros, Path out) {

    public static final String USAGE =
            "usage: engine --variant <name> --feed <file> [--rate <msgs/s>] [--warmup <msgs>] [--warmup-passes <n>] [--cpus <reader>,<engine>]"
            + " [--wait spin|yield|park] [--outliers <file.csv>] [--outlier-latency-us <n>] [--outlier-service-us <n>]"
            + " [--out <file.hgrm>]";

    public static Config parse(String[] args) {
        String variant = "baseline";
        Path feed = null;
        long rate = 100_000;
        long warmup = 0;
        int warmupPasses = 2;
        int readerCpu = -1;
        int engineCpu = -1;
        WaitStrategy wait = WaitStrategy.SPIN;
        Path outliers = null;
        long outlierLatencyMicros = 20;
        long outlierServiceMicros = 1;
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + flag);
            }
            String value = args[++i];
            switch (flag) {
                case "--variant" -> variant = value;
                case "--feed" -> feed = Path.of(value);
                case "--rate" -> rate = Long.parseLong(value);
                case "--warmup" -> warmup = Long.parseLong(value);
                case "--warmup-passes" -> warmupPasses = Integer.parseInt(value);
                case "--cpus" -> {
                    String[] cpus = value.split(",");
                    if (cpus.length != 2) {
                        throw new IllegalArgumentException("--cpus takes <reader>,<engine>");
                    }
                    readerCpu = Integer.parseInt(cpus[0].trim());
                    engineCpu = Integer.parseInt(cpus[1].trim());
                }
                case "--wait" -> wait = WaitStrategy.parse(value);
                case "--outliers" -> outliers = Path.of(value);
                case "--outlier-latency-us" -> outlierLatencyMicros = Long.parseLong(value);
                case "--outlier-service-us" -> outlierServiceMicros = Long.parseLong(value);
                case "--out" -> out = Path.of(value);
                default -> throw new IllegalArgumentException("unknown flag " + flag);
            }
        }
        if (feed == null) {
            throw new IllegalArgumentException("--feed is required");
        }
        if (rate <= 0) {
            throw new IllegalArgumentException("--rate must be positive");
        }
        if (warmup < 0 || warmupPasses < 0) {
            throw new IllegalArgumentException("--warmup and --warmup-passes must be >= 0");
        }
        return new Config(variant, feed, rate, warmup, warmupPasses, readerCpu, engineCpu, wait, outliers,
                outlierLatencyMicros, outlierServiceMicros, out);
    }
}
