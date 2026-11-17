package poc.compute.jobs;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.ignite.Ignite;

/**
 * Ids for rows created in GridGain. GridGain 9 has no atomic sequences, so each name is a counter row in id_seq and a job reserves the ids it
 * needs in one small transaction (a job runs in a fresh class loader, so nothing can be cached between calls). Ids start at 1,000,000,000, far
 * above the values the source systems generate and the CDC readers replicate, so they cannot collide. Ids left unused when a job rolls back are gaps.
 */
final class Ids {
    static final long BASE = 1_000_000_000L;

    private Ids() {}

    static void ensureTable(Ignite ignite) {
        Sql.exec(ignite, "CREATE TABLE IF NOT EXISTS id_seq (name VARCHAR(40) PRIMARY KEY, next_id BIGINT) ZONE c360_ref");
    }

    /** Reserve {@code counts.get(name)} ids for each name; returns the first id of each range. */
    static Map<String, Integer> take(Ignite ignite, Map<String, Integer> counts) {
        ensureTable(ignite);
        for (int attempt = 0; ; attempt++) {
            try {
                Map<String, Integer> first = new LinkedHashMap<>();
                ignite.transactions().runInTransaction(tx -> {
                    for (Map.Entry<String, Integer> e : counts.entrySet()) {
                        Object cur = Sql.scalar(ignite, tx, "SELECT next_id FROM id_seq WHERE name = ?", e.getKey());
                        long start = cur == null ? BASE : ((Number) cur).longValue();
                        if (cur == null) Sql.exec(ignite, tx, "INSERT INTO id_seq (name, next_id) VALUES (?, ?)", e.getKey(), start + e.getValue());
                        else Sql.exec(ignite, tx, "UPDATE id_seq SET next_id = ? WHERE name = ?", start + e.getValue(), e.getKey());
                        first.put(e.getKey(), (int) start);
                    }
                });
                return first;
            } catch (RuntimeException e) {
                if (attempt >= 8) throw e;            // another job reserved ids at the same moment: try again
                try { Thread.sleep(15L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    static int one(Ignite ignite, String name) {
        return take(ignite, Map.of(name, 1)).get(name);
    }

    static Map<String, Integer> need(Object... nameCount) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < nameCount.length; i += 2) m.put((String) nameCount[i], (Integer) nameCount[i + 1]);
        return m;
    }
}
