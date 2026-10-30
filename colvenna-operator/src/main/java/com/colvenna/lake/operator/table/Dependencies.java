package com.colvenna.lake.operator.table;

import com.colvenna.lake.operator.catalog.Owners;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which tables a layered table may read, and whether its declaration closes a cycle.
 *
 * <p>A namespace is the boundary: a LakeTable reads the ODS tables of the namespace's LakeSources
 * and the targets of its other LakeTables, nothing else. Whoever can declare tables in a namespace
 * can otherwise copy any catalog table into one they can read.
 */
final class Dependencies {
    private Dependencies() {}

    /** Why the upstream table may not be read, or null when it may. */
    static String refusal(String namespace, String table, String databaseOwner, String tableOwner) {
        if (Owners.isSourceOf(databaseOwner, namespace)
                || Owners.isTableOf(tableOwner, namespace)) {
            return null;
        }
        return table
                + " is neither an ODS table of a LakeSource nor a LakeTable target in namespace "
                + namespace;
    }

    /** One table's edges: its target and the tables it reads. */
    record Node(String target, Collection<String> upstream) {}

    /**
     * The cycle {@code self} would close, as the chain of tables it reads from its target back to
     * it ({@code [a, b, a]}: a reads b, which reads a), or an empty list. Edges are the tables the
     * other declarations read.
     */
    static List<String> cycle(Node self, Collection<Node> others) {
        Map<String, Node> byTarget = new HashMap<>();
        for (Node n : others) {
            byTarget.put(n.target(), n);
        }
        // A breadth-first walk upstream; each table remembers the one that first reached it.
        Map<String, String> readBy = new HashMap<>();
        Deque<String> todo = new ArrayDeque<>(self.upstream());
        Set<String> discovered = new HashSet<>(self.upstream());
        while (!todo.isEmpty()) {
            String table = todo.poll();
            if (table.equals(self.target())) {
                List<String> chain = new ArrayList<>();
                for (String t = table; t != null; t = readBy.get(t)) {
                    chain.add(0, t);
                }
                chain.add(0, self.target());
                return chain;
            }
            Node producer = byTarget.get(table);
            if (producer != null) {
                for (String u : producer.upstream()) {
                    if (discovered.add(u)) {
                        readBy.put(u, table);
                        todo.add(u);
                    }
                }
            }
        }
        return List.of();
    }
}
