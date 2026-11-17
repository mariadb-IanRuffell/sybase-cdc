package poc.api.repo;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Id generator for rows created through the API. GridGain 9 has no atomic sequences, so each name is a counter row in id_seq and this process
 * takes blocks of ids from it (the compute jobs reserve their own ranges from the same rows, so nothing overlaps). Ids start at 1,000,000,000,
 * far above the values the source systems generate and the CDC readers replicate, so they cannot collide with replicated rows.
 * Order ids are NOT generated here: orders are created by the place-order job.
 */
@Component
public class Ids {
    private static final Logger log = LoggerFactory.getLogger(Ids.class);
    private static final long BASE = 1_000_000_000L;
    private static final int BLOCK = 100;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Map<String, long[]> blocks = new HashMap<>();    // name -> {next, end}

    Ids(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        // a block is taken in its own transaction, so it never holds the id_seq row while the caller's transaction runs on
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** DDL cannot run inside a transaction, so the counter table is created at start-up (the jobs create it on first use too). */
    @EventListener(ApplicationReadyEvent.class)
    void ensureTable() {
        try {
            jdbc.sql("CREATE TABLE IF NOT EXISTS id_seq (name VARCHAR(40) PRIMARY KEY, next_id BIGINT) ZONE c360_ref").update();
        } catch (RuntimeException e) {
            log.warn("id_seq not created yet (the first job that needs ids creates it): {}", e.getMessage());
        }
    }

    public synchronized int next(String name) {
        long[] b = blocks.get(name);
        if (b == null || b[0] >= b[1]) {
            b = take(name);
            blocks.put(name, b);
        }
        return (int) b[0]++;
    }

    private long[] take(String name) {
        for (int attempt = 0; ; attempt++) {
            try {
                return tx.execute(status -> {
                    Long cur = jdbc.sql("SELECT next_id FROM id_seq WHERE name = ?").param(name).query(Long.class).optional().orElse(null);
                    long start = cur == null ? BASE : cur;
                    if (cur == null) jdbc.sql("INSERT INTO id_seq (name, next_id) VALUES (?, ?)").params(name, start + BLOCK).update();
                    else jdbc.sql("UPDATE id_seq SET next_id = ? WHERE name = ?").params(start + BLOCK, name).update();
                    return new long[] {start, start + BLOCK};
                });
            } catch (RuntimeException e) {
                if (attempt >= 8) throw e;           // a job or another instance reserved ids at the same moment: try again
                try { Thread.sleep(15L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }
}
