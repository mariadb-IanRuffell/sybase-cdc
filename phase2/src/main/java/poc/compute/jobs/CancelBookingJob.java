package poc.compute.jobs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.Ignite;

/**
 * Replaces the Oracle procedure sp_cancel_booking: cancel every future segment, release the seats and work out the refund
 * (more than 72 h before departure 90%, more than 24 h 50%, otherwise nothing), all in one SQL transaction.
 * Argument: booking_id. Errors as the procedure: 20110 not found, 20111 not active, 20112 nothing cancellable.
 */
public class CancelBookingJob extends JsonJob {
    @Override
    protected boolean transactional() {
        return false;      // manages its own transaction
    }

    @Override
    protected Object run(Ignite ignite, Map<String, Object> arg) {
        int bookingId = intArg(arg, "booking_id");
        LocalDateTime now = Loyalty.now();

        return ignite.transactions().runInTransaction(tx -> {
            List<LinkedHashMap<String, Object>> b = Sql.rows(ignite, tx, "SELECT customer_id, status, refunded FROM booking WHERE booking_id = ?", bookingId);
            if (b.isEmpty()) return fail(-1, 20110, "Booking not found");
            int cid = ((Number) b.get(0).get("customer_id")).intValue();
            if (!"CONFIRMED".equals(b.get(0).get("status"))) return fail(-1, 20111, "Booking is not active");

            // all segments of the booking, so we know whether any live one remains afterwards
            List<LinkedHashMap<String, Object>> all = Sql.rows(ignite, tx,
                "SELECT s.segment_id, s.flight_id, s.cabin, s.fare, s.status, f.dep_time FROM booking_segment s "
                    + "JOIN flight f ON f.flight_id = s.flight_id WHERE s.booking_id = ? AND s.customer_id = ? ORDER BY s.segment_id", bookingId, cid);
            BigDecimal refund = BigDecimal.ZERO;
            int cancelled = 0, stillLive = 0;
            for (Map<String, Object> s : all) {
                String st = (String) s.get("status");
                LocalDateTime dep = (LocalDateTime) s.get("dep_time");
                boolean cancellable = ("TICKETED".equals(st) || "CHECKED_IN".equals(st)) && dep.isAfter(now);
                if (!cancellable) {
                    if (!"CANCELLED".equals(st)) stillLive++;
                    continue;
                }
                double hours = Duration.between(now, dep).toMillis() / 3_600_000.0;
                refund = refund.add(((BigDecimal) s.get("fare")).multiply(Travel.refundPct(hours)).setScale(2, RoundingMode.HALF_UP));
                Sql.exec(ignite, tx, "UPDATE booking_segment SET status = 'CANCELLED' WHERE segment_id = ? AND customer_id = ?", s.get("segment_id"), cid);
                Sql.exec(ignite, tx, "UPDATE flight_inventory SET seats_available = seats_available + 1 WHERE flight_id = ? AND cabin = ?", s.get("flight_id"), s.get("cabin"));
                cancelled++;
            }
            if (cancelled == 0) return fail(-1, 20112, "No cancellable segments");

            BigDecimal refunded = ((BigDecimal) b.get(0).get("refunded")).add(refund).setScale(2, RoundingMode.HALF_UP);
            String newStatus = stillLive == 0 ? "CANCELLED" : "CONFIRMED";
            Sql.exec(ignite, tx, "UPDATE booking SET status = ?, refunded = ? WHERE booking_id = ? AND customer_id = ?", newStatus, refunded, bookingId, cid);

            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("rc", 0);
            out.put("booking_id", bookingId);
            out.put("refund", refund);
            out.put("segments_cancelled", cancelled);
            out.put("booking_status", newStatus);
            return out;
        });
    }
}
