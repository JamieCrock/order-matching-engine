package io.github.jamiecrock.matching.cli;

import io.github.jamiecrock.matching.OrderRequest;
import io.github.jamiecrock.matching.Side;
import io.github.jamiecrock.matching.TimeInForce;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * Parses shell commands. Prices are decimals converted to integer ticks.
 *
 * <pre>
 *   buy 100 @ 101.25 [gtc|ioc|fok]
 *   sell 50 market [fok]
 *   cancel 7
 *   reduce 7 40
 *   replace 7 60 @ 101.30
 *   book [levels]
 *   help | quit
 * </pre>
 */
public final class CommandParser {
    private final BigDecimal tickSize;
    private final int priceScale;

    /** @param tickSize smallest price increment, e.g. {@code 0.01} */
    public CommandParser(BigDecimal tickSize) {
        if (tickSize.signum() <= 0) {
            throw new IllegalArgumentException("tick size must be positive");
        }
        this.tickSize = tickSize;
        this.priceScale = Math.max(0, tickSize.stripTrailingZeros().scale());
    }

    public Command parse(String line) {
        String[] t = line.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) {
            throw new IllegalArgumentException("empty command");
        }
        return switch (t[0]) {
            case "buy", "b" -> order(Side.BUY, t);
            case "sell", "s" -> order(Side.SELL, t);
            case "cancel", "c" -> {
                expect(t, 2, "cancel <id>");
                yield new Command.Cancel(number(t[1], "order id"));
            }
            case "reduce" -> {
                expect(t, 3, "reduce <id> <new quantity>");
                yield new Command.Reduce(number(t[1], "order id"), number(t[2], "quantity"));
            }
            case "replace" -> {
                expect(t, 5, "replace <id> <quantity> @ <price>");
                requireAt(t[3]);
                yield new Command.Replace(number(t[1], "order id"), toTicks(t[4]), number(t[2], "quantity"));
            }
            case "book", "depth" -> new Command.ShowBook(t.length > 1 ? (int) number(t[1], "levels") : 5);
            case "help", "?" -> new Command.Help();
            case "quit", "exit", "q" -> new Command.Quit();
            default -> throw new IllegalArgumentException("unknown command '" + t[0] + "' (try 'help')");
        };
    }

    /** Convert a decimal price string to ticks, rejecting prices off the tick grid. */
    public long toTicks(String price) {
        BigDecimal value;
        try {
            value = new BigDecimal(price);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid price '" + price + "'");
        }
        BigDecimal[] quotientAndRemainder = value.divideAndRemainder(tickSize);
        if (quotientAndRemainder[1].signum() != 0) {
            throw new IllegalArgumentException("price " + price + " is not a multiple of the tick size " + tickSize);
        }
        return quotientAndRemainder[0].longValueExact();
    }

    public String formatPrice(long ticks) {
        return BigDecimal.valueOf(ticks).multiply(tickSize).setScale(priceScale).toPlainString();
    }

    private Command order(Side side, String[] t) {
        if (t.length < 3) {
            throw new IllegalArgumentException("usage: " + t[0] + " <qty> @ <price> [gtc|ioc|fok]  or  " + t[0] + " <qty> market");
        }
        long qty = number(t[1], "quantity");
        if (t[2].equals("market") || t[2].equals("mkt")) {
            TimeInForce tif = t.length > 3 ? tif(t[3]) : TimeInForce.IOC;
            return new Command.Submit(OrderRequest.market(side, qty, tif));
        }
        requireAt(t[2]);
        if (t.length < 4) {
            throw new IllegalArgumentException("missing price after '@'");
        }
        TimeInForce tif = t.length > 4 ? tif(t[4]) : TimeInForce.GTC;
        return new Command.Submit(OrderRequest.limit(side, toTicks(t[3]), qty, tif));
    }

    private static TimeInForce tif(String s) {
        try {
            return TimeInForce.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("time in force must be gtc, ioc or fok");
        }
    }

    private static long number(String s, String what) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid " + what + " '" + s + "'");
        }
    }

    private static void requireAt(String s) {
        if (!s.equals("@")) {
            throw new IllegalArgumentException("expected '@' before the price");
        }
    }

    private static void expect(String[] t, int n, String usage) {
        if (t.length != n) {
            throw new IllegalArgumentException("usage: " + usage);
        }
    }
}
