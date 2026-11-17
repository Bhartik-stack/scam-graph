package com.scamgraph.api;

import java.util.List;

/** A money loop (round-tripping): A -> B -> C -> A. */
public record CycleDto(List<String> accounts, long amount, String duration) {
}
