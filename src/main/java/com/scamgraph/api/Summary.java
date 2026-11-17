package com.scamgraph.api;

import java.util.List;

/** Overview of the whole analysis: numbers, rings, chains, loops and top accounts. */
public record Summary(String dataset, String period, int accounts, int transactions, long totalAmount,
                      int suspects, int watchlist, int victims, int rings, long moneyStolen, long moneyTraced,
                      int chainCount, int cycleCount, List<Integer> riskHistogram,
                      List<RingDto> ringList, List<AccountRow> topAccounts, List<ChainDto> chains,
                      List<CycleDto> cycles, List<WeightDto> weights, BlockResult bestBlock,
                      long analysisMillis) {
}
