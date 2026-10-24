package com.georgeliao.engine.wire;

/** Decoded message. The baseline allocates one of these per message on purpose. */
public sealed interface Message {

    long seq();

    record NewOrder(long seq, long orderId, Side side, long price, int qty) implements Message {}

    record Cancel(long seq, long orderId) implements Message {}

    /** Market data: last trade at {@code price} for {@code qty}. */
    record Tick(long seq, long price, int qty) implements Message {}
}
