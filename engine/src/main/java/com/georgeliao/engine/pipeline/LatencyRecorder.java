package com.georgeliao.engine.pipeline;

/** Called on the engine thread after each message is processed. */
public interface LatencyRecorder {

    /**
     * @param index 0-based message index in the feed
     * @param latencyNanos completion time minus the message's intended send time
     * @param triggered whether this was a tick that triggered a strategy order
     */
    void record(long index, long latencyNanos, boolean triggered);

    /**
     * True if the pipeline should also time the engine's own processing of each message and call
     * {@link #recordWithService}. Costs an extra {@code System.nanoTime()} per message, so it's
     * off unless a recorder asks for it.
     */
    default boolean wantsServiceTime() {
        return false;
    }

    /** Like {@link #record}, plus how long the engine itself spent on this message. */
    default void recordWithService(long index, long latencyNanos, long serviceNanos, boolean triggered) {
        record(index, latencyNanos, triggered);
    }

    LatencyRecorder NOOP = (index, latencyNanos, triggered) -> {};
}
