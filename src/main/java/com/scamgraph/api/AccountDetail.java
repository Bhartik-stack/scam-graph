package com.scamgraph.api;

import java.util.List;

/** Full investigation view of one account. */
public record AccountDetail(String id, String name, int risk, String level, String role, String pattern,
                            Integer ring, long inAmount, long outAmount, int inDegree, int outDegree,
                            String avgHoldTime, int forwardedPercent, long victimLoss,
                            List<SignalDto> signals, List<String> reasons, List<TxnDto> transactions) {
}
