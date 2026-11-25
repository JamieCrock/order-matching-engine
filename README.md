# Order Matching Engine

[![CI](https://github.com/JamieCrock/order-matching-engine/actions/workflows/ci.yml/badge.svg)](https://github.com/JamieCrock/order-matching-engine/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-21-orange)
![JUnit 5](https://img.shields.io/badge/tests-JUnit%205-green)

This is a **limit order book and matching engine** in plain Java 21 with no runtime dependencies. It does the job an exchange does for a single instrument: accept buy and sell orders, match them by **price-time priority**, report trades and keep the book consistent.

It covers limit and market orders, three time-in-force policies (GTC, IOC, FOK), cancel, reduce and cancel/replace. There is an interactive shell, a throughput and latency harness, and a test suite that includes **differential testing against a reference model**.

```text
> sell 100 @ 101.00
  order #1 NEW  filled=0  resting=100
> sell 50 @ 101.00
  order #2 NEW  filled=0  resting=50
> buy 120 @ 101.00
  TRADE #1  100 @ 101.00  (buy #3 / sell #1)
  TRADE #2  20 @ 101.00  (buy #3 / sell #2)
  order #3 FILLED  filled=120  resting=0
> book
   BID QTY ORDERS        BID | ASK        ORDERS ASK QTY
                             | 101.00     1      30
```

## Matching rules

| Rule | Behaviour |
|---|---|
| Price priority | A buy matches the lowest ask first; a sell matches the highest bid first. |
| Time priority | At the same price, the earliest order fills first (FIFO). |
| Execution price | Always the **resting** order's price, so an aggressive order can get a better price than its limit but never a worse one. |
| GTC | Any unfilled remainder rests on the book. |
| IOC | Fills what it can immediately and cancels the rest. |
| FOK | Checks available liquidity *inside its limit* first. It either fills completely or is rejected with no trades. |
| Market | Takes liquidity at any price and never rests. |
| Reduce | Lowers open quantity and **keeps** queue position. |
| Replace | Cancel and re-submit: gets a new id and goes to the **back** of the queue. |
| Validation | Non-positive quantities or prices, and market orders with a price, are rejected with a reason. |

## Design

```
OrderBook
├── bids : TreeMap<price, PriceLevel>  (descending)
├── asks : TreeMap<price, PriceLevel>  (ascending)
└── index: HashMap<orderId, Order>     (O(1) lookup for cancel / reduce)

PriceLevel
└── LinkedHashMap<orderId, Order>      (FIFO iteration + O(1) removal, running total quantity)
```

| Operation | Cost (P = price levels) |
|---|---|
| Best bid or ask | O(log P) |
| Add a resting order | O(log P) |
| Match against k resting orders | O(k + log P) |
| Cancel or reduce | O(1), plus O(log P) only when a level empties |

Other design choices:

- **Prices are `long` ticks**, never `double`, so there is no rounding error. The shell converts decimals with `BigDecimal` and rejects prices that are off the tick grid.
- **Results are immutable values.** `ExecutionReport`, `Trade`, `LevelView` and `OrderRequest` are Java records. Mutable `Order` and `PriceLevel` are package-private, so callers cannot corrupt book state.
- **It is single-threaded by design**, like a real exchange's per-instrument sequencer. Determinism makes the engine testable and replayable.
- The shell parses input into a **sealed `Command` hierarchy** and dispatches with pattern-matching `switch`.

## Testing

```bash
mvn verify        # compile, run all tests, write a JaCoCo coverage report to target/site/jacoco
```

- **Unit tests** (`OrderBookTest`) cover each rule above: priority, partial fills, level sweeps, IOC and FOK edge cases, queue position after reduce and replace, validation and depth views.
- **Differential tests** (`RandomisedEquivalenceTest`) apply 100,000 random operations (20 seeds × 5,000 steps) to both the optimised `OrderBook` and a deliberately naive `ReferenceBook`. The reference scans a flat list for every fill, which makes its correctness easy to check by eye. Every execution report must match exactly. After every step the test also checks that the book is never crossed, that per-side totals agree, and that **quantity is conserved**: everything submitted is resting, traded or cancelled.
- **Shell tests** cover command parsing (aliases, tick validation, error messages) and a scripted session end to end.

The suite has been checked against injected bugs: breaking the price-crossing comparison or the partial-fill status makes it fail immediately.

## Running it

Requires Java 21 and Maven.

```bash
mvn -q package
java -jar target/order-matching-engine-1.0.0.jar                       # interactive shell
java -jar target/order-matching-engine-1.0.0.jar examples/session.txt  # scripted demo
java -cp target/classes io.github.jamiecrock.matching.bench.Benchmark   # throughput & latency
```

Shell commands:

```text
buy  <qty> @ <price> [gtc|ioc|fok]    sell <qty> @ <price> [gtc|ioc|fok]
buy  <qty> market [fok]               sell <qty> market [fok]
cancel <id>    reduce <id> <qty>    replace <id> <qty> @ <price>    book [levels]    help    quit
```

The benchmark replays a random order flow (passive and crossing limits, IOC and market orders, cancels and reductions). It prints throughput and p50, p99 and p99.9 latency. It's a quick sanity check rather than a JMH-grade benchmark, so treat the numbers as indicative for your machine.

## Project layout

```
src/main/java/io/github/jamiecrock/matching/
├── OrderBook.java          # matching logic and book state
├── PriceLevel.java, Order.java
├── OrderRequest.java, ExecutionReport.java, Trade.java, LevelView.java
├── Side.java, OrderType.java, TimeInForce.java, OrderStatus.java
├── cli/                    # Command (sealed), CommandParser, Main (shell)
└── bench/Benchmark.java
src/test/java/...           # OrderBookTest, RandomisedEquivalenceTest + ReferenceBook, CLI tests
```

## Possible extensions

- Multiple instruments behind a `MatchingEngine` façade, with a single-writer event loop
- Self-trade prevention and participant ids
- Stop and iceberg orders
- An event-sourced journal so the book can be rebuilt by replaying its input
