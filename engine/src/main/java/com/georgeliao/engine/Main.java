package com.georgeliao.engine;

import com.georgeliao.engine.book.BookListener;
import com.georgeliao.engine.feed.FeedGenerator;
import com.georgeliao.engine.pipeline.BaselinePipeline;
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
        ByteBuffer feed = ByteBuffer.wrap(Files.readAllBytes(config.feed())).order(ByteOrder.LITTLE_ENDIAN);
        Engine engine = new Engine(BookListener.NOOP);
        RunResult result = switch (config.variant()) {
            case "baseline" -> new BaselinePipeline(BaselinePipeline.DEFAULT_CAPACITY).run(feed, engine);
            default -> {
                System.err.println("unknown variant " + config.variant());
                System.exit(2);
                yield null;
            }
        };
        System.out.printf("%s: %d messages, %d triggers, %.1f ms, %.0f msg/s%n", config.variant(),
                result.messages(), result.triggers(), result.elapsedNanos() / 1e6, result.messagesPerSecond());
    }
}
