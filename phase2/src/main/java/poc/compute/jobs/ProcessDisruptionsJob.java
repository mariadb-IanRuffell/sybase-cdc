package poc.compute.jobs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.ignite.Ignite;
import org.apache.ignite.table.RecordView;
import org.apache.ignite.table.Tuple;

/**
 * Replaces the Oracle procedure sp_process_disruptions (a cursor over every affected segment): one set-based query finds the segments of
 * confirmed bookings on cancelled or long-delayed flights and the compensation rows are upserted in one batch.
 * Rule: cancelled flight 100% of the fare; delay >= 720 min 75%, >= 360 min 50%, otherwise 25%. Considered: confirmed bookings, segments
 * TICKETED/CHECKED_IN/FLOWN, flights CANCELLED or (DELAYED/ARRIVED/DEPARTED with delay >= min_delay).
 * Arguments: min_delay (default 180), as_of (epoch millis, default now). Result: {reason, segments, total_amount} per reason.
 */
public class ProcessDisruptionsJob extends JsonJob {
    /** Compensation reason code: cancelled flight, delay of 720+ / 360+ minutes, otherwise a short delay. */
    static String reason(boolean cancelled, int delayMinutes) {
        return cancelled ? "CANCELLED" : delayMinutes >= 720 ? "DELAY_720" : delayMinutes >= 360 ? "DELAY_360" : "DELAY_SHORT";
    }

    /** Share of the fare compensated: 100% cancelled, 75% / 50% for long delays, 25% otherwise. */
    static BigDecimal share(boolean cancelled, int delayMinutes) {
        return cancelled ? BigDecimal.ONE : delayMinutes >= 720 ? new BigDecimal("0.75") : delayMinutes >= 360 ? new BigDecimal("0.5") : new BigDecimal("0.25");
    }

    @Override
    protected Object run(Ignite ignite, Map<String, Object> arg) {
        int minDelay = arg.get("min_delay") == null ? 180 : intArg(arg, "min_delay");
        LocalDateTime asOf = arg.get("as_of") == null ? Loyalty.now()
            : LocalDateTime.ofInstant(Instant.ofEpochMilli(((Number) arg.get("as_of")).longValue()), ZoneOffset.UTC);

        RecordView<Tuple> compensation = ignite.tables().table("COMPENSATION").recordView();
        List<Tuple> batch = new ArrayList<>();
        Map<String, Object[]> byReason = new TreeMap<>();   // reason -> {segments, total}
        for (Map<String, Object> s : Sql.rows(ignite,
            "SELECT s.segment_id, s.customer_id, s.fare, f.status AS fstatus, f.delay_minutes FROM booking_segment s "
                + "JOIN booking b ON b.booking_id = s.booking_id AND b.customer_id = s.customer_id AND b.status = 'CONFIRMED' "
                + "JOIN flight f ON f.flight_id = s.flight_id "
                + "WHERE s.status IN ('TICKETED', 'CHECKED_IN', 'FLOWN') AND (f.status = 'CANCELLED' OR "
                + "(f.status IN ('DELAYED', 'ARRIVED', 'DEPARTED') AND f.delay_minutes >= ?)) ORDER BY s.segment_id", minDelay)) {
            boolean cancelled = "CANCELLED".equals(s.get("fstatus"));
            int delay = ((Number) s.get("delay_minutes")).intValue();
            String reason = reason(cancelled, delay);
            BigDecimal amount = ((BigDecimal) s.get("fare")).multiply(share(cancelled, delay)).setScale(2, RoundingMode.HALF_UP);
            batch.add(Tuple.create().set("SEGMENT_ID", s.get("segment_id")).set("CUSTOMER_ID", s.get("customer_id")).set("AMOUNT", amount)
                .set("REASON", reason).set("CREATED_AT", asOf));
            Object[] acc = byReason.computeIfAbsent(reason, k -> new Object[] {0L, BigDecimal.ZERO});
            acc[0] = (Long) acc[0] + 1;
            acc[1] = ((BigDecimal) acc[1]).add(amount);
        }
        if (!batch.isEmpty()) compensation.upsertAll(Sql.CURRENT.get(), batch);

        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Object[]> e : byReason.entrySet()) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            row.put("reason", e.getKey());
            row.put("segments", e.getValue()[0]);
            row.put("total_amount", e.getValue()[1]);
            out.add(row);
        }
        return out;
    }
}
