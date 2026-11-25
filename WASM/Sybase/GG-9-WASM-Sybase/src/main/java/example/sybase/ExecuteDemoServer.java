package example.sybase;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.apache.ignite.client.IgniteClient;
import java.util.UUID;
import org.apache.ignite.IgniteServer;
import org.apache.ignite.InitParameters;

/**
 * Starts one embedded node with the Customer 360 schema, seed rows and the WASM job unit deployed, then waits until
 * the file {@code build/stop-execute-server} appears. Lets external clients (ODBC, C++, BI tools) call
 * {@code EXECUTE} against a running cluster; see {@code odbc/README.md}.
 */
public class ExecuteDemoServer {
    private static final String NODE = "sybase-wasm-node";

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

            try (IgniteClient client = IgniteClient.builder().addresses("127.0.0.1:10890").build()) {
                client.sql().executeScript(Files.readString(example.resolve("generated/customer360-schema.sql")));
                client.sql().executeScript("""
                        INSERT INTO loyalty_tier_rule VALUES ('BLUE',0,1),('SILVER',5000,1.25),('GOLD',20000,1.5),('PLATINUM',60000,2);
                        INSERT INTO customer VALUES
                          (1,'Ada','Lovelace','ada@example.test',NULL,DATE '1990-01-01','GB','STANDARD','BLUE',0,TRUE,'ACTIVE',LOCALTIMESTAMP,LOCALTIMESTAMP);
                        INSERT INTO address VALUES (1,1,'HOME','Example road','Dubai',NULL,'AE',TRUE);
                        INSERT INTO product VALUES (1,'SKU-1','Ticket','FLIGHT',75.25,10,TRUE);
                        INSERT INTO orders VALUES (100,1,TIMESTAMPADD(DAY,-1,LOCALTIMESTAMP),'COMPLETED','WEB',4900,'AED');
                        INSERT INTO order_item VALUES (100,100,1,1,4900,4900,1);
                        UPDATE id_seq SET next_id=101 WHERE name IN ('order_id','order_item_id');
                        """);
            }
            Path stop = example.resolve("build/stop-execute-server");
            Files.deleteIfExists(stop);
            System.out.println("EXECUTE SERVER READY on 127.0.0.1:10890 (create " + stop + " to stop)");
            while (!Files.exists(stop)) {
                Thread.sleep(500);
            }
        } finally {
            server.shutdown();
        }
    }
}
