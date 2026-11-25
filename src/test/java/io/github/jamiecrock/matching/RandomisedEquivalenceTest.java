package io.github.jamiecrock.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Differential testing: thousands of random operations are applied to both the optimised
 * {@link OrderBook} and the naive {@link ReferenceBook}, and every report must match exactly.
 * Book-level invariants are checked after each step.
 */
class RandomisedEquivalenceTest {
    private static final int STEPS = 5_000;

    @Test
    @DisplayName("optimised book matches the reference model on random order flow")
    void matchesReferenceAcrossSeeds() {
        for (long seed = 1; seed <= 20; seed++) {
            runScenario(seed);
        }
    }

    private static void runScenario(long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        OrderBook book = new OrderBook("RANDOM");
        ReferenceBook reference = new ReferenceBook();
        List<Long> ids = new ArrayList<>();

        long submitted = 0;
        long tradedTwice = 0; // every trade fills both sides, so count each trade's quantity twice
        long cancelledOrReduced = 0;

        for (int step = 0; step < STEPS; step++) {
            String where = "seed " + seed + ", step " + step;
            int roll = rng.nextInt(100);
            ExecutionReport actual;
            ExecutionReport expected;

            if (roll < 15 && !ids.isEmpty()) {
                long id = ids.get(rng.nextInt(ids.size()));
                long restingBefore = reference.remaining(id);
                actual = book.cancel(id);
                expected = reference.cancel(id);
                if (actual.status() == OrderStatus.CANCELLED) {
                    cancelledOrReduced += restingBefore;
                }
            } else if (roll < 20 && !ids.isEmpty()) {
                long id = ids.get(rng.nextInt(ids.size()));
                long newQty = 1 + rng.nextInt(10);
                long restingBefore = reference.remaining(id);
                actual = book.reduce(id, newQty);
                expected = reference.reduce(id, newQty);
                if (actual.status() != OrderStatus.REJECTED) {
                    cancelledOrReduced += restingBefore - newQty;
                }
            } else if (roll < 25 && !ids.isEmpty()) {
                long id = ids.get(rng.nextInt(ids.size()));
                long price = 95 + rng.nextInt(11);
                long qty = 1 + rng.nextInt(20);
                long restingBefore = reference.remaining(id);
                actual = book.replace(id, price, qty);
                expected = reference.replace(id, price, qty);
                if (actual.status() != OrderStatus.REJECTED) {
                    cancelledOrReduced += restingBefore;
                    submitted += qty;
                }
            } else {
                OrderRequest request = randomRequest(rng);
                actual = book.submit(request);
                expected = reference.submit(request);
                if (actual.status() != OrderStatus.REJECTED) {
                    submitted += request.quantity();
                    if (actual.remaining() == 0) {
                        cancelledOrReduced += request.quantity() - actual.filled(); // IOC / market leftovers
                    }
                }
            }

            assertSameReport(expected, actual, where);
            for (Trade t : actual.trades()) {
                tradedTwice += 2 * t.quantity();
            }
            if (actual.remaining() > 0) {
                ids.add(actual.orderId());
            }

            assertFalse(book.isCrossed(), "book crossed at " + where);
            assertEquals(reference.size(), book.restingOrderCount(), "resting count at " + where);
            for (Side side : Side.values()) {
                long depthTotal = book.depth(side, Integer.MAX_VALUE).stream().mapToLong(LevelView::quantity).sum();
                assertEquals(reference.totalQuantity(side), depthTotal, side + " quantity at " + where);
            }
            for (long id : reference.restingIds()) {
                assertTrue(book.isResting(id), "order " + id + " should be resting at " + where);
            }

            // Conservation: everything submitted is either resting, traded, or was cancelled/reduced away.
            long restingTotal = reference.totalQuantity(Side.BUY) + reference.totalQuantity(Side.SELL);
            assertEquals(submitted, restingTotal + tradedTwice + cancelledOrReduced, "quantity conservation at " + where);
        }
    }

    private static OrderRequest randomRequest(SplittableRandom rng) {
        Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
        long qty = rng.nextInt(50) == 0 ? 0 : 1 + rng.nextInt(20); // occasional invalid order
        int kind = rng.nextInt(100);
        if (kind < 10) {
            TimeInForce tif = rng.nextInt(4) == 0 ? TimeInForce.FOK : TimeInForce.IOC;
            return OrderRequest.market(side, qty, tif);
        }
        long price = 95 + rng.nextInt(11);
        TimeInForce tif = kind < 20 ? TimeInForce.IOC : kind < 28 ? TimeInForce.FOK : TimeInForce.GTC;
        return OrderRequest.limit(side, price, qty, tif);
    }

    private static void assertSameReport(ExecutionReport expected, ExecutionReport actual, String where) {
        assertEquals(expected.orderId(), actual.orderId(), "order id at " + where);
        assertEquals(expected.status(), actual.status(), "status at " + where);
        assertEquals(expected.filled(), actual.filled(), "filled at " + where);
        assertEquals(expected.remaining(), actual.remaining(), "remaining at " + where);
        assertEquals(expected.trades(), actual.trades(), "trades at " + where);
    }
}
