package com.georgeliao.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConfigTest {

    @Test
    void parsesAllFlags() {
        Config c = Config.parse(new String[] {
            "--variant", "spsc", "--feed", "feeds/burst.bin", "--rate", "500000", "--out", "out.hgrm"});
        assertEquals("spsc", c.variant());
        assertEquals(Path.of("feeds/burst.bin"), c.feed());
        assertEquals(500_000, c.rate());
        assertEquals(Path.of("out.hgrm"), c.out());
    }

    @Test
    void defaultsVariantAndRate() {
        Config c = Config.parse(new String[] {"--feed", "f.bin"});
        assertEquals("baseline", c.variant());
        assertEquals(100_000, c.rate());
        assertNull(c.out());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> Config.parse(new String[] {}));
        assertThrows(IllegalArgumentException.class, () -> Config.parse(new String[] {"--feed"}));
        assertThrows(IllegalArgumentException.class, () -> Config.parse(new String[] {"--bogus", "x"}));
        assertThrows(IllegalArgumentException.class,
                () -> Config.parse(new String[] {"--feed", "f.bin", "--rate", "0"}));
    }
}
