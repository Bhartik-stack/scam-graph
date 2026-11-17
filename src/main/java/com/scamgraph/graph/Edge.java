package com.scamgraph.graph;

import com.scamgraph.model.Transaction;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A directed edge (arrow) from one account to another.
 * All payments from A to B are combined into one edge,
 * so the edge stores the total amount and the number of payments.
 */
public final class Edge {

    private final int from;
    private final int to;
    private final List<Transaction> transactions = new ArrayList<>();
    private long total;
    private LocalDateTime first;
    private LocalDateTime last;

    Edge(int from, int to) {
        this.from = from;
        this.to = to;
    }

    void add(Transaction t) {
        transactions.add(t);
        total += t.amount();
        if (first == null || t.time().isBefore(first)) first = t.time();
        if (last == null || t.time().isAfter(last)) last = t.time();
    }

    public int from() { return from; }
    public int to() { return to; }
    public long total() { return total; }
    public int count() { return transactions.size(); }
    public LocalDateTime first() { return first; }
    public LocalDateTime last() { return last; }

    /** Payments on this edge, in time order. */
    public List<Transaction> transactions() { return transactions; }
}
