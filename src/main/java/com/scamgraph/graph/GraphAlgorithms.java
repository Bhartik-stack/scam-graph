package com.scamgraph.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Classic graph algorithms used by ScamGraph.
 * Every method works on a simple adjacency list: adj.get(u) = list of nodes that u points to.
 *
 * V = number of nodes (accounts), E = number of edges (payment links).
 */
public final class GraphAlgorithms {

    private GraphAlgorithms() {
    }

    // ------------------------------------------------------------------
    // 1. Cycle detection with DFS colouring             Time: O(V + E)
    // ------------------------------------------------------------------

    /**
     * Returns true if the directed graph has at least one cycle.
     *
     * Idea: colour every node
     *   WHITE (0) = not visited yet
     *   GREY  (1) = currently on the DFS path
     *   BLACK (2) = completely finished
     * If DFS ever reaches a GREY node, we went "back" into our own path, so there is a loop.
     * Written without recursion so very deep graphs cannot overflow the stack.
     */
    public static boolean hasCycle(List<List<Integer>> adj) {
        int n = adj.size();
        int[] colour = new int[n];
        int[] nextChild = new int[n];
        Deque<Integer> stack = new ArrayDeque<>();
        for (int start = 0; start < n; start++) {
            if (colour[start] != 0) continue;
            stack.push(start);
            colour[start] = 1;
            while (!stack.isEmpty()) {
                int u = stack.peek();
                if (nextChild[u] < adj.get(u).size()) {
                    int v = adj.get(u).get(nextChild[u]++);
                    if (colour[v] == 1) return true;          // back edge -> cycle
                    if (colour[v] == 0) {
                        colour[v] = 1;
                        stack.push(v);
                    }
                } else {
                    colour[u] = 2;
                    stack.pop();
                }
            }
        }
        return false;
    }

    /**
     * Lists simple cycles (loops that do not repeat a node) with between
     * {@code minLen} and {@code maxLen} nodes.
     *
     * To avoid reporting the same loop several times, each cycle is only
     * reported from its smallest-numbered node, and the DFS from start node s
     * only visits nodes with a number bigger than s.
     * The length limit keeps the search fast (bounded depth DFS).
     */
    public static List<List<Integer>> simpleCycles(List<List<Integer>> adj, int minLen, int maxLen, int limit) {
        List<List<Integer>> result = new ArrayList<>();
        boolean[] onPath = new boolean[adj.size()];
        List<Integer> path = new ArrayList<>();
        for (int s = 0; s < adj.size() && result.size() < limit; s++) {
            path.add(s);
            onPath[s] = true;
            cycleDfs(adj, s, s, path, onPath, minLen, maxLen, limit, result);
            onPath[s] = false;
            path.remove(path.size() - 1);
        }
        return result;
    }

    private static void cycleDfs(List<List<Integer>> adj, int start, int u, List<Integer> path, boolean[] onPath,
                                 int minLen, int maxLen, int limit, List<List<Integer>> result) {
        for (int v : adj.get(u)) {
            if (result.size() >= limit) return;
            if (v == start) {
                if (path.size() >= minLen) result.add(new ArrayList<>(path));
            } else if (v > start && !onPath[v] && path.size() < maxLen) {
                path.add(v);
                onPath[v] = true;
                cycleDfs(adj, start, v, path, onPath, minLen, maxLen, limit, result);
                onPath[v] = false;
                path.remove(path.size() - 1);
            }
        }
    }

    // ------------------------------------------------------------------
    // 2. Breadth-first search distances                  Time: O(V + E)
    // ------------------------------------------------------------------

    /** Number of hops from {@code source} to every node (-1 if unreachable). */
    public static int[] bfsDistances(List<List<Integer>> adj, int source) {
        int[] dist = new int[adj.size()];
        Arrays.fill(dist, -1);
        Deque<Integer> queue = new ArrayDeque<>();
        dist[source] = 0;
        queue.add(source);
        while (!queue.isEmpty()) {
            int u = queue.poll();
            for (int v : adj.get(u)) {
                if (dist[v] < 0) {
                    dist[v] = dist[u] + 1;
                    queue.add(v);
                }
            }
        }
        return dist;
    }

    // ------------------------------------------------------------------
    // 3. Betweenness centrality (Brandes' algorithm)      Time: O(V * E)
    // ------------------------------------------------------------------

    /**
     * Betweenness of node v = sum over all pairs (s, t) of
     *        (number of shortest s->t paths that pass through v) / (number of shortest s->t paths)
     *
     * Accounts with high betweenness are "bridges": money has to pass through them.
     * Brandes' algorithm runs one BFS per node and then adds up "dependencies"
     * from the farthest nodes back towards the source.
     */
    public static double[] betweenness(List<List<Integer>> adj) {
        int n = adj.size();
        double[] cb = new double[n];
        int[] dist = new int[n];
        double[] sigma = new double[n];
        double[] delta = new double[n];
        List<List<Integer>> pred = new ArrayList<>(n);
        for (int i = 0; i < n; i++) pred.add(new ArrayList<>());

        for (int s = 0; s < n; s++) {
            Deque<Integer> order = new ArrayDeque<>();   // nodes in order of discovery (used as a stack)
            Deque<Integer> queue = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                pred.get(i).clear();
                dist[i] = -1;
                sigma[i] = 0;
                delta[i] = 0;
            }
            dist[s] = 0;
            sigma[s] = 1;
            queue.add(s);
            while (!queue.isEmpty()) {
                int v = queue.poll();
                order.push(v);
                for (int w : adj.get(v)) {
                    if (dist[w] < 0) {                     // first time we see w
                        dist[w] = dist[v] + 1;
                        queue.add(w);
                    }
                    if (dist[w] == dist[v] + 1) {          // v is on a shortest path to w
                        sigma[w] += sigma[v];
                        pred.get(w).add(v);
                    }
                }
            }
            while (!order.isEmpty()) {                     // farthest nodes first
                int w = order.pop();
                for (int v : pred.get(w)) {
                    delta[v] += (sigma[v] / sigma[w]) * (1 + delta[w]);
                }
                if (w != s) cb[w] += delta[w];
            }
        }
        return cb;
    }

    // ------------------------------------------------------------------
    // 4. Union-Find (Disjoint Set Union)        Time: almost O(1) per step
    // ------------------------------------------------------------------

    /**
     * Groups nodes into connected groups. Used to find fraud rings:
     * suspicious accounts that are connected to each other end up in the same group.
     * Uses "path compression" and "union by size" so it is very fast.
     */
    public static final class UnionFind {
        private final int[] parent;
        private final int[] size;

        public UnionFind(int n) {
            parent = new int[n];
            size = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
                size[i] = 1;
            }
        }

        public int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];   // path compression
                x = parent[x];
            }
            return x;
        }

        public void union(int a, int b) {
            int ra = find(a);
            int rb = find(b);
            if (ra == rb) return;
            if (size[ra] < size[rb]) {
                int tmp = ra;
                ra = rb;
                rb = tmp;
            }
            parent[rb] = ra;
            size[ra] += size[rb];
        }

        public int sizeOf(int x) {
            return size[find(x)];
        }
    }

    // ------------------------------------------------------------------
    // 5. Maximum flow (Edmonds-Karp)                    Time: O(V * E^2)
    // ------------------------------------------------------------------

    /**
     * Computes the maximum amount of money that can flow from {@code source} to {@code sink}.
     * capacity[u][v] = most money that can move from u to v.
     *
     * Edmonds-Karp: repeatedly find the SHORTEST path (BFS) that still has spare capacity,
     * push as much money as possible along it, and stop when no path is left.
     * By the max-flow min-cut theorem, the answer equals the smallest total capacity
     * we must cut to separate source from sink.
     */
    public static long maxFlow(long[][] capacity, int source, int sink) {
        int n = capacity.length;
        long[][] residual = new long[n][];
        for (int i = 0; i < n; i++) residual[i] = capacity[i].clone();
        long flow = 0;
        int[] parent = new int[n];
        while (true) {
            Arrays.fill(parent, -1);
            parent[source] = source;
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(source);
            while (!queue.isEmpty() && parent[sink] == -1) {
                int u = queue.poll();
                for (int v = 0; v < n; v++) {
                    if (parent[v] == -1 && residual[u][v] > 0) {
                        parent[v] = u;
                        queue.add(v);
                    }
                }
            }
            if (parent[sink] == -1) break;                  // no more paths
            long push = Long.MAX_VALUE;
            for (int v = sink; v != source; v = parent[v]) {
                push = Math.min(push, residual[parent[v]][v]);
            }
            for (int v = sink; v != source; v = parent[v]) {
                residual[parent[v]][v] -= push;
                residual[v][parent[v]] += push;
            }
            flow += push;
        }
        return flow;
    }
}
