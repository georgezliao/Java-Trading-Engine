package com.georgeliao.engine.book;

import com.georgeliao.engine.wire.Side;

/** Limit order book. Implementations must emit identical {@link BookListener} events. */
public interface Book {

    long NO_PRICE = Long.MIN_VALUE;

    void add(long id, Side side, long price, int qty);

    void cancel(long id);

    long bestBid();

    long bestAsk();

    /** Total resting quantity at a price on one side. */
    int depth(Side side, long price);

    int restingOrders();
}
