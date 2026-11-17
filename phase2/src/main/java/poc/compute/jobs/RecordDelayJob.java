package poc.compute.jobs;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.ignite.Ignite;

/**
 * Replaces the Oracle procedures sp_record_delay and sp_cancel_flight: change a flight's status and write a flight event in one
 * transaction, and report how many confirmed bookings are affected.
 * Arguments: flight_id, minutes (delay only), reason (optional), cancel (boolean: true = cancel the flight).
 * Errors: 20120 delay out of range, 20103 flight not found, 20121 flight already operated or cancelled.
 */
public class RecordDelayJob extends JsonJob {
    @Override
    protected boolean transactional() {
        return false;      // manages its own transaction
    }

    @Override
    protected Object run(Ignite ignite, Map<String, Object> a) {
        int fid = intArg(a, "flight_id");
        int minutes = a.get("minutes") == null ? 0 : intArg(a, "minutes");
        String reason = (String) a.get("reason");
        boolean cancel = Boolean.TRUE.equals(a.get("cancel"));
        if (!cancel && (minutes < 1 || minutes > 1440)) return fail(-1, 20120, "Delay must be between 1 and 1440 minutes");

        int eventId = Ids.one(ignite, "flight_event_id");
        LocalDateTime now = Loyalty.now();
        Object failure = ignite.transactions().runInTransaction(tx -> {
            Object st = Sql.scalar(ignite, tx, "SELECT status FROM flight WHERE flight_id = ?", fid);
            if (st == null) return fail(-1, 20103, "Flight not found");
            if ("DEPARTED".equals(st) || "ARRIVED".equals(st) || "CANCELLED".equals(st)) return fail(-1, 20121, "Flight has already operated or is cancelled");

            String suffix = reason != null ? ": " + reason : "";
            if (cancel) Sql.exec(ignite, tx, "UPDATE flight SET status = 'CANCELLED' WHERE flight_id = ?", fid);
            else Sql.exec(ignite, tx, "UPDATE flight SET status = 'DELAYED', delay_minutes = ? WHERE flight_id = ?", minutes, fid);
            Sql.exec(ignite, tx, "INSERT INTO flight_event (event_id, flight_id, event_type, detail, event_time) VALUES (?, ?, ?, ?, ?)",
                eventId, fid, cancel ? "CANCEL" : "DELAY", cancel ? "Flight cancelled" + suffix : "Delayed " + minutes + " min" + suffix, now);
            Object affected = Sql.scalar(ignite, tx, "SELECT COUNT(*) FROM booking_segment s JOIN booking b ON b.booking_id = s.booking_id "
                + "AND b.customer_id = s.customer_id WHERE s.flight_id = ? AND s.status IN " + Travel.ACTIVE_SEGMENTS + " AND b.status = 'CONFIRMED'", fid);
            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("rc", 0);
            out.put("flight_id", fid);
            out.put("status", cancel ? "CANCELLED" : "DELAYED");
            out.put("affected_bookings", ((Number) affected).longValue());
            return out;
        });
        return failure;
    }
}
