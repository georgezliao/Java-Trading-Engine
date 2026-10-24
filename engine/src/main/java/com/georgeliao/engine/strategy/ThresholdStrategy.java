package com.georgeliao.engine.strategy;

import com.georgeliao.engine.book.OrderBook;
import com.georgeliao.engine.wire.Message;
import com.georgeliao.engine.wire.Side;

/**
 * Mean-reversion toy: when a tick moves at least {@code threshold} ticks away from the reference
 * price, send an order the other way at the tick price and reset the reference. Integer-only so
 * the output is deterministic across variants.
 */
public final class ThresholdStrategy {

    /** Strategy order ids live above this, so they never collide with feed ids. */
    public static final long ID_BASE = 1L << 62;

    private final OrderBook book;
    private final long threshold;
    private final int qty;
    private long reference = Long.MIN_VALUE;
    private long nextId = ID_BASE;
    private long triggers;

    public ThresholdStrategy(OrderBook book, long threshold, int qty) {
        if (threshold <= 0 || qty <= 0) {
            throw new IllegalArgumentException("threshold and qty must be positive");
        }
        this.book = book;
        this.threshold = threshold;
        this.qty = qty;
    }

    /** Returns true if this tick triggered an order. */
    public boolean onTick(Message.Tick tick) {
        long price = tick.price();
        if (reference == Long.MIN_VALUE) {
            reference = price;
            return false;
        }
        Side side;
        if (price >= reference + threshold) {
            side = Side.SELL;
        } else if (price <= reference - threshold) {
            side = Side.BUY;
        } else {
            return false;
        }
        reference = price;
        triggers++;
        book.add(nextId++, side, price, qty);
        return true;
    }

    public long triggers() {
        return triggers;
    }
}
