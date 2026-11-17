# ScamGraph: catching mule-account fraud rings with graph theory

## Team

| Name | Roll No. | Role |
|---|---|---|
| Virendra Charan | [Roll No.] | Algorithms, maths & backend (Spring Boot, database, tests) |
| Bhartik Sharma | [Roll No.] | Data, website, demo & documentation |

**Guide:** Dr. Rakesh Kumar · **Department:** [Department], [College] · **Semester:** [Semester], 2026

Scam money (digital arrest, UPI fraud, fake jobs) is moved through chains of borrowed **"mule" bank accounts** within hours. One account alone looks normal. **All accounts together as a network** reveal the pattern.

ScamGraph turns bank transactions into a graph, runs 7 explainable detectors (no machine learning), and shows the fraud ring lighting up on an interactive network.

**Tech stack:** Java 17 + Spring Boot 3 (REST API) · Spring Data JPA + H2 database · HTML, CSS, JavaScript · vis-network (graph drawing) · Chart.js

---

## 1. Install (once)

1. **Java 17 or newer** (JDK, not only JRE). Check in a terminal: `java -version`
   Download if needed: Eclipse Temurin 17 or 21 (adoptium.net).
2. **IntelliJ IDEA Community** (free). It already includes Maven, so you don't need to install Maven separately.
3. **Internet for the first run only.** Maven downloads Spring Boot and H2 (about 50 MB). After that it works offline, including the website (graph libraries are inside the project).

## 2. Run it

### Option A: IntelliJ (easiest)
1. Unzip the project.
2. IntelliJ → **File → Open** → choose the `scamgraph` folder (the one with `pom.xml`) → **Trust project**.
3. Wait until the bottom bar finishes "Downloading / Indexing" (first time: 2–5 minutes).
4. Open `src/main/java/com/scamgraph/ScamGraphApplication.java` → click the green ▶ next to `main` → **Run**.
5. When the console shows `Started ScamGraphApplication`, open **http://localhost:8080**

### Option B: Terminal (if Maven is installed)
```
cd scamgraph
mvn spring-boot:run
```
Then open http://localhost:8080

### Run the tests
IntelliJ: right-click the `src/test/java` folder → **Run 'All Tests'**. Terminal: `mvn test`. All 11 tests should pass.

### See the database
While the app runs, open http://localhost:8080/h2-console
JDBC URL `jdbc:h2:mem:scamgraph`, user `sa`, empty password → Connect → `SELECT * FROM TRANSACTIONS;`

---

## 3. Demo script (about 4 minutes)

1. **Landing page.** "Scammers hide stolen money in a web of accounts. We untangle the web."
2. Click **Load demo town**. A table of **5,381 transactions** between **319 accounts** appears. Ask the panel: *"Somewhere in here is a fraud ring. Can you spot it?"* (Nobody can.)
3. Click **Analyse with ScamGraph**. The detectors tick off one by one, the network appears grey, then the **15 mule accounts turn red, 22 victims turn blue**, and the view zooms into the ring.
4. Point at the KPIs: 15 suspects, 22 victims, ₹41.55 L stolen, ₹38.8 L traced to cash-out.
5. Click **Fraud ring** (top-left of the graph): victims on the left → collector mules → layering mules → cash-out accounts on the right.
6. Click a red account (e.g. the top one in "Most suspicious accounts"): show the **risk score breakdown**, the reasons in plain English, and "money stays 3 hours".
7. Click **▶ Animate money trail**: the money moves hop by hop in gold.
8. Go to Overview → **Best account to freeze** → Investigate → **❄ Freeze account (what-if)**: max-flow shows how much stolen money is stopped.
9. Click **Clean town** → Analyse: **zero suspects**. "No false alarms on honest data."
10. Optional: **How it works** tab shows the formula and all algorithms.

Tips: on a washed-out projector click **Light mode**. Practise the demo 2–3 times. Keep a screen recording as backup.

---

## 4. Upload your own data

CSV with a header row. Required: `from_account, to_account, amount, timestamp`. Optional: `txn_id, from_name, to_name, channel`.
Column names are matched loosely (`sender`, `receiver`, `date` also work). Dates like `2026-10-05 14:30:00`, `2026-10-05 14:30`, `05/10/2026 14:30` work. Amounts like `25,000` or `₹25000` are fine.

Ready-made files in `sample-data/`:
- `demo_with_fraud_ring.csv`: the demo town (same as the button)
- `clean_no_fraud.csv`: the same town without the scam
- `tiny_example.csv`: 9 rows, good for explaining the format

---

## 5. Project structure

```
src/main/java/com/scamgraph/
  ScamGraphApplication.java      starts Spring Boot
  web/ApiController.java         REST endpoints (/api/...)
  service/AnalysisService.java   saves to DB, reads back, runs the analysis
  persistence/                   JPA entity (table "transactions") + repository
  model/Transaction.java         one payment
  graph/TransactionGraph.java    builds the graph (adjacency list)
  graph/GraphAlgorithms.java     cycle detection, BFS, Brandes centrality, Union-Find, max-flow
  analysis/FraudAnalyzer.java    the 7 detectors + risk score + victims + rings
  analysis/Analysis.java         results, money trail, "freeze" what-if (max-flow)
  analysis/Signal.java           the 7 signals and their weights
  core/ScamGraphEngine.java      turns results into what the website shows
  data/SampleDataGenerator.java  creates the fake town with the hidden ring
  data/CsvIO.java                reads and writes CSV files
  api/                           response objects (JSON)
src/main/resources/static/       the website (index.html, styles.css, app.js, lib/)
src/test/java/                   unit tests
sample-data/                     CSV files
TECHNICAL_DESIGN.md              graph model, detectors, algorithms, complexity, results
```

## 6. REST API

| Method | URL | What it does |
|---|---|---|
| POST | `/api/upload` (form field `file`) | upload a CSV and analyse it |
| POST | `/api/demo?fraud=true` | load the demo town (`false` = clean town) |
| GET | `/api/sample.csv` | download demo data |
| GET | `/api/status` | is data loaded? |
| GET | `/api/summary` | numbers, ring, chains, loops, top accounts, best freeze |
| GET | `/api/graph` | all nodes and edges |
| GET | `/api/transactions?limit=200` | raw rows |
| GET | `/api/account/{id}` | full investigation of one account |
| GET | `/api/trail/{id}` | money trail (4 hops each way) |
| GET | `/api/block/{id}` | what-if: freeze this account (max-flow) |

## 7. Team split (2 people)

- **Person 1, backend & algorithms:** `graph/`, `analysis/`, `web/`, `service/`, `persistence/`, tests. Must explain: adjacency list, DFS cycles, Brandes, Union-Find, max-flow, FIFO ageing, DP chains, risk formula.
- **Person 2, frontend, data & presentation:** `static/`, `data/`, `sample-data/`, report, slides, demo. Must explain: the scam story, how the demo data is built, what each screen shows, the risk score in simple words.
- **Both:** the overall flow (upload → DB → graph → detectors → score → ring → website) and the demo.

## 8. Troubleshooting

- **"Port 8080 already in use"**: close the other running copy, or add `server.port=8081` in `src/main/resources/application.properties`.
- **Red errors in IntelliJ right after opening**: Maven is still downloading. Wait, or open the Maven panel (right side) → click ⟳ Reload.
- **"Unsupported class file" / Java errors**: IntelliJ → File → Project Structure → SDK = Java 17 or 21.
- **₹ shows as a strange character**: File → Settings → Editor → File Encodings → set all to UTF-8.
- **Graph looks empty**: click **Fit view**.

All demo names, accounts and transactions are randomly generated and fictional.
