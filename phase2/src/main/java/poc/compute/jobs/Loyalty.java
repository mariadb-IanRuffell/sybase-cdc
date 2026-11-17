package poc.compute.jobs;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.ignite.Ignite;
import org.apache.ignite.tx.Transaction;

/** Loyalty rules shared by several jobs (the SQL equivalents are inlined in the stored procedures). */
final class Loyalty {
    private Loyalty() {}

    static LocalDateTime now() {
        return LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** Trailing-12-month spend on COMPLETED orders. */
    static BigDecimal spend12m(Ignite ignite, Transaction tx, int customerId, LocalDateTime now) {
        Object v = Sql.scalar(ignite, tx,
            "SELECT COALESCE(SUM(total_amount), 0) FROM orders WHERE customer_id = ? AND status = 'COMPLETED' AND order_date >= ?",
            customerId, now.minusDays(365));
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v));
    }

    /** Tier with the highest threshold not above the spend (loyalty_tier_rule). */
    static String tierForSpend(Ignite ignite, Transaction tx, BigDecimal spend) {
        return (String) Sql.scalar(ignite, tx,
            "SELECT tier FROM loyalty_tier_rule WHERE min_spend = (SELECT MAX(min_spend) FROM loyalty_tier_rule WHERE min_spend <= ?)", spend);
    }

    static BigDecimal multiplier(Ignite ignite, Transaction tx, String tier) {
        return (BigDecimal) Sql.scalar(ignite, tx, "SELECT points_multiplier FROM loyalty_tier_rule WHERE tier = ?", tier);
    }

    static List<Object> list(Object... v) {
        List<Object> l = new ArrayList<>();
        for (Object o : v) l.add(o);
        return l;
    }
}
