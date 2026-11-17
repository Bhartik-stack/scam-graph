package com.scamgraph.api;

import java.util.List;

/** A money chain: one payment quickly followed by the next, hop after hop. */
public record ChainDto(List<String> accounts, List<Long> amounts, List<String> times, int hops, String duration) {
}
