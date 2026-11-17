# ScamGraph: Technical Design

How ScamGraph detects mule-account fraud rings: the graph model, the seven detectors, the algorithms behind them, their complexity, and measured results.

---

## 1. The problem (30-second version)

In a "digital arrest" scam, fraudsters pretend to be police/CBI and scare people into transferring money. The money goes to a **mule account** (a real person's account, rented out for a commission). Within minutes it is split and passed through more mule accounts, kept just under ₹50,000 per payment, sent round in loops to confuse investigators, and finally cashed out (crypto, gold, cash).

**Key insight:** one mule account looks almost normal. The fraud is in the **connections**. So we treat the data as a **graph**.

## 2. The graph

- **Node** = a bank account. **Directed edge** u → v = money sent from u to v (all payments combined; the edge stores total amount and count).
- Stored as an **adjacency list**: for each node, a list of the nodes it pays. Memory O(V + E). An adjacency matrix would need V² cells (319² ≈ 1 lakh cells for 2,887 edges: mostly empty).
- Demo town: **V = 319 accounts, E = 2,887 edges, 5,381 transactions.**

## 3. The 7 signals (each gives a value from 0 to 1)

**Risk score = 100 × Σ (weightᵢ × signalᵢ)**. Weights add up to 1, so risk is between 0 and 100.
Suspect ≥ 45 · Watchlist 35–44 · Normal < 35.

| # | Signal | Weight | How it is calculated |
|---|---|---|---|
| 1 | Pass-through money | 25% | forwarded ratio × hold factor (FIFO money ageing) |
| 2 | Money chains & loops | 20% | min(1, chains through account ÷ 3); = 1 if in a loop |
| 3 | Money from suspects | 15% | (money received from suspicious accounts) ÷ (all money received) |
| 4 | Large fan-in / fan-out | 10% | clamp((big senders + big receivers − 2) ÷ 8), "big" = ₹20,000+ |
| 5 | Split payments (smurfing) | 10% | min(1, payments between ₹45,000 and ₹49,999 ÷ 4) |
| 6 | Burst activity | 10% | clamp((max transactions in one hour − 3) ÷ 5), only windows with money both in and out |
| 7 | Bridge position | 10% | betweenness centrality ÷ highest betweenness |

`clamp(x)` means "cut to the range 0 to 1".

### 3.1 FIFO money ageing (pass-through)
Imagine the account as a **queue** (first in, first out). Incoming money joins the back; outgoing money is taken from the front. For every rupee that leaves we know how long it stayed.

```
average holding time = Σ (amount × time it stayed) ÷ Σ amount
forwarded ratio      = money that left ÷ money that came in
hold factor          = 1 if ≤ 2 hours, 0 if ≥ 72 hours, straight line in between
                     = 1 − (hours − 2) ÷ 70
pass-through signal  = forwarded ratio × hold factor
```
**Example:** a mule receives ₹7.24 L and forwards 98% of it; money stayed 3.1 hours on average → hold factor = 1 − (3.1 − 2) ÷ 70 = 0.984 → signal ≈ 0.98 × 0.984 = 0.96 → contributes 0.25 × 0.96 × 100 = **24 points**.
A salaried person: salary arrives on the 1st, spent over the month → money stays ~2 weeks → hold factor 0 → **0 points**. A shop: takings paid to the supplier weekly → ~3–4 days → 0 points.

### 3.2 Money chains (dynamic programming)
Payment P can be followed by payment Q if Q leaves P's receiver **within 6 hours** and carries **30%–102%** of P's amount (money gets split, a commission is cut).
```
longest[P] = 1 + max(longest[Q])  over all valid next payments Q
```
Payments are processed **from latest to earliest**, so each longest[Q] is already known: classic DP, like "longest path in a DAG". Time order makes it acyclic. A chain with ≥ 3 hops (4 accounts) is reported. Each step uses binary search to find payments after time t.
**Demo:** 63 chains. Example: victim → collector (₹1.96 L) → layering mule (₹66,800) → structuring mule (₹47,600) → cash-out (₹47,700) in 3 hours.

### 3.3 Loops (DFS)
- **Cycle detection with colours** (white / grey / black): if DFS reaches a grey node (still on the current path), there is a back edge → a cycle. O(V + E).
- To **list** loops of length 3–6 we use a bounded DFS from each start node s, visiting only nodes numbered higher than s, so every cycle is found exactly once.
- Then a **time check**: payments must go round the loop one after another, within 7 days, with the same 30%–102% amount rule.
**Demo:** ₹1.8 L went round 4 mule accounts in 16 hours (round-tripping).

### 3.4 Betweenness centrality (Brandes' algorithm)
```
betweenness(v) = Σ over pairs (s, t) of  σst(v) ÷ σst
```
σst = number of shortest paths from s to t, σst(v) = how many of them pass through v.
Naive method: O(V³). **Brandes:** one BFS per node, then add "dependencies" from the farthest nodes back:
```
δ(v) += (σ(v) ÷ σ(w)) × (1 + δ(w))     for each predecessor v of w
```
Total O(V × E). Computed on links of ₹10,000+ so tiny shop payments don't count.
Hand example (unit test): in 0 → 1 → 2, node 1 has betweenness 1. In a star where 3 accounts pay a hub that pays 2 accounts, the hub has 3 × 2 = 6.

### 3.5 Sliding window (burst)
Sort the account's payments by time, two pointers (left, right). Move right forward; while the window is longer than 60 minutes move left forward. Track the biggest window that has money **both in and out**. O(n) per account.
Why "both in and out": a company paying 25 salaries in one hour is normal (only out); a mule receiving and forwarding within minutes is not.

### 3.6 Risk propagation (association)
First compute a score without signal 3. Then for each account: what share of its incoming money came from accounts that already scored ≥ 35? This is "guilt by association" spreading one step through the graph (the same idea as PageRank-style propagation). It catches **cash-out accounts**, which don't forward money but receive 100% from mules.

### 3.7 Victims
An account with low risk (< 35) that sent ₹25,000+ to flagged accounts is a **likely victim**, not a mule. Demo: all 22 victims found.

### 3.8 Fraud rings (Union-Find)
For every edge between two flagged accounts: `union(u, v)`. Groups with ≥ 3 accounts are rings.
Union-Find uses **path compression** and **union by size**: almost O(1) per operation (inverse Ackermann α(n)).
**Demo:** 1 ring of 15 accounts: 3 collector, 10 layering, 2 cash-out.

### 3.9 "Which account should police freeze?" (max-flow / min-cut)
Flow network: SOURCE → each victim (capacity = money lost) → ring accounts (capacity = money sent on each link) → cash-out accounts → SINK.
**Max-flow (Edmonds–Karp):** repeatedly find the shortest augmenting path with BFS and push money along it. O(V × E²).
Max-flow = most stolen money that can reach cash-out (**₹38.8 L of ₹41.55 L**).
"Freeze" = remove a node and recompute. The account whose removal drops the flow most is the best to freeze. By the **max-flow min-cut theorem**, max flow = capacity of the smallest cut separating victims from cash-out.
**Demo:** freezing one cash-out account cuts 52% of the flow.

## 4. Complexity summary

| Step | Algorithm | Time |
|---|---|---|
| Build graph | adjacency list | O(V + E) |
| FIFO ageing, bursts | queue, two pointers | O(T) total (T = transactions) |
| Chains | DP + binary search | O(T × k log T), k = payments in 6h window |
| Loops | bounded DFS (length ≤ 6) | fast in practice; exponential worst case, so length is capped |
| Centrality | Brandes | O(V × E) |
| Rings | Union-Find | O(E × α(V)) |
| Freeze what-if | Edmonds–Karp | O(V × E²) on the small ring network |

Whole demo analysis: about **30–60 milliseconds**.

## 5. Results on simulated data

- 8 different random towns, each with a hidden ring of 15 mules + 22 victims: **ring fully found every time**, all victims found.
- Clean towns (no scam): **0 suspects, 0 rings**. Highest score of any honest account: 30 (threshold 35).
- Unit tests check every algorithm on small graphs with known answers (e.g. textbook max-flow = 23).

## 6. Limitations

- Data is **simulated**: real bank data is private. The patterns are based on how mule networks are publicly described (fan-in, fast pass-through, structuring, layering, cash-out).
- Thresholds (₹20,000, 6 hours, 45 points…) are **chosen by us**, not learned. A bank would tune them on real data.
- Criminals who adapt (slower forwarding, bigger amounts) can lower their scores. Combining more signals (device, location, account age) would help.
- A legitimate business that receives and quickly forwards money (e.g. a payment aggregator) could score high. That is why the score comes with **reasons**: a human investigator decides.

## 7. Future scope

- Live stream of transactions (score updates in real time).
- Extra data: account age, device/IP, KYC address, phone numbers shared between accounts.
- Learn thresholds from labelled past cases; add ML later as a second opinion.
- Connect to the national cybercrime reporting flow so frozen-account requests can be raised quickly.
