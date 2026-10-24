package com.georgeliao.engine.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistogramRecorderTest {

    @Test
    void dropsWarmupAndSplitsTriggered() {
        HistogramRecorder r = new HistogramRecorder(3);
        for (int i = 0; i < 10; i++) {
            r.record(i, (i + 1) * 1_000L, i % 2 == 0);
        }
        assertEquals(7, r.all().getTotalCount());
        assertEquals(3, r.triggered().getTotalCount()); // indices 4, 6, 8
        assertEquals(4_000, r.all().getMinValue(), 4);
    }

    @Test
    void clampsOutOfRangeValues() {
        HistogramRecorder r = new HistogramRecorder(0);
        r.record(0, -5, false);
        r.record(1, Long.MAX_VALUE, false);
        assertEquals(2, r.all().getTotalCount());
        assertEquals(0, r.all().getMinValue());
    }

    @Test
    void writesBothHgrmFiles(@TempDir Path dir) throws Exception {
        HistogramRecorder r = new HistogramRecorder(0);
        r.record(0, 2_500, true);
        Path out = dir.resolve("runs/baseline.hgrm");
        r.writeHgrm(out);
        assertTrue(Files.readString(out).contains("#[Mean"));
        assertTrue(Files.exists(dir.resolve("runs/baseline-triggered.hgrm")));
    }
}
