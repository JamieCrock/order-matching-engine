package io.github.jamiecrock.matching;

/**
 * A single execution between an incoming (aggressor) order and a resting order.
 *
 * @param sequence      monotonically increasing trade number
 * @param buyOrderId    id of the buy order
 * @param sellOrderId   id of the sell order
 * @param price         execution price in ticks (always the resting order's price)
 * @param quantity      executed quantity
 * @param aggressor     side of the incoming order that triggered the trade
 */
public record Trade(long sequence, long buyOrderId, long sellOrderId, long price, long quantity, Side aggressor) {}
