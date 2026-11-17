package com.scamgraph.graph;

import com.scamgraph.model.Transaction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The money network.
 *
 * Every account becomes a NODE (numbered 0, 1, 2, ...).
 * Every payment A -> B becomes part of a directed EDGE A -> B.
 *
 * Internally we store the graph as an ADJACENCY LIST:
 * for each node, a map of "who did this account pay" and "who paid this account".
 * This takes O(V + E) memory, much less than a V x V matrix.
 */
public final class TransactionGraph {

    private final List<Transaction> transactions;
    private final Map<String, Integer> index = new HashMap<>();
    private final List<String> ids = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<List<Transaction>> outTx = new ArrayList<>();
    private final List<List<Transaction>> inTx = new ArrayList<>();
    private final List<Map<Integer, Edge>> outEdges = new ArrayList<>();
    private final List<Map<Integer, Edge>> inEdges = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();

    public TransactionGraph(Collection<Transaction> input) {
        List<Transaction> sorted = new ArrayList<>();
        for (Transaction t : input) {
            if (t.from() == null || t.to() == null || t.from().equals(t.to()) || t.amount() <= 0 || t.time() == null) {
                continue; // skip self-transfers and broken rows
            }
            sorted.add(t);
        }
        sorted.sort(Comparator.comparing(Transaction::time).thenComparing(Transaction::id));
        this.transactions = Collections.unmodifiableList(sorted);

        for (Transaction t : transactions) {
            int u = node(t.from(), t.fromName());
            int v = node(t.to(), t.toName());
            outTx.get(u).add(t);
            inTx.get(v).add(t);
            Edge e = outEdges.get(u).get(v);
            if (e == null) {
                e = new Edge(u, v);
                outEdges.get(u).put(v, e);
                inEdges.get(v).put(u, e);
                edges.add(e);
            }
            e.add(t);
        }
    }

    private int node(String id, String name) {
        Integer existing = index.get(id);
        if (existing != null) {
            if ((names.get(existing) == null || names.get(existing).isBlank()) && name != null && !name.isBlank()) {
                names.set(existing, name);
            }
            return existing;
        }
        int i = ids.size();
        index.put(id, i);
        ids.add(id);
        names.add(name == null ? "" : name);
        outTx.add(new ArrayList<>());
        inTx.add(new ArrayList<>());
        outEdges.add(new LinkedHashMap<>());
        inEdges.add(new LinkedHashMap<>());
        return i;
    }

    /** Number of accounts (nodes). */
    public int size() { return ids.size(); }

    public String id(int node) { return ids.get(node); }

    public String name(int node) { return names.get(node); }

    /** Node number for an account id, or null if unknown. */
    public Integer indexOf(String accountId) { return index.get(accountId); }

    /** All transactions, sorted by time. */
    public List<Transaction> transactions() { return transactions; }

    /** Payments sent by this account, in time order. */
    public List<Transaction> outgoing(int node) { return outTx.get(node); }

    /** Payments received by this account, in time order. */
    public List<Transaction> incoming(int node) { return inTx.get(node); }

    public Collection<Edge> outEdges(int node) { return outEdges.get(node).values(); }

    public Collection<Edge> inEdges(int node) { return inEdges.get(node).values(); }

    public Edge edge(int from, int to) { return outEdges.get(from).get(to); }

    public List<Edge> edges() { return edges; }

    /** Out-degree: how many different accounts this account paid. */
    public int outDegree(int node) { return outEdges.get(node).size(); }

    /** In-degree: how many different accounts paid this account. */
    public int inDegree(int node) { return inEdges.get(node).size(); }

    /**
     * Plain adjacency list (node -> list of nodes it paid), keeping only edges
     * whose total amount is at least {@code minTotal}. Used by the algorithms.
     */
    public List<List<Integer>> adjacency(long minTotal) {
        List<List<Integer>> adj = new ArrayList<>(size());
        for (int u = 0; u < size(); u++) {
            List<Integer> list = new ArrayList<>();
            for (Edge e : outEdges.get(u).values()) {
                if (e.total() >= minTotal) list.add(e.to());
            }
            adj.add(list);
        }
        return adj;
    }
}
