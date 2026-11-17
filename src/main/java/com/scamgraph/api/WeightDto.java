package com.scamgraph.api;

/** How much each signal counts in the risk score. */
public record WeightDto(String key, String label, double weight, String meaning) {
}
