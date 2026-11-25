package io.github.jamiecrock.matching;

/** Final state of an order after a single call into the book. */
public enum OrderStatus {
    /** Accepted and resting with no fills yet. */
    NEW,
    /** Some quantity filled and the remainder is resting. */
    PARTIALLY_FILLED,
    /** Completely filled. */
    FILLED,
    /** Removed from the book, or an unfilled IOC/market remainder was dropped. */
    CANCELLED,
    /** Failed validation or a fill-or-kill could not be satisfied; nothing happened. */
    REJECTED
}
