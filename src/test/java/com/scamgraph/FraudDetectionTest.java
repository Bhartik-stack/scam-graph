package com.scamgraph;

import com.scamgraph.api.BlockResult;
import com.scamgraph.api.NodeDto;
import com.scamgraph.api.Summary;
import com.scamgraph.core.ScamGraphEngine;
import com.scamgraph.data.CsvIO;
import com.scamgraph.data.SampleDataGenerator;
import com.scamgraph.model.Transaction;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end checks on the generated demo town. */
class FraudDetectionTest {

    @Test
    void findsTheHiddenRingInDemoData() {
        ScamGraphEngine engine = new ScamGraphEngine();
        Summary s = engine.analyze(new SampleDataGenerator(2026).generate(true), "test");
        assertEquals(1, s.rings(), "exactly one fraud ring");
        assertEquals(15, s.ringList().get(0).size(), "3 collectors + 6 layering + 4 structuring + 2 cash-out");
        assertEquals(22, s.victims(), "all 22 victims identified");
        assertTrue(s.cycleCount() >= 1, "the round-tripping loop is found");
        assertTrue(s.moneyTraced() > 0.8 * s.moneyStolen(), "most stolen money is traced to cash-out");
        assertNotNull(s.bestBlock());
    }

    @Test
    void noFalseAlarmsOnCleanData() {
        ScamGraphEngine engine = new ScamGraphEngine();
        Summary s = engine.analyze(new SampleDataGenerator(2026).generate(false), "clean");
        assertEquals(0, s.rings());
        assertEquals(0, s.suspects());
        for (NodeDto n : engine.graph().nodes()) assertTrue(n.risk() < 35, n.name() + " should not be flagged");
    }

    @Test
    void blockingAnAccountReducesFlow() {
        ScamGraphEngine engine = new ScamGraphEngine();
        Summary s = engine.analyze(new SampleDataGenerator(2026).generate(true), "test");
        BlockResult best = s.bestBlock();
        assertTrue(best.after() < best.before());
        assertEquals(best.before() - best.after(), best.stopped());
    }

    @Test
    void csvRoundTrip() throws Exception {
        List<Transaction> original = new SampleDataGenerator(1).generate(true);
        String csv = CsvIO.write(original);
        List<Transaction> back = CsvIO.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        assertEquals(original.size(), back.size());
        assertEquals(original.get(10), back.get(10));
    }

    @Test
    void csvAcceptsQuotesAndOtherColumnNames() throws Exception {
        String csv = "Sender,Receiver,Amount,Date\n"
                + "A1,B1,\"25,000\",2026-10-05 10:15\n"
                + "B1,C1,24500,05/10/2026 10:40\n";
        List<Transaction> rows = CsvIO.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, rows.size());
        assertEquals(25_000, rows.get(0).amount());
    }
}
