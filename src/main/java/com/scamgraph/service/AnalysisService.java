package com.scamgraph.service;

import com.scamgraph.api.AccountDetail;
import com.scamgraph.api.BlockResult;
import com.scamgraph.api.GraphDto;
import com.scamgraph.api.Summary;
import com.scamgraph.api.TrailDto;
import com.scamgraph.api.TxnDto;
import com.scamgraph.core.ScamGraphEngine;
import com.scamgraph.data.CsvIO;
import com.scamgraph.data.SampleDataGenerator;
import com.scamgraph.model.Transaction;
import com.scamgraph.persistence.TransactionRecord;
import com.scamgraph.persistence.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Business layer:
 *  1. saves uploaded transactions in the database,
 *  2. reads them back,
 *  3. runs the graph analysis (ScamGraphEngine) and keeps the result in memory.
 */
@Service
public class AnalysisService {

    public static final long DEMO_SEED = 2026;

    private final TransactionRepository repository;
    private final ScamGraphEngine engine = new ScamGraphEngine();

    public AnalysisService(TransactionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Summary loadCsv(InputStream csv, String fileName) throws IOException {
        return store(CsvIO.read(csv), fileName);
    }

    @Transactional
    public Summary loadDemo(boolean withFraud) {
        List<Transaction> txns = new SampleDataGenerator(DEMO_SEED).generate(withFraud);
        return store(txns, withFraud ? "Demo town (with hidden fraud ring)" : "Demo town (clean, no fraud)");
    }

    public String demoCsv(boolean withFraud) {
        return CsvIO.write(new SampleDataGenerator(DEMO_SEED).generate(withFraud));
    }

    private Summary store(List<Transaction> txns, String dataset) {
        repository.deleteAllInBatch();
        repository.saveAll(txns.stream().map(TransactionRecord::from).toList());
        List<Transaction> fromDb = repository.findAll().stream().map(TransactionRecord::toTransaction).toList();
        return engine.analyze(fromDb, dataset);
    }

    public boolean hasData() { return engine.hasData(); }
    public long storedCount() { return repository.count(); }
    public Summary summary() { return engine.summary(); }
    public GraphDto graph() { return engine.graph(); }
    public AccountDetail account(String id) { return engine.account(id); }
    public TrailDto trail(String id) { return engine.trail(id); }
    public List<TxnDto> transactions(int limit) { return engine.transactions(limit); }
    public BlockResult block(String id) { return engine.block(id); }
}
