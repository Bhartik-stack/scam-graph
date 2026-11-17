package com.scamgraph.api;

/** One payment link (all payments from one account to another) on the graph. */
public record EdgeDto(String from, String to, long amount, int count, boolean suspicious) {
}
