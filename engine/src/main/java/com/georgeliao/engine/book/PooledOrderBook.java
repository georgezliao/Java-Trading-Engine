package com.georgeliao.engine.book;

import com.georgeliao.engine.wire.Side;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Same matching semantics as {@link OrderBook}, without per-message allocation once warm:
 *
 * <ul>
 *   <li>orders and price levels come from free lists and go back on fill/cancel;
 *   <li>orders in a level form an intrusive doubly-linked list, so cancel is O(1);
 *   <li>id → order and price → level lookups use Agrona primitive-key maps (no boxing);
 *   <li>active prices per side sit in a sorted {@code long[]} with the best price at the end,
 *       so consuming the best level is O(1) and inserting a level is a binary search plus
 *       arraycopy.
 * </ul>
 *
 * Pools and arrays still grow (allocate) when they run out, which should only happen during
 * warmup.
 */
public final class PooledOrderBook implements Book {

    private static final class Node {
        long id;
        long price;
        int remaining;
        Side side;
        Level level;
        Node prev;
        Node next;
    }

    private static final class Level {
        long price;
        Node head;
        Node tail;
        Level nextFree;
    }

    /** One side of the book. {@code prices[0..count)} sorted so the best price is last. */
    private static final class Ladder {
        final boolean bids;
        final Long2ObjectHashMap<Level> levels;
        long[] prices = new long[256];
        int count;

        Ladder(boolean bids, int capacity) {
            this.bids = bids;
            this.levels = new Long2ObjectHashMap<>(capacity, 0.65f);
        }

        /** True if {@code a} is a better price than {@code b} on this side. */
        boolean better(long a, long b) {
            return bids ? a > b : a < b;
        }

        long best() {
            return count == 0 ? NO_PRICE : prices[count - 1];
        }

        /** Index where {@code price} is or would be inserted, keeping worse-to-better order. */
        int search(long price) {
            int lo = 0;
            int hi = count;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (better(price, prices[mid])) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        void insert(long price) {
            if (count == prices.length) {
                prices = java.util.Arrays.copyOf(prices, count * 2);
            }
            int i = search(price);
            System.arraycopy(prices, i, prices, i + 1, count - i);
            prices[i] = price;
            count++;
        }

        void remove(long price) {
            int i = search(price);
            System.arraycopy(prices, i + 1, prices, i, count - i - 1);
            count--;
        }
    }

    private final BookListener listener;
    private final Ladder bids;
    private final Ladder asks;
    private final Long2ObjectHashMap<Node> byId;
    private Node freeNodes;
    private Level freeLevels;
    private int resting;

    public PooledOrderBook(BookListener listener) {
        this(listener, 1 << 16);
    }

    public PooledOrderBook(BookListener listener, int expectedOrders) {
        this.listener = listener;
        this.bids = new Ladder(true, 1024);
        this.asks = new Ladder(false, 1024);
        this.byId = new Long2ObjectHashMap<>(expectedOrders * 2, 0.65f);
        for (int i = 0; i < expectedOrders; i++) {
            Node n = new Node();
            n.next = freeNodes;
            freeNodes = n;
        }
        for (int i = 0; i < 1024; i++) {
            Level l = new Level();
            l.nextFree = freeLevels;
            freeLevels = l;
        }
    }

    @Override
    public void add(long id, Side side, long price, int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive: " + qty);
        }
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("duplicate order id " + id);
        }
        Ladder opposite = side == Side.BUY ? asks : bids;
        int remaining = qty;
        while (remaining > 0 && opposite.count > 0) {
            long levelPrice = opposite.best();
            boolean crosses = side == Side.BUY ? levelPrice <= price : levelPrice >= price;
            if (!crosses) {
                break;
            }
            Level level = opposite.levels.get(levelPrice);
            while (remaining > 0 && level.head != null) {
                Node resting = level.head;
                int traded = Math.min(remaining, resting.remaining);
                remaining -= traded;
                resting.remaining -= traded;
                listener.onFill(id, resting.id, levelPrice, traded);
                if (resting.remaining == 0) {
                    unlink(resting);
                    byId.remove(resting.id);
                    release(resting);
                }
            }
            if (level.head == null) {
                // Best level is the last element, so this is just count--.
                opposite.count--;
                opposite.levels.remove(levelPrice);
                release(level);
            }
        }
        if (remaining > 0) {
            Ladder own = side == Side.BUY ? bids : asks;
            Level level = own.levels.get(price);
            if (level == null) {
                level = acquireLevel(price);
                own.levels.put(price, level);
                own.insert(price);
            }
            Node n = acquireNode();
            n.id = id;
            n.side = side;
            n.price = price;
            n.remaining = remaining;
            n.level = level;
            n.prev = level.tail;
            n.next = null;
            if (level.tail == null) {
                level.head = n;
            } else {
                level.tail.next = n;
            }
            level.tail = n;
            byId.put(id, n);
            this.resting++;
            listener.onRest(id, price, remaining);
        }
    }

    @Override
    public void cancel(long id) {
        Node n = byId.remove(id);
        if (n == null) {
            listener.onCancelReject(id);
            return;
        }
        Level level = n.level;
        unlink(n);
        if (level.head == null) {
            Ladder own = n.side == Side.BUY ? bids : asks;
            own.remove(level.price);
            own.levels.remove(level.price);
            release(level);
        }
        int remaining = n.remaining;
        release(n);
        listener.onCancel(id, remaining);
    }

    private void unlink(Node n) {
        Level level = n.level;
        if (n.prev == null) {
            level.head = n.next;
        } else {
            n.prev.next = n.next;
        }
        if (n.next == null) {
            level.tail = n.prev;
        } else {
            n.next.prev = n.prev;
        }
        resting--;
    }

    private Node acquireNode() {
        Node n = freeNodes;
        if (n == null) {
            return new Node();
        }
        freeNodes = n.next;
        return n;
    }

    private void release(Node n) {
        n.level = null;
        n.prev = null;
        n.side = null;
        n.next = freeNodes;
        freeNodes = n;
    }

    private Level acquireLevel(long price) {
        Level l = freeLevels;
        if (l == null) {
            l = new Level();
        } else {
            freeLevels = l.nextFree;
        }
        l.price = price;
        l.head = null;
        l.tail = null;
        l.nextFree = null;
        return l;
    }

    private void release(Level l) {
        l.head = null;
        l.tail = null;
        l.nextFree = freeLevels;
        freeLevels = l;
    }

    @Override
    public long bestBid() {
        return bids.best();
    }

    @Override
    public long bestAsk() {
        return asks.best();
    }

    @Override
    public int depth(Side side, long price) {
        Level level = (side == Side.BUY ? bids : asks).levels.get(price);
        int total = 0;
        for (Node n = level == null ? null : level.head; n != null; n = n.next) {
            total += n.remaining;
        }
        return total;
    }

    @Override
    public int restingOrders() {
        return resting;
    }
}
