package io.github.jamiecrock.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderBookTest {
    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook("TEST");
    }

    private long sell(long price, long qty) {
        return book.submit(OrderRequest.limit(Side.SELL, price, qty)).orderId();
    }

    private long buy(long price, long qty) {
        return book.submit(OrderRequest.limit(Side.BUY, price, qty)).orderId();
    }

    // ------------------------------------------------------------- resting & basic matching

    @Test
    @DisplayName("an order with nothing to match rests on the book")
    void restsWhenNoLiquidity() {
        ExecutionReport r = book.submit(OrderRequest.limit(Side.BUY, 100, 10));
        assertEquals(OrderStatus.NEW, r.status());
        assertEquals(0, r.filled());
        assertEquals(10, r.remaining());
        assertTrue(r.trades().isEmpty());
        assertEquals(OptionalLong.of(100), book.bestBid());
        assertEquals(OptionalLong.empty(), book.bestAsk());
        assertTrue(book.isResting(r.orderId()));
    }

    @Test
    @DisplayName("non-crossing orders both rest and define the spread")
    void nonCrossingOrdersRest() {
        buy(99, 5);
        sell(101, 5);
        assertEquals(OptionalLong.of(2), book.spread());
        assertEquals(2, book.restingOrderCount());
        assertFalse(book.isCrossed());
    }

    @Test
    @DisplayName("aggressive order trades at the resting price (price improvement)")
    void tradesAtRestingPrice() {
        long ask = sell(101, 10);
        ExecutionReport r = book.submit(OrderRequest.limit(Side.BUY, 105, 10));
        assertEquals(OrderStatus.FILLED, r.status());
        assertEquals(1, r.trades().size());
        Trade t = r.trades().get(0);
        assertEquals(101, t.price());
        assertEquals(10, t.quantity());
        assertEquals(r.orderId(), t.buyOrderId());
        assertEquals(ask, t.sellOrderId());
        assertEquals(Side.BUY, t.aggressor());
        assertEquals(0, book.restingOrderCount());
    }

    @Test
    @DisplayName("partial fill leaves the remainder resting at the limit price")
    void partialFillThenRests() {
        sell(100, 30);
        ExecutionReport r = book.submit(OrderRequest.limit(Side.BUY, 100, 50));
        assertEquals(OrderStatus.PARTIALLY_FILLED, r.status());
        assertEquals(30, r.filled());
        assertEquals(20, r.remaining());
        assertEquals(OptionalLong.of(100), book.bestBid());
        assertEquals(20, book.quantityAt(Side.BUY, 100));
        assertEquals(OptionalLong.empty(), book.bestAsk());
    }

    @Test
    @DisplayName("a resting order that is partly hit keeps the rest of its size")
    void restingOrderPartiallyFilled() {
        long ask = sell(100, 50);
        book.submit(OrderRequest.limit(Side.BUY, 100, 20));
        assertTrue(book.isResting(ask));
        assertEquals(30, book.quantityAt(Side.SELL, 100));
    }

    // ------------------------------------------------------------- priority

    @Test
    @DisplayName("price priority beats time priority, then FIFO within a level")
    void priceTimePriority() {
        long first101 = sell(101, 50);
        long second101 = sell(101, 50);
        long best100 = sell(100, 50);

        ExecutionReport r = book.submit(OrderRequest.limit(Side.BUY, 101, 120));
        List<Trade> trades = r.trades();
        assertEquals(3, trades.size());
        assertEquals(best100, trades.get(0).sellOrderId());
        assertEquals(100, trades.get(0).price());
        assertEquals(first101, trades.get(1).sellOrderId());
        assertEquals(50, trades.get(1).quantity());
        assertEquals(second101, trades.get(2).sellOrderId());
        assertEquals(20, trades.get(2).quantity());
        assertEquals(List.of(second101), book.queueAt(Side.SELL, 101));
        assertEquals(30, book.quantityAt(Side.SELL, 101));
    }

    @Test
    @DisplayName("sell side matches the highest bid first")
    void sellMatchesHighestBid() {
        buy(98, 10);
        long best = buy(99, 10);
        ExecutionReport r = book.submit(OrderRequest.limit(Side.SELL, 98, 10));
        assertEquals(best, r.trades().get(0).buyOrderId());
        assertEquals(99, r.trades().get(0).price());
        assertEquals(Side.SELL, r.trades().get(0).aggressor());
    }

    @Test
    @DisplayName("trade sequence numbers increase across calls")
    void tradeSequenceIsMonotonic() {
        sell(100, 10);
        sell(100, 10);
        long a = book.submit(OrderRequest.limit(Side.BUY, 100, 5)).trades().get(0).sequence();
        List<Trade> next = book.submit(OrderRequest.limit(Side.BUY, 100, 10)).trades();
        assertEquals(a + 1, next.get(0).sequence());
        assertEquals(a + 2, next.get(1).sequence());
    }

    // ------------------------------------------------------------- market orders & time in force

    @Test
    @DisplayName("market order sweeps levels and drops any unfilled remainder")
    void marketOrderSweepsAndCancelsRemainder() {
        sell(100, 10);
        sell(101, 10);
        sell(102, 10);
        ExecutionReport r = book.submit(OrderRequest.market(Side.BUY, 40));
        assertEquals(OrderStatus.CANCELLED, r.status());
        assertEquals(30, r.filled());
        assertEquals(0, r.remaining());
        assertEquals(3, r.trades().size());
        assertEquals(101.0, r.averagePrice(), 1e-9);
        assertEquals(0, book.restingOrderCount());
    }

    @Test
    @DisplayName("market order on an empty book is cancelled, never rested")
    void marketOrderOnEmptyBook() {
        ExecutionReport r = book.submit(OrderRequest.market(Side.SELL, 10));
        assertEquals(OrderStatus.CANCELLED, r.status());
        assertEquals(0, book.restingOrderCount());
    }

    @Test
    @DisplayName("IOC fills what it can and cancels the rest")
    void iocCancelsRemainder() {
        sell(100, 10);
        ExecutionReport r = book.submit(OrderRequest.limit(Side.BUY, 100, 25, TimeInForce.IOC));
        assertEquals(OrderStatus.CANCELLED, r.status());
        assertEquals(10, r.filled());
        assertEquals(OptionalLong.empty(), book.bestBid());
    }

    @Test
    @DisplayName("IOC that fully fills reports FILLED")
    void iocFullFill() {
        sell(100, 10);
        assertEquals(OrderStatus.FILLED, book.submit(OrderRequest.limit(Side.BUY, 100, 10, TimeInForce.IOC)).status());
    }

    @Test
    @DisplayName("FOK without enough liquidity is rejected and leaves the book untouched")
    void fokRejectedWhenInsufficient() {
        sell(100, 10);
        sell(101, 10);
        ExecutionReport r = book.submit(OrderRequest.limit(Side.BUY, 101, 25, TimeInForce.FOK));
        assertEquals(OrderStatus.REJECTED, r.status());
        assertTrue(r.trades().isEmpty());
        assertEquals(10, book.quantityAt(Side.SELL, 100));
        assertEquals(10, book.quantityAt(Side.SELL, 101));
    }

    @Test
    @DisplayName("FOK only counts liquidity inside its limit price")
    void fokRespectsLimit() {
        sell(100, 10);
        sell(105, 100);
        assertEquals(OrderStatus.REJECTED, book.submit(OrderRequest.limit(Side.BUY, 101, 20, TimeInForce.FOK)).status());
        assertEquals(OrderStatus.FILLED, book.submit(OrderRequest.limit(Side.BUY, 105, 20, TimeInForce.FOK)).status());
    }

    @Test
    @DisplayName("FOK market order fills across levels when liquidity is sufficient")
    void fokMarketFills() {
        buy(100, 10);
        buy(99, 10);
        ExecutionReport r = book.submit(OrderRequest.market(Side.SELL, 20, TimeInForce.FOK));
        assertEquals(OrderStatus.FILLED, r.status());
        assertEquals(2, r.trades().size());
    }

    // ------------------------------------------------------------- cancel, reduce, replace

    @Test
    @DisplayName("cancel removes the order and empties the level")
    void cancelRemovesOrder() {
        long id = buy(100, 10);
        ExecutionReport r = book.cancel(id);
        assertEquals(OrderStatus.CANCELLED, r.status());
        assertFalse(book.isResting(id));
        assertEquals(OptionalLong.empty(), book.bestBid());
        assertTrue(book.depth(Side.BUY, 5).isEmpty());
    }

    @Test
    @DisplayName("cancelling the middle of a queue keeps the others in order")
    void cancelMiddleOfQueue() {
        long a = buy(100, 1);
        long b = buy(100, 2);
        long c = buy(100, 3);
        book.cancel(b);
        assertEquals(List.of(a, c), book.queueAt(Side.BUY, 100));
        assertEquals(4, book.quantityAt(Side.BUY, 100));
    }

    @Test
    @DisplayName("cancelling unknown or filled orders is rejected")
    void cancelUnknownOrFilled() {
        assertEquals(OrderStatus.REJECTED, book.cancel(42).status());
        long ask = sell(100, 5);
        buy(100, 5);
        assertEquals(OrderStatus.REJECTED, book.cancel(ask).status());
    }

    @Test
    @DisplayName("reducing size keeps time priority")
    void reduceKeepsPriority() {
        long first = sell(100, 50);
        long second = sell(100, 50);
        ExecutionReport r = book.reduce(first, 10);
        assertEquals(OrderStatus.NEW, r.status());
        assertEquals(10, r.remaining());
        assertEquals(List.of(first, second), book.queueAt(Side.SELL, 100));
        assertEquals(60, book.quantityAt(Side.SELL, 100));

        Trade t = book.submit(OrderRequest.limit(Side.BUY, 100, 10)).trades().get(0);
        assertEquals(first, t.sellOrderId());
    }

    @Test
    @DisplayName("reduce after a partial fill reports PARTIALLY_FILLED")
    void reduceAfterPartialFill() {
        long ask = sell(100, 50);
        buy(100, 20);
        assertEquals(OrderStatus.PARTIALLY_FILLED, book.reduce(ask, 10).status());
    }

    @Test
    @DisplayName("reduce rejects increases, zero and unknown orders")
    void reduceValidation() {
        long id = sell(100, 50);
        assertEquals(OrderStatus.REJECTED, book.reduce(id, 50).status());
        assertEquals(OrderStatus.REJECTED, book.reduce(id, 80).status());
        assertEquals(OrderStatus.REJECTED, book.reduce(id, 0).status());
        assertEquals(OrderStatus.REJECTED, book.reduce(999, 1).status());
        assertEquals(50, book.quantityAt(Side.SELL, 100));
    }

    @Test
    @DisplayName("replace loses time priority and gets a new id")
    void replaceLosesPriority() {
        long first = sell(100, 50);
        long second = sell(100, 50);
        ExecutionReport r = book.replace(first, 100, 60);
        assertTrue(r.orderId() > second);
        assertFalse(book.isResting(first));
        assertEquals(List.of(second, r.orderId()), book.queueAt(Side.SELL, 100));
    }

    @Test
    @DisplayName("replace to a crossing price trades immediately")
    void replaceCanTrade() {
        buy(100, 10);
        long ask = sell(105, 10);
        ExecutionReport r = book.replace(ask, 100, 10);
        assertEquals(OrderStatus.FILLED, r.status());
        assertEquals(100, r.trades().get(0).price());
    }

    @Test
    @DisplayName("replace rejects bad input without touching the original order")
    void replaceValidation() {
        long id = buy(100, 10);
        assertEquals(OrderStatus.REJECTED, book.replace(id, 0, 10).status());
        assertEquals(OrderStatus.REJECTED, book.replace(777, 100, 10).status());
        assertTrue(book.isResting(id));
    }

    // ------------------------------------------------------------- validation & views

    @Test
    @DisplayName("invalid orders are rejected with a reason and still consume an id")
    void validation() {
        ExecutionReport zeroQty = book.submit(OrderRequest.limit(Side.BUY, 100, 0));
        ExecutionReport badPrice = book.submit(OrderRequest.limit(Side.BUY, -1, 10));
        ExecutionReport pricedMarket = book.submit(new OrderRequest(Side.BUY, OrderType.MARKET, 100, 10, TimeInForce.IOC));
        for (ExecutionReport r : List.of(zeroQty, badPrice, pricedMarket)) {
            assertEquals(OrderStatus.REJECTED, r.status());
            assertTrue(r.rejectReason() != null && !r.rejectReason().isBlank());
        }
        assertEquals(List.of(1L, 2L, 3L), List.of(zeroQty.orderId(), badPrice.orderId(), pricedMarket.orderId()));
        assertEquals(0, book.restingOrderCount());
    }

    @Test
    @DisplayName("depth aggregates quantity and order count per level, best first")
    void depthView() {
        buy(100, 10);
        buy(100, 5);
        buy(98, 7);
        buy(99, 1);
        List<LevelView> depth = book.depth(Side.BUY, 2);
        assertEquals(List.of(new LevelView(100, 15, 2), new LevelView(99, 1, 1)), depth);
        assertEquals(3, book.depth(Side.BUY, 10).size());
    }

    @Test
    @DisplayName("accepted reports carry no reject reason and a zero average price when nothing traded")
    void reportDefaults() {
        ExecutionReport r = book.submit(OrderRequest.limit(Side.SELL, 100, 1));
        assertNull(r.rejectReason());
        assertEquals(0.0, r.averagePrice(), 0.0);
    }

    @Test
    @DisplayName("toString renders both sides")
    void toStringRendersBook() {
        buy(99, 3);
        sell(101, 4);
        String text = book.toString();
        assertTrue(text.contains("99") && text.contains("101"));
    }
}
