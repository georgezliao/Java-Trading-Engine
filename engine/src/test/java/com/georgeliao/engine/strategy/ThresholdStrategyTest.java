package com.georgeliao.engine.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.georgeliao.engine.book.BookListener;
import com.georgeliao.engine.book.OrderBook;
import com.georgeliao.engine.feed.FeedGenerator;
import com.georgeliao.engine.wire.Codec;
import com.georgeliao.engine.wire.Message;
import com.georgeliao.engine.wire.Side;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class ThresholdStrategyTest {

    private final OrderBook book = new OrderBook(BookListener.NOOP);
    private final ThresholdStrategy strategy = new ThresholdStrategy(book, 3, 10);

    private boolean tick(long price) {
        return strategy.onTick(new Message.Tick(0, price, 1));
    }

    @Test
    void firstTickOnlySetsReference() {
        assertFalse(tick(100));
        assertEquals(0, book.restingOrders());
    }

    @Test
    void sellsAfterUpMoveAndBuysAfterDownMove() {
        tick(100);
        assertFalse(tick(102));
        assertTrue(tick(103));
        assertEquals(103, book.bestAsk());
        assertEquals(10, book.depth(Side.SELL, 103));
        // Reference reset to 103, so 101 isn't far enough but 100 is.
        assertFalse(tick(101));
        assertTrue(tick(100));
        assertEquals(100, book.bestBid());
        assertEquals(2, strategy.triggers());
    }

    @Test
    void strategyOrdersTradeWithFeedOrders() {
        book.add(1, Side.BUY, 105, 4);
        tick(100);
        assertTrue(tick(105));
        assertEquals(0, book.depth(Side.BUY, 105));
        assertEquals(6, book.depth(Side.SELL, 105));
    }

    @Test
    void triggersAreRareButPresentOnBurstFeed() {
        ByteBuffer feed = FeedGenerator.generate(FeedGenerator.Profile.BURST, 100_000, 1);
        long ticks = 0;
        while (feed.hasRemaining()) {
            if (Codec.decode(feed) instanceof Message.Tick t) {
                ticks++;
                tick(t.price());
            }
        }
        assertTrue(strategy.triggers() > 0 && strategy.triggers() < ticks / 2,
                strategy.triggers() + " triggers of " + ticks + " ticks");
    }
}
