package com.georgeliao.engine.book;

import com.georgeliao.engine.wire.Side;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Baseline limit order book: price-time priority, boxed keys, one deque per level, cancels by
 * linear scan of the level. Deliberately naive; the ladder book in Phase 4 replaces it.
 */
public final class OrderBook {

    public static final long NO_PRICE = Long.MIN_VALUE;

    private final NavigableMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();
    private final Map<Long, Order> byId = new HashMap<>();
    private final BookListener listener;

    public OrderBook(BookListener listener) {
        this.listener = listener;
    }

    public void add(long id, Side side, long price, int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be positive: " + qty);
        }
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException("duplicate order id " + id);
        }
        NavigableMap<Long, ArrayDeque<Order>> opposite = side == Side.BUY ? asks : bids;
        int remaining = qty;
        while (remaining > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, ArrayDeque<Order>> best = opposite.firstEntry();
            long levelPrice = best.getKey();
            boolean crosses = side == Side.BUY ? levelPrice <= price : levelPrice >= price;
            if (!crosses) {
                break;
            }
            ArrayDeque<Order> level = best.getValue();
            while (remaining > 0 && !level.isEmpty()) {
                Order resting = level.peekFirst();
                int traded = Math.min(remaining, resting.remaining);
                remaining -= traded;
                resting.remaining -= traded;
                listener.onFill(id, resting.id, levelPrice, traded);
                if (resting.remaining == 0) {
                    level.pollFirst();
                    byId.remove(resting.id);
                }
            }
            if (level.isEmpty()) {
                opposite.remove(levelPrice);
            }
        }
        if (remaining > 0) {
            Order order = new Order(id, side, price, remaining);
            (side == Side.BUY ? bids : asks).computeIfAbsent(price, p -> new ArrayDeque<>()).addLast(order);
            byId.put(id, order);
            listener.onRest(id, price, remaining);
        }
    }

    public void cancel(long id) {
        Order order = byId.remove(id);
        if (order == null) {
            listener.onCancelReject(id);
            return;
        }
        NavigableMap<Long, ArrayDeque<Order>> book = order.side == Side.BUY ? bids : asks;
        ArrayDeque<Order> level = book.get(order.price);
        level.remove(order);
        if (level.isEmpty()) {
            book.remove(order.price);
        }
        listener.onCancel(id, order.remaining);
    }

    public long bestBid() {
        return bids.isEmpty() ? NO_PRICE : bids.firstKey();
    }

    public long bestAsk() {
        return asks.isEmpty() ? NO_PRICE : asks.firstKey();
    }

    /** Total resting quantity at a price on one side. */
    public int depth(Side side, long price) {
        ArrayDeque<Order> level = (side == Side.BUY ? bids : asks).get(price);
        if (level == null) {
            return 0;
        }
        int total = 0;
        for (Order o : level) {
            total += o.remaining;
        }
        return total;
    }

    public int restingOrders() {
        return byId.size();
    }
}
