package com.scamgraph.api;

import java.util.List;

/** A fraud ring: a connected group of suspicious accounts. */
public record RingDto(int id, int size, List<String> members, List<String> cashOut,
                      int victims, long stolen, int internalPayments, long internalAmount) {
}
