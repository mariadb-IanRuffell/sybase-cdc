package example.sybase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.compute.ComputeException;
import org.apache.ignite.compute.JobDescriptor;
import org.apache.ignite.compute.JobExecutionOptions;
import org.apache.ignite.compute.JobTarget;
import org.apache.ignite.deployment.DeploymentUnit;
import org.apache.ignite.tx.Transaction;

/** Runs every pinned fixture through the deployed Java wrapper, asserting real SQL results. */
final class CorpusChecks {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JobDescriptor<String, String> JOB = JobDescriptor.<String, String>builder("example.sybase.SybaseWasmJob")
            .units(new DeploymentUnit("sybase-wasm-example", "1.0.0"))
            .options(JobExecutionOptions.builder().maxRetries(0).build()).build();
    private final IgniteClient client;
    private final Path example;
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private String fixture;

    private CorpusChecks(IgniteClient client, Path example) { this.client = client; this.example = example; }

    static void run(IgniteClient client, Path example, Map<String, Object> evidence) throws Exception {
        new CorpusChecks(client, example).run(evidence);
    }

    private void run(Map<String, Object> evidence) throws Exception {
        client.sql().executeScript("""
                CREATE TABLE records_tests (__fixture_id UUID DEFAULT RAND_UUID PRIMARY KEY, data_field INT);
                CREATE TABLE test_text_table (__fixture_id UUID DEFAULT RAND_UUID PRIMARY KEY, Fragment VARCHAR(2000000));
                CREATE TABLE AllTypes (ID INT PRIMARY KEY, char20DataType VARCHAR(20));
                CREATE TABLE Person (PersonID INT PRIMARY KEY, FirstName VARCHAR(50), LastName VARCHAR(50), MiddleName VARCHAR(50), Gender VARCHAR(1));
                INSERT INTO Person VALUES (1,'John','Pupkin',NULL,'M'),(4,'Jürgen','König','Ko','M');
                INSERT INTO id_seq VALUES ('alltypes_id',1);
                """);
        JsonNode manifest = JSON.readTree(Files.readString(example.resolve("generated/corpus-manifest.json")));
        Map<String, Object> results = new LinkedHashMap<>();
        int passed = 0, blocked = 0, failed = 0;
        var entries = manifest.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            fixture = entry.getKey();
            JsonNode spec = entry.getValue();
            checks.clear();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("procedure", spec.get("procedure").asText());
            record.put("source_sha256", spec.get("sha256").asText());
            record.put("runtime_digest", spec.get("runtime_digest").asText());
            record.put("artifact_digest", spec.path("artifact_digest").asText());
            try {
                if (!spec.get("compiled").asBoolean()) {
                    expectFailure(Map.of(), "Requires unavailable ASE services", "ASE service rejection");
                    record.put("state", "blocked");
                    record.put("capabilities", spec.get("capabilities"));
                    record.put("executed_wasm", false);
                } else {
                    JsonNode abi = JSON.readTree(Files.readString(example.resolve("generated/" + spec.get("resource_prefix").asText()
                            + spec.get("procedure").asText() + ".abi.json")));
                    exercise(spec.get("procedure").asText(), abi);
                    record.put("state", "passed");
                    record.put("executed_wasm", true);
                }
                require(noTemporaryTables(), "temporary tables leaked after " + fixture);
                if (spec.get("compiled").asBoolean()) passed++; else blocked++;
                System.out.println("CORPUS " + record.get("state") + " " + fixture + " (" + checks.size() + " calls)");
            } catch (Exception | AssertionError error) {
                record.put("state", "failed");
                record.put("diagnostic", String.valueOf(error.getMessage()));
                failed++;
                System.out.println("CORPUS FAILED " + fixture + ": " + error);
            }
            record.put("checks", new ArrayList<>(checks));
            results.put(fixture, record);
        }
        var report = Map.of("fixtures", results, "passed", passed, "blocked", blocked, "failed", failed,
                "scope", "Java wrapper execution on GridGain; not an ASE equivalence test");
        Files.writeString(example.resolve("build/corpus-results.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        evidence.put("corpus", Map.of("passed", passed, "blocked", blocked, "failed", failed));
        require(failed == 0 && passed == 30 && blocked == 7, "Corpus results: " + passed + " passed, " + blocked + " blocked, " + failed + " failed");
        System.out.println("ALL CORPUS CHECKS PASSED (30 executed fixtures; 7 ASE-service rejections verified through the wrapper)");
    }

    private void exercise(String name, JsonNode abi) throws Exception {
        String text = "Unicode café 日本語 'quoted'\n" + "x".repeat(65_536);
        switch (name) {
            case "AseDataAdapterTests_Proc1": {
                Map<String, Object> input = new LinkedHashMap<>();
                for (JsonNode p : abi.get("parameters")) {
                    String key = p.asText();
                    if (abi.get("defaults").has(key) || key.equals("o1")) continue;
                    JsonNode type = abi.get("types").get("@" + key);
                    Object value = switch (type.get("kind").asText()) {
                        case "integer" -> type.get("ase").asText().equals("bit") ? 1 : 7;
                        case "decimal" -> "12.1250";
                        case "float" -> 1.25;
                        case "binary" -> "0x01fe";
                        case "string" -> "sample";
                        case "date" -> "2024-02-29";
                        case "time" -> "12:34:56";
                        case "datetime" -> "2026-11-18T12:34:00";
                        default -> throw new AssertionError(type);
                    };
                    input.put(key, value);
                }
                JsonNode r = invoke(input, "all parameter families");
                ok(r, 0, 1); require(r.path("outputs").path("o1").isNull(), "unassigned output is NULL");
                rows(r, 0, "[[1,\"something\"]]");
                break;
            }
            case "Test_Select": case "Test_Insert": case "Test_Delete": case "Test_Update":
            case "Test_Select_Insert": case "Test_Insert_Select": case "Test_Insert_Delete": {
                client.sql().executeScript("DELETE FROM records_tests;");
                if (!name.equals("Test_Insert_Select")) client.sql().executeScript("INSERT INTO records_tests(data_field) VALUES(7);");
                JsonNode r = invoke(Map.of(), "statement sequence");
                int sets = name.contains("Select") ? 1 : 0;
                ok(r, 0, sets);
                if (sets == 1) {
                    require(r.path("result_sets").get(0).path("columns").toString().equals("[\"DATA_FIELD\"]"), "synthetic key is hidden");
                    rows(r, 0, name.equals("Test_Insert_Select") ? "[[1]]" : "[[7]]");
                }
                int count = name.contains("Delete") ? 0 : name.equals("Test_Insert") || name.equals("Test_Select_Insert") ? 2 : 1;
                require(scalar("SELECT COUNT(*) FROM records_tests").intValue() == count, "actual DML count");
                if (name.equals("Test_Update")) require(scalar("SELECT data_field FROM records_tests").intValue() == 1, "UPDATE persisted");
                break;
            }
            case "sp_test_echo": {
                JsonNode r=invoke(Map.of("nechovalue",-17),"signed status and two result sets");
                ok(r,-17,2); output(r,"nechovaluereturn",-17); rows(r,0,"[[-17]]"); rows(r,1,"[[-16]]");
                break;
            }
            case "sp_test_echo_char": {
                JsonNode r=invoke(Map.of("input","A"),"CHAR output"); ok(r,0,0); output(r,"output","A");
                r=invoke(Map.of("input",""),"empty CHAR"); output(r,"output"," ");
                r=invoke(JSON.readTree("{\"input\":null}"),"NULL output"); require(r.path("outputs").path("output").isNull(),"NULL preserved");
                break;
            }
            case "sp_test_echo_string": {
                JsonNode r=invoke(Map.of("input","hello"),"fixed CHAR padding"); ok(r,0,0); output(r,"output","hello"+" ".repeat(250)); break;
            }
            case "sp_test_echo_binary": {
                JsonNode r=invoke(Map.of("input","0xdeadbeef"),"fixed binary padding"); ok(r,0,0); output(r,"output","0xdeadbeef"+"00".repeat(251)); break;
            }
            case "sp_multitype_output": {
                JsonNode r=invoke(Map.of("inbinary","0xdeadbeef"),"character and binary outputs"); ok(r,0,0);
                output(r,"echochar","X"); output(r,"echobinary","0xdeadbeef"+"00".repeat(12)); break;
            }
            case "EchoParameter": {
                JsonNode r=invoke(Map.of("parameter2","second","parameter1","first"),"named inputs and string plus"); ok(r,0,1); rows(r,0,"[[\"first second\"]]");
                r=invoke(JSON.readTree("{\"parameter1\":null,\"parameter2\":\"second\"}"),"NULL concatenation"); rows(r,0,"[[\" second\"]]");
                String quoted="'; DROP TABLE Person; --";
                r=invoke(Map.of("parameter1",quoted,"parameter2","bound"),"SQL injection stays data");
                require(r.path("result_sets").get(0).path("rows").get(0).get(0).asText().equals(quoted+" bound"),"bound string");
                require(scalar("SELECT COUNT(*) FROM Person").intValue()==2,"Person still exists"); break;
            }
            case "sp_test_173": {
                JsonNode r=invoke(Map.of(),"exact SET decimal output"); ok(r,0,0); output(r,"totalvalue","2819.0444"); break;
            }
            case "sp_test_simple": ok(invoke(Map.of(),"bare RETURN"),0,0); break;
            case "sp_test_raiseerror": {
                JsonNode r=invoke(Map.of(),"RAISERROR and NULL return contract"); ok(r,0,0);
                require(r.path("messages").get(0).path("number").asInt()==100000,"error number");
                require(r.path("messages").get(0).path("message").asText().equals("BAD BAD BAD"),"error text");
                require(r.path("messages").get(1).path("kind").asText().equals("warning"),"NULL status warning"); break;
            }
            case "sp_test_message_order": {
                JsonNode r=invoke(Map.of(),"interleaved PRINT/results"); ok(r,0,3); output(r,"status","OK");
                rows(r,0,"[[\"value1\"]]"); rows(r,1,"[]"); rows(r,2,"[[\"value3\"]]");
                require(eventKinds(r).equals(List.of("message","message","result_set","message","result_set","message","result_set","message")),"message/result order");
                require(r.path("messages").get(0).path("message").asText().equals("Report Header"),"header");
                require(r.path("messages").get(4).path("message").asText().equals("Report Trailer"),"trailer");
                r=invoke(Map.of("runselect","N"),"skipped result sets"); ok(r,0,0); require(r.path("messages").size()==5,"PRINT still executes"); break;
            }
            case "stream-messages": {
                JsonNode r=invoke(Map.of("sleeptime","00:00:00","numberofloops",3),"buffered PRINT loop"); ok(r,0,0);
                require(r.path("messages").size()==3,"three messages");
                for(JsonNode m:r.path("messages")) require(m.path("message").asText().equals("General Kenobi"),"print value");
                long start=System.nanoTime();
                r=invoke(Map.of("sleeptime","00:00:01","numberofloops",1),"actual WAITFOR delay");
                require(System.nanoTime()-start>=900_000_000L,"WAITFOR must wait");
                expectFailure(Map.of("sleeptime","00:01:01","numberofloops",1),"WAITFOR budget exceeded","bounded WAITFOR"); break;
            }
            case "large-results": {
                JsonNode r=invoke(Map.of("numberofrecords",1000),"temporary table and 1000 rows"); largeRows(r,1000);
                require(noTemporaryTables(),"explicit DROP cleaned temporary table");
                r=invoke(Map.of("numberofrecords",10000),"cross join and 10000 rows"); largeRows(r,10000);
                expectFailure(Map.of("numberofrecords",100000),"Buffered row limit exceeded","bounded large results");
                require(noTemporaryTables(),"failed query still cleaned temporary table"); break;
            }
            case "sp_test_echo_text": {
                JsonNode r=invoke(Map.of("input",text),"text above 64 KiB"); ok(r,0,0); output(r,"output",text); break;
            }
            case "sp_test_true_echo_text_locator": case "sp_test_echo_text_locator": {
                JsonNode r=invoke(Map.of("input",Map.of("text",text)),"materialized locator input"); ok(r,0,0);
                output(r,"output",abi.path("types").path("@output").path("kind").asText().equals("string") ? text : 100);
                expectFailure(Map.of("input","0x1234"),"text_locator requires","reject raw ASE locator token"); break;
            }
            case "sp_test_text_type_coloumn": {
                JsonNode r=invoke(Map.of("text_locator_input",Map.of("text",text),"text_input",text),"text/locator parameters"); ok(r,0,0); output(r,"output",10); break;
            }
            case "sp_just_test_echo_text": {
                JsonNode r=invoke(Map.of("input",text),"text input"); ok(r,0,0); output(r,"output",10); break;
            }
            case "sp_insert_test_echo_text": {
                client.sql().executeScript("DELETE FROM test_text_table;");
                JsonNode r=invoke(Map.of("input",text),"text INSERT into GridGain"); ok(r,0,0); output(r,"output",10);
                try(var rs=client.sql().execute((Transaction)null,"SELECT Fragment FROM test_text_table")) {
                    require(rs.next().stringValue(0).equals(text),"stored text equals input"); require(!rs.hasNext(),"single insert");
                }
                break;
            }
            case "AddIssue792Record": {
                JsonNode r=invoke(Map.of(),"identity INSERT"); ok(r,0,0);
                require(scalar("SELECT ID FROM AllTypes").intValue()==1,"identity allocation");
                try(var rs=client.sql().execute((Transaction)null,"SELECT char20DataType FROM AllTypes")) { require(rs.next().stringValue(0).equals("issue792"),"inserted value"); }
                break;
            }
            case "Person_SelectAll": {
                JsonNode r=invoke(Map.of(),"schema-based SELECT star"); ok(r,0,1);
                List<String> actual=new ArrayList<>();
                for(JsonNode row:r.path("result_sets").get(0).path("rows")) actual.add(row.toString());
                List<String> expected=List.of("[1,\"John\",\"Pupkin\",null,\"M\"]","[4,\"Jürgen\",\"König\",\"Ko\",\"M\"]");
                require(actual.stream().sorted().toList().equals(expected.stream().sorted().toList()),"all Person columns/rows"); break;
            }
            default: throw new AssertionError("No executable test for " + name);
        }
    }

    private JsonNode invoke(Object parameters, String label) throws Exception {
        String response=client.compute().execute(JobTarget.anyNode(client.cluster().nodes()),JOB,
                JSON.writeValueAsString(Map.of("fixture",fixture,"parameters",parameters,
                        "trace",Boolean.parseBoolean(System.getenv().getOrDefault("SYBASE_TRACE","false")))));
        JsonNode result=JSON.readTree(response);
        List<Integer> rowCounts=new ArrayList<>();
        for(JsonNode set:result.path("result_sets")) rowCounts.add(set.path("rows").size());
        checks.add(Map.of("case",label,"status",result.path("status").asInt(),"row_counts",rowCounts,
                "message_count",result.path("messages").size(),"events",result.path("events")));
        return result;
    }

    private void expectFailure(Object parameters, String fragment, String label) throws Exception {
        try { invoke(parameters,label); }
        catch(ComputeException expected) {
            require(expected.getMessage().contains(fragment),label+": wrong failure: "+expected.getMessage());
            checks.add(Map.of("case",label,"expected_failure",fragment)); return;
        }
        throw new AssertionError("Expected failure: "+label);
    }

    private Number scalar(String sql) {
        try(var rs=client.sql().execute((Transaction)null,sql)) { return rs.next().value(0); }
    }

    private boolean noTemporaryTables() {
        return scalar("SELECT COUNT(*) FROM SYSTEM.TABLES WHERE TABLE_NAME LIKE 'WASM_TMP_%'").intValue()==0;
    }

    static void afterRestart(IgniteClient client) {
        try(var rs=client.sql().execute((Transaction)null,"SELECT LENGTH(Fragment) FROM test_text_table")) {
            require(rs.next().intValue(0)>65_536,"corpus text persists after restart");
        }
        try(var rs=client.sql().execute((Transaction)null,"SELECT ID FROM AllTypes")) {
            require(rs.next().intValue(0)==1,"corpus identity row persists after restart");
        }
    }

    private static void largeRows(JsonNode result,int count) {
        ok(result,0,1);
        JsonNode set=result.path("result_sets").get(0);
        require(set.path("columns").size()==2,"hidden temporary key must not leak");
        require(set.path("rows").size()==count,"large row count");
        for(JsonNode row:set.path("rows")) {
            require(row.get(0).asInt()>=0 && row.get(0).asInt()<1000,"temporary row id");
            require(row.get(1).asText().equals("X".repeat(4000)),"large row payload");
        }
    }

    private static void ok(JsonNode result,int status,int sets) {
        require(result.path("status").asInt()==status,"return status "+result.path("status"));
        require(result.path("result_sets").size()==sets,"result-set count");
    }
    private static void output(JsonNode result,String key,Object expected) {
        require(result.path("outputs").path(key).equals(JSON.valueToTree(expected)),"output "+key+" differs");
    }
    private static void rows(JsonNode result,int index,String expected) throws Exception {
        require(result.path("result_sets").get(index).path("rows").equals(JSON.readTree(expected)),"result rows "+index+": expected "+expected+", got "+result.path("result_sets").get(index).path("rows"));
    }
    private static List<String> eventKinds(JsonNode result) {
        List<String> kinds=new ArrayList<>();
        for(JsonNode e:result.path("events")) kinds.add(e.path("kind").asText());
        return kinds;
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
