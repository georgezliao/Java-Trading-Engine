package com.georgeliao.engine.book;

import static com.georgeliao.engine.wire.Side.BUY;
import static com.georgeliao.engine.wire.Side.SELL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderBookTest {

    private final List<String> events = new ArrayList<>();
    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook(new BookListener() {
            @Override public void onRest(long id, long price, int qty) { events.add("rest " + id + " " + qty + "@" + price); }
            @Override public void onFill(long a, long r, long price, int qty) { events.add("fill " + a + "x" + r + " " + qty + "@" + price); }
            @Override public void onCancel(long id, int qty) { events.add("cancel " + id + " " + qty); }
            @Override public void onCancelReject(long id) { events.add("reject " + id); }
        });
    }

    @Test
    void nonCrossingOrdersRest() {
        book.add(1, BUY, 99, 10);
        book.add(2, SELL, 101, 5);
        assertEquals(99, book.bestBid());
        assertEquals(101, book.bestAsk());
        assertEquals(List.of("rest 1 10@99", "rest 2 5@101"), events);
    }

    @Test
    void timePriorityWithinLevel() {
        book.add(1, SELL, 100, 5);
        book.add(2, SELL, 100, 5);
        book.add(3, BUY, 100, 7);
        assertEquals(List.of("rest 1 5@100", "rest 2 5@100", "fill 3x1 5@100", "fill 3x2 2@100"), events.subList(0, 4));
        assertEquals(3, book.depth(SELL, 100));
        assertEquals(1, book.restingOrders());
    }

    @Test
    void pricePriorityAndFillAtRestingPrice() {
        book.add(1, SELL, 102, 5);
        book.add(2, SELL, 101, 5);
        book.add(3, BUY, 105, 8);
        assertEquals(List.of("fill 3x2 5@101", "fill 3x1 3@102"), events.subList(2, 4));
        assertEquals(102, book.bestAsk());
        assertEquals(2, book.depth(SELL, 102));
    }

    @Test
    void partialFillRemainderRests() {
        book.add(1, BUY, 100, 4);
        book.add(2, SELL, 99, 10);
        assertEquals(List.of("rest 1 4@100", "fill 2x1 4@100", "rest 2 6@99"), events);
        assertEquals(OrderBook.NO_PRICE, book.bestBid());
        assertEquals(99, book.bestAsk());
    }

    @Test
    void cancelRemovesRestingAndRejectsUnknown() {
        book.add(1, BUY, 100, 10);
        book.add(2, BUY, 100, 3);
        book.add(3, SELL, 100, 4);
        book.cancel(1);
        book.cancel(1);
        book.cancel(42);
        assertEquals(List.of("cancel 1 6", "reject 1", "reject 42"), events.subList(3, 6));
        assertEquals(3, book.depth(BUY, 100));
        book.cancel(2);
        assertEquals(OrderBook.NO_PRICE, book.bestBid());
        assertEquals(0, book.restingOrders());
    }

    @Test
    void rejectsBadOrders() {
        book.add(1, BUY, 100, 1);
        assertThrows(IllegalArgumentException.class, () -> book.add(1, BUY, 100, 1));
        assertThrows(IllegalArgumentException.class, () -> book.add(2, BUY, 100, 0));
    }
}
