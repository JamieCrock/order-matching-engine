package io.github.jamiecrock.matching.cli;

import io.github.jamiecrock.matching.ExecutionReport;
import io.github.jamiecrock.matching.LevelView;
import io.github.jamiecrock.matching.OrderBook;
import io.github.jamiecrock.matching.OrderStatus;
import io.github.jamiecrock.matching.Side;
import io.github.jamiecrock.matching.Trade;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Interactive shell for the order book. Reads commands from stdin, or from a script file given as
 * the first argument.
 */
public final class Main {
    private static final String HELP =
            """
            Commands (prices in decimal, tick size 0.01):
              buy  <qty> @ <price> [gtc|ioc|fok]   place a limit order (default GTC)
              sell <qty> @ <price> [gtc|ioc|fok]
              buy  <qty> market [fok]              market order (never rests)
              cancel  <id>                         cancel a resting order
              reduce  <id> <qty>                   lower open quantity, keeping queue position
              replace <id> <qty> @ <price>         cancel/replace (loses queue position)
              book [levels]                        show market depth
              help | quit
            """;

    private final OrderBook book;
    private final CommandParser parser;
    private final PrintStream out;

    Main(OrderBook book, CommandParser parser, PrintStream out) {
        this.book = book;
        this.parser = parser;
        this.out = out;
    }

    public static void main(String[] args) throws IOException {
        Main shell = new Main(new OrderBook("DEMO"), new CommandParser(new BigDecimal("0.01")), System.out);
        boolean interactive = args.length == 0;
        try (Reader reader = interactive
                ? new InputStreamReader(System.in, StandardCharsets.UTF_8)
                : Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
            if (interactive) {
                System.out.println("Order matching engine - type 'help' for commands.");
            }
            shell.run(new BufferedReader(reader), interactive);
        }
    }

    void run(BufferedReader in, boolean prompt) throws IOException {
        while (true) {
            if (prompt) {
                out.print("> ");
                out.flush();
            }
            String line = in.readLine();
            if (line == null) {
                return;
            }
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!prompt) {
                out.println("> " + line);
            }
            if (!execute(line)) {
                return;
            }
        }
    }

    /** Execute one command line; returns false when the shell should exit. */
    boolean execute(String line) {
        Command command;
        try {
            command = parser.parse(line);
        } catch (IllegalArgumentException e) {
            out.println("  error: " + e.getMessage());
            return true;
        }
        switch (command) {
            case Command.Submit s -> print(book.submit(s.request()));
            case Command.Cancel c -> print(book.cancel(c.orderId()));
            case Command.Reduce r -> print(book.reduce(r.orderId(), r.quantity()));
            case Command.Replace r -> print(book.replace(r.orderId(), r.price(), r.quantity()));
            case Command.ShowBook b -> printBook(b.levels());
            case Command.Help h -> out.print(HELP);
            case Command.Quit q -> {
                return false;
            }
        }
        return true;
    }

    private void print(ExecutionReport report) {
        for (Trade t : report.trades()) {
            out.printf(
                    "  TRADE #%d  %d @ %s  (buy #%d / sell #%d)%n",
                    t.sequence(), t.quantity(), parser.formatPrice(t.price()), t.buyOrderId(), t.sellOrderId());
        }
        if (report.status() == OrderStatus.REJECTED) {
            out.printf("  order #%d REJECTED: %s%n", report.orderId(), report.rejectReason());
            return;
        }
        out.printf(
                "  order #%d %s  filled=%d  resting=%d%n",
                report.orderId(), report.status(), report.filled(), report.remaining());
    }

    private void printBook(int levels) {
        List<LevelView> bids = book.depth(Side.BUY, levels);
        List<LevelView> asks = book.depth(Side.SELL, levels);
        out.printf("  %8s %6s %10s | %-10s %-6s %-8s%n", "BID QTY", "ORDERS", "BID", "ASK", "ORDERS", "ASK QTY");
        for (int i = 0; i < Math.max(bids.size(), asks.size()); i++) {
            String bq = "", bo = "", bp = "", ap = "", ao = "", aq = "";
            if (i < bids.size()) {
                bq = Long.toString(bids.get(i).quantity());
                bo = Integer.toString(bids.get(i).orderCount());
                bp = parser.formatPrice(bids.get(i).price());
            }
            if (i < asks.size()) {
                ap = parser.formatPrice(asks.get(i).price());
                ao = Integer.toString(asks.get(i).orderCount());
                aq = Long.toString(asks.get(i).quantity());
            }
            out.printf("  %8s %6s %10s | %-10s %-6s %-8s%n", bq, bo, bp, ap, ao, aq);
        }
        book.spread().ifPresent(s -> out.println("  spread: " + parser.formatPrice(s)));
    }
}
