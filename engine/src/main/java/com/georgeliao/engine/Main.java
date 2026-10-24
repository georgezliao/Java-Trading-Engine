package com.georgeliao.engine;

public final class Main {

    private Main() {}

    public static void main(String[] args) {
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
