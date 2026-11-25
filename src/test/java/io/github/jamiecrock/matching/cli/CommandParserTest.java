package io.github.jamiecrock.matching.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jamiecrock.matching.OrderRequest;
import io.github.jamiecrock.matching.OrderType;
import io.github.jamiecrock.matching.Side;
import io.github.jamiecrock.matching.TimeInForce;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommandParserTest {
    private final CommandParser parser = new CommandParser(new BigDecimal("0.01"));

    private OrderRequest submit(String line) {
        return assertInstanceOf(Command.Submit.class, parser.parse(line)).request();
    }

    @Test
    @DisplayName("limit orders default to GTC and convert prices to ticks")
    void parsesLimitOrder() {
        OrderRequest r = submit("buy 100 @ 101.25");
        assertEquals(Side.BUY, r.side());
        assertEquals(OrderType.LIMIT, r.type());
        assertEquals(10125, r.price());
        assertEquals(100, r.quantity());
        assertEquals(TimeInForce.GTC, r.timeInForce());
    }

    @Test
    @DisplayName("time in force, short aliases and mixed case are accepted")
    void parsesTimeInForceAndAliases() {
        assertEquals(TimeInForce.IOC, submit("S 5 @ 99 IOC").timeInForce());
        assertEquals(TimeInForce.FOK, submit("b 5 @ 99.5 fok").timeInForce());
        assertEquals(9950, submit("b 5 @ 99.5").price());
    }

    @Test
    @DisplayName("market orders are IOC unless FOK is requested")
    void parsesMarketOrders() {
        OrderRequest m = submit("sell 30 market");
        assertEquals(OrderType.MARKET, m.type());
        assertEquals(TimeInForce.IOC, m.timeInForce());
        assertEquals(TimeInForce.FOK, submit("buy 30 mkt fok").timeInForce());
    }

    @Test
    void parsesManagementCommands() {
        assertEquals(new Command.Cancel(7), parser.parse("cancel 7"));
        assertEquals(new Command.Reduce(7, 40), parser.parse("reduce 7 40"));
        assertEquals(new Command.Replace(7, 10130, 60), parser.parse("replace 7 60 @ 101.30"));
        assertEquals(new Command.ShowBook(5), parser.parse("book"));
        assertEquals(new Command.ShowBook(3), parser.parse("depth 3"));
        assertInstanceOf(Command.Quit.class, parser.parse("exit"));
        assertInstanceOf(Command.Help.class, parser.parse("?"));
    }

    @Test
    @DisplayName("prices off the tick grid are rejected")
    void rejectsOffTickPrices() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> parser.parse("buy 1 @ 100.005"));
        assertTrue(e.getMessage().contains("tick size"));
    }

    @Test
    @DisplayName("malformed input produces helpful errors")
    void rejectsMalformedInput() {
        for (String bad : new String[] {"", "fly 1 @ 2", "buy", "buy x @ 1", "buy 1 1.00", "buy 1 @", "buy 1 @ abc",
                "buy 1 @ 1 day", "cancel", "replace 1 2 3 4"}) {
            assertThrows(IllegalArgumentException.class, () -> parser.parse(bad), bad);
        }
    }

    @Test
    void formatsPricesAtTickPrecision() {
        assertEquals("101.25", parser.formatPrice(10125));
        assertEquals("0.50", parser.formatPrice(50));
        assertEquals(42, new CommandParser(BigDecimal.ONE).toTicks("42"));
        assertEquals("42", new CommandParser(BigDecimal.ONE).formatPrice(42));
    }

    @Test
    void rejectsNonPositiveTickSize() {
        assertThrows(IllegalArgumentException.class, () -> new CommandParser(BigDecimal.ZERO));
    }
}
