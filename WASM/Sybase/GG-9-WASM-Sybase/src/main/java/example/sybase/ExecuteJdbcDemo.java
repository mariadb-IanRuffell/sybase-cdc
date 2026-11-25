package example.sybase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.IgniteServer;
import org.apache.ignite.InitParameters;

/**
 * Calls the WASM-backed {@code SybaseWasmJob} from plain JDBC using the SQL {@code EXECUTE} statement (prototype in the
 * GridGain source tree). Any JDBC client or BI tool can do the same: the statement returns a one-row, one-column result
 * set whose {@code RESULT} column holds the job's JSON response.
 *
 * <pre>
 *   EXECUTE 'example.sybase.SybaseWasmJob' UNITS 'sybase-wasm-example:1.0.0' ARGS '{"procedure":"sp_customer_360",...}'
 * </pre>
 *
 * Run with {@code ./gradlew :example-sybase-wasm:run -Dsybase.main=example.sybase.ExecuteJdbcDemo} (same way as
 * {@link ExecuteSqlDemo}).
 */
public class ExecuteJdbcDemo {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE = "sybase-wasm-node";
    private static final String UNIT = "sybase-wasm-example:1.0.0";
    private static final String JOB = "example.sybase.SybaseWasmJob";
    private static final String URL = "jdbc:ignite:thin://127.0.0.1:10890";

    public static void main(String[] args) throws Exception {
        Path example = Path.of(System.getProperty("sybase.example.dir"));
        Path source = Path.of(System.getProperty("gridgain.source.dir"));
        Path work = example.resolve("build/execute-jdbc-node-" + UUID.randomUUID());
        Files.createDirectories(work);
        Path configFile = work.resolve("ignite-config.conf");
        Files.writeString(configFile, """
                ignite {
                  network { port = 33490, nodeFinder.netClusterNodes = ["127.0.0.1:33490"] }
                  network.listenAddresses = ["127.0.0.1"]
                  clientConnector.port = 10890
                  clientConnector.listenAddresses = ["127.0.0.1"]
                  clientConnector.sendServerExceptionStackTraceToClient = true
                  rest.port = 10390
                  storage.profiles = [{name = "default", engine = "aipersist"}]
                }
                """);
        String licensePath = System.getenv("GG_LICENSE");
        String license = Files.readString(licensePath == null
                ? source.resolve("dev-utilities/src/main/resources/gridgain-license.json") : Path.of(licensePath));

        IgniteServer server = IgniteServer.start(NODE, configFile, work);
        try {
            server.initCluster(InitParameters.builder().clusterName("execute-jdbc-demo")
                    .metaStorageNodeNames(NODE).license(license).build());
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
                ComputeDemo.deploy(http, Path.of(System.getProperty("sybase.job.jar")));
            }

            try (Connection conn = DriverManager.getConnection(URL)) {
                // Schema and seed data through ordinary JDBC.
                runScript(conn, Files.readString(example.resolve("generated/customer360-schema.sql")));
                runScript(conn, """
                        INSERT INTO loyalty_tier_rule VALUES ('BLUE',0,1),('SILVER',5000,1.25),('GOLD',20000,1.5),('PLATINUM',60000,2);
                        INSERT INTO customer VALUES
                          (1,'Ada','Lovelace','ada@example.test',NULL,DATE '1990-01-01','GB','STANDARD','BLUE',0,TRUE,'ACTIVE',LOCALTIMESTAMP,LOCALTIMESTAMP);
                        INSERT INTO address VALUES (1,1,'HOME','Example road','Dubai',NULL,'AE',TRUE);
                        INSERT INTO product VALUES (1,'SKU-1','Ticket','FLIGHT',75.25,10,TRUE);
                        INSERT INTO orders VALUES (100,1,TIMESTAMPADD(DAY,-1,LOCALTIMESTAMP),'COMPLETED','WEB',4900,'AED');
                        INSERT INTO order_item VALUES (100,100,1,1,4900,4900,1);
                        UPDATE id_seq SET next_id=101 WHERE name IN ('order_id','order_item_id');
                        """);

                // 1. Read-only procedure: six result sets come back as JSON in one cell.
                JsonNode c360 = call(conn, "sp_customer_360", Map.of("customer_id", 1));
                require(c360.path("status").asInt() == 0, "status 0: " + c360);
                System.out.println("sp_customer_360: status=" + c360.path("status").asInt()
                        + ", result sets=" + c360.path("result_sets").size()
                        + ", first row=" + c360.path("result_sets").get(0).path("rows").get(0));

                // 2. Expected business failure is a normal result, not an SQLException.
                JsonNode missing = call(conn, "sp_customer_360", Map.of("customer_id", 999));
                require(missing.path("status").asInt() == -1, "missing customer: " + missing);
                System.out.println("sp_customer_360 (unknown customer): status=" + missing.path("status").asInt());

                // 3. Writing procedure, then verify with a normal parameterised SELECT on the same connection.
                JsonNode created = call(conn, "sp_create_order", Map.of("customer_id", 1));
                require(created.path("status").asInt() == 0, "create order: " + created);
                int order = created.path("outputs").path("order_id").asInt();
                try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM orders WHERE order_id = ?")) {
                    ps.setInt(1, order);
                    try (ResultSet rs = ps.executeQuery()) {
                        require(rs.next() && "NEW".equals(rs.getString(1)), "order row visible to SQL");
                    }
                }
                JsonNode added = call(conn, "sp_add_order_item", Map.of("order_id", order, "product_id", 1, "qty", 2));
                require(added.path("status").asInt() == 0, "add item: " + added);
                System.out.println("sp_create_order + sp_add_order_item: order " + order + " created, item added");

                // 4. Failures (unknown unit) surface as SQLException.
                try (Statement st = conn.createStatement()) {
                    st.executeQuery("EXECUTE '" + JOB + "' UNITS 'no-such-unit:1.0.0' ARGS '{}'");
                    throw new AssertionError("expected SQLException for unknown unit");
                } catch (SQLException e) {
                    System.out.println("unknown unit rejected: " + e.getMessage().lines().findFirst().orElse(""));
                }

                // 5. Result-set metadata is what any JDBC tool would see.
                try (Statement st = conn.createStatement();
                        ResultSet rs = st.executeQuery(sql("sp_customer_360", Map.of("customer_id", 1)))) {
                    var md = rs.getMetaData();
                    System.out.println("result set: " + md.getColumnCount() + " column(s), name=" + md.getColumnName(1)
                            + ", type=" + md.getColumnTypeName(1));
                }
            }
            System.out.println("EXECUTE-OVER-JDBC CHECKS PASSED");
        } finally {
            server.shutdown();
        }
    }

    /** Builds the EXECUTE statement for one procedure call; ARGS is a string literal, so single quotes are doubled. */
    private static String sql(String procedure, Map<String, ?> parameters) throws Exception {
        String request = JSON.writeValueAsString(Map.of("procedure", procedure, "parameters", parameters));
        return "EXECUTE '" + JOB + "' UNITS '" + UNIT + "' ARGS '" + request.replace("'", "''") + "'";
    }

    private static JsonNode call(Connection conn, String procedure, Map<String, ?> parameters) throws Exception {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql(procedure, parameters))) {
            require(rs.next(), "EXECUTE returned no row");
            return JSON.readTree(rs.getString("RESULT"));
        }
    }

    private static void runScript(Connection conn, String script) throws SQLException {
        for (String statement : script.split(";\\s*(\\r?\\n|$)")) {
            if (!statement.isBlank()) {
                try (Statement st = conn.createStatement()) {
                    st.execute(statement);
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
