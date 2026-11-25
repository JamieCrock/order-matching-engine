package io.github.jamiecrock.matching;

import java.util.Objects;

/**
 * An instruction to place an order. Use the static factories rather than the canonical constructor.
 *
 * @param side        buy or sell
 * @param type        limit or market
 * @param price       limit price in ticks; ignored (0) for market orders
 * @param quantity    quantity to trade; must be positive
 * @param timeInForce GTC, IOC or FOK (market orders are always treated as immediate)
 */
public record OrderRequest(Side side, OrderType type, long price, long quantity, TimeInForce timeInForce) {

    public OrderRequest {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(timeInForce, "timeInForce");
    }

    public static OrderRequest limit(Side side, long price, long quantity) {
        return new OrderRequest(side, OrderType.LIMIT, price, quantity, TimeInForce.GTC);
    }

    public static OrderRequest limit(Side side, long price, long quantity, TimeInForce tif) {
        return new OrderRequest(side, OrderType.LIMIT, price, quantity, tif);
    }

    public static OrderRequest market(Side side, long quantity) {
        return new OrderRequest(side, OrderType.MARKET, 0, quantity, TimeInForce.IOC);
    }

    public static OrderRequest market(Side side, long quantity, TimeInForce tif) {
        return new OrderRequest(side, OrderType.MARKET, 0, quantity, tif);
    }
}
