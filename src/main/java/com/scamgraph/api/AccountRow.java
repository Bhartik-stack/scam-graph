package com.scamgraph.api;

/** Short row for the "most suspicious accounts" table. */
public record AccountRow(String id, String name, int risk, String level, String pattern, String topReason, Integer ring) {
}
