package com.scamgraph.analysis;

import com.scamgraph.api.BlockResult;
import com.scamgraph.api.TrailDto;
import com.scamgraph.api.TrailStep;
import com.scamgraph.graph.Edge;
import com.scamgraph.graph.GraphAlgorithms;
import com.scamgraph.graph.TransactionGraph;
import com.scamgraph.model.Transaction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The result of analysing one dataset. Holds every measurement per account
 * (index i = node number in the graph) and can answer follow-up questions
 * such as "show the money trail" or "what if we block this account?".
 */
public final class Analysis {

    public static final String SUSPECT = "SUSPECT";
    public static final String WATCH = "WATCH";
    public static final String VICTIM = "VICTIM";
    public static final String NORMAL = "NORMAL";

    public final TransactionGraph graph;
    public final int n;

    // money totals
    public final long[] inTotal;
    public final long[] outTotal;

    // raw measurements behind the signals
    public final double[] forwardedRatio;   // share of received money that was sent on (FIFO)
    public final double[] avgHoldSeconds;   // how long money stayed before leaving (FIFO), NaN if none left
    public final int[] largeIn;             // distinct senders with a payment >= Rs 20,000
    public final int[] largeOut;            // distinct receivers with a payment >= Rs 20,000
    public final int[] structured;          // payments between Rs 45,000 and Rs 49,999
    public final int[] maxBurst;            // most transactions inside any 60-minute window
    public final int[] chainCount;          // how many money chains pass through
    public final boolean[] inLoop;          // part of a circular loop
    public final double[] betweenness;      // raw betweenness centrality
    public final double[] suspectInflowShare;
    public final double[] victimInflowShare;

    // results
    public final double[][] signals;        // signals[i][signal.ordinal()] in 0..1
    public final int[] risk;                // 0..100
    public final String[] level;            // HIGH / MEDIUM / LOW
    public final String[] role;             // SUSPECT / WATCH / VICTIM / NORMAL
    public final String[] pattern;          // Collector mule / Layering mule / Cash-out account / ...
    public final long[] victimLoss;         // money a victim sent to suspects
    public final int[] ringOf;              // ring id, 0 = not in a ring

    public final List<List<Transaction>> chains = new ArrayList<>();
    public final List<Loop> loops = new ArrayList<>();
    public final List<Ring> rings = new ArrayList<>();
    public long analysisMillis;

    /** A circular money loop found in the data. */
    public record Loop(List<Integer> nodes, long amount, long seconds) {
    }

    /** A fraud ring (connected group of suspicious accounts). */
    public record Ring(int id, List<Integer> members, int victims, long stolen, int internalPayments, long internalAmount) {
    }

    Analysis(TransactionGraph graph) {
        this.graph = graph;
        this.n = graph.size();
        inTotal = new long[n];
        outTotal = new long[n];
        forwardedRatio = new double[n];
        avgHoldSeconds = new double[n];
        largeIn = new int[n];
        largeOut = new int[n];
        structured = new int[n];
        maxBurst = new int[n];
        chainCount = new int[n];
        inLoop = new boolean[n];
        betweenness = new double[n];
        suspectInflowShare = new double[n];
        victimInflowShare = new double[n];
        signals = new double[n][Signal.values().length];
        risk = new int[n];
        level = new String[n];
        role = new String[n];
        pattern = new String[n];
        victimLoss = new long[n];
        ringOf = new int[n];
    }

    public double signal(int node, Signal s) {
        return signals[node][s.ordinal()];
    }

    public boolean isSuspicious(int node) {
        return SUSPECT.equals(role[node]) || WATCH.equals(role[node]);
    }

    // ==================================================================
    // Money trail (BFS forwards and backwards from one account)
    // ==================================================================

    /**
     * Follows the money from one account: backwards (where did it come from?)
     * and forwards (where did it go?), up to 4 hops each way.
     * We only keep expanding through suspicious accounts, so the trail stays
     * focused on the scam and does not spread over the whole city.
     */
    public TrailDto trail(String accountId) {
        Integer start = graph.indexOf(accountId);
        if (start == null) throw new IllegalArgumentException("Unknown account: " + accountId);
        List<TrailStep> steps = new ArrayList<>();
        Set<Edge> seen = new HashSet<>();
        walk(start, true, steps, seen);
        walk(start, false, steps, seen);
        steps.sort(Comparator.comparingInt(TrailStep::hop).thenComparing(s -> -s.amount()));
        return new TrailDto(accountId, steps);
    }

    private void walk(int start, boolean forward, List<TrailStep> steps, Set<Edge> seen) {
        final int maxHops = 4;
        final long minAmount = 10_000;
        Deque<int[]> queue = new ArrayDeque<>();
        Set<Integer> visited = new HashSet<>();
        queue.add(new int[]{start, 0});
        visited.add(start);
        while (!queue.isEmpty() && steps.size() < 150) {
            int[] cur = queue.poll();
            int u = cur[0];
            int depth = cur[1];
            if (depth >= maxHops) continue;
            List<Edge> next = new ArrayList<>(forward ? graph.outEdges(u) : graph.inEdges(u));
            next.sort(Comparator.comparingLong(Edge::total).reversed());
            for (Edge e : next) {
                if (e.total() < minAmount || !seen.add(e)) continue;
                int other = forward ? e.to() : e.from();
                int hop = forward ? depth + 1 : -(depth + 1);
                steps.add(new TrailStep(graph.id(e.from()), graph.id(e.to()), e.total(), e.count(), hop));
                if (visited.add(other) && isSuspicious(other)) {
                    queue.add(new int[]{other, depth + 1});
                }
            }
        }
    }

    // ==================================================================
    // "What if we block this account?"  (max-flow / min-cut)
    // ==================================================================

    private static final long INFINITE = Long.MAX_VALUE / 4;

    /**
     * Builds a flow network:
     *   SOURCE -> each victim            capacity = money the victim lost
     *   account -> account (in the ring) capacity = total money sent on that link
     *   cash-out account -> SINK         capacity = infinite
     * The maximum flow = most stolen money that can reach the cash-out accounts.
     * Blocking an account removes it from the network; the drop in max-flow is
     * the money we stop.
     */
    public BlockResult block(String accountId) {
        Integer x = graph.indexOf(accountId);
        if (x == null) throw new IllegalArgumentException("Unknown account: " + accountId);
        FlowNetwork net = flowNetwork();
        if (net == null) {
            return new BlockResult(accountId, graph.name(x), 0, 0, 0, 0, List.of(),
                    "No fraud ring was found, so there is no stolen-money flow to block.");
        }
        long before = GraphAlgorithms.maxFlow(net.capacity, 0, 1);
        Integer local = net.local.get(x);
        long after = before;
        if (local != null) after = GraphAlgorithms.maxFlow(net.without(local), 0, 1);
        long stopped = before - after;
        int percent = before == 0 ? 0 : (int) Math.round(100.0 * stopped / before);

        List<Edge> links = new ArrayList<>(graph.inEdges(x));
        links.addAll(graph.outEdges(x));
        links.sort(Comparator.comparingLong(Edge::total).reversed());
        List<String> broken = new ArrayList<>();
        for (Edge e : links) {
            if (broken.size() >= 6) break;
            if (e.total() < 10_000) continue;
            broken.add(graph.id(e.from()) + " → " + graph.id(e.to()) + " (" + Format.rupees(e.total()) + ")");
        }

        String message;
        if (local == null) {
            message = "This account is not on the path of the stolen money. Blocking it stops nothing.";
        } else if (VICTIM.equals(role[x])) {
            message = "This is a victim account. Blocking it would only stop money that has already been stolen from them.";
        } else if (percent >= 50) {
            message = "Freezing this account cuts " + percent + "% of the stolen money flow. It is a key point in the ring.";
        } else if (percent > 0) {
            message = "Freezing this account stops " + percent + "% of the flow. Money can still escape through other mules.";
        } else {
            message = "The ring has other routes around this account, so the flow is not reduced.";
        }
        return new BlockResult(accountId, graph.name(x), before, after, stopped, percent, broken, message);
    }

    /** Tries every ring member and returns the one whose blocking stops the most money. */
    public BlockResult bestBlock() {
        FlowNetwork net = flowNetwork();
        if (net == null) return null;
        long before = GraphAlgorithms.maxFlow(net.capacity, 0, 1);
        int bestNode = -1;
        long bestStopped = -1;
        for (Map.Entry<Integer, Integer> entry : net.local.entrySet()) {
            int node = entry.getKey();
            if (ringOf[node] == 0) continue;
            long stopped = before - GraphAlgorithms.maxFlow(net.without(entry.getValue()), 0, 1);
            if (stopped > bestStopped || (stopped == bestStopped && bestNode >= 0 && risk[node] > risk[bestNode])) {
                bestStopped = stopped;
                bestNode = node;
            }
        }
        return bestNode < 0 ? null : block(graph.id(bestNode));
    }

    /** Max money that can flow from all victims to cash-out accounts. */
    public long tracedMoney() {
        FlowNetwork net = flowNetwork();
        return net == null ? 0 : GraphAlgorithms.maxFlow(net.capacity, 0, 1);
    }

    private FlowNetwork flowNetwork() {
        List<Integer> members = new ArrayList<>();
        List<Integer> victims = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (ringOf[i] > 0) members.add(i);
            else if (victimLoss[i] > 0) victims.add(i);
        }
        if (members.isEmpty() || victims.isEmpty()) return null;

        // Cash-out accounts = ring members that keep most of what they receive.
        List<Integer> sinks = new ArrayList<>();
        for (int m : members) {
            if ("Cash-out account".equals(pattern[m]) || outTotal[m] <= 0.2 * inTotal[m]) sinks.add(m);
        }
        if (sinks.isEmpty()) {
            int best = members.get(0);
            for (int m : members) if (inTotal[m] - outTotal[m] > inTotal[best] - outTotal[best]) best = m;
            sinks.add(best);
        }

        Map<Integer, Integer> local = new HashMap<>();
        int size = 2;
        for (int v : victims) local.put(v, size++);
        for (int m : members) local.put(m, size++);
        long[][] cap = new long[size][size];
        for (int v : victims) cap[0][local.get(v)] = victimLoss[v];
        Set<Integer> memberSet = new HashSet<>(members);
        for (Map.Entry<Integer, Integer> entry : local.entrySet()) {
            int u = entry.getKey();
            for (Edge e : graph.outEdges(u)) {
                if (memberSet.contains(e.to())) cap[entry.getValue()][local.get(e.to())] += e.total();
            }
        }
        for (int s : sinks) cap[local.get(s)][1] = INFINITE;
        return new FlowNetwork(cap, local);
    }

    private record FlowNetwork(long[][] capacity, Map<Integer, Integer> local) {
        long[][] without(int node) {
            long[][] copy = new long[capacity.length][];
            for (int i = 0; i < capacity.length; i++) copy[i] = capacity[i].clone();
            for (int i = 0; i < copy.length; i++) {
                copy[node][i] = 0;
                copy[i][node] = 0;
            }
            return copy;
        }
    }
}
