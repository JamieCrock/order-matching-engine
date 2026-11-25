package io.github.jamiecrock.matching.bench;

import io.github.jamiecrock.matching.ExecutionReport;
import io.github.jamiecrock.matching.OrderBook;
import io.github.jamiecrock.matching.OrderRequest;
import io.github.jamiecrock.matching.OrderStatus;
import io.github.jamiecrock.matching.Side;
import io.github.jamiecrock.matching.TimeInForce;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Simple throughput and latency harness: a random but realistic order flow (passive limits around a
 * mid price, crossing limits, IOC and market orders, cancels and reductions).
 *
 * <p>This is a quick sanity check, not a rigorous JMH benchmark - numbers depend heavily on the JVM
 * and machine. Run with
 * {@code java -cp target/classes io.github.jamiecrock.matching.bench.Benchmark [operations]}.
 */
public final class Benchmark {
    private static final long MID = 10_000;

    public static void main(String[] args) {
        int operations = args.length > 0 ? Integer.parseInt(args[0]) : 2_000_000;
        System.out.println("Warming up...");
        run(operations / 4, 1L, false);
        System.out.printf("Measuring %,d operations...%n", operations);
        run(operations, 42L, true);
    }

    static void run(int operations, long seed, boolean report) {
        SplittableRandom rng = new SplittableRandom(seed);
        OrderBook book = new OrderBook("BENCH");
        long[] live = new long[1 << 20];
        int liveCount = 0;
        long[] latencies = new long[operations];
        long trades = 0;

        long start = System.nanoTime();
        for (int i = 0; i < operations; i++) {
            int roll = rng.nextInt(100);
            long t0 = System.nanoTime();
            if (roll < 35 && liveCount > 0) {
                int slot = rng.nextInt(liveCount);
                book.cancel(live[slot]);
                live[slot] = live[--liveCount];
            } else if (roll < 40 && liveCount > 0) {
                book.reduce(live[rng.nextInt(liveCount)], 1);
            } else {
                Side side = rng.nextBoolean() ? Side.BUY : Side.SELL;
                long qty = 1 + rng.nextInt(100);
                OrderRequest request;
                if (roll < 45) {
                    request = OrderRequest.market(side, qty);
                } else {
                    // Mostly passive prices, with ~15% of limits crossing the spread.
                    int offset = rng.nextInt(-5, 40);
                    long price = side == Side.BUY ? MID - offset : MID + offset;
                    TimeInForce tif = roll < 50 ? TimeInForce.IOC : TimeInForce.GTC;
                    request = OrderRequest.limit(side, price, qty, tif);
                }
                ExecutionReport r = book.submit(request);
                trades += r.trades().size();
                if ((r.status() == OrderStatus.NEW || r.status() == OrderStatus.PARTIALLY_FILLED)
                        && liveCount < live.length) {
                    live[liveCount++] = r.orderId();
                }
            }
            latencies[i] = System.nanoTime() - t0;
        }
        long elapsed = System.nanoTime() - start;

        if (report) {
            Arrays.sort(latencies);
            System.out.printf("Throughput : %,.0f ops/sec%n", operations / (elapsed / 1e9));
            System.out.printf("Trades     : %,d%n", trades);
            System.out.printf("Resting    : %,d orders at end%n", book.restingOrderCount());
            System.out.printf(
                    "Latency ns : p50=%d  p99=%d  p99.9=%d  max=%d%n",
                    latencies[operations / 2],
                    latencies[(int) (operations * 0.99)],
                    latencies[(int) (operations * 0.999)],
                    latencies[operations - 1]);
        }
    }

    private Benchmark() {}
}
