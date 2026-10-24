package com.georgeliao.engine.book;

/** Everything the book does, in order. The output hash is built from these calls. */
public interface BookListener {

    /** Order (or its unfilled remainder) is resting on the book. */
    void onRest(long orderId, long price, int qty);

    /** Aggressor traded with a resting order at the resting order's price. */
    void onFill(long aggressorId, long restingId, long price, int qty);

    void onCancel(long orderId, int remainingQty);

    /** Cancel for an id that isn't resting (already filled, or never existed). */
    void onCancelReject(long orderId);

    BookListener NOOP = new BookListener() {
        @Override public void onRest(long orderId, long price, int qty) {}
        @Override public void onFill(long aggressorId, long restingId, long price, int qty) {}
        @Override public void onCancel(long orderId, int remainingQty) {}
        @Override public void onCancelReject(long orderId) {}
    };
}
