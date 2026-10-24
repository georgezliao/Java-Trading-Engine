package com.georgeliao.engine;

import com.georgeliao.engine.feed.FeedGenerator;
import java.io.IOException;
import java.util.Arrays;

public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException {
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
        // The pipeline lands in Phase 1; for now just echo the parsed run.
        System.out.println(config);
    }
}
