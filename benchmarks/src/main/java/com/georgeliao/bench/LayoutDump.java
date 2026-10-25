package com.georgeliao.bench;

import com.georgeliao.engine.queue.SlotSignalRing;
import com.georgeliao.engine.queue.SpscIntRing;
import com.georgeliao.engine.wire.FrameView;
import com.georgeliao.engine.wire.Message;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.FieldLayout;

/**
 * Object layouts for the writeup: the per-message records the baseline allocates, the flyweight
 * that replaces them, and the two SPSC rings (checks that the padding actually separates the
 * producer and consumer fields).
 *
 * <p>Run: {@code java -cp benchmarks/target/benchmarks.jar com.georgeliao.bench.LayoutDump}
 *
 * <p>JDK 25, compressed oops, M4: NewOrder 48 B, Cancel 32 B, Tick 32 B (the feed mix gives the
 * 35.7 B/op DecodeBench measured); FrameView 24 B, once per pipeline. SpscIntRing: tail @144,
 * head @288. SlotSignalRing: tail @144, head @280. Both >= 128 B apart. The JVM packs the
 * read-only {@code mask} into the header gap at offset 12, which is harmless since nobody writes it.
 */
public final class LayoutDump {

    public static void main(String[] args) {
        // Unsafe refuses field offsets for record classes; this makes JOL find them another way.
        // Must be set before JOL initialises.
        System.setProperty("jol.magicFieldOffset", "true");
        Class<?>[] classes = {
            Message.NewOrder.class, Message.Cancel.class, Message.Tick.class,
            FrameView.class, SpscIntRing.class, SlotSignalRing.class,
        };
        for (Class<?> c : classes) {
            ClassLayout layout = ClassLayout.parseClass(c);
            System.out.println("=== " + c.getName() + ": " + layout.instanceSize() + " bytes");
            if (c == SpscIntRing.class || c == SlotSignalRing.class) {
                // Padding fields make the full dump long; print only the fields that matter.
                for (FieldLayout f : layout.fields()) {
                    if (!f.name().matches("[a-r]\\d\\d")) {
                        System.out.printf("  %4d  %-10s %s%n", f.offset(), f.name(), f.typeClass());
                    }
                }
            } else {
                System.out.print(layout.toPrintable());
            }
            System.out.println();
        }
    }
}
