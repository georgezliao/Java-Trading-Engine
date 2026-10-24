package com.georgeliao.engine.book;

/**
 * Folds every book event into a 64-bit hash. Allocation-free, so variants can run it on the hot
 * path. Two runs agree on the hash only if they produced the same events in the same order.
 */
public final class HashingListener implements BookListener {

    private static final long REST = 1, FILL = 2, CANCEL = 3, CANCEL_REJECT = 4;

    private long hash = 0xcbf29ce484222325L;
    private long events;

    private void mix(long v) {
        hash ^= v;
        hash *= 0x100000001b3L;
        hash ^= hash >>> 29;
    }

    @Override
    public void onRest(long orderId, long price, int qty) {
        events++;
        mix(REST);
        mix(orderId);
        mix(price);
        mix(qty);
    }

    @Override
    public void onFill(long aggressorId, long restingId, long price, int qty) {
        events++;
        mix(FILL);
        mix(aggressorId);
        mix(restingId);
        mix(price);
        mix(qty);
    }

    @Override
    public void onCancel(long orderId, int remainingQty) {
        events++;
        mix(CANCEL);
        mix(orderId);
        mix(remainingQty);
    }

    @Override
    public void onCancelReject(long orderId) {
        events++;
        mix(CANCEL_REJECT);
        mix(orderId);
    }

    public long hash() {
        return hash;
    }

    public long events() {
        return events;
    }

    public String hex() {
        return String.format("%016x", hash);
    }
}
