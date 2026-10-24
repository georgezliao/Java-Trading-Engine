package com.georgeliao.engine.book;

class OrderBookTest extends BookContractTest {

    @Override
    Book create(BookListener listener) {
        return new OrderBook(listener);
    }
}
