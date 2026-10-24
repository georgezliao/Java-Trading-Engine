package com.georgeliao.engine.pipeline;

public record RunResult(long messages, long triggers, long elapsedNanos) {

    public double messagesPerSecond() {
        return messages * 1e9 / elapsedNanos;
    }
}
