package example.sybase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.IgniteServer;
import org.apache.ignite.InitParameters;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.compute.JobDescriptor;
import org.apache.ignite.compute.JobExecutionOptions;
import org.apache.ignite.compute.JobTarget;
import org.apache.ignite.deployment.DeploymentUnit;
import org.apache.ignite.sql.SqlRow;

/**
 * Runs {@code SybaseWasmJob} through the SQL {@code EXECUTE} statement (prototype in the GridGain source tree) and
 * compares the results with the same calls made through the compute API.
 */
public class ExecuteSqlDemo {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE = "sybase-wasm-node";
    private static final DeploymentUnit UNIT = new DeploymentUnit("sybase-wasm-example", "1.0.0");
    private static final JobDescriptor<String, String> JOB = JobDescriptor.<String, String>builder("example.sybase.SybaseWasmJob")
            .units(UNIT).options(JobExecutionOptions.builder().maxRetries(0).build()).build();

    public static void main(String[] args) throws Exception {
        Path example = Path.of(System.getProperty("sybase.example.dir"));
        Path source = Path.of(System.getProperty("gridgain.source.dir"));
        Path work = example.resolve("build/execute-node-" + UUID.randomUUID());
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
            server.initCluster(InitParameters.builder().clusterName("execute-demo")
                    .metaStorageNodeNames(NODE).license(license).build());
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                    IgniteClient client = IgniteClient.builder().addresses("127.0.0.1:10890").build()) {
                ComputeDemo.deploy(http, Path.of(System.getProperty("sybase.job.jar")));
                client.sql().executeScript(Files.readString(example.resolve("generated/customer360-schema.sql")));
                client.sql().executeScript("""
                        INSERT INTO loyalty_tier_rule VALUES ('BLUE',0,1),('SILVER',5000,1.25),('GOLD',20000,1.5),('PLATINUM',60000,2);
                        INSERT INTO customer VALUES
                          (1,'Ada','Lovelace','ada@example.test',NULL,DATE '1990-01-01','GB','STANDARD','BLUE',0,TRUE,'ACTIVE',LOCALTIMESTAMP,LOCALTIMESTAMP),
                          (3,'Inactive','Customer','inactive@example.test',NULL,NULL,NULL,'STANDARD','BLUE',0,FALSE,'INACTIVE',LOCALTIMESTAMP,LOCALTIMESTAMP);
                        INSERT INTO address VALUES (1,1,'HOME','Example road','Dubai',NULL,'AE',TRUE);
                        INSERT INTO product VALUES (1,'SKU-1','Ticket','FLIGHT',75.25,10,TRUE);
                        INSERT INTO orders VALUES (100,1,TIMESTAMPADD(DAY,-1,LOCALTIMESTAMP),'COMPLETED','WEB',4900,'AED');
                        INSERT INTO order_item VALUES (100,100,1,1,4900,4900,1);
                        UPDATE id_seq SET next_id=101 WHERE name IN ('order_id','order_item_id');
                        """);

                // 1. Read-only procedure: same answer through EXECUTE and through the compute API.
                String request = JSON.writeValueAsString(Map.of("procedure", "sp_customer_360", "parameters", Map.of("customer_id", 1)));
                JsonNode viaSql = JSON.readTree(execute(client, request));
                JsonNode viaCompute = JSON.readTree(client.compute().execute(JobTarget.anyNode(client.cluster().nodes()), JOB, request));
                require(strip(viaSql).equals(strip(viaCompute)), "EXECUTE and compute API differ:\n" + viaSql + "\n" + viaCompute);
                require(viaSql.path("status").asInt() == 0, "status 0: " + viaSql);
                require(viaSql.path("result_sets").size() == 6, "six result sets: " + viaSql);
                System.out.println("sp_customer_360 via EXECUTE: status=" + viaSql.path("status").asInt()
                        + " result_sets=" + viaSql.path("result_sets").size()
                        + " first row=" + viaSql.path("result_sets").get(0).path("rows").get(0));

                // 2. Expected business failure comes back as a normal result.
                JsonNode missing = JSON.readTree(execute(client, JSON.writeValueAsString(
                        Map.of("procedure", "sp_customer_360", "parameters", Map.of("customer_id", 999)))));
                require(missing.path("status").asInt() == -1, "missing customer status -1: " + missing);

                // 3. A writing procedure, then check the rows with plain SQL.
                JsonNode created = JSON.readTree(execute(client, JSON.writeValueAsString(
                        Map.of("procedure", "sp_create_order", "parameters", Map.of("customer_id", 1)))));
                require(created.path("status").asInt() == 0, "create order: " + created);
                int order = created.path("outputs").path("order_id").asInt();
                try (var rs = client.sql().execute((org.apache.ignite.tx.Transaction) null, "SELECT status FROM orders WHERE order_id = ?", order)) {
                    SqlRow row = rs.next();
                    require(row.stringValue(0).equals("NEW"), "order row visible to SQL: " + row);
                }
                JsonNode added = JSON.readTree(execute(client, JSON.writeValueAsString(
                        Map.of("procedure", "sp_add_order_item", "parameters", Map.of("order_id", order, "product_id", 1, "qty", 2)))));
                require(added.path("status").asInt() == 0, "add item: " + added);
                System.out.println("sp_create_order + sp_add_order_item via EXECUTE: order " + order + " created, item added");

                // 4. Unknown unit and unknown procedure fail cleanly.
                expectFailure(client, "EXECUTE 'example.sybase.SybaseWasmJob' UNITS 'no-such-unit:1.0.0' ARGS '{}'");
                expectFailure(client, "EXECUTE 'example.sybase.SybaseWasmJob' UNITS 'sybase-wasm-example:1.0.0' ARGS '{\"procedure\":\"nope\"}'");

                // 5. Latency of the two entry points.
                int n = 200;
                for (int i = 0; i < 30; i++) {
                    execute(client, request);
                    client.compute().execute(JobTarget.anyNode(client.cluster().nodes()), JOB, request);
                }
                List<Double> sql = new ArrayList<>();
                List<Double> api = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    long t = System.nanoTime();
                    execute(client, request);
                    sql.add((System.nanoTime() - t) / 1e6);
                    t = System.nanoTime();
                    client.compute().execute(JobTarget.anyNode(client.cluster().nodes()), JOB, request);
                    api.add((System.nanoTime() - t) / 1e6);
                }
                System.out.printf("sp_customer_360 median ms over %d calls: EXECUTE %.2f  compute API %.2f%n", n, median(sql), median(api));
            }
            System.out.println("EXECUTE CHECKS PASSED");
        } finally {
            server.shutdown();
        }
    }

    private static String execute(IgniteClient client, String request) {
        String sql = "EXECUTE 'example.sybase.SybaseWasmJob' UNITS 'sybase-wasm-example:1.0.0' ARGS '"
                + request.replace("'", "''") + "'";
        try (var rs = client.sql().execute((org.apache.ignite.tx.Transaction) null, sql)) {
            return rs.next().stringValue(0);
        }
    }

    private static void expectFailure(IgniteClient client, String sql) {
        try (var rs = client.sql().execute((org.apache.ignite.tx.Transaction) null, sql)) {
            String result = rs.hasNext() ? rs.next().stringValue(0) : "";
            if (result.contains("\"error\"") || result.contains("Unknown")) {
                System.out.println("expected failure reported in result: " + abbreviate(result));
                return;
            }
            throw new AssertionError("expected a failure for: " + sql + " but got " + result);
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            System.out.println("expected failure: " + abbreviate(String.valueOf(e.getMessage())));
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    private static JsonNode strip(JsonNode result) {
        ObjectNode copy = result.deepCopy();
        copy.remove("timing");
        copy.remove("trace");
        return copy;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
