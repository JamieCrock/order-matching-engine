package io.github.jamiecrock.matching;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately naive order book used as a test oracle.
 *
 * <p>It keeps every resting order in one flat list and, for each fill, scans the whole list for the
 * best counterparty (best price, then lowest arrival sequence). It is O(n) per fill and has none of
 * the real book's data-structure tricks, which makes it easy to convince yourself it is correct.
 * The randomised test checks that {@link OrderBook} produces exactly the same output.
 */
final class ReferenceBook {
    private static final class Resting {
        final long id;
        final Side side;
        final long price;
        final long sequence;
        long remaining;
        long filled;

        Resting(long id, Side side, long price, long sequence, long remaining, long filled) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.sequence = sequence;
            this.remaining = remaining;
            this.filled = filled;
        }
    }

    private final List<Resting> resting = new ArrayList<>();
    private long nextId = 1;
    private long nextSequence = 1;
    private long nextTrade = 1;

    ExecutionReport submit(OrderRequest req) {
        long id = nextId++;
        boolean market = req.type() == OrderType.MARKET;
        if (req.quantity() <= 0 || (!market && req.price() <= 0) || (market && req.price() != 0)) {
            return ExecutionReport.rejected(id, "invalid");
        }
        if (req.timeInForce() == TimeInForce.FOK) {
            long available = 0;
            for (Resting r : resting) {
                if (r.side != req.side() && crosses(req, r.price)) {
                    available += r.remaining;
                }
            }
            if (available < req.quantity()) {
                return ExecutionReport.rejected(id, "fok");
            }
        }

        List<Trade> trades = new ArrayList<>();
        long qty = req.quantity();
        while (qty > 0) {
            Resting best = null;
            for (Resting r : resting) {
                if (r.side == req.side() || !crosses(req, r.price)) {
                    continue;
                }
                if (best == null || better(r, best)) {
                    best = r;
                }
            }
            if (best == null) {
                break;
            }
            long traded = Math.min(qty, best.remaining);
            best.remaining -= traded;
            best.filled += traded;
            qty -= traded;
            long buyId = req.side() == Side.BUY ? id : best.id;
            long sellId = req.side() == Side.BUY ? best.id : id;
            trades.add(new Trade(nextTrade++, buyId, sellId, best.price, traded, req.side()));
            if (best.remaining == 0) {
                resting.remove(best);
            }
        }

        long filled = req.quantity() - qty;
        if (qty > 0 && !market && req.timeInForce() == TimeInForce.GTC) {
            resting.add(new Resting(id, req.side(), req.price(), nextSequence++, qty, filled));
            return new ExecutionReport(id, filled == 0 ? OrderStatus.NEW : OrderStatus.PARTIALLY_FILLED, filled, qty, trades, null);
        }
        return new ExecutionReport(id, qty == 0 ? OrderStatus.FILLED : OrderStatus.CANCELLED, filled, 0, trades, null);
    }

    ExecutionReport cancel(long id) {
        Resting r = find(id);
        if (r == null) {
            return ExecutionReport.rejected(id, "unknown");
        }
        resting.remove(r);
        return new ExecutionReport(id, OrderStatus.CANCELLED, 0, 0, List.of(), null);
    }

    ExecutionReport reduce(long id, long newQuantity) {
        Resting r = find(id);
        if (r == null || newQuantity <= 0 || newQuantity >= r.remaining) {
            return ExecutionReport.rejected(id, "invalid");
        }
        r.remaining = newQuantity;
        return new ExecutionReport(id, r.filled > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW, 0, newQuantity, List.of(), null);
    }

    ExecutionReport replace(long id, long price, long quantity) {
        Resting r = find(id);
        if (r == null || price <= 0 || quantity <= 0) {
            return ExecutionReport.rejected(id, "invalid");
        }
        resting.remove(r);
        return submit(OrderRequest.limit(r.side, price, quantity));
    }

    long totalQuantity(Side side) {
        return resting.stream().filter(r -> r.side == side).mapToLong(r -> r.remaining).sum();
    }

    /** Open quantity of a resting order, or 0 if it is not resting. */
    long remaining(long id) {
        Resting r = find(id);
        return r == null ? 0 : r.remaining;
    }

    int size() {
        return resting.size();
    }

    List<Long> restingIds() {
        return resting.stream().map(r -> r.id).toList();
    }

    private Resting find(long id) {
        for (Resting r : resting) {
            if (r.id == id) {
                return r;
            }
        }
        return null;
    }

    private static boolean crosses(OrderRequest req, long restingPrice) {
        if (req.type() == OrderType.MARKET) {
            return true;
        }
        return req.side() == Side.BUY ? restingPrice <= req.price() : restingPrice >= req.price();
    }

    private static boolean better(Resting a, Resting b) {
        if (a.price != b.price) {
            return a.side == Side.SELL ? a.price < b.price : a.price > b.price;
        }
        return a.sequence < b.sequence;
    }
}
