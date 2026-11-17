package com.scamgraph.core;

import com.scamgraph.analysis.Analysis;
import com.scamgraph.analysis.Format;
import com.scamgraph.analysis.FraudAnalyzer;
import com.scamgraph.analysis.Signal;
import com.scamgraph.api.AccountDetail;
import com.scamgraph.api.AccountRow;
import com.scamgraph.api.BlockResult;
import com.scamgraph.api.ChainDto;
import com.scamgraph.api.CycleDto;
import com.scamgraph.api.EdgeDto;
import com.scamgraph.api.GraphDto;
import com.scamgraph.api.NodeDto;
import com.scamgraph.api.RingDto;
import com.scamgraph.api.SignalDto;
import com.scamgraph.api.Summary;
import com.scamgraph.api.TrailDto;
import com.scamgraph.api.TxnDto;
import com.scamgraph.api.WeightDto;
import com.scamgraph.graph.Edge;
import com.scamgraph.graph.TransactionGraph;
import com.scamgraph.model.Transaction;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Plain-Java "engine" that holds the current analysis and turns it into the
 * objects the website needs. Spring Boot only wraps this class (see AnalysisService),
 * so all the logic can be tested without starting a server.
 */
public final class ScamGraphEngine {

    private Analysis analysis;
    private Summary summary;

    public synchronized Summary analyze(List<Transaction> txns, String dataset) {
        TransactionGraph graph = new TransactionGraph(txns);
        if (graph.size() == 0) throw new IllegalArgumentException("No valid transactions to analyse.");
        Analysis a = new FraudAnalyzer().analyze(graph);
        this.analysis = a;
        this.summary = buildSummary(a, dataset);
        return summary;
    }

    public synchronized boolean hasData() {
        return analysis != null;
    }

    public synchronized Summary summary() {
        require();
        return summary;
    }

    // ------------------------------------------------------------------ graph
    public synchronized GraphDto graph() {
        Analysis a = require();
        TransactionGraph g = a.graph;
        List<NodeDto> nodes = new ArrayList<>(a.n);
        for (int i = 0; i < a.n; i++) {
            List<String> tags = new ArrayList<>();
            if (a.inLoop[i]) tags.add("loop");
            if (a.structured[i] >= 3 && a.isSuspicious(i)) tags.add("structuring");
            if (a.chainCount[i] > 0 && a.isSuspicious(i)) tags.add("chain");
            nodes.add(new NodeDto(g.id(i), g.name(i), a.risk[i], a.level[i], a.role[i], a.pattern[i],
                    a.ringOf[i] > 0 ? a.ringOf[i] : null, a.inTotal[i], a.outTotal[i],
                    g.incoming(i).size() + g.outgoing(i).size(), tags));
        }
        List<EdgeDto> edges = new ArrayList<>(g.edges().size());
        for (Edge e : g.edges()) {
            boolean suspicious = (a.isSuspicious(e.from()) || Analysis.VICTIM.equals(a.role[e.from()]))
                    && a.isSuspicious(e.to());
            edges.add(new EdgeDto(g.id(e.from()), g.id(e.to()), e.total(), e.count(), suspicious));
        }
        return new GraphDto(nodes, edges);
    }

    // ------------------------------------------------------------------ account
    public synchronized AccountDetail account(String id) {
        Analysis a = require();
        TransactionGraph g = a.graph;
        Integer i = g.indexOf(id);
        if (i == null) throw new IllegalArgumentException("Unknown account: " + id);

        List<SignalDto> signals = new ArrayList<>();
        for (Signal s : Signal.values()) {
            double v = a.signal(i, s);
            signals.add(new SignalDto(s.key(), s.label(), round2(v), s.weight(), round1(100 * s.weight() * v), explain(a, i, s)));
        }
        signals.sort(Comparator.comparingDouble(SignalDto::points).reversed());

        List<String> reasons = new ArrayList<>();
        if (a.victimLoss[i] > 0) {
            reasons.add("Sent " + Format.rupees(a.victimLoss[i]) + " to suspicious accounts. This looks like a scam victim, not a mule.");
        }
        for (SignalDto s : signals) {
            if (s.points() >= 2.5) reasons.add(s.explanation());
        }
        if (reasons.isEmpty()) reasons.add("Nothing unusual: money stays in the account for days and goes to the usual places.");

        List<Transaction> all = new ArrayList<>(g.incoming(i));
        all.addAll(g.outgoing(i));
        all.sort(Comparator.comparing(Transaction::time).reversed());
        List<TxnDto> txns = new ArrayList<>();
        for (Transaction t : all) {
            if (txns.size() >= 80) break;
            txns.add(txnDto(t, t.to().equals(id) ? "IN" : "OUT"));
        }

        return new AccountDetail(g.id(i), g.name(i), a.risk[i], a.level[i], a.role[i], a.pattern[i],
                a.ringOf[i] > 0 ? a.ringOf[i] : null, a.inTotal[i], a.outTotal[i], g.inDegree(i), g.outDegree(i),
                Format.duration(a.avgHoldSeconds[i]), (int) Math.round(100 * a.forwardedRatio[i]), a.victimLoss[i],
                signals, reasons, txns);
    }

    private String explain(Analysis a, int i, Signal s) {
        switch (s) {
            case PASS_THROUGH:
                if (a.inTotal[i] == 0) return "Received no money.";
                return "Forwarded " + Math.round(100 * a.forwardedRatio[i]) + "% of the " + Format.rupees(a.inTotal[i])
                        + " it received; money stayed only " + Format.duration(a.avgHoldSeconds[i]) + " on average.";
            case LAYERING:
                String chain = a.chainCount[i] == 0 ? "Not part of any fast money chain"
                        : "Part of " + a.chainCount[i] + " fast money chain" + (a.chainCount[i] == 1 ? "" : "s");
                return chain + (a.inLoop[i] ? ", and part of a circular money loop (round-tripping)." : ".");
            case ASSOCIATION:
                return Math.round(100 * a.suspectInflowShare[i]) + "% of its incoming money came from other suspicious accounts.";
            case HUB:
                return "Big payments (₹20,000+) from " + a.largeIn[i] + " different account" + (a.largeIn[i] == 1 ? "" : "s")
                        + " and to " + a.largeOut[i] + ".";
            case STRUCTURING:
                return a.structured[i] + " payment" + (a.structured[i] == 1 ? "" : "s")
                        + " between ₹45,000 and ₹49,999, just under the ₹50,000 mark.";
            case VELOCITY:
                return a.maxBurst[i] + " transactions inside a single hour (busiest hour).";
            case CENTRALITY:
                return "Bridge score " + String.format("%.2f", a.signal(i, Signal.CENTRALITY))
                        + " (1.00 = the account that most money paths pass through).";
            default:
                return "";
        }
    }

    /** First {@code limit} transactions in time order (the "raw data" view before analysis). */
    public synchronized List<TxnDto> transactions(int limit) {
        Analysis a = require();
        List<Transaction> all = a.graph.transactions();
        List<TxnDto> rows = new ArrayList<>();
        for (int k = 0; k < Math.min(limit, all.size()); k++) rows.add(txnDto(all.get(k), ""));
        return rows;
    }

    // ------------------------------------------------------------------ trail & block
    public synchronized TrailDto trail(String id) {
        return require().trail(id);
    }

    public synchronized BlockResult block(String id) {
        return require().block(id);
    }

    // ------------------------------------------------------------------ summary
    private Summary buildSummary(Analysis a, String dataset) {
        TransactionGraph g = a.graph;
        long totalAmount = 0;
        for (Transaction t : g.transactions()) totalAmount += t.amount();
        int suspects = 0, watch = 0, victims = 0;
        long stolen = 0;
        List<Integer> histogram = new ArrayList<>();
        for (int b = 0; b < 10; b++) histogram.add(0);
        for (int i = 0; i < a.n; i++) {
            if (Analysis.SUSPECT.equals(a.role[i])) suspects++;
            if (Analysis.WATCH.equals(a.role[i])) watch++;
            if (a.victimLoss[i] > 0) {
                victims++;
                stolen += a.victimLoss[i];
            }
            int bucket = Math.min(9, a.risk[i] / 10);
            histogram.set(bucket, histogram.get(bucket) + 1);
        }

        List<RingDto> rings = new ArrayList<>();
        for (Analysis.Ring r : a.rings) {
            List<String> members = new ArrayList<>();
            List<String> cashOut = new ArrayList<>();
            for (int m : r.members()) {
                members.add(g.id(m));
                if ("Cash-out account".equals(a.pattern[m])) cashOut.add(g.id(m));
            }
            rings.add(new RingDto(r.id(), members.size(), members, cashOut, r.victims(), r.stolen(),
                    r.internalPayments(), r.internalAmount()));
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < a.n; i++) order.add(i);
        order.sort(Comparator.comparingInt((Integer i) -> -a.risk[i]));
        List<AccountRow> top = new ArrayList<>();
        for (int i : order) {
            if (top.size() >= 25 || a.risk[i] < FraudAnalyzer.MEDIUM) break;
            top.add(new AccountRow(g.id(i), g.name(i), a.risk[i], a.level[i], a.pattern[i], topReason(a, i),
                    a.ringOf[i] > 0 ? a.ringOf[i] : null));
        }

        List<ChainDto> chains = new ArrayList<>();
        for (List<Transaction> c : a.chains) {
            if (chains.size() >= 30) break;
            List<String> accounts = new ArrayList<>();
            List<Long> amounts = new ArrayList<>();
            List<String> times = new ArrayList<>();
            accounts.add(c.get(0).from());
            for (Transaction t : c) {
                accounts.add(t.to());
                amounts.add(t.amount());
                times.add(Format.shortTime(t.time()));
            }
            long secs = Duration.between(c.get(0).time(), c.get(c.size() - 1).time()).getSeconds();
            chains.add(new ChainDto(accounts, amounts, times, c.size(), Format.duration(secs)));
        }

        List<CycleDto> cycles = new ArrayList<>();
        for (Analysis.Loop loop : a.loops) {
            List<String> ids = new ArrayList<>();
            for (int node : loop.nodes()) ids.add(g.id(node));
            ids.add(g.id(loop.nodes().get(0)));
            cycles.add(new CycleDto(ids, loop.amount(), Format.duration(loop.seconds())));
        }

        List<WeightDto> weights = new ArrayList<>();
        for (Signal s : Signal.values()) weights.add(new WeightDto(s.key(), s.label(), s.weight(), s.meaning()));

        String period = g.transactions().isEmpty() ? "" : Format.date(g.transactions().get(0).time()) + " – "
                + Format.date(g.transactions().get(g.transactions().size() - 1).time());

        return new Summary(dataset, period, a.n, g.transactions().size(), totalAmount, suspects, watch, victims,
                rings.size(), stolen, a.tracedMoney(), a.chains.size(), a.loops.size(), histogram, rings, top,
                chains, cycles, weights, a.bestBlock(), a.analysisMillis);
    }

    private String topReason(Analysis a, int i) {
        Signal best = null;
        double bestPoints = -1;
        for (Signal s : Signal.values()) {
            double p = s.weight() * a.signal(i, s);
            if (p > bestPoints) {
                bestPoints = p;
                best = s;
            }
        }
        return best == null ? "" : explain(a, i, best);
    }

    private static TxnDto txnDto(Transaction t, String direction) {
        return new TxnDto(t.id(), Format.time(t.time()), t.from(), t.fromName(), t.to(), t.toName(), t.amount(),
                t.channel(), direction);
    }

    private Analysis require() {
        if (analysis == null) throw new IllegalStateException("No data loaded yet. Upload a CSV or load the demo data first.");
        return analysis;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
