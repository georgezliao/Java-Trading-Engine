package com.georgeliao.engine.book;

import com.georgeliao.engine.wire.Side;

/** A resting order. Mutable: {@code remaining} shrinks as it fills. */
public final class Order {

    final long id;
    final Side side;
    final long price;
    int remaining;

    Order(long id, Side side, long price, int qty) {
        this.id = id;
        this.side = side;
        this.price = price;
        this.remaining = qty;
    }

    public long id() {
        return id;
    }

    public int remaining() {
        return remaining;
    }
}
