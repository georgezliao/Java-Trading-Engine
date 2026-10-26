package com.georgeliao.engine.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.georgeliao.engine.Engine;
import com.georgeliao.engine.book.HashingListener;
import com.georgeliao.engine.book.LadderBook;
import com.georgeliao.engine.feed.FeedGenerator;
import com.georgeliao.engine.pipeline.SpscPipeline;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OutlierRecorderTest {

    @Test
    void keepsSlowOrExpensiveMessagesAfterWarmup(@TempDir Path dir) throws Exception {
        OutlierRecorder r = new OutlierRecorder(new HistogramRecorder(2), 2, 1_000, 500, 2);
        r.recordWithService(0, 5_000, 5_000, false); // warmup, dropped
        r.recordWithService(2, 100, 100, false);     // fast
        r.recordWithService(3, 2_000, 50, true);     // slow end to end
        r.recordWithService(4, 600, 600, false);     // expensive to process
        r.recordWithService(5, 9_000, 10, false);    // over capacity
        assertEquals(2, r.stored());
        assertEquals(1, r.dropped());
        assertEquals(4, r.histograms().all().getTotalCount());
        Path csv = dir.resolve("o.csv");
        r.writeCsv(csv);
        assertEquals(List.of("index,latency_ns,service_ns,triggered", "3,2000,50,1", "4,600,600,0"), Files.readAllLines(csv));
    }

    @Test
    void pipelineReportsServiceTime() throws Exception {
        OutlierRecorder r = new OutlierRecorder(new HistogramRecorder(0), 0, 0, 0, 10_000);
        new SpscPipeline(64).run(FeedGenerator.generate(FeedGenerator.Profile.STEADY, 5_000, 3),
                new Engine(new LadderBook(new HashingListener())), 1_000_000, r);
        // Thresholds of 0 keep everything up to capacity.
        assertEquals(5_000, r.stored());
        assertTrue(r.histograms().all().getTotalCount() == 5_000);
    }
}
