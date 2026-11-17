package poc.compute.jobs;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.ignite.Ignite;

/** Replaces sp_recalc_loyalty_tier. Colocated with the customer. Argument: {customer_id}. */
public class RecalcLoyaltyTierJob extends JsonJob {
    @Override
    protected Object run(Ignite ignite, Map<String, Object> arg) {
        int cid = intArg(arg, "customer_id");
        LocalDateTime now = Loyalty.now();
        String oldTier = (String) Sql.scalar(ignite, "SELECT loyalty_tier FROM customer WHERE customer_id = ?", cid);
        if (oldTier == null) return fail(-1, 20002, "Customer not found");

        BigDecimal spend = Loyalty.spend12m(ignite, null, cid, now);
        String newTier = Loyalty.tierForSpend(ignite, null, spend);
        boolean changed = !newTier.equals(oldTier);
        if (changed) Sql.exec(ignite, "UPDATE customer SET loyalty_tier = ?, updated_at = ? WHERE customer_id = ?", newTier, now, cid);

        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("rc", 0);
        out.put("customer_id", cid);
        out.put("old_tier", oldTier);
        out.put("new_tier", newTier);
        out.put("changed", changed);
        out.put("spend_12m", spend);
        return out;
    }
}
