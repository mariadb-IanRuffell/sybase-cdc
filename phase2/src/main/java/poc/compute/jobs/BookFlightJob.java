package poc.compute.jobs;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.Ignite;

/**
 * Replaces the Oracle procedure sp_book_flight: reserve one seat and create the booking and its segment in ONE SQL transaction on the
 * customer's partition. Arguments: customer_id, flight_id, cabin, passenger, channel (optional).
 *
 * Same validation order and error numbers as the procedure: 20104 passenger, 20105 cabin, 20103 flight not found, 20106 flight not open,
 * 20107 cabin sold out. Ids come from id_seq blocks starting at 1,000,000,000 so they cannot collide with the identity values CDC replicates.
 */
public class BookFlightJob extends JsonJob {
    @Override
    protected boolean transactional() {
        return false;      // manages its own transaction
    }

    @Override
    protected Object run(Ignite ignite, Map<String, Object> arg) {
        int cid = intArg(arg, "customer_id"), fid = intArg(arg, "flight_id");
        String cabin = (String) arg.get("cabin"), passenger = (String) arg.get("passenger");
        String channel = arg.get("channel") == null ? "WEB" : (String) arg.get("channel");
        if (passenger == null || passenger.trim().isEmpty()) return fail(-1, 20104, "Passenger name required");
        if (!Travel.validCabin(cabin)) return fail(-1, 20105, "Invalid cabin");

        LocalDateTime now = Loyalty.now();

        Map<String, Integer> ids = Ids.take(ignite, Ids.need("booking_id", 1, "segment_id", 1));
        int bookingId = ids.get("booking_id"), segmentId = ids.get("segment_id");
        String ref = Travel.randomRef();
        while (!Sql.rows(ignite, "SELECT 1 AS x FROM booking WHERE booking_ref = ?", ref).isEmpty()) ref = Travel.randomRef();
        final String bookingRef = ref;

        return ignite.transactions().runInTransaction(tx -> {
            List<LinkedHashMap<String, Object>> fl = Sql.rows(ignite, tx, "SELECT status, dep_time, distance_km FROM flight WHERE flight_id = ?", fid);
            if (fl.isEmpty()) return fail(-1, 20103, "Flight not found");
            String status = (String) fl.get(0).get("status");
            LocalDateTime dep = (LocalDateTime) fl.get(0).get("dep_time");
            if (!("SCHEDULED".equals(status) || "DELAYED".equals(status)) || !dep.isAfter(now)) return fail(-1, 20106, "Flight is not open for booking");
            int distance = ((Number) fl.get(0).get("distance_km")).intValue();

            List<LinkedHashMap<String, Object>> inv = Sql.rows(ignite, tx,
                "SELECT seats_available, next_seat, base_fare FROM flight_inventory WHERE flight_id = ? AND cabin = ?", fid, cabin);
            if (inv.isEmpty() || ((Number) inv.get(0).get("seats_available")).intValue() <= 0) return fail(-1, 20107, "Cabin sold out");

            BigDecimal fare = (BigDecimal) inv.get(0).get("base_fare");
            int n = ((Number) inv.get(0).get("next_seat")).intValue();
            Sql.exec(ignite, tx, "UPDATE flight_inventory SET seats_available = seats_available - 1, next_seat = ? WHERE flight_id = ? AND cabin = ?", n + 1, fid, cabin);
            Sql.exec(ignite, tx, "INSERT INTO booking (booking_id, customer_id, booking_ref, status, booked_at, channel, total_fare, refunded, currency) "
                + "VALUES (?, ?, ?, 'CONFIRMED', ?, ?, ?, 0, 'AED')", bookingId, cid, bookingRef, now, channel, fare);
            Sql.exec(ignite, tx, "INSERT INTO booking_segment (segment_id, customer_id, booking_id, flight_id, cabin, seat_no, passenger_name, fare, status, miles_earned) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'TICKETED', ?)", segmentId, cid, bookingId, fid, cabin, Travel.seatNo(cabin, n), passenger, fare, Travel.miles(distance, cabin));

            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("rc", 0);
            out.put("booking_id", bookingId);
            out.put("booking_ref", bookingRef);
            out.put("customer_id", cid);
            out.put("flight_id", fid);
            out.put("cabin", cabin);
            out.put("seat_no", Travel.seatNo(cabin, n));
            out.put("fare", fare);
            out.put("miles_earned", Travel.miles(distance, cabin));
            return out;
        });
    }
}
