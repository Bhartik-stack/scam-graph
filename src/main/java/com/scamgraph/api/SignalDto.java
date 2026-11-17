package com.scamgraph.api;

/** One fraud signal for an account: its value (0 to 1), weight and the points it adds to the risk score. */
public record SignalDto(String key, String label, double value, double weight, double points, String explanation) {
}
