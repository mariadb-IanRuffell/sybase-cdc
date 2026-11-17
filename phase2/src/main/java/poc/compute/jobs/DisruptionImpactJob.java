package poc.compute.jobs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.ignite.Ignite;

/**
 * The passengers of a delayed or cancelled flight, ranked by how valuable they are, with the compensation each is owed: passengers and
 * fares come from the travel data (originally Oracle), tier, loyalty multiplier and retail spend from the CRM data (originally Sybase). One
 * query joins them (all colocated on customer_id), the ranking and totals are computed here. Argument: flight_id.
 */
public class DisruptionImpactJob extends JsonJob {
    /** Share of the fare owed: 100% cancelled; delays 720+ min 75%, 360+ min 50%, 180+ min 25%, shorter none. */
    static BigDecimal share(boolean cancelled, int delayMinutes) {
        return cancelled ? BigDecimal.ONE : delayMinutes >= 720 ? new BigDecimal("0.75")
            : delayMinutes >= 360 ? new BigDecimal("0.5") : delayMinutes >= 180 ? new BigDecimal("0.25") : BigDecimal.ZERO;
    }

    /** Service priority: tier (hundreds) + cabin (tens) + retail spend band (units, 0-9 per 10,000 spent). */
    static int priority(String tier, String cabin, BigDecimal retailSpend) {
        int tierWeight = "PLATINUM".equals(tier) ? 4 : "GOLD".equals(tier) ? 3 : "SILVER".equals(tier) ? 2 : 1;
        int cabinWeight = "FIRST".equals(cabin) ? 3 : "BUSINESS".equals(cabin) ? 2 : 1;
        int spendBand = Math.min(9, retailSpend.divide(BigDecimal.valueOf(10_000), 0, RoundingMode.DOWN).intValue());
        return tierWeight * 100 + cabinWeight * 10 + spendBand;
    }

    @Override
    protected Object run(Ignite ignite, Map<String, Object> arg) {
        int flightId = intArg(arg, "flight_id");
        Map<String, BigDecimal> mult = new HashMap<>();
        for (Map<String, Object> r : Sql.rows(ignite, "SELECT tier, points_multiplier FROM loyalty_tier_rule"))
            mult.put((String) r.get("tier"), (BigDecimal) r.get("points_multiplier"));

        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> r : Sql.rows(ignite,
            "SELECT s.segment_id, c.customer_id, c.first_name, c.last_name, c.email, c.loyalty_tier, c.loyalty_points, "
                + "s.passenger_name, s.cabin, s.seat_no, s.fare, s.status AS segment_status, f.flight_no, f.status AS flight_status, "
                + "f.delay_minutes, COALESCE((SELECT SUM(o.total_amount) FROM orders o WHERE o.customer_id = c.customer_id "
                + "AND o.status = 'COMPLETED'), 0) AS retail_spend "
                + "FROM booking_segment s JOIN booking b ON b.booking_id = s.booking_id AND b.customer_id = s.customer_id "
                + "JOIN customer c ON c.customer_id = s.customer_id JOIN flight f ON f.flight_id = s.flight_id "
                + "WHERE s.flight_id = ? AND s.status IN " + Travel.ACTIVE_SEGMENTS + " AND b.status = 'CONFIRMED'", flightId)) {
            String tier = (String) r.get("loyalty_tier"), cabin = (String) r.get("cabin");
            int delay = ((Number) r.get("delay_minutes")).intValue();
            BigDecimal base = ((BigDecimal) r.get("fare")).multiply(share("CANCELLED".equals(r.get("flight_status")), delay)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal suggested = base.multiply(mult.getOrDefault(tier, BigDecimal.ONE)).setScale(2, RoundingMode.HALF_UP);
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(r);
            row.put("base_compensation", base);
            row.put("suggested_compensation", suggested);
            row.put("priority", priority(tier, cabin, (BigDecimal) r.get("retail_spend")));
            all.add(row);
        }
        all.sort(Comparator.<Map<String, Object>>comparingInt(m -> -((Number) m.get("priority")).intValue())
            .thenComparingInt(m -> ((Number) m.get("segment_id")).intValue()));

        Map<String, Object[]> byTier = new TreeMap<>();    // tier -> {passengers, base, suggested}
        BigDecimal base = BigDecimal.ZERO, suggested = BigDecimal.ZERO;
        for (Map<String, Object> m : all) {
            BigDecimal b = (BigDecimal) m.get("base_compensation"), s = (BigDecimal) m.get("suggested_compensation");
            base = base.add(b);
            suggested = suggested.add(s);
            Object[] acc = byTier.computeIfAbsent((String) m.get("loyalty_tier"), k -> new Object[] {0L, BigDecimal.ZERO, BigDecimal.ZERO});
            acc[0] = (Long) acc[0] + 1;
            acc[1] = ((BigDecimal) acc[1]).add(b);
            acc[2] = ((BigDecimal) acc[2]).add(s);
        }
        List<Object> tiers = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : byTier.entrySet()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("loyalty_tier", e.getKey());
            row.put("passengers", e.getValue()[0]);
            row.put("base_compensation", e.getValue()[1]);
            row.put("suggested_compensation", e.getValue()[2]);
            tiers.add(row);
        }
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("rc", 0);
        out.put("passengers", new ArrayList<Object>(all));
        out.put("by_tier", tiers);
        out.put("total_base_compensation", base);
        out.put("total_suggested_compensation", suggested);
        return out;
    }
}
