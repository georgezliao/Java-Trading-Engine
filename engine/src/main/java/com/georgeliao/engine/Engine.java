package com.georgeliao.engine;

import com.georgeliao.engine.book.Book;
import com.georgeliao.engine.book.BookListener;
import com.georgeliao.engine.book.OrderBook;
import com.georgeliao.engine.strategy.ThresholdStrategy;
import com.georgeliao.engine.wire.Codec;
import com.georgeliao.engine.wire.FrameView;
import com.georgeliao.engine.wire.Message;

/** Single-threaded business logic: book + strategy. Every pipeline variant drives one of these. */
public final class Engine {

    public static final long DEFAULT_THRESHOLD = 3;
    public static final int DEFAULT_STRATEGY_QTY = 50;

    private final Book book;
    private final ThresholdStrategy strategy;

    /** Baseline engine on the TreeMap {@link OrderBook}. */
    public Engine(BookListener listener) {
        this(new OrderBook(listener));
    }

    public Engine(Book book) {
        this.book = book;
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

    /** Allocation-free path: reads fields straight off the frame. Same semantics as above. */
    public boolean onFrame(FrameView f) {
        switch (f.type()) {
            case Codec.NEW_ORDER -> {
                book.add(f.orderId(), f.side(), f.price(), f.qty());
                return false;
            }
            case Codec.CANCEL -> {
                book.cancel(f.orderId());
                return false;
            }
            case Codec.TICK -> {
                return strategy.onTick(f.price());
            }
            default -> throw new IllegalArgumentException("bad message type " + f.type());
        }
    }

    public Book book() {
        return book;
    }
}
