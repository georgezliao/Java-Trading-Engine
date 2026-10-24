package com.georgeliao.engine;

import java.nio.file.Path;

/** Command-line options for a single run. */
public record Config(String variant, Path feed, long rate, Path out) {

    public static final String USAGE =
            "usage: engine --variant <name> --feed <file> [--rate <msgs/s>] [--out <file.hgrm>]";

    public static Config parse(String[] args) {
        String variant = "baseline";
        Path feed = null;
        long rate = 100_000;
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
        return new Config(variant, feed, rate, out);
    }
}
