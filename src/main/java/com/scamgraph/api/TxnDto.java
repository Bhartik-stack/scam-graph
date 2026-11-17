package com.scamgraph.api;

/** A single transaction, formatted for display. */
public record TxnDto(String id, String time, String from, String fromName, String to, String toName,
                     long amount, String channel, String direction) {
}
