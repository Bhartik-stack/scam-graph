package com.scamgraph.api;

import java.util.List;

/** One account as shown on the network graph. */
public record NodeDto(String id, String name, int risk, String level, String role, String pattern,
                      Integer ring, long inAmount, long outAmount, int transactions, List<String> tags) {
}
