package example.sybase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.IgniteServer;
import org.apache.ignite.InitParameters;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.compute.JobDescriptor;
import org.apache.ignite.compute.JobExecutionOptions;
import org.apache.ignite.compute.JobTarget;
import org.apache.ignite.compute.wasm.WasmJobDescriptor;
import org.apache.ignite.compute.wasm.WasmType;
import org.apache.ignite.deployment.DeploymentUnit;

/** Checks native validation and Java-hosted WASM SQL jobs, including persistent data after a node restart. */
public class ComputeDemo {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NODE = "sybase-wasm-node";
    private static final String REST = "http://127.0.0.1:10390/management/v1/deployment/";
    private static final DeploymentUnit UNIT = new DeploymentUnit("sybase-wasm-example", "1.0.0");
    private static final boolean CUSTOMER360 = Boolean.parseBoolean(System.getenv().getOrDefault("SYBASE_CUSTOMER360", "false"));
    private static final boolean TRACE = Boolean.parseBoolean(System.getenv().getOrDefault("SYBASE_TRACE", "true"));

    public static void main(String[] args) throws Exception {
        Path example = Path.of(System.getProperty("sybase.example.dir"));
        Path source = Path.of(System.getProperty("gridgain.source.dir"));
        Path work = example.resolve("build/node-" + UUID.randomUUID());
        String config = """
                ignite {
                  network { port = 33490, nodeFinder.netClusterNodes = ["127.0.0.1:33490"] }
                  network.listenAddresses = ["127.0.0.1"]
                  clientConnector.port = 10890
                  clientConnector.listenAddresses = ["127.0.0.1"]
                  clientConnector.sendServerExceptionStackTraceToClient = true
                  rest.port = 10390
                  storage.profiles = [{name = "default", engine = "aipersist"}]
                }
                """;
        String licensePath = System.getenv("GG_LICENSE");
        Path licenseFile = licensePath == null
                ? source.resolve("dev-utilities/src/main/resources/gridgain-license.json")
                : Path.of(licensePath);
        String license = Files.readString(licenseFile);
        Files.createDirectories(work);
        Path configFile = work.resolve("ignite-config.conf");
        Files.writeString(configFile, config);
        Map<String, Object> evidence = new LinkedHashMap<>();
        IgniteServer server = IgniteServer.start(NODE, configFile, work);
        try {
            server.initCluster(InitParameters.builder().clusterName("sybase-wasm-demo")
                    .metaStorageNodeNames(NODE).license(license).build());
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                    IgniteClient client = IgniteClient.builder().addresses("127.0.0.1:10890").build()) {
                deploy(http, Path.of(System.getProperty("sybase.job.jar")),
                        example.resolve("generated/validate_restock.wasm"));
                client.sql().executeScript("""
                        CREATE TABLE GG9_WASM_INVENTORY (lot INT PRIMARY KEY, sku INT, quantity INT);
                        INSERT INTO GG9_WASM_INVENTORY VALUES (1,101,7), (2,101,5), (3,202,3), (4,303,0);
                        """);
                JobTarget target = JobTarget.anyNode(client.cluster().nodes());
                var nativeJob = WasmJobDescriptor.<Integer, Integer>builder(
                        "validate_restock.wasm", "validate_restock", WasmType.INT32)
                        .units(UNIT).options(WasmJobDescriptor.optionsBuilder().maxRetries(0).build()).build();
                for (int units : new int[] {5, 0, -3}) {
                    int status = client.compute().execute(target, nativeJob, units);
                    if (status != (units > 0 ? 0 : 1)) {
                        throw new AssertionError("Native WASM returned " + status + " for " + units);
                    }
                    evidence.put("native_validate_" + units, Map.of("units", units, "status", status));
                }
                evidence.put("restock_existing", check(client, target, "restock",
                        Map.of("sku", 101, "units", 5), """
                        {"status":0,"outputs":{},"result_sets":[]}
                        """));
                evidence.put("restock_missing", check(client, target, "restock",
                        Map.of("sku", 999, "units", 5), """
                        {"status":2,"outputs":{},"result_sets":[]}
                        """));
                evidence.put("restock_invalid", check(client, target, "restock",
                        Map.of("sku", 101, "units", -5), """
                        {"status":1,"outputs":{},"result_sets":[]}
                        """));
                evidence.put("inventory_summary", check(client, target, "inventory_summary", Map.of(), """
                        {"status":0,"outputs":{"matches":2},"result_sets":[
                          {"columns":["SKU","AVAILABLE","STOCK_LEVEL"],"rows":[[101,22,"ready"],[202,3,"low"]]}
                        ]}
                        """));
                evidence.put("inventory_summary_empty", check(client, target, "inventory_summary",
                        Map.of("minimum", 99), """
                        {"status":2,"outputs":{"matches":0},"result_sets":[]}
                        """));
                evidence.put("inventory_results", check(client, target, "inventory_results", Map.of(), """
                        {"status":0,"outputs":{"matches":2},"result_sets":[
                          {"columns":["SKU","AVAILABLE"],"rows":[[101,22],[202,3]]},
                          {"columns":["MATCHING_SKUS","REQUESTED_MINIMUM"],"rows":[[2,1]]}
                        ]}
                        """));
                evidence.put("inventory_results_empty", check(client, target, "inventory_results",
                        Map.of("minimum", 99), """
                        {"status":0,"outputs":{"matches":0},"result_sets":[
                          {"columns":["SKU","AVAILABLE"],"rows":[]},
                          {"columns":["MATCHING_SKUS","REQUESTED_MINIMUM"],"rows":[[0,99]]}
                        ]}
                        """));
                if (CUSTOMER360) Customer360Checks.run(client, example, evidence);
            }
        } finally {
            server.shutdown();
        }
        server = IgniteServer.start(NODE, configFile, work);
        try {
            server.waitForInitAsync().orTimeout(60, java.util.concurrent.TimeUnit.SECONDS).join();
            try (IgniteClient client = IgniteClient.builder().addresses("127.0.0.1:10890").build()) {
                evidence.put("inventory_summary_after_restart", check(client, JobTarget.anyNode(client.cluster().nodes()),
                        "inventory_summary", Map.of(), """
                        {"status":0,"outputs":{"matches":2},"result_sets":[
                          {"columns":["SKU","AVAILABLE","STOCK_LEVEL"],"rows":[[101,22,"ready"],[202,3,"low"]]}
                        ]}
                        """));
                if (CUSTOMER360) Customer360Checks.afterRestart(client, evidence);
            }
        } finally {
            server.shutdown();
        }
        evidence.put("storage", Map.of("product", "GridGain 9", "engine", "aipersist", "directory", work.toString()));
        String output = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence);
        Files.writeString(example.resolve("build/demo-results.json"), output + "\n");
        System.out.println(output);
        System.out.println(CUSTOMER360
                ? "ALL COMPUTE CHECKS PASSED (40 client calls: 34 responses + 6 expected failures; persistent data after restart)"
                : "ALL COMPUTE CHECKS PASSED (3 native + 8 Java-wrapper calls, including persistent data after restart)");
    }

    private static JsonNode check(IgniteClient client, JobTarget target,
            String procedure, Map<String, Integer> parameters, String expectedJson) throws Exception {
        var descriptor = JobDescriptor.<String, String>builder("example.sybase.SybaseWasmJob")
                .units(UNIT).options(JobExecutionOptions.builder().maxRetries(0).build()).build();
        String request = JSON.writeValueAsString(Map.of("procedure", procedure, "parameters", parameters, "trace", TRACE));
        JsonNode result = JSON.readTree(client.compute().execute(target, descriptor, request));
        var expected = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(expectedJson);
        expected.put("node", NODE);
        if (!expected.equals(result)) {
            throw new AssertionError(procedure + ": expected " + expected + ", got " + result);
        }
        return result;
    }

    private static void deploy(HttpClient http, Path... files) throws Exception {
        String boundary = "sybase-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Path file : files) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"unitContent\"; filename=\""
                    + file.getFileName() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(Files.readAllBytes(file));
            body.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create(REST + "units/" + UNIT.name() + "/" + UNIT.version()
                        + "?deployMode=ALL"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Deployment failed: " + response.statusCode() + " " + response.body());
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            var status = http.send(HttpRequest.newBuilder(URI.create(REST + "cluster/units/" + UNIT.name()))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (status.statusCode() != 200) {
                throw new IllegalStateException("Deployment status failed: " + status.statusCode() + " " + status.body());
            }
            for (JsonNode unit : JSON.readTree(status.body())) {
                for (JsonNode version : unit.path("versionToStatus")) {
                    if (version.path("version").asText().equals(UNIT.version().toString())
                            && version.path("status").asText().equals("DEPLOYED")) {
                        return;
                    }
                }
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Deployment did not reach DEPLOYED within 60 seconds");
    }
}
