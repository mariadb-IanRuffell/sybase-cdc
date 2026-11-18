import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.compute.*;
import org.apache.ignite.deployment.DeploymentUnit;

/**
 * Runs the real Sybase procedures sp_customer_360, sp_recalc_loyalty_tier, sp_create_order, sp_complete_order and
 * sp_score_customers on ASE and their WASM compilations on GridGain 9, and compares return status, error number, output
 * parameters, result sets and the rows they leave behind. Modes:
 *   prepare         create two open orders in Sybase (one with an item, one empty) and print "withItems empty"
 *   compare w e     run every case against the same data on both systems (GridGain must hold the prepared orders)
 */
public class ProcedureParity {
    static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static Connection ase, gg;
    static IgniteClient client;
    static int failed, passed;

    record Result(int status, int error, Map<String, Object> outputs, List<List<List<Object>>> sets) { }

    public static void main(String[] a) {
        try { run(a); } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
    }

    static String env(String k, String d) { String v = System.getenv(k); return v == null ? d : v; }

    static void run(String[] a) throws Exception {
        ase = DriverManager.getConnection(env("ASE_URL", "jdbc:jtds:sybase://192.168.68.73:5000/testdb"),
                env("ASE_USER", "tester"), env("ASE_PASSWORD", "guest1234"));
        if (a[0].equals("prepare")) {
            int[] orders = new int[2];
            for (int i = 0; i < 2; i++) {
                try (CallableStatement cs = ase.prepareCall("{call sp_create_order(?, ?, ?)}")) {
                    cs.setInt(1, 2); cs.setString(2, "APP"); cs.registerOutParameter(3, Types.INTEGER);
                    cs.execute();
                    orders[i] = cs.getInt(3);
                }
            }
            try (CallableStatement cs = ase.prepareCall("{? = call sp_add_order_item(?, ?, ?)}")) {
                cs.registerOutParameter(1, Types.INTEGER);
                cs.setInt(2, orders[0]); cs.setInt(3, 4); cs.setInt(4, 2);
                cs.execute();
            }
            System.out.println(orders[0] + " " + orders[1]);
            return;
        }
        int withItems = Integer.parseInt(a[1]), empty = Integer.parseInt(a[2]);
        gg = DriverManager.getConnection(env("GG_JDBC", "jdbc:ignite:thin://127.0.0.1:11800,127.0.0.1:11801,127.0.0.1:11802"));
        gg.setAutoCommit(false);
        client = IgniteClient.builder().addresses("127.0.0.1:11800", "127.0.0.1:11801", "127.0.0.1:11802").build();
        try {
            customer360();
            recalc(withItems);
            create();
            complete(withItems, empty);
            score();
            System.out.println(failed == 0 ? "\nWASM PARITY OK: " + passed + " checks passed" : "\nWASM PARITY FAILED: " + failed + " failed, " + passed + " passed");
        } finally {
            client.close();
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- procedures ------------------------------------------------------------------------------------------------

    static void customer360() throws Exception {
        System.out.println("== sp_customer_360");
        for (int customer : new int[] {2, 5, 17, 999999999}) {
            String label = "customer " + customer;
            Result s = sybase("{? = call sp_customer_360(?)}", cs -> cs.setInt(2, customer), 1, null);
            Result g = gridgain("sp_customer_360", "{\"customer_id\":" + customer + "}", null);
            compare(label, s, g);
        }
    }

    static void recalc(int order) throws Exception {
        System.out.println("== sp_recalc_loyalty_tier");
        for (int customer : new int[] {2, 5, 17, 999999999}) {
            String label = "customer " + customer;
            Result s = sybase("{? = call sp_recalc_loyalty_tier(?, ?)}", cs -> { cs.setInt(2, customer); cs.registerOutParameter(3, Types.VARCHAR); },
                    1, Map.of("new_tier", 3));
            Result g = gridgain("sp_recalc_loyalty_tier", "{\"customer_id\":" + customer + "}", null);
            compare(label, s, g);
            same(label + ": customer tier afterwards", "select loyalty_tier from customer where customer_id = " + customer);
        }
    }

    static void create() throws Exception {
        System.out.println("== sp_create_order");
        for (int customer : new int[] {2, 999999999}) {
            String label = "customer " + customer;
            Result s = sybase("{? = call sp_create_order(?, ?, ?)}", cs -> { cs.setInt(2, customer); cs.setString(3, "APP"); cs.registerOutParameter(4, Types.INTEGER); },
                    1, Map.of("order_id", 4));
            Result g = gridgain("sp_create_order", "{\"customer_id\":" + customer + ",\"channel\":\"APP\"}", null);
            check(label + ": return status (Sybase " + s.status + ", WASM " + g.status + ")", s.status == g.status);
            check(label + ": error number (Sybase " + s.error + ", WASM " + g.error + ")", s.error == g.error);
            check(label + ": order id returned by both or neither", s.outputs.containsKey("order_id") == g.outputs.containsKey("order_id")
                    || (s.outputs.get("order_id") == null) == (g.outputs.get("order_id") == null));
            if (s.status == 0) {
                String cols = "select customer_id, status, channel, total_amount, currency from orders where order_id = ";
                List<List<Object>> sr = rows(ase, cols + s.outputs.get("order_id")), gr = rows(gg, cols + g.outputs.get("order_id"));
                check(label + ": new order row identical " + sr, sr.equals(gr) && !sr.isEmpty());
            }
        }
    }

    static void complete(int withItems, int empty) throws Exception {
        System.out.println("== sp_complete_order");
        String[][] cases = {{"order not open", "999999999", "CARD"}, {"order without items", "" + empty, "CARD"},
                {"order with items", "" + withItems, "CASH"}, {"already completed", "" + withItems, "CARD"}};
        for (String[] c : cases) {
            String label = c[0];
            int order = Integer.parseInt(c[1]);
            Result s = sybase("{? = call sp_complete_order(?, ?)}", cs -> { cs.setInt(2, order); cs.setString(3, c[2]); }, 1, null);
            Result g = gridgain("sp_complete_order", "{\"order_id\":" + order + ",\"payment_method\":\"" + c[2] + "\"}", null);
            check(label + ": return status (Sybase " + s.status + ", WASM " + g.status + ")", s.status == g.status);
            check(label + ": error number (Sybase " + s.error + ", WASM " + g.error + ")", s.error == g.error);
            same(label + ": order", "select order_id, customer_id, status, total_amount from orders where order_id = " + order);
            same(label + ": payment", "select method, amount, status from payment where order_id = " + order + " order by 1, 2");
            same(label + ": loyalty txn", "select points, reason from loyalty_txn where order_id = " + order + " order by 1");
            same(label + ": customer points and tier", "select loyalty_points, loyalty_tier from customer where customer_id = 2");
        }
    }

    static void score() throws Exception {
        System.out.println("== sp_score_customers");
        for (int customer : new int[] {2, 5, 17}) {
            String label = "customer " + customer;
            Result s = sybase("{? = call sp_score_customers(?, ?)}", cs -> { cs.setTimestamp(2, Timestamp.valueOf("2026-11-18 12:00:00")); cs.setInt(3, customer); }, 1, null);
            Result g = gridgain("sp_score_customers", "{\"as_of\":\"2026-11-18T12:00:00\",\"customer_id\":" + customer + "}", null);
            compare(label, s, g);
            same(label + ": customer_score row", "select customer_id, churn_score, risk_band from customer_score where customer_id = " + customer);
        }
    }

    // ---- calling ---------------------------------------------------------------------------------------------------

    interface Setup { void apply(CallableStatement cs) throws SQLException; }

    static Result sybase(String call, Setup setup, int statusIndex, Map<String, Integer> outputIndexes) throws Exception {
        int error = 0;
        List<List<List<Object>>> sets = new ArrayList<>();
        Map<String, Object> outputs = new TreeMap<>();
        int status;
        try (CallableStatement cs = ase.prepareCall(call)) {
            cs.registerOutParameter(statusIndex, Types.INTEGER);
            setup.apply(cs);
            try {
                boolean results = cs.execute();
                while (true) {
                    if (results) sets.add(read(cs.getResultSet()));
                    else if (cs.getUpdateCount() == -1) break;
                    results = cs.getMoreResults();
                }
            } catch (SQLException e) {
                for (SQLException x = e; x != null && error == 0; x = x.getNextException()) error = x.getErrorCode();
            }
            for (SQLWarning w = cs.getWarnings(); w != null && error == 0; w = w.getNextWarning()) error = w.getErrorCode();
            status = cs.getInt(statusIndex);
            if (outputIndexes != null) for (var e : outputIndexes.entrySet()) outputs.put(e.getKey(), normalise(cs.getObject(e.getValue())));
        }
        return new Result(status, error, outputs, sets);
    }

    static Result gridgain(String procedure, String parameters, Object unused) throws Exception {
        var d = JobDescriptor.<String, String>builder("example.sybase.SybaseWasmJob")
                .units(new DeploymentUnit("sybase-wasm-example", "latest"))
                .options(JobExecutionOptions.builder().maxRetries(0).build()).build();
        String reply = client.compute().execute(JobTarget.anyNode(client.cluster().nodes()), d,
                "{\"procedure\":\"" + procedure + "\",\"parameters\":" + parameters + "}");
        JsonNode node = JSON.readTree(reply);
        int error = 0;
        for (JsonNode e : node.path("errors")) if (error == 0) error = e.path("number").asInt();
        for (JsonNode e : node.path("messages")) if (error == 0 && e.path("number").asInt() > 0) error = e.path("number").asInt();
        Map<String, Object> outputs = new TreeMap<>();
        node.path("outputs").fields().forEachRemaining(e -> outputs.put(e.getKey(), normalise(e.getValue().isNull() ? null : e.getValue().isNumber() ? e.getValue().decimalValue() : e.getValue().asText())));
        List<List<List<Object>>> sets = new ArrayList<>();
        for (JsonNode rs : node.path("result_sets")) {
            List<List<Object>> table = new ArrayList<>();
            for (JsonNode row : rs.path("rows")) {
                List<Object> cells = new ArrayList<>();
                for (JsonNode cell : row) cells.add(normalise(cell.isNull() ? null : cell.isBoolean() ? cell.asBoolean() : cell.isNumber() ? cell.decimalValue() : cell.asText()));
                table.add(cells);
            }
            sets.add(table);
        }
        return new Result(node.path("status").asInt(), error, outputs, sets);
    }

    static void compare(String label, Result s, Result g) {
        check(label + ": return status (Sybase " + s.status + ", WASM " + g.status + ")", s.status == g.status);
        check(label + ": error number (Sybase " + s.error + ", WASM " + g.error + ")", s.error == g.error);
        check(label + ": output parameters " + s.outputs, s.outputs.equals(g.outputs));
        boolean sameSets = s.sets.equals(g.sets);
        check(label + ": " + s.sets.size() + " result set(s) identical", sameSets);
        if (!sameSets) {
            System.out.println("      sybase  =" + s.sets);
            System.out.println("      gridgain=" + g.sets);
        }
    }

    // ---- comparison helpers ----------------------------------------------------------------------------------------

    /** Numbers compare at 2 decimals, bits and booleans as 0/1, timestamps to the second, strings trimmed. */
    static Object normalise(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return BigDecimal.valueOf(b ? 1 : 0);
        if (v instanceof Number n) return new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_UP);
        if (v instanceof Timestamp t) return t.toLocalDateTime().withNano(0).toString();
        String text = v.toString().trim();
        if (text.matches("-?\\d+(\\.\\d+)?")) return new BigDecimal(text).setScale(2, RoundingMode.HALF_UP);
        if (text.matches("\\d{4}-\\d\\d-\\d\\dT.*")) return java.time.LocalDateTime.parse(text.length() > 23 ? text.substring(0, 23) : text).withNano(0).toString();
        return text;
    }

    static List<List<Object>> read(ResultSet r) throws SQLException {
        List<List<Object>> out = new ArrayList<>();
        try (r) {
            int n = r.getMetaData().getColumnCount();
            while (r.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) row.add(normalise(r.getObject(i)));
                out.add(row);
            }
        }
        return out;
    }

    static List<List<Object>> rows(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            List<List<Object>> out = read(s.executeQuery(sql));
            if (c == gg) c.commit();
            return out;
        }
    }

    static void same(String label, String query) throws Exception {
        List<List<Object>> s = rows(ase, query), g = rows(gg, query);
        check(label + " identical afterwards", s.equals(g));
        if (!s.equals(g)) System.out.println("      " + query + "\n      sybase=" + s + "\n      gridgain=" + g);
    }

    static void check(String label, boolean ok) {
        System.out.println("   " + (ok ? "PASS " : "FAIL ") + label);
        if (ok) passed++; else failed++;
    }
}
