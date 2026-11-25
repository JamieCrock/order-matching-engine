package io.github.jamiecrock.matching;

/**
 * A resting order. Owned and mutated only by {@link OrderBook}; time priority is the order's
 * position within its {@link PriceLevel}.
 */
final class Order {
    final long id;
    final Side side;
    final long price;
    long remaining;
    long filled;

    Order(long id, Side side, long price, long remaining, long filled) {
        this.id = id;
        this.side = side;
        this.price = price;
        this.remaining = remaining;
        this.filled = filled;
    }

    @Override
    public String toString() {
        return "Order[id=%d, %s %d @ %d, filled=%d]".formatted(id, side, remaining, price, filled);
    }
}
