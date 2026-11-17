package poc.compute.client;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.ignite.client.IgniteClient;

/**
 * Lets the parity harnesses call jobs by their old task names: routes each to its GridGain 9 job (on the customer's node, or anywhere)
 * and turns the JSON result back into what the harness compares against database values (ints as Integer, ISO timestamps as java.sql.Timestamp).
 */
final class Compute {
    private static final Pattern ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
    private final IgniteClient client;

    Compute(IgniteClient client) {
        this.client = client;
    }

    @SuppressWarnings("unchecked")
    <T, R> R execute(String task, T arg) {
        String name = task.substring(task.lastIndexOf('.') + 1);
        Object result;
        switch (name) {
            case "Customer360Task": result = Jobs.forCustomer(client, "Customer360Job", (Integer) arg, Map.of("customer_id", arg)); break;
            case "RecalcLoyaltyTierTask": result = Jobs.forCustomer(client, "RecalcLoyaltyTierJob", (Integer) arg, Map.of("customer_id", arg)); break;
            case "TripsTask": result = Jobs.forCustomer(client, "TripsJob", (Integer) arg, Map.of("customer_id", arg)); break;
            case "PlaceOrderTask": result = Jobs.forCustomer(client, "PlaceOrderJob", (Integer) ((Map<String, Object>) arg).get("customer_id"), (Map<String, Object>) arg); break;
            case "BookFlightTask": result = Jobs.forCustomer(client, "BookFlightJob", (Integer) ((Map<String, Object>) arg).get("customer_id"), (Map<String, Object>) arg); break;
            case "CancelBookingTask": result = Jobs.anywhere(client, "CancelBookingJob", Map.of("booking_id", arg)); break;
            case "RecordDelayTask": result = Jobs.anywhere(client, "RecordDelayJob", (Map<String, Object>) arg); break;
            case "ProcessDisruptionsTask": result = Jobs.anywhere(client, "ProcessDisruptionsJob", (Map<String, Object>) arg); break;
            case "ScoreCustomersTask": {
                Map<String, Object> m = new LinkedHashMap<>();
                if (arg != null) m.put("as_of", arg);
                result = Jobs.anywhere(client, "ScoreCustomersJob", m);
                break;
            }
            default: throw new IllegalArgumentException("no job for " + task);
        }
        return (R) convert(result);
    }

    @SuppressWarnings("unchecked")
    static Object convert(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, x) -> out.put(k, convert(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) out.add(convert(x));
            return out;
        }
        if (v instanceof Long n && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) return n.intValue();
        if (v instanceof String s && ISO.matcher(s).matches()) return Timestamp.valueOf(LocalDateTime.parse(s));
        return v;
    }
}
