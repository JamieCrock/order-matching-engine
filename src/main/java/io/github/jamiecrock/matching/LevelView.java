package io.github.jamiecrock.matching;

/** Read-only snapshot of one price level for market-data style depth views. */
public record LevelView(long price, long quantity, int orderCount) {}
