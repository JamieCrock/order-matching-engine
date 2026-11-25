package io.github.jamiecrock.matching.cli;

import io.github.jamiecrock.matching.OrderRequest;

/** A parsed line of input from the interactive shell. */
public sealed interface Command {

    record Submit(OrderRequest request) implements Command {}

    record Cancel(long orderId) implements Command {}

    record Reduce(long orderId, long quantity) implements Command {}

    record Replace(long orderId, long price, long quantity) implements Command {}

    record ShowBook(int levels) implements Command {}

    record Help() implements Command {}

    record Quit() implements Command {}
}
