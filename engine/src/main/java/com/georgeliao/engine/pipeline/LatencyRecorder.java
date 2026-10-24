package com.georgeliao.engine.pipeline;

/** Called on the engine thread after each message is processed. */
public interface LatencyRecorder {

    /**
     * @param index 0-based message index in the feed
     * @param latencyNanos completion time minus the message's intended send time
     * @param triggered whether this was a tick that triggered a strategy order
     */
    void record(long index, long latencyNanos, boolean triggered);

    LatencyRecorder NOOP = (index, latencyNanos, triggered) -> {};
}
