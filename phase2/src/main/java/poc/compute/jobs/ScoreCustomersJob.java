package poc.compute.jobs;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.ignite.Ignite;
import org.apache.ignite.table.RecordView;
import org.apache.ignite.table.Tuple;

/**
 * Replaces sp_score_customers. The stored procedure walks every customer with a cursor and runs three queries per row. Here three
 * set-based aggregate queries (one pass each) provide the inputs for every active customer at once, the score is computed in memory, and the
 * results are upserted into customer_score through the key-value API in one batch. Argument: as_of (epoch millis, optional; default now).
 * Result: one row per risk band {risk_band, customers, avg_score}, with the integer average the procedure gives.
 */
public class ScoreCustomersJob extends JsonJob {
    /** Churn score 0..100: recency (0-60) + 12-month order count (0-30) + open cases (0-10) + negative sentiment (10). */
    static int churnScore(long recencyDays, long orders12m, long openCases, double avgSentiment) {
        int score = (recencyDays <= 90 ? 0 : recencyDays <= 180 ? 20 : recencyDays <= 365 ? 40 : 60)
            + (orders12m >= 4 ? 0 : orders12m >= 2 ? 10 : orders12m == 1 ? 20 : 30)
            + (int) Math.min(openCases * 5, 10)
            + (avgSentiment < 0 ? 10 : 0);
        return Math.min(score, 100);
    }

    static String riskBand(int score) {
        return score < 30 ? "LOW" : score < 60 ? "MEDIUM" : "HIGH";
    }

    @Override
    protected Object run(Ignite ignite, Map<String, Object> arg) {
        LocalDateTime asOf = arg.get("as_of") == null ? Loyalty.now()
            : LocalDateTime.ofInstant(Instant.ofEpochMilli(((Number) arg.get("as_of")).longValue()), ZoneOffset.UTC);
        LocalDateTime cutoff = asOf.minusDays(365);

        Map<Integer, LocalDateTime> lastOrder = new HashMap<>();
        Map<Integer, Long> orders12m = new HashMap<>();
        for (Map<String, Object> r : Sql.rows(ignite,
            "SELECT customer_id, MAX(order_date) AS last_order, SUM(CASE WHEN order_date >= ? THEN 1 ELSE 0 END) AS orders_12m "
                + "FROM orders WHERE status = 'COMPLETED' AND order_date <= ? GROUP BY customer_id", cutoff, asOf)) {
            lastOrder.put((Integer) r.get("customer_id"), (LocalDateTime) r.get("last_order"));
            orders12m.put((Integer) r.get("customer_id"), ((Number) r.get("orders_12m")).longValue());
        }
        Map<Integer, Long> openCases = new HashMap<>();
        for (Map<String, Object> r : Sql.rows(ignite, "SELECT customer_id, COUNT(*) AS n FROM interaction WHERE status = 'OPEN' GROUP BY customer_id"))
            openCases.put((Integer) r.get("customer_id"), ((Number) r.get("n")).longValue());
        Map<Integer, Double> avgSentiment = new HashMap<>();
        for (Map<String, Object> r : Sql.rows(ignite,
            "SELECT customer_id, AVG(CAST(sentiment AS DOUBLE)) AS s FROM interaction WHERE opened_at >= ? GROUP BY customer_id", cutoff))
            avgSentiment.put((Integer) r.get("customer_id"), ((Number) r.get("s")).doubleValue());

        RecordView<Tuple> scores = ignite.tables().table("CUSTOMER_SCORE").recordView();
        List<Tuple> batch = new ArrayList<>();
        Map<String, long[]> bands = new TreeMap<>();   // band -> {customers, score sum}
        for (Map<String, Object> c : Sql.rows(ignite, "SELECT customer_id FROM customer WHERE status = 'ACTIVE' ORDER BY customer_id")) {
            int cid = (Integer) c.get("customer_id");
            LocalDateTime last = lastOrder.get(cid);
            // calendar-day boundaries crossed (like DATEDIFF in the procedure), not elapsed 24h periods
            long recency = last == null ? 9999 : ChronoUnit.DAYS.between(last.toLocalDate(), asOf.toLocalDate());
            int score = churnScore(recency, orders12m.getOrDefault(cid, 0L), openCases.getOrDefault(cid, 0L), avgSentiment.getOrDefault(cid, 0.0));
            String band = riskBand(score);
            batch.add(Tuple.create().set("CUSTOMER_ID", cid).set("CHURN_SCORE", score).set("RISK_BAND", band).set("SCORED_AT", asOf));
            long[] acc = bands.computeIfAbsent(band, k -> new long[2]);
            acc[0]++;
            acc[1] += score;
        }
        if (!batch.isEmpty()) scores.upsertAll(Sql.CURRENT.get(), batch);

        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : bands.entrySet()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("risk_band", e.getKey());
            row.put("customers", e.getValue()[0]);
            row.put("avg_score", e.getValue()[1] / e.getValue()[0]);
            out.add(row);
        }
        return out;
    }
}
