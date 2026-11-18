package example.sybase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.compute.JobDescriptor;
import org.apache.ignite.compute.JobExecutionOptions;
import org.apache.ignite.compute.JobTarget;
import org.apache.ignite.deployment.DeploymentUnit;

/** End-to-end checks against the actual deployed WASM jobs and persistent GridGain tables. */
final class Customer360Checks {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JobDescriptor<String, String> JOB = JobDescriptor.<String, String>builder("example.sybase.SybaseWasmJob")
            .units(new DeploymentUnit("sybase-wasm-example", "1.0.0"))
            .options(JobExecutionOptions.builder().maxRetries(0).build()).build();

    static void run(IgniteClient client, Path example, Map<String, Object> evidence) throws Exception {
        client.sql().executeScript(Files.readString(example.resolve("generated/customer360-schema.sql")));
        client.sql().executeScript("""
                INSERT INTO loyalty_tier_rule VALUES ('BLUE',0,1),('SILVER',5000,1.25),('GOLD',20000,1.5),('PLATINUM',60000,2);
                INSERT INTO customer VALUES
                  (1,'Ada','Lovelace','ada@example.test',NULL,DATE '1990-01-01','GB','STANDARD','BLUE',0,1,'ACTIVE',LOCALTIMESTAMP,LOCALTIMESTAMP),
                  (2,'Grace','Hopper','grace@example.test',NULL,NULL,'US','STANDARD','BLUE',0,0,'ACTIVE',LOCALTIMESTAMP,LOCALTIMESTAMP),
                  (3,'Inactive','Customer','inactive@example.test',NULL,NULL,NULL,'STANDARD','BLUE',0,0,'INACTIVE',LOCALTIMESTAMP,LOCALTIMESTAMP);
                INSERT INTO address VALUES (1,1,'HOME','Example road','Dubai',NULL,'AE',1);
                INSERT INTO product VALUES (1,'SKU-1','Ticket','FLIGHT',75.25,10,1),(2,'SKU-2','Inactive','HOTEL',10,10,0);
                INSERT INTO orders VALUES (100,1,TIMESTAMPADD(DAY,-1,LOCALTIMESTAMP),'COMPLETED','WEB',4900,'AED');
                INSERT INTO order_item VALUES (100,100,1,1,4900,4900);
                INSERT INTO interaction VALUES (1,1,'EMAIL','ENQUIRY',NULL,-1,'OPEN',LOCALTIMESTAMP,NULL);
                UPDATE WASM_IDENTITY SET next_id=100 WHERE table_name IN ('orders','order_item');
                UPDATE WASM_IDENTITY SET next_id=3 WHERE table_name='customer';
                UPDATE WASM_IDENTITY SET next_id=2 WHERE table_name='product';
                UPDATE WASM_IDENTITY SET next_id=1 WHERE table_name IN ('address','interaction');
                """);
        JsonNode tier = call(client, evidence, "tier_before", "sp_recalc_loyalty_tier", Map.of("customer_id",1), 0);
        require(tier.path("outputs").path("new_tier").asText().equals("BLUE"), "initial string output");
        call(client,evidence,"tier_missing","sp_recalc_loyalty_tier",Map.of("customer_id",999),-1);
        call(client,evidence,"profile_missing","sp_customer_360",Map.of("customer_id",999),-1);
        call(client,evidence,"create_inactive","sp_create_order",Map.of("customer_id",3),-1);
        JsonNode created = call(client,evidence,"create","sp_create_order",Map.of("customer_id",1),0);
        int order = created.path("outputs").path("order_id").asInt();
        require(order == 101, "generated identity follows the imported high-water mark");
        call(client,evidence,"empty_order","sp_complete_order",Map.of("order_id",order),-7);
        call(client,evidence,"complete_missing","sp_complete_order",Map.of("order_id",999),-5);
        call(client,evidence,"bad_quantity","sp_add_order_item",Map.of("order_id",order,"product_id",1,"qty",0),-4);
        call(client,evidence,"order_missing","sp_add_order_item",Map.of("order_id",999,"product_id",1,"qty",1),-5);
        call(client,evidence,"inactive_product","sp_add_order_item",Map.of("order_id",order,"product_id",2,"qty",1),-6);
        call(client,evidence,"product_missing","sp_add_order_item",Map.of("order_id",order,"product_id",999,"qty",1),-6);
        call(client,evidence,"no_stock","sp_add_order_item",Map.of("order_id",order,"product_id",1,"qty",11),-3);
        number(client,"SELECT stock_qty FROM product WHERE product_id=1", "10");
        number(client,"SELECT COUNT(*) FROM order_item WHERE order_id="+order,"0");
        call(client,evidence,"add_items","sp_add_order_item",Map.of("order_id",order,"product_id",1,"qty",2),0);
        number(client,"SELECT stock_qty FROM product WHERE product_id=1","8");
        number(client,"SELECT total_amount FROM orders WHERE order_id="+order,"150.50");

        // Force a real SQL failure after the stock UPDATE. The wrapper must roll it back.
        client.sql().executeScript("UPDATE WASM_IDENTITY SET next_id=99 WHERE table_name='order_item';");
        expectFailure(client,"sp_add_order_item",Map.of("order_id",order,"product_id",1,"qty",1),"duplicate identity");
        number(client,"SELECT stock_qty FROM product WHERE product_id=1","8");
        number(client,"SELECT total_amount FROM orders WHERE order_id="+order,"150.50");
        client.sql().executeScript("UPDATE WASM_IDENTITY SET next_id=101 WHERE table_name='order_item';");

        call(client,evidence,"complete","sp_complete_order",Map.of("order_id",order),0);
        number(client,"SELECT loyalty_points FROM customer WHERE customer_id=1","150");
        number(client,"SELECT amount FROM payment WHERE order_id="+order,"150.50");
        number(client,"SELECT points FROM loyalty_txn WHERE order_id="+order,"150");
        JsonNode after = call(client,evidence,"tier_after","sp_recalc_loyalty_tier",Map.of("customer_id",1),0);
        require(after.path("outputs").path("new_tier").asText().equals("SILVER"),"nested procedure updated tier");
        JsonNode profile = call(client,evidence,"customer_360","sp_customer_360",Map.of("customer_id",1),0);
        require(profile.path("result_sets").size()==6,"six ordered result sets");
        require(profile.path("result_sets").get(0).path("rows").get(0).get(7).asText().equals("SILVER"),"profile tier");
        require(profile.path("result_sets").get(1).path("rows").get(0).get(1).asText().equals("5050.50"),"exact aggregate decimal");
        JsonNode noOrders = call(client,evidence,"customer_without_orders","sp_customer_360",Map.of("customer_id",2),0);
        require(noOrders.path("result_sets").get(2).path("rows").isEmpty(),"empty result set retained");
        // Score one day after the newest fixture order; never round the cutoff before that order.
        // Read the typed timestamp directly, preserving its fractional seconds.
        String asOf;
        try (var rs=client.sql().execute((org.apache.ignite.tx.Transaction) null,"SELECT TIMESTAMPADD(DAY,1,MAX(order_date)) FROM orders")) {
            asOf=rs.next().<java.time.LocalDateTime>value(0).toString();
        }
        call(client,evidence,"score_insert","sp_score_customers",Map.of("as_of",asOf),0);
        number(client,"SELECT COUNT(*) FROM customer_score","2");
        number(client,"SELECT churn_score FROM customer_score WHERE customer_id=1","25");
        number(client,"SELECT churn_score FROM customer_score WHERE customer_id=2","90");
        call(client,evidence,"score_update","sp_score_customers",Map.of("as_of",asOf,"customer_id",1),0);
        number(client,"SELECT COUNT(*) FROM customer_score","2");
        JsonNode noCursorRows = call(client,evidence,"score_empty","sp_score_customers",Map.of("as_of","2000-01-01T00:00:00","customer_id",999),0);
        require(noCursorRows.path("result_sets").size()==1 && noCursorRows.path("result_sets").get(0).path("rows").isEmpty(),"empty cursor terminates");
        call(client,evidence,"score_defaults","sp_score_customers",Map.of("customer_id",2),0);

        Map<String,Object> types = new LinkedHashMap<>();
        types.put("tiny",255); types.put("small",-32768); types.put("big","9223372036854775807");
        types.put("huge","18446744073709551615"); types.put("amount","12345678901234567890123456789.123456789");
        types.put("cash","123.4567"); types.put("smallcash","-214748.3648");
        types.put("real_value",1.25); types.put("float_value",-2.125); types.put("text_value","日本語 – café");
        types.put("fixed","A"); types.put("bytes","0x00feff"); types.put("version","0x00000000000000ff");
        types.put("day","2024-02-29"); types.put("clock","12:34:56.123456"); types.put("instant","2026-11-18T12:34:56.123456");
        types.put("flag",true);
        JsonNode roundtrip = call(client,evidence,"typed_roundtrip","typed_roundtrip",types,0);
        JsonNode output=roundtrip.path("outputs");
        for (String key: new String[]{"big","huge","amount","cash","smallcash","text_value","bytes","version","day","clock","instant"}) {
            require(output.path(key).asText().equals(types.get(key)),"typed roundtrip "+key+": "+output.path(key));
        }
        require(output.path("fixed").asText().equals("A   "),"fixed char padding");
        require(output.path("nullable").isNull(),"SQL NULL output");
        require(output.path("flag").asInt()==1,"bit roundtrip");
        expectFailure(client,"typed_roundtrip",Map.of("tiny",256),"tinyint overflow");
        expectFailure(client,"typed_roundtrip",Map.of("small",1.5),"fractional integer input");
        expectFailure(client,"typed_roundtrip",Map.of("amount","1.0000000001"),"decimal scale loss");
        expectFailure(client,"typed_roundtrip",Map.of("bytes","invalid"),"invalid binary input");
        expectFailure(client,"typed_roundtrip",Map.of("fixed","too long"),"oversized string");
        System.out.println("CUSTOMER 360 CHECKS PASSED (all six procedures, typed SQL roundtrips, cursor upsert, rollback and six result sets)");
    }

    static void afterRestart(IgniteClient client, Map<String,Object> evidence) throws Exception {
        JsonNode result=call(client,evidence,"customer_360_after_restart","sp_customer_360",Map.of("customer_id",1),0);
        require(result.path("result_sets").size()==6,"persistent customer result sets");
        number(client,"SELECT loyalty_points FROM customer WHERE customer_id=1","150");
        number(client,"SELECT COUNT(*) FROM customer_score","2");
        number(client,"SELECT stock_qty FROM product WHERE product_id=1","8");
    }

    private static String execute(IgniteClient client,String procedure,Map<String,?> parameters) throws Exception {
        return client.compute().execute(JobTarget.anyNode(client.cluster().nodes()),JOB,
                JSON.writeValueAsString(Map.of("procedure",procedure,"parameters",parameters,
                        "trace",Boolean.parseBoolean(System.getenv().getOrDefault("SYBASE_TRACE","true")))));
    }

    private static JsonNode call(IgniteClient client,Map<String,Object> evidence,String label,String proc,Map<String,?> params,int status) throws Exception {
        JsonNode result=JSON.readTree(execute(client,proc,params));
        require(result.path("status").asInt()==status,label+" unexpected status: "+result);
        require(status==0 ? result.path("messages").isEmpty() : !result.path("messages").isEmpty(),label+" error messages");
        evidence.put(label,result);
        return result;
    }

    private static void expectFailure(IgniteClient client,String proc,Map<String,?> params,String label) throws Exception {
        try { execute(client,proc,params); }
        catch (org.apache.ignite.compute.ComputeException expected) { return; }
        throw new AssertionError("Expected job failure: "+label);
    }

    private static void number(IgniteClient client,String sql,String expected) {
        try (var rs=client.sql().execute((org.apache.ignite.tx.Transaction) null,sql)) {
            Object actual=rs.next().value(0);
            require(new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected))==0,sql+": "+actual+" != "+expected);
        }
    }

    private static void require(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
    }
}
