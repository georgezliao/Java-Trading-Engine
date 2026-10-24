package com.georgeliao.engine.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunMetadataTest {

    @Test
    void capturesEnvironmentAndRunFields(@TempDir Path dir) throws Exception {
        Path hgrm = dir.resolve("baseline.hgrm");
        Path meta = RunMetadata.pathFor(hgrm);
        assertEquals(dir.resolve("baseline.meta"), meta);

        new RunMetadata().put("variant", "baseline").put("rate", 1_000_000).write(meta);
        List<String> lines = Files.readAllLines(meta);
        for (String key : List.of("host", "cpus", "java.version", "java.vm", "jvm.flags", "gc", "variant", "rate")) {
            assertTrue(lines.stream().anyMatch(l -> l.startsWith(key + "=")), "missing " + key);
        }
        assertTrue(lines.contains("variant=baseline"));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("java.version=" + Runtime.version().feature())));
        assertFalse(lines.stream().anyMatch(l -> l.startsWith("gc=") && l.length() == 3), "no GC names");
    }

    @Test
    void gcTotalsAreNonNegative() {
        long[] t = RunMetadata.gcTotals();
        assertTrue(t[0] >= 0 && t[1] >= 0);
    }
}
