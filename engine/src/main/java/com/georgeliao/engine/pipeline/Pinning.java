package com.georgeliao.engine.pipeline;

import net.openhft.affinity.Affinity;

/**
 * Pins the reader and engine threads to fixed CPUs with OpenHFT affinity
 * ({@code sched_setaffinity} on Linux). Configured once per process from {@code --cpus}.
 *
 * <p>macOS has no thread-to-core binding API, so there this is a no-op (reported once). That's
 * one reason headline numbers come from Linux.
 */
public final class Pinning {

    public static final int NONE = -1;

    private static volatile int readerCpu = NONE;
    private static volatile int engineCpu = NONE;
    private static volatile int readerActual = NONE;
    private static volatile int engineActual = NONE;
    private static volatile boolean warned;

    private Pinning() {}

    public static void configure(int reader, int engine) {
        readerCpu = reader;
        engineCpu = engine;
    }

    public static boolean supported() {
        return !System.getProperty("os.name").toLowerCase().contains("mac");
    }

    /** Call first thing on the reader thread. */
    public static void pinReader() {
        readerActual = pin(readerCpu);
    }

    /** Call first thing on the engine thread. */
    public static void pinEngine() {
        engineActual = pin(engineCpu);
    }

    private static int pin(int cpu) {
        if (cpu == NONE) {
            return NONE;
        }
        if (!supported()) {
            if (!warned) {
                warned = true;
                System.err.println("thread pinning not supported on " + System.getProperty("os.name") + "; ignoring --cpus");
            }
            return NONE;
        }
        Affinity.setAffinity(cpu);
        return Affinity.getCpu();
    }

    /** Requested and observed CPUs, for the run metadata. */
    public static String describe() {
        return "reader=" + readerCpu + "(on " + readerActual + ") engine=" + engineCpu + "(on " + engineActual + ")";
    }
}
