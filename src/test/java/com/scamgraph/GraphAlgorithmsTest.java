package com.scamgraph;

import com.scamgraph.graph.GraphAlgorithms;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks each algorithm on small graphs where we know the right answer by hand. */
class GraphAlgorithmsTest {

    /** Builds an adjacency list from pairs: edges(4, 0,1, 1,2) = 4 nodes with 0->1 and 1->2. */
    static List<List<Integer>> edges(int n, int... pairs) {
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        for (int k = 0; k < pairs.length; k += 2) adj.get(pairs[k]).add(pairs[k + 1]);
        return adj;
    }

    @Test
    void cycleDetection() {
        assertFalse(GraphAlgorithms.hasCycle(edges(4, 0, 1, 1, 2, 2, 3)));          // straight line
        assertTrue(GraphAlgorithms.hasCycle(edges(4, 0, 1, 1, 2, 2, 0)));           // triangle loop
        assertFalse(GraphAlgorithms.hasCycle(edges(4, 0, 1, 0, 2, 1, 3, 2, 3)));    // diamond, no loop
    }

    @Test
    void simpleCyclesAreFoundOnce() {
        // two loops: 0->1->2->0 and 2->3->4->2
        List<List<Integer>> adj = edges(5, 0, 1, 1, 2, 2, 0, 2, 3, 3, 4, 4, 2);
        List<List<Integer>> cycles = GraphAlgorithms.simpleCycles(adj, 3, 6, 100);
        assertEquals(2, cycles.size());
    }

    @Test
    void bfsDistances() {
        int[] d = GraphAlgorithms.bfsDistances(edges(5, 0, 1, 1, 2, 0, 3), 0);
        assertEquals(0, d[0]);
        assertEquals(1, d[1]);
        assertEquals(2, d[2]);
        assertEquals(1, d[3]);
        assertEquals(-1, d[4]);   // node 4 cannot be reached
    }

    @Test
    void betweennessOfMiddleNode() {
        // 0 -> 1 -> 2 : only the pair (0,2) passes through node 1
        double[] bc = GraphAlgorithms.betweenness(edges(3, 0, 1, 1, 2));
        assertEquals(0.0, bc[0], 1e-9);
        assertEquals(1.0, bc[1], 1e-9);
        assertEquals(0.0, bc[2], 1e-9);

        // star: 0,1,2 all send to hub 3, hub sends to 4 and 5 -> 3 x 2 = 6 paths go through the hub
        double[] star = GraphAlgorithms.betweenness(edges(6, 0, 3, 1, 3, 2, 3, 3, 4, 3, 5));
        assertEquals(6.0, star[3], 1e-9);
    }

    @Test
    void maxFlowTextbookExample() {
        // classic CLRS example network, answer = 23
        long[][] c = new long[6][6];
        c[0][1] = 16; c[0][2] = 13; c[1][3] = 12; c[2][1] = 4; c[2][4] = 14;
        c[3][2] = 9; c[3][5] = 20; c[4][3] = 7; c[4][5] = 4;
        assertEquals(23, GraphAlgorithms.maxFlow(c, 0, 5));
    }

    @Test
    void unionFindGroups() {
        GraphAlgorithms.UnionFind uf = new GraphAlgorithms.UnionFind(6);
        uf.union(0, 1);
        uf.union(1, 2);
        uf.union(4, 5);
        assertEquals(uf.find(0), uf.find(2));
        assertEquals(3, uf.sizeOf(1));
        assertEquals(2, uf.sizeOf(5));
        assertEquals(1, uf.sizeOf(3));
    }
}
