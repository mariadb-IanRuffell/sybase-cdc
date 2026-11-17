package poc.compute.jobs;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.Ignite;

/**
 * Replaces sp_create_order + sp_add_order_item + sp_complete_order (three round trips, three transactions) with ONE job and ONE SQL
 * transaction on the customer's partition.
 *
 * Arguments: customer_id, channel, payment_method, items (list: product_id, qty, product_id, qty, ...).
 *
 * Differences from the procedures, both deliberate:
 *  - All-or-nothing: if the 2nd item is out of stock nothing is changed (the T-SQL flow left a NEW order with the 1st item already decremented).
 *  - New ids come from blocks taken from id_seq starting at 1,000,000,000, so they can never collide with the identity values CDC replicates.
 */
public class PlaceOrderJob extends JsonJob {
    @Override
    protected boolean transactional() {
        return false;      // manages its own transaction
    }

    @Override
    protected Object run(Ignite ignite, Map<String, Object> arg) {
        int cid = intArg(arg, "customer_id");
        String channel = (String) arg.getOrDefault("channel", "WEB"), method = (String) arg.getOrDefault("payment_method", "CARD");
        List<?> flat = (List<?>) arg.get("items");
        int n = flat == null ? 0 : flat.size() / 2;
        int[] pids = new int[n], qtys = new int[n];
        for (int i = 0; i < n; i++) {
            pids[i] = ((Number) flat.get(2 * i)).intValue();
            qtys[i] = ((Number) flat.get(2 * i + 1)).intValue();
        }
        if (n == 0) return fail(-7, 20007, "Order has no items");
        for (int q : qtys) if (q <= 0) return fail(-4, 20004, "Quantity must be positive");

        // ids first (own small transaction), then everything else in one transaction
        Map<String, Integer> ids = Ids.take(ignite, Ids.need("order_id", 1, "payment_id", 1, "loyalty_txn_id", 1, "order_item_id", n));
        int orderId = ids.get("order_id"), payId = ids.get("payment_id"), txnId = ids.get("loyalty_txn_id");
        int[] itemIds = new int[n];
        for (int i = 0; i < n; i++) itemIds[i] = ids.get("order_item_id") + i;
        LocalDateTime now = Loyalty.now();

        return ignite.transactions().runInTransaction(tx -> {
            // sp_create_order: the customer must exist and be ACTIVE
            List<LinkedHashMap<String, Object>> cust = Sql.rows(ignite, tx, "SELECT status, loyalty_tier, loyalty_points FROM customer WHERE customer_id = ?", cid);
            if (cust.isEmpty() || !"ACTIVE".equals(cust.get(0).get("status"))) return fail(-1, 20002, "Customer not found or inactive");

            // sp_add_order_item, per item and in order: product, stock
            Map<Integer, Integer> stock = new HashMap<>();
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal[] price = new BigDecimal[n];
            for (int i = 0; i < n; i++) {
                if (!stock.containsKey(pids[i])) {
                    List<LinkedHashMap<String, Object>> p = Sql.rows(ignite, tx, "SELECT unit_price, stock_qty, active FROM product WHERE product_id = ?", pids[i]);
                    if (p.isEmpty() || !Boolean.TRUE.equals(p.get(0).get("active"))) return fail(-6, 20006, "Product not found or inactive");
                    stock.put(pids[i], ((Number) p.get(0).get("stock_qty")).intValue());
                    price[i] = (BigDecimal) p.get(0).get("unit_price");
                } else {
                    price[i] = (BigDecimal) Sql.scalar(ignite, tx, "SELECT unit_price FROM product WHERE product_id = ?", pids[i]);
                }
                if (stock.get(pids[i]) < qtys[i]) return fail(-3, 20003, "Insufficient stock");
                stock.put(pids[i], stock.get(pids[i]) - qtys[i]);
                total = total.add(price[i].multiply(BigDecimal.valueOf(qtys[i])));
            }

            // sp_complete_order: points use the tier held BEFORE this order, then the tier is recalculated
            String tierBefore = (String) cust.get(0).get("loyalty_tier");
            int points = total.multiply(Loyalty.multiplier(ignite, tx, tierBefore)).intValue();
            BigDecimal spend = Loyalty.spend12m(ignite, tx, cid, now).add(total);
            String tierAfter = Loyalty.tierForSpend(ignite, tx, spend);

            Sql.exec(ignite, tx, "INSERT INTO orders (order_id, customer_id, order_date, status, channel, total_amount, currency) VALUES (?, ?, ?, 'COMPLETED', ?, ?, 'AED')",
                orderId, cid, now, channel, total);
            for (int i = 0; i < n; i++)
                Sql.exec(ignite, tx, "INSERT INTO order_item (order_item_id, customer_id, order_id, product_id, qty, unit_price, line_total) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    itemIds[i], cid, orderId, pids[i], qtys[i], price[i], price[i].multiply(BigDecimal.valueOf(qtys[i])));
            for (Map.Entry<Integer, Integer> e : stock.entrySet())
                Sql.exec(ignite, tx, "UPDATE product SET stock_qty = ? WHERE product_id = ?", e.getValue(), e.getKey());
            Sql.exec(ignite, tx, "INSERT INTO payment (payment_id, customer_id, order_id, method, amount, status, paid_at) VALUES (?, ?, ?, ?, ?, 'CAPTURED', ?)",
                payId, cid, orderId, method, total, now);
            Sql.exec(ignite, tx, "INSERT INTO loyalty_txn (txn_id, customer_id, order_id, points, reason, txn_date) VALUES (?, ?, ?, ?, 'PURCHASE', ?)",
                txnId, cid, orderId, points, now);
            int newPoints = ((Number) cust.get(0).get("loyalty_points")).intValue() + points;
            Sql.exec(ignite, tx, "UPDATE customer SET loyalty_points = ?, loyalty_tier = ?, updated_at = ? WHERE customer_id = ?", newPoints, tierAfter, now, cid);

            LinkedHashMap<String, Object> out = new LinkedHashMap<>();
            out.put("rc", 0);
            out.put("order_id", orderId);
            out.put("customer_id", cid);
            out.put("total_amount", total);
            out.put("points", points);
            out.put("tier_before", tierBefore);
            out.put("tier_after", tierAfter);
            out.put("loyalty_points", newPoints);
            return out;
        });
    }
}
