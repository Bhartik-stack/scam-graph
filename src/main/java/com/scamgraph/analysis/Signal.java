package com.scamgraph.analysis;

/**
 * The seven fraud signals. Each signal gives every account a value between 0 and 1.
 *
 *   Risk score = 100 x (sum of weight x value)
 *
 * The weights add up to 1.0, so the risk score is always between 0 and 100.
 */
public enum Signal {

    PASS_THROUGH("passThrough", "Pass-through money", 0.25,
            "Money comes in and leaves again within hours (measured with FIFO money ageing)."),
    LAYERING("layering", "Money chains & loops", 0.20,
            "The account is a hop in fast money chains, or part of a circular money loop."),
    ASSOCIATION("association", "Money from suspects", 0.15,
            "Share of incoming money that came from other suspicious accounts (risk spreads through the network)."),
    HUB("hub", "Large fan-in / fan-out", 0.10,
            "Many different accounts send to it or receive from it in big amounts (₹20,000 or more)."),
    STRUCTURING("structuring", "Split payments (smurfing)", 0.10,
            "Payments kept just below ₹50,000 so they look small and avoid checks."),
    VELOCITY("velocity", "Burst activity", 0.10,
            "Many transactions packed inside a single hour."),
    CENTRALITY("centrality", "Bridge position", 0.10,
            "Betweenness centrality: many money paths in the network pass through this account.");

    private final String key;
    private final String label;
    private final double weight;
    private final String meaning;

    Signal(String key, String label, double weight, String meaning) {
        this.key = key;
        this.label = label;
        this.weight = weight;
        this.meaning = meaning;
    }

    public String key() { return key; }
    public String label() { return label; }
    public double weight() { return weight; }
    public String meaning() { return meaning; }
}
