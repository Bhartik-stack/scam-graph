package com.scamgraph.analysis;

import com.scamgraph.graph.Edge;
import com.scamgraph.graph.GraphAlgorithms;
import com.scamgraph.graph.TransactionGraph;
import com.scamgraph.model.Transaction;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs every detection step on a transaction graph and produces an {@link Analysis}.
 *
 * Steps:
 *  1. Money totals per account
 *  2. FIFO money ageing        -> pass-through signal
 *  3. Large-payment degrees    -> hub signal
 *  4. Structuring count        -> smurfing signal
 *  5. 60-minute sliding window -> velocity signal
 *  6. Money chains (DP on time-ordered payments) + loops (bounded DFS) -> layering signal
 *  7. Brandes betweenness      -> centrality signal
 *  8. First risk score, then risk propagation -> association signal
 *  9. Victims, patterns, fraud rings (Union-Find)
 * No machine learning: every number can be explained.
 */
public final class FraudAnalyzer {

    // ---- settings (all in one place so they are easy to explain and change) ----
    public static final long LARGE_AMOUNT = 20_000;            // "big" payment
    public static final long STRUCTURING_FLOOR = 45_000;       // just below the
    public static final long REPORTING_LIMIT = 50_000;         // Rs 50,000 mark
    public static final long CHAIN_MIN_AMOUNT = 10_000;        // ignore tiny payments in chains
    public static final Duration CHAIN_MAX_GAP = Duration.ofHours(6);
    public static final double CHAIN_MAX_GROWTH = 1.02;        // next hop can't be much bigger
    public static final double CHAIN_MIN_SHARE = 0.30;         // next hop carries at least 30%
    public static final int CHAIN_MIN_HOPS = 3;                // 3 hops = 4 accounts
    public static final long LOOP_MIN_EDGE = 10_000;
    public static final Duration LOOP_MAX_SPAN = Duration.ofDays(7);
    public static final long CENTRALITY_MIN_EDGE = 10_000;
    public static final double FAST_HOURS = 2;                 // money leaving within 2h = fully suspicious
    public static final double SLOW_HOURS = 72;                // money staying 3 days+  = normal
    public static final int HIGH = 45;                         // risk >= 45 -> suspect
    public static final int MEDIUM = 35;                       // risk >= 35 -> watchlist
    public static final int PROPAGATION_THRESHOLD = 35;        // first-pass score treated as "suspicious source"
    public static final long VICTIM_MIN_LOSS = 25_000;

    public Analysis analyze(TransactionGraph g) {
        long started = System.nanoTime();
        Analysis a = new Analysis(g);
        int n = a.n;

        moneyTotals(g, a);
        for (int i = 0; i < n; i++) {
            fifoAgeing(g, a, i);
            largeDegrees(g, a, i);
            structuringAndBurst(g, a, i);
        }
        findChains(g, a);
        findLoops(g, a);
        double[] bc = GraphAlgorithms.betweenness(g.adjacency(CENTRALITY_MIN_EDGE));
        System.arraycopy(bc, 0, a.betweenness, 0, n);
        double maxBc = 0;
        for (double v : bc) maxBc = Math.max(maxBc, v);

        // ---- signals that depend only on the account itself ----
        for (int i = 0; i < n; i++) {
            double[] s = a.signals[i];
            s[Signal.PASS_THROUGH.ordinal()] = a.inTotal[i] >= LARGE_AMOUNT
                    ? a.forwardedRatio[i] * holdFactor(a.avgHoldSeconds[i]) : 0;
            s[Signal.LAYERING.ordinal()] = Math.max(Format.clamp01(a.chainCount[i] / 3.0), a.inLoop[i] ? 1 : 0);
            s[Signal.HUB.ordinal()] = Format.clamp01((a.largeIn[i] + a.largeOut[i] - 2) / 8.0);
            s[Signal.STRUCTURING.ordinal()] = Format.clamp01(a.structured[i] / 4.0);
            s[Signal.VELOCITY.ordinal()] = Format.clamp01((a.maxBurst[i] - 3) / 5.0);
            s[Signal.CENTRALITY.ordinal()] = maxBc > 0 ? bc[i] / maxBc : 0;
        }

        // ---- first pass score (without association) ----
        double[] firstPass = new double[n];
        for (int i = 0; i < n; i++) firstPass[i] = score(a.signals[i]);

        // ---- risk propagation: how much incoming money came from suspicious accounts ----
        for (int i = 0; i < n; i++) {
            if (a.inTotal[i] == 0) continue;
            long fromSuspects = 0;
            for (Edge e : g.inEdges(i)) {
                if (firstPass[e.from()] >= PROPAGATION_THRESHOLD) fromSuspects += e.total();
            }
            a.suspectInflowShare[i] = (double) fromSuspects / a.inTotal[i];
            a.signals[i][Signal.ASSOCIATION.ordinal()] = a.suspectInflowShare[i];
        }

        // ---- final score, level, role ----
        for (int i = 0; i < n; i++) {
            a.risk[i] = (int) Math.round(Math.min(100, score(a.signals[i])));
            a.level[i] = a.risk[i] >= HIGH ? "HIGH" : a.risk[i] >= MEDIUM ? "MEDIUM" : "LOW";
            a.role[i] = a.risk[i] >= HIGH ? Analysis.SUSPECT : a.risk[i] >= MEDIUM ? Analysis.WATCH : Analysis.NORMAL;
        }

        findVictims(g, a);
        assignPatterns(g, a);
        findRings(g, a);
        a.analysisMillis = (System.nanoTime() - started) / 1_000_000;
        return a;
    }

    /** Risk = 100 x sum(weight x value). */
    static double score(double[] s) {
        double total = 0;
        for (Signal sig : Signal.values()) total += sig.weight() * s[sig.ordinal()];
        return 100 * total;
    }

    /** 1.0 if money left within 2 hours, falling in a straight line to 0 at 72 hours. */
    static double holdFactor(double seconds) {
        if (Double.isNaN(seconds)) return 0;
        double hours = seconds / 3600.0;
        if (hours <= FAST_HOURS) return 1;
        if (hours >= SLOW_HOURS) return 0;
        return 1 - (hours - FAST_HOURS) / (SLOW_HOURS - FAST_HOURS);
    }

    private static long epoch(LocalDateTime t) {
        return t.toEpochSecond(ZoneOffset.UTC);
    }

    // ------------------------------------------------------------------
    private void moneyTotals(TransactionGraph g, Analysis a) {
        for (Transaction t : g.transactions()) {
            a.outTotal[g.indexOf(t.from())] += t.amount();
            a.inTotal[g.indexOf(t.to())] += t.amount();
        }
    }

    /**
     * FIFO money ageing.
     * Think of the account as a queue of "money packets". Incoming money joins the back of
     * the queue; outgoing money is taken from the front (oldest money first).
     * For every rupee that leaves, we know how long it stayed. The amount-weighted average
     * of those times is the holding time. Mules hold money for minutes, normal people for days.
     */
    private void fifoAgeing(TransactionGraph g, Analysis a, int i) {
        List<Transaction> events = mergeByTime(g, i);
        String id = g.id(i);
        ArrayDeque<long[]> queue = new ArrayDeque<>();   // {amount left, time received}
        long matched = 0;
        double weightedSeconds = 0;
        for (Transaction t : events) {
            long when = epoch(t.time());
            if (t.to().equals(id)) {
                queue.addLast(new long[]{t.amount(), when});
            } else {
                long remaining = t.amount();
                while (remaining > 0 && !queue.isEmpty()) {
                    long[] lot = queue.peekFirst();
                    long take = Math.min(lot[0], remaining);
                    weightedSeconds += (double) take * (when - lot[1]);
                    matched += take;
                    lot[0] -= take;
                    remaining -= take;
                    if (lot[0] == 0) queue.pollFirst();
                }
            }
        }
        a.forwardedRatio[i] = a.inTotal[i] > 0 ? (double) matched / a.inTotal[i] : 0;
        a.avgHoldSeconds[i] = matched > 0 ? weightedSeconds / matched : Double.NaN;
    }

    /** Incoming and outgoing payments of one account in time order (incoming first on ties). */
    private static List<Transaction> mergeByTime(TransactionGraph g, int i) {
        List<Transaction> in = g.incoming(i);
        List<Transaction> out = g.outgoing(i);
        List<Transaction> all = new ArrayList<>(in.size() + out.size());
        int x = 0, y = 0;
        while (x < in.size() || y < out.size()) {
            if (y >= out.size() || (x < in.size() && !in.get(x).time().isAfter(out.get(y).time()))) {
                all.add(in.get(x++));
            } else {
                all.add(out.get(y++));
            }
        }
        return all;
    }

    private void largeDegrees(TransactionGraph g, Analysis a, int i) {
        Set<String> senders = new HashSet<>();
        for (Transaction t : g.incoming(i)) if (t.amount() >= LARGE_AMOUNT) senders.add(t.from());
        Set<String> receivers = new HashSet<>();
        for (Transaction t : g.outgoing(i)) if (t.amount() >= LARGE_AMOUNT) receivers.add(t.to());
        a.largeIn[i] = senders.size();
        a.largeOut[i] = receivers.size();
    }

    /**
     * Counts structured payments and finds the busiest 60-minute window (two-pointer sliding window).
     * A window only counts if money both CAME IN and WENT OUT inside it. This way a company paying
     * all salaries in one hour (outgoing only) is not treated like a mule passing money through.
     */
    private void structuringAndBurst(TransactionGraph g, Analysis a, int i) {
        List<Transaction> events = mergeByTime(g, i);
        String id = g.id(i);
        int structured = 0;
        for (Transaction t : events) {
            if (t.amount() >= STRUCTURING_FLOOR && t.amount() < REPORTING_LIMIT) structured++;
        }
        int best = 0;
        int left = 0;
        int ins = 0;
        int outs = 0;
        for (int right = 0; right < events.size(); right++) {
            Transaction r = events.get(right);
            if (r.to().equals(id)) ins++;
            else outs++;
            long rt = epoch(r.time());
            while (rt - epoch(events.get(left).time()) > 3600) {
                if (events.get(left).to().equals(id)) ins--;
                else outs--;
                left++;
            }
            if (ins > 0 && outs > 0) best = Math.max(best, right - left + 1);
        }
        a.structured[i] = structured;
        a.maxBurst[i] = best;
    }

    /**
     * Money chains with dynamic programming.
     * Payment P can be followed by payment Q if Q leaves P's receiver within 6 hours,
     * and Q's amount is between 30% and 102% of P's amount (money is split or a commission is cut).
     *
     *   longest[P] = 1 + max(longest[Q]) over all valid next payments Q
     *
     * Payments are processed from latest to earliest, so every longest[Q] is ready when needed.
     * Chains with 3 or more hops (4+ accounts) are reported.
     */
    private void findChains(TransactionGraph g, Analysis a) {
        List<Transaction> txs = g.transactions();
        int m = txs.size();
        Map<Transaction, Integer> position = new IdentityHashMap<>();
        for (int k = 0; k < m; k++) position.put(txs.get(k), k);
        int[] longest = new int[m];
        int[] next = new int[m];
        java.util.Arrays.fill(next, -1);

        for (int k = m - 1; k >= 0; k--) {
            Transaction p = txs.get(k);
            if (p.amount() < CHAIN_MIN_AMOUNT) continue;
            longest[k] = 1;
            List<Transaction> outs = g.outgoing(g.indexOf(p.to()));
            LocalDateTime limit = p.time().plus(CHAIN_MAX_GAP);
            for (int j = firstAfter(outs, p.time()); j < outs.size(); j++) {
                Transaction q = outs.get(j);
                if (q.time().isAfter(limit)) break;
                if (q.amount() < CHAIN_MIN_AMOUNT) continue;
                if (q.amount() > p.amount() * CHAIN_MAX_GROWTH || q.amount() < p.amount() * CHAIN_MIN_SHARE) continue;
                int qi = position.get(q);
                boolean better = longest[qi] + 1 > longest[k]
                        || (longest[qi] + 1 == longest[k] && next[k] >= 0 && q.amount() > txs.get(next[k]).amount());
                if (better) {
                    longest[k] = longest[qi] + 1;
                    next[k] = qi;
                }
            }
        }

        boolean[] isContinuation = new boolean[m];
        for (int k = 0; k < m; k++) {
            if (longest[k] >= CHAIN_MIN_HOPS && next[k] >= 0) isContinuation[next[k]] = true;
        }
        Set<String> seen = new HashSet<>();
        List<List<Transaction>> found = new ArrayList<>();
        for (int k = 0; k < m; k++) {
            if (longest[k] < CHAIN_MIN_HOPS || isContinuation[k]) continue;
            List<Transaction> chain = new ArrayList<>();
            StringBuilder key = new StringBuilder();
            for (int c = k; c >= 0; c = next[c]) {
                chain.add(txs.get(c));
                key.append(txs.get(c).from()).append('>');
            }
            key.append(chain.get(chain.size() - 1).to());
            if (!seen.add(key.toString())) continue;
            found.add(chain);
            Set<Integer> nodes = new HashSet<>();
            for (Transaction t : chain) {
                nodes.add(g.indexOf(t.from()));
                nodes.add(g.indexOf(t.to()));
            }
            // the first sender (often the victim) is not counted as a chain member
            nodes.remove(g.indexOf(chain.get(0).from()));
            for (int node : nodes) a.chainCount[node]++;
        }
        found.sort(Comparator.comparingInt((List<Transaction> c) -> -c.size())
                .thenComparingLong(c -> -c.get(0).amount()));
        a.chains.addAll(found);
    }

    /** Binary search: index of the first payment strictly after {@code time}. */
    private static int firstAfter(List<Transaction> list, LocalDateTime time) {
        int lo = 0, hi = list.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).time().isAfter(time)) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /**
     * Circular loops: bounded DFS finds candidate cycles (3 to 6 accounts) among links of Rs 10,000+.
     * Then we check time order: there must be payments going around the loop one after another,
     * finishing within 7 days. That is "round-tripping": money sent in a circle to fake business.
     */
    private void findLoops(TransactionGraph g, Analysis a) {
        List<List<Integer>> candidates = GraphAlgorithms.simpleCycles(g.adjacency(LOOP_MIN_EDGE), 3, 6, 300);
        for (List<Integer> cycle : candidates) {
            // try every rotation's first payment; keep the loop that moved the most money
            Analysis.Loop bestLoop = null;
            for (int r = 0; r < cycle.size(); r++) {
                List<Integer> rotated = new ArrayList<>(cycle.subList(r, cycle.size()));
                rotated.addAll(cycle.subList(0, r));
                Analysis.Loop found = loopFrom(g, rotated);
                if (found != null && (bestLoop == null || found.amount() > bestLoop.amount())) bestLoop = found;
            }
            if (bestLoop != null) {
                a.loops.add(bestLoop);
                for (int node : cycle) a.inLoop[node] = true;
            }
        }
        a.loops.sort(Comparator.comparingLong((Analysis.Loop l) -> -l.amount()));
    }

    /** Biggest time-ordered trip around the loop that starts on its first link and ends within 7 days. */
    private Analysis.Loop loopFrom(TransactionGraph g, List<Integer> cycle) {
        Analysis.Loop best = null;
        Edge firstEdge = g.edge(cycle.get(0), cycle.get(1));
        for (Transaction start : firstEdge.transactions()) {
            if (start.amount() < LOOP_MIN_EDGE) continue;
            LocalDateTime current = start.time();
            long previous = start.amount();
            boolean ok = true;
            for (int k = 1; k < cycle.size() && ok; k++) {
                Edge e = g.edge(cycle.get(k), cycle.get((k + 1) % cycle.size()));
                Transaction step = null;
                for (Transaction t : e.transactions()) {
                    // same rule as chains: the next payment comes later and carries 30%-102% of the money
                    if (t.time().isAfter(current) && t.amount() >= LOOP_MIN_EDGE
                            && t.amount() <= previous * CHAIN_MAX_GROWTH && t.amount() >= previous * CHAIN_MIN_SHARE) {
                        step = t;
                        break;
                    }
                }
                if (step == null) {
                    ok = false;
                } else {
                    current = step.time();
                    previous = step.amount();
                }
            }
            long seconds = Duration.between(start.time(), current).getSeconds();
            if (ok && seconds <= LOOP_MAX_SPAN.getSeconds() && (best == null || start.amount() > best.amount())) {
                best = new Analysis.Loop(new ArrayList<>(cycle), start.amount(), seconds);
            }
        }
        return best;
    }

    /** A victim is a normal-looking account that sent big money (₹25,000+) to flagged accounts. */
    private void findVictims(TransactionGraph g, Analysis a) {
        for (int i = 0; i < a.n; i++) {
            if (a.risk[i] >= MEDIUM) continue;
            long sent = 0;
            for (Edge e : g.outEdges(i)) {
                if (a.risk[e.to()] >= MEDIUM) sent += e.total();
            }
            if (sent >= VICTIM_MIN_LOSS) {
                a.victimLoss[i] = sent;
                a.role[i] = Analysis.VICTIM;
            }
        }
        for (int i = 0; i < a.n; i++) {
            if (a.inTotal[i] == 0) continue;
            long fromVictims = 0;
            for (Edge e : g.inEdges(i)) if (a.victimLoss[e.from()] > 0) fromVictims += e.total();
            a.victimInflowShare[i] = (double) fromVictims / a.inTotal[i];
        }
    }

    private void assignPatterns(TransactionGraph g, Analysis a) {
        for (int i = 0; i < a.n; i++) {
            if (Analysis.VICTIM.equals(a.role[i])) {
                a.pattern[i] = "Likely victim";
            } else if (!a.isSuspicious(i)) {
                a.pattern[i] = "Normal";
            } else if (a.victimInflowShare[i] >= 0.5) {
                a.pattern[i] = "Collector mule";
            } else if (a.outTotal[i] <= 0.2 * a.inTotal[i] && a.suspectInflowShare[i] >= 0.5) {
                a.pattern[i] = "Cash-out account";
            } else if (a.forwardedRatio[i] >= 0.6 && a.avgHoldSeconds[i] <= 24 * 3600) {
                a.pattern[i] = "Layering mule";
            } else {
                a.pattern[i] = "Suspicious account";
            }
        }
    }

    /**
     * Fraud rings with Union-Find: join every pair of suspicious accounts that paid each other.
     * Each group of 3 or more accounts is a ring.
     */
    private void findRings(TransactionGraph g, Analysis a) {
        GraphAlgorithms.UnionFind uf = new GraphAlgorithms.UnionFind(a.n);
        for (Edge e : g.edges()) {
            if (a.isSuspicious(e.from()) && a.isSuspicious(e.to())) uf.union(e.from(), e.to());
        }
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < a.n; i++) {
            if (a.isSuspicious(i) && uf.sizeOf(i) >= 3) groups.computeIfAbsent(uf.find(i), k -> new ArrayList<>()).add(i);
        }
        List<List<Integer>> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparingInt((List<Integer> grp) -> -grp.stream().mapToInt(i -> a.risk[i]).sum()));
        int id = 1;
        for (List<Integer> members : ordered) {
            members.sort(Comparator.comparingInt((Integer i) -> -a.risk[i]));
            Set<Integer> memberSet = new HashSet<>(members);
            Set<Integer> victims = new HashSet<>();
            long stolen = 0;
            int internalPayments = 0;
            long internalAmount = 0;
            for (int m : members) {
                a.ringOf[m] = id;
                for (Edge e : g.inEdges(m)) {
                    if (a.victimLoss[e.from()] > 0) {
                        victims.add(e.from());
                        stolen += e.total();
                    } else if (memberSet.contains(e.from())) {
                        internalPayments += e.count();
                        internalAmount += e.total();
                    }
                }
            }
            a.rings.add(new Analysis.Ring(id, members, victims.size(), stolen, internalPayments, internalAmount));
            id++;
        }
    }
}
