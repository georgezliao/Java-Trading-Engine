package com.georgeliao.engine.book;

import com.georgeliao.engine.wire.Side;
import java.util.Arrays;
import org.agrona.collections.Long2LongHashMap;

/**
 * Book for the {@code ladder-book} and {@code spsc} variants. Same semantics as {@link OrderBook}, laid out as primitive arrays:
 *
 * <ul>
 *   <li>orders live in parallel arrays indexed by an int slot ({@code ids}, {@code prices},
 *       {@code remaining}, {@code prev}/{@code next} links), recycled through a free list;
 *   <li>price levels are directly indexed by {@code price - base}: each side has int head/tail
 *       arrays, so finding a level is an array index instead of a tree or hash lookup;
 *   <li>best bid/ask are tracked explicitly; when the best level empties we scan toward worse
 *       prices to the next non-empty level (cheap while prices cluster near the market);
 *   <li>id → slot is an Agrona {@link Long2LongHashMap}.
 * </ul>
 *
 * The price window re-centres and grows if a price falls outside it (allocates, rare).
 */
public final class LadderBook implements Book {

    private static final int NONE = -1;

    private final BookListener listener;

    // Orders, struct-of-arrays.
    private long[] ids;
    private long[] prices;
    private int[] remaining;
    private boolean[] isBuy;
    private int[] prev;
    private int[] next;
    private int freeSlot = NONE;
    private int nextUnused;
    private final Long2LongHashMap slotById;
    private int resting;

    // Price ladder. Index = price - base.
    private long base;
    private int[] bidHead;
    private int[] bidTail;
    private int[] askHead;
    private int[] askTail;
    private long bestBid = NO_PRICE;
    private long bestAsk = NO_PRICE;
    private int bidLevels;
    private int askLevels;
    private boolean based;

    public LadderBook(BookListener listener) {
        this(listener, 1 << 16, 1 << 14);
    }

    public LadderBook(BookListener listener, int expectedOrders, int ladderSize) {
        this.listener = listener;
        ids = new long[expectedOrders];
        prices = new long[expectedOrders];
        remaining = new int[expectedOrders];
        isBuy = new boolean[expectedOrders];
        prev = new int[expectedOrders];
        next = new int[expectedOrders];
        slotById = new Long2LongHashMap(expectedOrders * 2, 0.65f, NONE);
        bidHead = filled(ladderSize);
        bidTail = filled(ladderSize);
        askHead = filled(ladderSize);
        askTail = filled(ladderSize);
    }

    private static int[] filled(int n) {
        int[] a = new int[n];
        Arrays.fill(a, NONE);
        return a;
    }

    @Override
    public void add(long id, Side side, long price, int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive: " + qty);
        }
        if (slotById.containsKey(id)) {
            throw new IllegalArgumentException("duplicate order id " + id);
        }
        boolean buy = side == Side.BUY;
        int left = qty;
        if (buy) {
            while (left > 0 && askLevels > 0 && bestAsk <= price) {
                left = matchLevel(id, askHead, askTail, bestAsk, left);
                if (askHead[idx(bestAsk)] == NONE) {
                    askLevels--;
                    bestAsk = askLevels == 0 ? NO_PRICE : scan(askHead, bestAsk, 1);
                }
            }
        } else {
            while (left > 0 && bidLevels > 0 && bestBid >= price) {
                left = matchLevel(id, bidHead, bidTail, bestBid, left);
                if (bidHead[idx(bestBid)] == NONE) {
                    bidLevels--;
                    bestBid = bidLevels == 0 ? NO_PRICE : scan(bidHead, bestBid, -1);
                }
            }
        }
        if (left > 0) {
            rest(id, buy, price, left);
        }
    }

    /** Fills against the FIFO at {@code levelPrice}; returns the aggressor's remaining qty. */
    private int matchLevel(long aggressorId, int[] head, int[] tail, long levelPrice, int left) {
        int i = idx(levelPrice);
        while (left > 0 && head[i] != NONE) {
            int s = head[i];
            int traded = Math.min(left, remaining[s]);
            left -= traded;
            remaining[s] -= traded;
            listener.onFill(aggressorId, ids[s], levelPrice, traded);
            if (remaining[s] == 0) {
                unlink(s, head, tail, i);
                slotById.remove(ids[s]);
                release(s);
            }
        }
        return left;
    }

    private void rest(long id, boolean buy, long price, int qty) {
        ensureInWindow(price);
        int s = acquire();
        ids[s] = id;
        prices[s] = price;
        remaining[s] = qty;
        isBuy[s] = buy;
        int i = idx(price);
        int[] head = buy ? bidHead : askHead;
        int[] tail = buy ? bidTail : askTail;
        prev[s] = tail[i];
        next[s] = NONE;
        if (tail[i] == NONE) {
            head[i] = s;
            if (buy) {
                bidLevels++;
                if (bestBid == NO_PRICE || price > bestBid) {
                    bestBid = price;
                }
            } else {
                askLevels++;
                if (bestAsk == NO_PRICE || price < bestAsk) {
                    bestAsk = price;
                }
            }
        } else {
            next[tail[i]] = s;
        }
        tail[i] = s;
        slotById.put(id, s);
        resting++;
        listener.onRest(id, price, qty);
    }

    @Override
    public void cancel(long id) {
        int s = (int) slotById.remove(id);
        if (s == NONE) {
            listener.onCancelReject(id);
            return;
        }
        long price = prices[s];
        int i = idx(price);
        if (isBuy[s]) {
            unlink(s, bidHead, bidTail, i);
            if (bidHead[i] == NONE) {
                bidLevels--;
                if (price == bestBid) {
                    bestBid = bidLevels == 0 ? NO_PRICE : scan(bidHead, bestBid, -1);
                }
            }
        } else {
            unlink(s, askHead, askTail, i);
            if (askHead[i] == NONE) {
                askLevels--;
                if (price == bestAsk) {
                    bestAsk = askLevels == 0 ? NO_PRICE : scan(askHead, bestAsk, 1);
                }
            }
        }
        int left = remaining[s];
        release(s);
        listener.onCancel(id, left);
    }

    /** Next non-empty level from {@code from} (exclusive) in direction {@code step}. */
    private long scan(int[] head, long from, int step) {
        int i = idx(from) + step;
        while (head[i] == NONE) {
            i += step;
        }
        return base + i;
    }

    private void unlink(int s, int[] head, int[] tail, int i) {
        if (prev[s] == NONE) {
            head[i] = next[s];
        } else {
            next[prev[s]] = next[s];
        }
        if (next[s] == NONE) {
            tail[i] = prev[s];
        } else {
            prev[next[s]] = prev[s];
        }
        resting--;
    }

    private int idx(long price) {
        return (int) (price - base);
    }

    private void ensureInWindow(long price) {
        int size = bidHead.length;
        if (!based) {
            base = price - size / 2;
            based = true;
            return;
        }
        if (price >= base && price < base + size) {
            return;
        }
        long lo = Math.min(base, price);
        long hi = Math.max(base + size - 1, price);
        long span = hi - lo + 1;
        int newSize = size;
        while (newSize < span * 2) {
            newSize *= 2;
        }
        long newBase = lo - (newSize - span) / 2;
        int shift = (int) (base - newBase);
        bidHead = shifted(bidHead, newSize, shift);
        bidTail = shifted(bidTail, newSize, shift);
        askHead = shifted(askHead, newSize, shift);
        askTail = shifted(askTail, newSize, shift);
        base = newBase;
    }

    private static int[] shifted(int[] old, int newSize, int shift) {
        int[] a = filled(newSize);
        System.arraycopy(old, 0, a, shift, old.length);
        return a;
    }

    private int acquire() {
        if (freeSlot != NONE) {
            int s = freeSlot;
            freeSlot = next[s];
            return s;
        }
        if (nextUnused == ids.length) {
            int n = ids.length * 2;
            ids = Arrays.copyOf(ids, n);
            prices = Arrays.copyOf(prices, n);
            remaining = Arrays.copyOf(remaining, n);
            isBuy = Arrays.copyOf(isBuy, n);
            prev = Arrays.copyOf(prev, n);
            next = Arrays.copyOf(next, n);
        }
        return nextUnused++;
    }

    private void release(int s) {
        next[s] = freeSlot;
        freeSlot = s;
    }

    @Override
    public long bestBid() {
        return bestBid;
    }

    @Override
    public long bestAsk() {
        return bestAsk;
    }

    @Override
    public int depth(Side side, long price) {
        if (!based || price < base || price >= base + bidHead.length) {
            return 0;
        }
        int total = 0;
        for (int s = (side == Side.BUY ? bidHead : askHead)[idx(price)]; s != NONE; s = next[s]) {
            total += remaining[s];
        }
        return total;
    }

    @Override
    public int restingOrders() {
        return resting;
    }
}
