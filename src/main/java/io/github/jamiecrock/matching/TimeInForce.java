package io.github.jamiecrock.matching;

/** How long an order may live once it reaches the book. */
public enum TimeInForce {
    /** Good 'til cancelled: any unfilled remainder rests on the book. */
    GTC,
    /** Immediate or cancel: fill what is possible now, cancel the rest. */
    IOC,
    /** Fill or kill: fill the whole quantity immediately or do nothing at all. */
    FOK
}
