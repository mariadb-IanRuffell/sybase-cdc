package poc.compute.jobs;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.Ignite;

/**
 * The hub view of one customer: profile, tier and orders from the CRM data (originally Sybase) next to bookings, miles, upcoming and
 * at-risk trips from the travel data (originally Oracle). Customer, orders, booking and booking_segment are all colocated on customer_id,
 * so everything is read on the owning node. lifetime_value adds retail spend and travel spend, which neither source system could compute alone.
 */
public class CustomerHubJob extends JsonJob {
    @Override
    protected Object run(Ignite ignite, Map<String, Object> arg) {
        int cid = intArg(arg, "customer_id");
        LocalDateTime now = Loyalty.now();
        List<LinkedHashMap<String, Object>> profile = Sql.rows(ignite,
            "SELECT customer_id, first_name, last_name, email, nationality, segment, loyalty_tier, loyalty_points, status "
                + "FROM customer WHERE customer_id = ?", cid);
        if (profile.isEmpty()) return fail(-1, 20002, "Customer not found");

        LinkedHashMap<String, Object> retail = Sql.rows(ignite,
            "SELECT COUNT(*) AS orders, COALESCE(SUM(total_amount), 0) AS retail_spend FROM orders WHERE customer_id = ? AND status = 'COMPLETED'", cid).get(0);
        LinkedHashMap<String, Object> travel = Sql.rows(ignite,
            "SELECT COUNT(DISTINCT CASE WHEN b.status = 'CONFIRMED' THEN b.booking_id END) AS bookings, "
                + "COALESCE(SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.fare ELSE 0 END), 0) AS travel_spend, "
                + "COALESCE(SUM(CASE WHEN s.status <> 'CANCELLED' THEN s.miles_earned ELSE 0 END), 0) AS miles "
                + "FROM booking b LEFT JOIN booking_segment s ON s.booking_id = b.booking_id AND s.customer_id = b.customer_id "
                + "WHERE b.customer_id = ?", cid).get(0);

        List<LinkedHashMap<String, Object>> upcoming = Sql.rows(ignite,
            "SELECT " + TripsJob.COLS + TripsJob.FROM + "WHERE b.customer_id = ? AND s.status <> 'CANCELLED' AND f.dep_time >= ? "
                + "ORDER BY f.dep_time, s.segment_id LIMIT 5", cid, now);
        List<LinkedHashMap<String, Object>> atRisk = Sql.rows(ignite,
            "SELECT " + TripsJob.COLS + TripsJob.FROM + "WHERE b.customer_id = ? AND s.status IN " + Travel.ACTIVE_SEGMENTS
                + " AND f.dep_time >= ? AND f.status IN ('DELAYED', 'CANCELLED') ORDER BY f.dep_time, s.segment_id", cid, now);

        BigDecimal retailSpend = (BigDecimal) retail.get("retail_spend"), travelSpend = (BigDecimal) travel.get("travel_spend");
        BigDecimal total = retailSpend.add(travelSpend);
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("loyalty_tier", profile.get(0).get("loyalty_tier"));
        value.put("loyalty_points", profile.get(0).get("loyalty_points"));
        value.put("retail_spend", retailSpend);
        value.put("travel_spend", travelSpend);
        value.put("lifetime_value", total);
        value.put("travel_share_pct", total.signum() == 0 ? BigDecimal.ZERO
            : travelSpend.multiply(BigDecimal.valueOf(100)).divide(total, 1, java.math.RoundingMode.HALF_UP));

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("rc", 0);
        out.put("profile", new ArrayList<Object>(profile));
        out.put("retail", Loyalty.list(retail));
        out.put("travel", Loyalty.list(travel));
        out.put("upcoming_trips", new ArrayList<Object>(upcoming));
        out.put("at_risk_trips", new ArrayList<Object>(atRisk));
        out.put("value", Loyalty.list(value));
        return out;
    }
}
