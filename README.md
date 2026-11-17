# ScamGraph

**Detecting mule-account fraud rings in bank transactions with graph theory.**

In digital arrest and UPI scams, stolen money is moved through chains of borrowed "mule" bank accounts within hours. Each account looks normal on its own; the fraud only becomes visible when all accounts are viewed together as a network. ScamGraph models transactions as a directed graph, scores every account with seven explainable detectors, exposes the fraud ring, and recommends which account to freeze first.

![Java](https://img.shields.io/badge/Java-17-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen) ![Tests](https://img.shields.io/badge/tests-11%20passing-success) ![ML](https://img.shields.io/badge/machine%20learning-none%20(explainable)-blue)

---

## Highlights

- **Graph-based detection:** 7 detectors built on classic algorithms: FIFO money ageing, dynamic programming, DFS cycle detection, Brandes betweenness centrality, Union-Find and max-flow.
- **Accurate on simulated data:** found 100% of mule accounts and victims across 8 independently generated towns (about 5,000 transactions each), with **zero false positives** on clean data.
- **Actionable output:** max-flow (Edmonds–Karp) identifies the single account whose freeze cuts **52%** of the traceable stolen money in the demo.
- **Explainable by design:** every risk score is a weighted sum of named signals, with plain-English reasons. No black-box model.
- **Fast:** full analysis of ~5,400 transactions in under 60 ms.
- **Full stack:** Spring Boot REST API, JPA + H2 persistence, and an interactive network UI with animated money trails.

## Features

| Feature | What it does |
|---|---|
| Network view | Every account as a node, every payment link as an edge; suspects, victims and cash-out accounts highlighted |
| Fraud ring view | Left-to-right flow: victims → collector mules → layering mules → cash-out |
| Account investigation | Risk score (0–100), signal breakdown, reasons, money held time, transaction history |
| Money trail | Animated hop-by-hop trace of where money came from and where it went |
| Freeze what-if | Max-flow simulation of how much stolen money a freeze would stop |
| CSV upload | Analyse any transaction file with `from_account, to_account, amount, timestamp` |
| Demo datasets | A simulated town with a hidden fraud ring, and the same town without it |

## How it works

```
CSV / demo data → REST API → JPA (H2) → Transaction graph → 7 detectors → Risk score → Rings, victims, freeze plan → Web UI
```

**Risk score = 100 × Σ (weight × signal)**, each signal between 0 and 1.

| Signal | Weight | Algorithm |
|---|---|---|
| Pass-through money | 25% | FIFO queue: how long each rupee stays before leaving |
| Money chains & loops | 20% | DP over time-ordered payments; bounded DFS for cycles |
| Money from suspects | 15% | One-step risk propagation through the graph |
| Large fan-in / fan-out | 10% | Degree counting on large payments |
| Structuring (smurfing) | 10% | Payments just below ₹50,000 |
| Burst activity | 10% | Sliding window (two pointers), money both in and out |
| Bridge position | 10% | Brandes betweenness centrality |

Suspects are grouped into rings with **Union-Find**; victims are low-risk accounts that sent large amounts to flagged accounts; **Edmonds–Karp max-flow** from victims to cash-out accounts measures traceable stolen money and ranks accounts to freeze.

Full details, formulas and complexity analysis: **[TECHNICAL_DESIGN.md](TECHNICAL_DESIGN.md)**

## Complexity

| Step | Algorithm | Time |
|---|---|---|
| Build graph | Adjacency list | O(V + E) |
| Money ageing, bursts | Queue, two pointers | O(T) |
| Money chains | DP + binary search | O(T · k log T) |
| Centrality | Brandes | O(V · E) |
| Ring grouping | Union-Find | O(E · α(V)) |
| Freeze simulation | Edmonds–Karp | O(V · E²) on the ring subgraph |

V = accounts, E = payment links, T = transactions, k = payments within the 6-hour chain window.

## Tech stack

**Backend:** Java 17, Spring Boot 3 (Web, Data JPA), H2 database
**Frontend:** HTML, CSS, JavaScript, vis-network (graph rendering), Chart.js
**Testing:** JUnit 5

## Getting started

**Requirements:** Java 17+, and VS Code (Extension Pack for Java) or IntelliJ IDEA. Internet is needed on the first run to download dependencies.

1. Clone or download the repository:
   ```
   git clone https://github.com/<username>/scamgraph.git
   ```
2. Open the `scamgraph` folder in VS Code or IntelliJ and wait for the project to load.
3. Run `src/main/java/com/scamgraph/ScamGraphApplication.java`.
4. Open **http://localhost:8080** → **Load demo town** → **Analyse with ScamGraph**.

With Maven installed you can also run `mvn spring-boot:run`, and `mvn test` for the tests.

Database console (while running): http://localhost:8080/h2-console · JDBC URL `jdbc:h2:mem:scamgraph` · user `sa` · no password.

## Using your own data

Upload a CSV with a header row. Required columns: `from_account, to_account, amount, timestamp`. Optional: `txn_id, from_name, to_name, channel`. Common alternatives (`sender`, `receiver`, `date`) and formats (`2026-10-05 14:30`, `05/10/2026 14:30`, `₹25,000`) are accepted.

Example files are in [`sample-data/`](sample-data): the demo town with a fraud ring, the same town without fraud, and a 9-row example.

## REST API

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/upload` | Upload and analyse a CSV (form field `file`) |
| POST | `/api/demo?fraud=true` | Load the demo town (`false` for the clean town) |
| GET | `/api/summary` | Totals, rings, chains, loops, top accounts, best freeze |
| GET | `/api/graph` | All nodes and edges |
| GET | `/api/account/{id}` | Full investigation of one account |
| GET | `/api/trail/{id}` | Money trail, 4 hops each way |
| GET | `/api/block/{id}` | Freeze what-if (max-flow) |
| GET | `/api/transactions?limit=200` | Raw transactions |
| GET | `/api/sample.csv` | Download the demo dataset |

## Project structure

```
src/main/java/com/scamgraph/
├── web/          REST controller
├── service/      Persistence + analysis orchestration
├── persistence/  JPA entity and repository
├── graph/        Transaction graph and core algorithms (DFS, BFS, Brandes, Union-Find, max-flow)
├── analysis/     Seven detectors, risk scoring, rings, trails, freeze simulation
├── core/         Engine that maps results to API responses
├── data/         CSV reader/writer and simulated data generator
├── api/          Response records
└── model/        Transaction model
src/main/resources/static/   Web UI
src/test/java/               Unit tests
sample-data/                 Example datasets
```

## Testing

11 unit tests cover each algorithm on small graphs with known answers (for example, the classic CLRS max-flow network = 23) and end-to-end detection on the demo data, including zero false alarms on the clean town.

## Limitations and future work

- Evaluated on **simulated data**, because real transaction data is private. Thresholds are hand-chosen and would be tuned on real cases.
- Adaptive fraudsters (slower forwarding, larger amounts) can lower their scores; additional signals such as account age, shared devices or phone numbers would help.
- Next steps: streaming analysis, a graph database for scale, approximate centrality for very large graphs, and learned thresholds once labelled data is available.

## Team

| Name | Contribution |
|---|---|
| **Virendra Charan** | Graph algorithms, fraud analysis, Spring Boot backend, database, tests |
| **Bhartik Sharma** | Data generation, interactive web UI, demo, documentation |

**Project mentor:** Dr. Rakesh Kumar

---

*All names, accounts and transactions in the demo data are randomly generated and fictional.*
