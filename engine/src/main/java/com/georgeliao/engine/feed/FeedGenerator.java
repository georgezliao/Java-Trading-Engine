package com.georgeliao.engine.feed;

import com.georgeliao.engine.wire.Codec;
import com.georgeliao.engine.wire.Message;
import com.georgeliao.engine.wire.Side;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Writes a synthetic feed of encoded frames. Same profile, count and seed give identical bytes.
 *
 * <p>The file has no timestamps; replay timing comes from the replayer's {@code --rate}. The
 * {@code burst} profile changes content instead: occasional volatile stretches where the price
 * moves in bigger steps and more orders and cancels arrive, so more ticks cross strategy
 * thresholds and the book does more work per message.
 */
public final class FeedGenerator {

    public enum Profile { STEADY, BURST }

    static final String USAGE =
            "usage: engine gen --profile steady|burst --count <n> --seed <s> --out <file.bin>";

    private static final long START_PRICE = 10_000;

    private FeedGenerator() {}

    public static ByteBuffer generate(Profile profile, int count, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        ByteBuffer buf = ByteBuffer.allocate(Math.multiplyExact(count, Codec.FRAME_SIZE)).order(ByteOrder.LITTLE_ENDIAN);
        List<Long> live = new ArrayList<>();
        long price = START_PRICE;
        long nextOrderId = 1;
        int burstLeft = 0;

        for (long seq = 1; seq <= count; seq++) {
            if (profile == Profile.BURST && burstLeft == 0 && rng.nextInt(1000) == 0) {
                burstLeft = rng.nextInt(200, 1000);
            }
            boolean inBurst = burstLeft > 0;
            if (inBurst) {
                burstLeft--;
            }
            int maxStep = inBurst ? 5 : 1;
            double tickShare = inBurst ? 0.5 : 0.7;
            double orderShare = inBurst ? 0.3 : 0.2;

            double r = rng.nextDouble();
            Message m;
            if (r < tickShare) {
                price = Math.max(1, price + rng.nextInt(-maxStep, maxStep + 1));
                m = new Message.Tick(seq, price, rng.nextInt(1, 101));
            } else if (r < tickShare + orderShare || live.isEmpty()) {
                Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
                // Mostly passive, sometimes crossing by a couple of ticks.
                long offset = rng.nextInt(-2, 11);
                long orderPrice = Math.max(1, side == Side.BUY ? price - offset : price + offset);
                long id = nextOrderId++;
                live.add(id);
                m = new Message.NewOrder(seq, id, side, orderPrice, rng.nextInt(1, 501));
            } else {
                int i = rng.nextInt(live.size());
                long id = live.get(i);
                live.set(i, live.get(live.size() - 1));
                live.remove(live.size() - 1);
                m = new Message.Cancel(seq, id);
            }
            Codec.encode(m, buf);
        }
        return buf.flip();
    }

    public static void write(ByteBuffer feed, Path out) throws IOException {
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileChannel ch = FileChannel.open(out, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer view = feed.duplicate();
            while (view.hasRemaining()) {
                ch.write(view);
            }
        }
    }

    /** Entry point for {@code engine gen ...}. */
    public static void run(String[] args) throws IOException {
        Profile profile = Profile.STEADY;
        int count = 1_000_000;
        long seed = 42;
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + flag);
            }
            String value = args[++i];
            switch (flag) {
                case "--profile" -> profile = Profile.valueOf(value.toUpperCase());
                case "--count" -> count = Integer.parseInt(value);
                case "--seed" -> seed = Long.parseLong(value);
                case "--out" -> out = Path.of(value);
                default -> throw new IllegalArgumentException("unknown flag " + flag);
            }
        }
        if (out == null) {
            throw new IllegalArgumentException("--out is required");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("--count must be positive");
        }
        write(generate(profile, count, seed), out);
        System.out.printf("wrote %d messages (%s, seed %d) to %s%n", count, profile, seed, out);
    }
}
