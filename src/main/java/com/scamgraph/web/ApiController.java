package com.scamgraph.web;

import com.scamgraph.api.AccountDetail;
import com.scamgraph.api.BlockResult;
import com.scamgraph.api.GraphDto;
import com.scamgraph.api.Summary;
import com.scamgraph.api.TrailDto;
import com.scamgraph.api.TxnDto;
import com.scamgraph.service.AnalysisService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST API used by the website.
 *
 *   POST /api/upload            upload a CSV file of transactions
 *   POST /api/demo?fraud=true   load the built-in demo town
 *   GET  /api/sample.csv        download the demo data as CSV
 *   GET  /api/status            is data loaded?
 *   GET  /api/summary           numbers, rings, chains, loops, top accounts
 *   GET  /api/graph             all nodes and edges for drawing
 *   GET  /api/transactions      first rows of the raw data
 *   GET  /api/account/{id}      full investigation of one account
 *   GET  /api/trail/{id}        money trail around one account
 *   GET  /api/block/{id}        "what if we freeze this account?" (max-flow)
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final AnalysisService service;

    public ApiController(AnalysisService service) {
        this.service = service;
    }

    @PostMapping("/upload")
    public Summary upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("Please choose a CSV file.");
        return service.loadCsv(file.getInputStream(), file.getOriginalFilename());
    }

    @PostMapping("/demo")
    public Summary demo(@RequestParam(defaultValue = "true") boolean fraud) {
        return service.loadDemo(fraud);
    }

    @GetMapping("/sample.csv")
    public ResponseEntity<String> sampleCsv(@RequestParam(defaultValue = "true") boolean fraud) {
        String name = fraud ? "scamgraph_demo_transactions.csv" : "scamgraph_clean_transactions.csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(service.demoCsv(fraud));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("loaded", service.hasData(), "storedTransactions", service.storedCount());
    }

    @GetMapping("/summary")
    public Summary summary() {
        return service.summary();
    }

    @GetMapping("/graph")
    public GraphDto graph() {
        return service.graph();
    }

    @GetMapping("/transactions")
    public List<TxnDto> transactions(@RequestParam(defaultValue = "200") int limit) {
        return service.transactions(Math.max(1, Math.min(limit, 2000)));
    }

    @GetMapping("/account/{id}")
    public AccountDetail account(@PathVariable String id) {
        return service.account(id);
    }

    @GetMapping("/trail/{id}")
    public TrailDto trail(@PathVariable String id) {
        return service.trail(id);
    }

    @GetMapping("/block/{id}")
    public BlockResult block(@PathVariable String id) {
        return service.block(id);
    }

    // ---- errors are returned as simple JSON messages ----
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> notReady(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
