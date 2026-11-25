package io.github.jamiecrock.matching.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jamiecrock.matching.OrderBook;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MainTest {

    private static String runScript(String script) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        Main shell = new Main(new OrderBook("T"), new CommandParser(new BigDecimal("0.01")), out);
        shell.run(new BufferedReader(new StringReader(script)), false);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    void scriptedSessionPrintsTradesAndBook() throws IOException {
        String output = runScript(
                """
                # comments and blank lines are ignored

                sell 100 @ 101.00
                sell 50 @ 101.50
                buy 120 @ 101.50
                book
                quit
                buy 1 @ 1.00
                """);
        assertTrue(output.contains("TRADE #1  100 @ 101.00  (buy #3 / sell #1)"), output);
        assertTrue(output.contains("TRADE #2  20 @ 101.50  (buy #3 / sell #2)"), output);
        assertTrue(output.contains("order #3 FILLED  filled=120  resting=0"), output);
        assertTrue(output.contains("101.50"), output);
        assertFalse(output.contains("order #4"), "commands after quit must not run");
    }

    @Test
    void errorsAreReportedWithoutStoppingTheShell() throws IOException {
        String output = runScript("bogus\ncancel 9\nhelp\n");
        assertTrue(output.contains("error: unknown command 'bogus'"), output);
        assertTrue(output.contains("order #9 REJECTED"), output);
        assertTrue(output.contains("Commands"), output);
    }
}
