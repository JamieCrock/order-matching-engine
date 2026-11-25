package io.github.jamiecrock.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * A single-instrument limit order book with price-time priority matching.
 *
 * <h2>Rules</h2>
 *
 * <ul>
 *   <li><b>Price priority</b>: a buy matches the lowest ask first; a sell matches the highest bid first.
 *   <li><b>Time priority</b>: at the same price, the order that arrived first fills first.
 *   <li><b>Execution price</b>: always the resting order's price, so an aggressive order can get price
 *       improvement but never a worse price than its limit.
 *   <li><b>Time in force</b>: GTC remainders rest; IOC remainders are cancelled; FOK either fills
 *       completely or is rejected without trading. Market orders never rest.
 *   <li><b>Amendments</b>: reducing quantity keeps time priority; changing price or increasing size is a
 *       cancel/replace and goes to the back of the queue.
 * </ul>
 *
 * <h2>Complexity</h2>
 *
 * Prices live in two {@link TreeMap}s (bids descending, asks ascending), so finding the best price is
 * O(log P) for P price levels. Each level keeps its orders in arrival order with O(1) removal, and an
 * id index gives O(1) lookup, so cancels cost O(log P) at most (only when a level empties).
 *
 * <p>Prices are integer ticks ({@code long}) to avoid floating-point rounding. Not thread-safe: an
 * exchange would feed each book from a single sequencer thread.
 */
public final class OrderBook {
    private final String symbol;
    private final NavigableMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();
    private final Map<Long, Order> index = new HashMap<>();
    private long nextOrderId = 1;
    private long nextTradeSequence = 1;

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    // ------------------------------------------------------------------ commands

    /** Submit a new order. The book assigns its id, which is returned in the report. */
    public ExecutionReport submit(OrderRequest request) {
        long id = nextOrderId++;
        String error = validate(request);
        if (error != null) {
            return ExecutionReport.rejected(id, error);
        }

        boolean market = request.type() == OrderType.MARKET;
        long limit = market ? (request.side() == Side.BUY ? Long.MAX_VALUE : Long.MIN_VALUE) : request.price();

        if (request.timeInForce() == TimeInForce.FOK
                && availableLiquidity(request.side(), limit, request.quantity()) < request.quantity()) {
            return ExecutionReport.rejected(id, "FOK order cannot be filled in full");
        }

        List<Trade> trades = new ArrayList<>();
        long remaining = match(id, request.side(), limit, request.quantity(), trades);
        long filled = request.quantity() - remaining;

        if (remaining > 0 && !market && request.timeInForce() == TimeInForce.GTC) {
            rest(new Order(id, request.side(), request.price(), remaining, filled));
            OrderStatus status = filled == 0 ? OrderStatus.NEW : OrderStatus.PARTIALLY_FILLED;
            return new ExecutionReport(id, status, filled, remaining, trades, null);
        }
        OrderStatus status = remaining == 0 ? OrderStatus.FILLED : OrderStatus.CANCELLED;
        return new ExecutionReport(id, status, filled, 0, trades, null);
    }

    /** Cancel a resting order. */
    public ExecutionReport cancel(long orderId) {
        Order order = index.remove(orderId);
        if (order == null) {
            return ExecutionReport.rejected(orderId, "unknown or already completed order");
        }
        NavigableMap<Long, PriceLevel> side = sideOf(order.side);
        PriceLevel level = side.get(order.price);
        level.remove(orderId);
        if (level.isEmpty()) {
            side.remove(order.price);
        }
        return new ExecutionReport(orderId, OrderStatus.CANCELLED, 0, 0, List.of(), null);
    }

    /** Reduce a resting order's open quantity. The order keeps its place in the queue. */
    public ExecutionReport reduce(long orderId, long newQuantity) {
        Order order = index.get(orderId);
        if (order == null) {
            return ExecutionReport.rejected(orderId, "unknown or already completed order");
        }
        if (newQuantity <= 0) {
            return ExecutionReport.rejected(orderId, "new quantity must be positive (use cancel instead)");
        }
        if (newQuantity >= order.remaining) {
            return ExecutionReport.rejected(orderId, "reduce can only lower quantity (use replace to increase)");
        }
        sideOf(order.side).get(order.price).reduce(order, newQuantity);
        OrderStatus status = order.filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW;
        return new ExecutionReport(orderId, status, 0, newQuantity, List.of(), null);
    }

    /**
     * Cancel/replace: cancel the order and submit a new GTC limit order on the same side. The new order
     * gets a new id and loses time priority, and may trade immediately if the new price crosses.
     */
    public ExecutionReport replace(long orderId, long newPrice, long newQuantity) {
        Order order = index.get(orderId);
        if (order == null) {
            return ExecutionReport.rejected(orderId, "unknown or already completed order");
        }
        if (newPrice <= 0 || newQuantity <= 0) {
            return ExecutionReport.rejected(orderId, "price and quantity must be positive");
        }
        cancel(orderId);
        return submit(OrderRequest.limit(order.side, newPrice, newQuantity));
    }

    // ------------------------------------------------------------------ queries

    public OptionalLong bestBid() {
        return bids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(bids.firstKey());
    }

    public OptionalLong bestAsk() {
        return asks.isEmpty() ? OptionalLong.empty() : OptionalLong.of(asks.firstKey());
    }

    public OptionalLong spread() {
        if (bids.isEmpty() || asks.isEmpty()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(asks.firstKey() - bids.firstKey());
    }

    /** Top {@code levels} price levels on one side, best first. */
    public List<LevelView> depth(Side side, int levels) {
        List<LevelView> out = new ArrayList<>(Math.min(levels, sideOf(side).size()));
        for (PriceLevel level : sideOf(side).values()) {
            if (out.size() == levels) {
                break;
            }
            out.add(new LevelView(level.price, level.totalQuantity(), level.orderCount()));
        }
        return Collections.unmodifiableList(out);
    }

    /** Total resting quantity at an exact price on one side. */
    public long quantityAt(Side side, long price) {
        PriceLevel level = sideOf(side).get(price);
        return level == null ? 0 : level.totalQuantity();
    }

    /** Ids of resting orders at a price, in time priority. Mainly useful for tests and debugging. */
    public List<Long> queueAt(Side side, long price) {
        PriceLevel level = sideOf(side).get(price);
        if (level == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(level.orderCount());
        level.iterator().forEachRemaining(o -> ids.add(o.id));
        return ids;
    }

    public boolean isResting(long orderId) {
        return index.containsKey(orderId);
    }

    public int restingOrderCount() {
        return index.size();
    }

    /** A healthy book is never crossed after matching completes. */
    public boolean isCrossed() {
        return !bids.isEmpty() && !asks.isEmpty() && bids.firstKey() >= asks.firstKey();
    }

    // ------------------------------------------------------------------ internals

    private static String validate(OrderRequest request) {
        if (request.quantity() <= 0) {
            return "quantity must be positive";
        }
        if (request.type() == OrderType.LIMIT && request.price() <= 0) {
            return "limit price must be positive";
        }
        if (request.type() == OrderType.MARKET && request.price() != 0) {
            return "market orders must not specify a price";
        }
        return null;
    }

    private NavigableMap<Long, PriceLevel> sideOf(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private static boolean crosses(Side incoming, long restingPrice, long limit) {
        return incoming == Side.BUY ? restingPrice <= limit : restingPrice >= limit;
    }

    private long availableLiquidity(Side incoming, long limit, long needed) {
        long total = 0;
        for (PriceLevel level : sideOf(incoming.opposite()).values()) {
            if (!crosses(incoming, level.price, limit) || total >= needed) {
                break;
            }
            total += level.totalQuantity();
        }
        return total;
    }

    private long match(long incomingId, Side side, long limit, long quantity, List<Trade> trades) {
        NavigableMap<Long, PriceLevel> opposite = sideOf(side.opposite());
        while (quantity > 0 && !opposite.isEmpty()) {
            PriceLevel level = opposite.firstEntry().getValue();
            if (!crosses(side, level.price, limit)) {
                break;
            }
            while (quantity > 0 && !level.isEmpty()) {
                Order resting = level.head();
                long traded = Math.min(quantity, resting.remaining);
                level.fill(resting, traded);
                quantity -= traded;
                if (resting.remaining == 0) {
                    index.remove(resting.id);
                }
                long buyId = side == Side.BUY ? incomingId : resting.id;
                long sellId = side == Side.SELL ? incomingId : resting.id;
                trades.add(new Trade(nextTradeSequence++, buyId, sellId, level.price, traded, side));
            }
            if (level.isEmpty()) {
                opposite.pollFirstEntry();
            }
        }
        return quantity;
    }

    private void rest(Order order) {
        sideOf(order.side).computeIfAbsent(order.price, PriceLevel::new).add(order);
        index.put(order.id, order);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-12s %10s | %-10s %12s%n", "BID QTY", "BID", "ASK", "ASK QTY"));
        List<LevelView> b = depth(Side.BUY, 10);
        List<LevelView> a = depth(Side.SELL, 10);
        for (int i = 0; i < Math.max(b.size(), a.size()); i++) {
            String bq = i < b.size() ? Long.toString(b.get(i).quantity()) : "";
            String bp = i < b.size() ? Long.toString(b.get(i).price()) : "";
            String ap = i < a.size() ? Long.toString(a.get(i).price()) : "";
            String aq = i < a.size() ? Long.toString(a.get(i).quantity()) : "";
            sb.append(String.format("%-12s %10s | %-10s %12s%n", bq, bp, ap, aq));
        }
        return sb.toString();
    }
}
