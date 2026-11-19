import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.compute.*;
import org.apache.ignite.deployment.DeploymentUnit;

/**
 * Runs the real Sybase procedure sp_add_order_item on ASE and its WASM compilation on GridGain 9 and compares the
 * return status, the error number and the resulting rows. Two modes:
 *   create          create an open order in Sybase (sp_create_order) and print its id
 *   compare <id>    run the cases against that order on both systems (GridGain must already hold the order)
 */
public class AddOrderItemParity {
    static Connection ase, gg;
    static IgniteClient client;
    static int failed, passed;

    public static void main(String[] a) {
        try {
            run(a);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(2);   // the client's threads would otherwise keep the JVM alive
        }
    }

    static void run(String[] a) throws Exception {
        ase = DriverManager.getConnection(env("ASE_URL", "jdbc:jtds:sybase://192.168.68.73:5000/testdb"),
                env("ASE_USER", "tester"), env("ASE_PASSWORD", "guest1234"));
        if (a[0].equals("create")) {
            try (CallableStatement cs = ase.prepareCall("{call sp_create_order(?, ?, ?)}")) {
                cs.setInt(1, 2); cs.setString(2, "APP"); cs.registerOutParameter(3, Types.INTEGER);
                cs.execute();
                System.out.println(cs.getInt(3));
            }
            return;
        }
        int oid = Integer.parseInt(a[1]);
        gg = DriverManager.getConnection(env("GG_JDBC", "jdbc:ignite:thin://127.0.0.1:11800,127.0.0.1:11801,127.0.0.1:11802"));
        gg.setAutoCommit(false);
        client = IgniteClient.builder().addresses(env("GG_ADDRS", "127.0.0.1:11800,127.0.0.1:11801,127.0.0.1:11802").split(",")).build();
        try {
            System.out.println("== sp_add_order_item on Sybase vs its WASM compilation on GridGain 9 (order " + oid + ")");
            same("GridGain holds the order Sybase created", oid);
            ccase("quantity zero", oid, 4, 0);
            ccase("order not open", 999999999, 4, 1);
            ccase("unknown product", oid, 999999, 1);
            ccase("insufficient stock", oid, 4, 1_000_000);
            ccase("item 4 x 2", oid, 4, 2);
            ccase("item 17 x 1", oid, 17, 1);
            ccase("item 9 x 3", oid, 9, 3);
            System.out.println(failed == 0 ? "\nWASM PARITY OK: " + passed + " checks passed" : "\nWASM PARITY FAILED: " + failed + " failed, " + passed + " passed");
        } finally {
            client.close();
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    static String env(String k, String d) { String v = System.getenv(k); return v == null ? d : v; }

    static void ccase(String label, int oid, int product, int qty) throws Exception {
        // Sybase
        int sybStatus = 0, sybErr = 0;
        try (CallableStatement cs = ase.prepareCall("{? = call sp_add_order_item(?, ?, ?)}")) {
            cs.registerOutParameter(1, Types.INTEGER);
            cs.setInt(2, oid); cs.setInt(3, product); cs.setInt(4, qty);
            try { cs.execute(); while (cs.getMoreResults() || cs.getUpdateCount() != -1) { } sybStatus = cs.getInt(1); }
            catch (SQLException e) {
                for (SQLException x = e; x != null && sybErr == 0; x = x.getNextException()) sybErr = x.getErrorCode();
                sybStatus = cs.getInt(1);
            }
            for (SQLWarning w = cs.getWarnings(); w != null && sybErr == 0; w = w.getNextWarning()) sybErr = w.getErrorCode();
        }
        // GridGain 9: the WASM export, as a compute job
        var d = JobDescriptor.<String, String>builder("example.sybase.SybaseWasmJob")
                .units(System.getenv("NO_UNIT") != null ? new DeploymentUnit[0] : new DeploymentUnit[] {new DeploymentUnit("sybase-wasm-example", "latest")})
                .options(JobExecutionOptions.builder().maxRetries(0).build()).build();
        String reply = client.compute().execute(JobTarget.anyNode(client.cluster().nodes()), d,
                "{\"procedure\":\"sp_add_order_item\",\"parameters\":{\"order_id\":" + oid + ",\"product_id\":" + product + ",\"qty\":" + qty + "}}");
        int wasmStatus = Integer.parseInt(reply.replaceAll(".*\"status\":(-?\\d+).*", "$1"));
        int wasmErr = reply.contains("\"number\":") ? Integer.parseInt(reply.replaceAll(".*\"number\":(\\d+).*", "$1")) : 0;
        check(label + ": return status (Sybase " + sybStatus + ", WASM " + wasmStatus + ")", sybStatus == wasmStatus);
        check(label + ": error number (Sybase " + sybErr + ", WASM " + wasmErr + ")", sybErr == wasmErr);
        same(label + ": order, items and stock identical afterwards", oid);
    }

    static void same(String label, int oid) throws Exception {
        String[] queries = {
            "select order_id, customer_id, status, total_amount from orders where order_id = " + oid,
            "select product_id, qty, unit_price, line_total from order_item where order_id = " + oid + " order by product_id, qty",
            "select product_id, stock_qty from product where product_id in (4, 17, 9) order by product_id"};
        String diff = null;
        for (String q : queries) {
            List<List<Object>> s = rows(ase, q), g = rows(gg, q);
            if (!s.equals(g) && diff == null) diff = q + "\n      sybase=" + s + "\n      gridgain=" + g;
        }
        check(label, diff == null);
        if (diff != null) System.out.println("      " + diff);
    }

    static List<List<Object>> rows(Connection c, String sql) throws SQLException {
        List<List<Object>> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            int n = r.getMetaData().getColumnCount();
            while (r.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    Object v = r.getObject(i);
                    if (v instanceof Number num && !(v instanceof Integer) && !(v instanceof Long)) v = new BigDecimal(num.toString()).setScale(2, java.math.RoundingMode.HALF_UP);
                    else if (v instanceof Long l) v = l.intValue();
                    row.add(v);
                }
                out.add(row);
            }
        }
        if (c == gg) c.commit();
        return out;
    }

    static void check(String label, boolean ok) {
        System.out.println("   " + (ok ? "PASS " : "FAIL ") + label);
        if (ok) passed++; else failed++;
    }
}
