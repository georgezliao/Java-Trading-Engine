package com.georgeliao.engine.metrics;

import com.georgeliao.engine.Engine;
import com.georgeliao.engine.book.Book;
import com.georgeliao.engine.book.BookListener;
import com.georgeliao.engine.book.LadderBook;
import com.georgeliao.engine.book.OrderBook;
import com.georgeliao.engine.wire.Codec;
import com.georgeliao.engine.wire.FrameView;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

/**
 * {@code engine annotate --feed f.bin --outliers o.csv --book treemap|ladder --out a.csv}
 *
 * <p>The feed is deterministic, so the book state at any position can be rebuilt offline. This
 * replays the feed single-threaded and, for every index in the outlier CSV and the message just
 * before it, writes what the engine did with that message: type, book events it caused, resting
 * orders after it, best bid/ask before and after, and (treemap only) whether the baseline
 * book's {@code HashMap<Long, Order>} id index resized on that message.
 */
public final class Annotate {

    private Annotate() {}

    /** Counts listener callbacks for the current message. */
    private static final class Counter implements BookListener {
        int events;
        int fills;
        @Override public void onRest(long orderId, long price, int qty) { events++; }
        @Override public void onFill(long aggressorId, long restingId, long price, int qty) { events++; fills++; }
        @Override public void onCancel(long orderId, int remainingQty) { events++; }
        @Override public void onCancelReject(long orderId) { events++; }
    }

    public static void run(String[] args) throws IOException {
        Path feedPath = null;
        Path outliers = null;
        Path out = null;
        String bookType = "ladder";
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--feed" -> feedPath = Path.of(args[i + 1]);
                case "--outliers" -> outliers = Path.of(args[i + 1]);
                case "--book" -> bookType = args[i + 1];
                case "--out" -> out = Path.of(args[i + 1]);
                default -> throw new IllegalArgumentException("unknown flag " + args[i]);
            }
        }
        if (feedPath == null || outliers == null || out == null) {
            throw new IllegalArgumentException(
                    "usage: engine annotate --feed <f.bin> --outliers <o.csv> --book treemap|ladder --out <a.csv>");
        }

        TreeSet<Long> wanted = new TreeSet<>();
        List<String> lines = Files.readAllLines(outliers);
        for (String line : lines.subList(1, lines.size())) {
            long idx = Long.parseLong(line.substring(0, line.indexOf(',')));
            wanted.add(idx);
            if (idx > 0) {
                wanted.add(idx - 1);
            }
        }

        ByteBuffer feed = ByteBuffer.wrap(Files.readAllBytes(feedPath)).order(ByteOrder.LITTLE_ENDIAN);
        Counter counter = new Counter();
        Book book = switch (bookType) {
            case "treemap" -> new OrderBook(counter);
            case "ladder" -> new LadderBook(counter);
            default -> throw new IllegalArgumentException("--book treemap|ladder");
        };
        Engine engine = new Engine(book);
        FrameView view = new FrameView();

        // java.util.HashMap: default capacity 16, resize when size exceeds 0.75 * capacity.
        long hashCapacity = 16;
        long maxResting = 0;

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("index,type,triggered,events,fills,resting,bid_before,ask_before,bid_after,ask_after,hashmap_resize_to");
            int frames = feed.limit() / Codec.FRAME_SIZE;
            for (int i = 0; i < frames; i++) {
                view.wrap(feed, i * Codec.FRAME_SIZE);
                long bidBefore = book.bestBid();
                long askBefore = book.bestAsk();
                counter.events = 0;
                counter.fills = 0;
                byte type = view.type();
                boolean triggered = engine.onFrame(view);
                int resting = book.restingOrders();
                long resizedTo = 0;
                if (resting > maxResting) {
                    maxResting = resting;
                    if (resting > hashCapacity * 3 / 4) {
                        hashCapacity *= 2;
                        resizedTo = hashCapacity;
                        if (bookType.equals("treemap")) {
                            System.out.printf("HashMap resize to %d buckets at index %d (%d entries)%n", hashCapacity, i, resting);
                        }
                    }
                }
                if (wanted.contains((long) i)) {
                    w.println(i + "," + typeName(type) + "," + (triggered ? 1 : 0) + "," + counter.events + ","
                            + counter.fills + "," + resting + "," + price(bidBefore) + "," + price(askBefore) + ","
                            + price(book.bestBid()) + "," + price(book.bestAsk()) + "," + resizedTo);
                }
            }
        }
        System.out.printf("annotated %d positions from %s into %s%n", wanted.size(), outliers, out);
    }

    private static String typeName(byte type) {
        return switch (type) {
            case Codec.NEW_ORDER -> "new";
            case Codec.CANCEL -> "cancel";
            case Codec.TICK -> "tick";
            default -> "?" + type;
        };
    }

    private static String price(long p) {
        return p == Book.NO_PRICE ? "" : Long.toString(p);
    }
}
