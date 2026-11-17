package com.scamgraph.api;

import java.util.List;

/**
 * What happens if we freeze one account.
 * before / after = maximum money that can still flow from victims to cash-out accounts (max-flow).
 */
public record BlockResult(String account, String name, long before, long after, long stopped, int percent,
                          List<String> brokenLinks, String message) {
}
