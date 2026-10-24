package com.georgeliao.engine;

import com.georgeliao.engine.book.HashingListener;
import com.georgeliao.engine.feed.FeedGenerator;
import com.georgeliao.engine.pipeline.BaselinePipeline;
import com.georgeliao.engine.metrics.HistogramRecorder;
import com.georgeliao.engine.pipeline.RunResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;

public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException, InterruptedException {
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
        HashingListener output = new HashingListener();
        Engine engine = new Engine(output);
        HistogramRecorder latency = new HistogramRecorder(config.warmup());
        RunResult result = switch (config.variant()) {
            case "baseline" -> new BaselinePipeline(BaselinePipeline.DEFAULT_CAPACITY)
                    .run(feed, engine, config.rate(), latency);
            default -> {
                System.err.println("unknown variant " + config.variant());
                System.exit(2);
                yield null;
            }
        };
        System.out.printf("%s: %d messages, %d triggers, %.1f ms, %.0f msg/s, output %s (%d events)%n",
                config.variant(), result.messages(), result.triggers(), result.elapsedNanos() / 1e6,
                result.messagesPerSecond(), output.hex(), output.events());
        System.out.println(HistogramRecorder.summary("all", latency.all()));
        System.out.println(HistogramRecorder.summary("triggered", latency.triggered()));
        if (config.out() != null) {
            latency.writeHgrm(config.out());
            System.out.println("wrote " + config.out() + " and " + HistogramRecorder.triggeredPath(config.out()));
        }
    }
}
