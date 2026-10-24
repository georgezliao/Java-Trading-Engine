package com.georgeliao.engine;

import com.georgeliao.engine.book.BookListener;
import com.georgeliao.engine.book.OrderBook;
import com.georgeliao.engine.strategy.ThresholdStrategy;
import com.georgeliao.engine.wire.Message;

/** Single-threaded business logic: book + strategy. Every pipeline variant drives one of these. */
public final class Engine {

    public static final long DEFAULT_THRESHOLD = 3;
    public static final int DEFAULT_STRATEGY_QTY = 50;

    private final OrderBook book;
    private final ThresholdStrategy strategy;

    public Engine(BookListener listener) {
        this.book = new OrderBook(listener);
        this.strategy = new ThresholdStrategy(book, DEFAULT_THRESHOLD, DEFAULT_STRATEGY_QTY);
    }

    /** Returns true if the message was a tick that triggered a strategy order. */
    public boolean onMessage(Message m) {
        return switch (m) {
            case Message.NewOrder o -> {
                book.add(o.orderId(), o.side(), o.price(), o.qty());
                yield false;
            }
            case Message.Cancel c -> {
                book.cancel(c.orderId());
                yield false;
            }
            case Message.Tick t -> strategy.onTick(t);
        };
    }

    public OrderBook book() {
        return book;
    }
}
