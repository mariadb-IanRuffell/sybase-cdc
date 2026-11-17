package poc.compute.jobs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.Ignite;
import org.apache.ignite.sql.ResultSet;
import org.apache.ignite.sql.SqlRow;
import org.apache.ignite.tx.Transaction;

/** Tiny SQL helpers for jobs (the default schema is PUBLIC). Pass a transaction to take part in it, or null for an implicit one. */
final class Sql {
    /** The transaction a job runs in (see JsonJob): statements with no explicit transaction join it. */
    static final ThreadLocal<Transaction> CURRENT = new ThreadLocal<>();

    private Sql() {}

    private static Transaction tx(Transaction tx) {
        return tx != null ? tx : CURRENT.get();
    }

    /** Rows as column-name -> value maps (names lower-cased, so alias every expression). */
    static List<LinkedHashMap<String, Object>> rows(Ignite ignite, Transaction tx, String sql, Object... args) {
        try (ResultSet<SqlRow> rs = ignite.sql().execute(tx(tx), sql, args)) {
            List<LinkedHashMap<String, Object>> out = new ArrayList<>();
            while (rs.hasNext()) {
                SqlRow r = rs.next();
                LinkedHashMap<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < r.columnCount(); i++) m.put(r.columnName(i).toLowerCase(), r.value(i));
                out.add(m);
            }
            return out;
        }
    }

    static List<LinkedHashMap<String, Object>> rows(Ignite ignite, String sql, Object... args) {
        return rows(ignite, null, sql, args);
    }

    static Object scalar(Ignite ignite, Transaction tx, String sql, Object... args) {
        try (ResultSet<SqlRow> rs = ignite.sql().execute(tx(tx), sql, args)) {
            return rs.hasNext() ? rs.next().value(0) : null;
        }
    }

    static Object scalar(Ignite ignite, String sql, Object... args) {
        return scalar(ignite, null, sql, args);
    }

    /** Run a statement that changes data; returns the number of rows affected. */
    static long exec(Ignite ignite, Transaction tx, String sql, Object... args) {
        try (ResultSet<SqlRow> rs = ignite.sql().execute(tx(tx), sql, args)) {
            return Math.max(0, rs.affectedRows());
        }
    }

    static long exec(Ignite ignite, String sql, Object... args) {
        return exec(ignite, null, sql, args);
    }

    /** Make a result row's values JSON-friendly copies (LinkedHashMap with the same keys). */
    static Map<String, Object> copy(Map<String, Object> m) {
        return new LinkedHashMap<>(m);
    }
}
