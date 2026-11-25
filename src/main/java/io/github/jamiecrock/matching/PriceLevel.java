package io.github.jamiecrock.matching;

import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * All resting orders at a single price, in time priority.
 *
 * <p>A {@link LinkedHashMap} keyed by order id gives FIFO iteration plus O(1) removal for
 * cancels, without needing a hand-rolled intrusive linked list.
 */
final class PriceLevel {
    final long price;
    private final LinkedHashMap<Long, Order> orders = new LinkedHashMap<>();
    private long totalQuantity;

    PriceLevel(long price) {
        this.price = price;
    }

    void add(Order order) {
        orders.put(order.id, order);
        totalQuantity += order.remaining;
    }

    Order remove(long orderId) {
        Order removed = orders.remove(orderId);
        if (removed != null) {
            totalQuantity -= removed.remaining;
        }
        return removed;
    }

    /** Oldest order at this price. */
    Order head() {
        return orders.isEmpty() ? null : orders.values().iterator().next();
    }

    /** Record a fill against an order at this level, removing it if fully filled. */
    void fill(Order order, long quantity) {
        order.remaining -= quantity;
        order.filled += quantity;
        totalQuantity -= quantity;
        if (order.remaining == 0) {
            orders.remove(order.id);
        }
    }

    /** Reduce an order's size in place; it keeps its time priority. */
    void reduce(Order order, long newRemaining) {
        totalQuantity -= order.remaining - newRemaining;
        order.remaining = newRemaining;
    }

    long totalQuantity() {
        return totalQuantity;
    }

    int orderCount() {
        return orders.size();
    }

    boolean isEmpty() {
        return orders.isEmpty();
    }

    Iterator<Order> iterator() {
        return orders.values().iterator();
    }
}
