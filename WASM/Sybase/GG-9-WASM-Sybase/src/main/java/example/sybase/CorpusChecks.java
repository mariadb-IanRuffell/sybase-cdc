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
                CREATE TABLE META_TARGET (z INT, a VARCHAR(20), id INT PRIMARY KEY);
                INSERT INTO META_TARGET VALUES (30,'third',3),(10,'first',1),(20,'second',2);
                CREATE TABLE META_EMPTY (id INT PRIMARY KEY);
                CREATE TABLE META_SINGLE (id INT PRIMARY KEY);
                INSERT INTO META_SINGLE VALUES (42);
                CREATE SCHEMA META_OTHER;
                CREATE TABLE META_OTHER.META_TARGET (id INT PRIMARY KEY, note VARCHAR(20));
                INSERT INTO META_OTHER.META_TARGET VALUES (8,'other');
                CREATE TABLE "Meta"" Odd;--" (id INT PRIMARY KEY, "a""quoted" INT, "select" VARCHAR(10));
                INSERT INTO "Meta"" Odd;--" VALUES (7,99,'safe');
                """);
        JsonNode manifest = JSON.readTree(Files.readString(example.resolve("generated/corpus-manifest.json")));
        Map<String, Object> results = new LinkedHashMap<>();
        int passed = 0, adapted = 0, mocked = 0, blocked = 0, failed = 0;
        Map<String, Object> dbcc = checkDbccMock();
        var entries = manifest.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            fixture = entry.getKey();
            JsonNode spec = entry.getValue();
            checks.clear();
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("procedure", spec.get("procedure").asText());
            record.put("source_sha256", spec.get("sha256").asText());
            if (spec.has("adaptation")) {
                record.put("adaptation", spec.get("adaptation").asText());
                record.put("adaptation_kind", spec.get("adaptation_kind").asText());
                record.put("adaptation_sha256", spec.get("adaptation_sha256").asText());
            }
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
                    record.put("state", switch (spec.path("adaptation_kind").asText()) {
                        case "metadata" -> "adapted";
                        case "mock" -> "mocked";
                        default -> "passed";
                    });
                    record.put("executed_wasm", true);
                }
                require(noTemporaryTables(), "temporary tables leaked after " + fixture);
                switch (record.get("state").toString()) {
                    case "passed" -> passed++;
                    case "adapted" -> adapted++;
                    case "mocked" -> mocked++;
                    case "blocked" -> blocked++;
                    default -> throw new AssertionError(record);
                }
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
                "adapted", adapted, "mocked", mocked, "dbcc_mock", dbcc,
                "scope", "Java wrapper execution: originals, explicit metadata ports and explicit mocks; not an ASE equivalence test");
        Files.writeString(example.resolve("build/corpus-results.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        evidence.put("corpus", Map.of("passed", passed, "adapted", adapted, "mocked", mocked, "blocked", blocked, "failed", failed));
        require(failed == 0 && passed == 30 && adapted == 3 && mocked == 1 && blocked == 3, "Corpus results: " + passed + " passed, " + adapted + " adapted, " + mocked + " mocked, " + blocked + " blocked, " + failed + " failed");
        System.out.println("ALL CORPUS CHECKS PASSED (30 original + 3 metadata adaptations + 1 mock; 3 ASE-service rejections; DBCC no-op verified)");
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
            case "sp_#count": metadataCounts(); break;
            case "sp_#ls": metadataObjects(); break;
            case "sp_#select": metadataSelect(); break;
            case "sp_thresholdaction": {
                long before=scalar("SELECT COUNT(*) FROM META_TARGET").longValue();
                for (int status : new int[]{0,1}) {
                    JsonNode r=invokeNamed("sp_thresholdaction",Map.of("dbname","'; DROP TABLE META_TARGET;--", "segmentname","logsegment",
                            "space_left",0,"status",status),"mock threshold status " + status);
                    ok(r,0,0);
                    require(r.path("messages").size()==1 && r.path("messages").get(0).path("message").asText().startsWith("MOCK sp_thresholdaction:"),"visible threshold mock");
                }
                require(scalar("SELECT COUNT(*) FROM META_TARGET").longValue()==before,"threshold mock did not change data");
                break;
            }
            default: throw new AssertionError("No executable test for " + name);
        }
    }

    private void metadataCounts() throws Exception {
        JsonNode r=invokeNamed("sp_#count",Map.of("objname","META%"),"exact populated/empty counts and NULL sizes");
        ok(r,0,1);
        rows(r,0,"[[\"PUBLIC\",\"META_EMPTY\",\"0\",null,null,null],[\"PUBLIC\",\"META_SINGLE\",\"1\",null,null,null],[\"PUBLIC\",\"META_TARGET\",\"3\",null,null,null]]");
        require(r.path("messages").get(0).path("message").asText().contains("size fields are NULL"),"unavailable physical sizes disclosed");
        r=invoke(Map.of("schema","META_OTHER","objname","META_TARGET"),"schema-isolated counts");
        rows(r,0,"[[\"META_OTHER\",\"META_TARGET\",\"1\",null,null,null]]");
        r=invoke(Map.of("objname","Meta%"),"quote metadata identifiers for COUNT");
        require(r.path("result_sets").get(0).path("rows").get(0).get(2).asText().equals("1"),"quoted table counted");
        client.sql().executeScript("INSERT INTO META_TARGET VALUES (40,'fourth',4);");
        r=invoke(Map.of("objname","META_TARGET"),"fresh count after thin-client write");
        require(r.path("result_sets").get(0).path("rows").get(0).get(2).asText().equals("4"),"fresh count");
        client.sql().executeScript("DELETE FROM META_TARGET WHERE id=4;");
        r=invoke(Map.of("objname","DOES_NOT_EXIST"),"empty table filter"); ok(r,0,1); rows(r,0,"[]");
        r=invoke(Map.of(),"default schema enumeration excludes work tables"); ok(r,0,1);
        for(JsonNode row:r.path("result_sets").get(0).path("rows")) {
            require(row.get(0).asText().equals("PUBLIC") && !row.get(1).asText().startsWith("WASM_TMP_"),"table scope");
            require(row.get(3).isNull() && row.get(4).isNull() && row.get(5).isNull(),"no fabricated physical sizes");
        }
    }

    private void metadataObjects() throws Exception {
        JsonNode r=invokeNamed("sp_#ls",Map.of("objname","META"),"table substring filtering"); ok(r,0,1);
        rows(r,0,"[[\"META_EMPTY\",\"TABLE\",\"PUBLIC\",null,null],[\"META_SINGLE\",\"TABLE\",\"PUBLIC\",null,null],[\"META_TARGET\",\"TABLE\",\"PUBLIC\",null,null]]");
        r=invoke(Map.of("objname","U","schema","META_OTHER"),"table type and schema filter");
        rows(r,0,"[[\"META_TARGET\",\"TABLE\",\"META_OTHER\",null,null]]");
        r=invoke(Map.of("objname","S"),"GridGain system views"); ok(r,0,1);
        boolean found=false;
        for(JsonNode row:r.path("result_sets").get(0).path("rows")) {
            found |= row.get(0).asText().equals("TABLE_COLUMNS");
            require(row.get(1).asText().equals("SYSTEM_VIEW") && row.get(2).asText().equals("SYSTEM"),"system view identity");
            require(row.get(3).isNull() && row.get(4).isNull(),"no fabricated ownership/timestamps");
        }
        require(found,"actual TABLE_COLUMNS system view listed");
        for(String type:List.of("D","P","TR","V","R","SP")) {
            r=invoke(Map.of("objname",type),"unsupported category " + type); ok(r,1,0);
            require(r.path("messages").size()==1,"unsupported type explained");
        }
        r=invoke(Map.of("objname","'; DROP TABLE META_TARGET;--"),"object filter stays data"); ok(r,0,1); rows(r,0,"[]");
        r=invoke(Map.of(),"default object pattern"); ok(r,0,1);
        require(r.path("result_sets").get(0).path("rows").size()>3,"default includes public tables");
    }

    private void metadataSelect() throws Exception {
        JsonNode r=invokeNamed("sp_#select",Map.of("table","META_TARGET"),"alphabetical SELECT command"); ok(r,0,1);
        String command=selectCommand(r);
        require(command.equals("SELECT\n \"A\"\n,\"ID\"\n,\"Z\"\nFROM \"PUBLIC\".\"META_TARGET\""),"alphabetical generated SQL: " + command);
        try(var rs=client.sql().execute((Transaction)null,command+" ORDER BY \"ID\"")) {
            var row=rs.next(); require(row.stringValue(0).equals("first") && row.intValue(1)==1 && row.intValue(2)==10,"generated query executes");
        }
        r=invoke(Map.of("table","META_SINGLE"),"single-column command");
        require(!selectCommand(r).contains(","),"no leading or trailing comma for one column");
        r=invoke(Map.of("schema","META_OTHER","table","META_TARGET"),"schema-qualified command");
        require(selectCommand(r).contains("\"META_OTHER\".\"META_TARGET\""),"explicit schema");
        r=invoke(Map.of("table","Meta\" Odd;--"),"quoted table and reserved/quoted columns");
        try(var rs=client.sql().execute((Transaction)null,selectCommand(r))) {
            var row=rs.next(); require(row.intValue(0)==7 && row.intValue(1)==99 && row.stringValue(2).equals("safe"),"identifier escaping");
        }
        for(String table:List.of("DOES_NOT_EXIST","'; DROP TABLE META_TARGET;--","PUBLIC.META_TARGET")) {
            r=invoke(Map.of("table",table),"missing/literal name " + table); ok(r,1,0);
            require(r.path("messages").get(0).path("message").asText().equals("Table is undefined."),"missing table status");
        }
        r=invoke(JSON.readTree("{\"table\":null}"),"NULL table name"); ok(r,1,0);
        require(scalar("SELECT COUNT(*) FROM META_TARGET").intValue()==3,"SELECT generator is read only");
    }

    private static String selectCommand(JsonNode response) {
        List<String> lines=new ArrayList<>();
        for(JsonNode row:response.path("result_sets").get(0).path("rows")) lines.add(row.get(0).asText());
        return String.join("\n",lines);
    }

    private Map<String,Object> checkDbccMock() throws Exception {
        String response=client.compute().execute(JobTarget.anyNode(client.cluster().nodes()),JOB,
                JSON.writeValueAsString(Map.of("procedure","dbcc_noop","parameters",Map.of("database","'; DROP TABLE META_TARGET;--"),"trace",true)));
        JsonNode r=JSON.readTree(response); ok(r,0,0);
        require(r.path("messages").size()==2 && r.path("timing").path("sql_statements").asInt(-1)==0,"DBCC performs zero SQL calls");
        for(JsonNode message:r.path("messages")) require(message.path("kind").asText().equals("mock"),"DBCC mock identified");
        require(scalar("SELECT COUNT(*) FROM META_TARGET").intValue()==3,"DBCC did not alter rows");
        return Map.of("status",0,"sql_statements",0,"messages",r.path("messages"),"data_unchanged",true);
    }

    private JsonNode invoke(Object parameters, String label) throws Exception {
        return submit(Map.of("fixture",fixture,"parameters",parameters,
                "trace",Boolean.parseBoolean(System.getenv().getOrDefault("SYBASE_TRACE","false"))),label);
    }

    private JsonNode invokeNamed(String name, Object parameters, String label) throws Exception {
        return submit(Map.of("procedure",name,"parameters",parameters,"trace",true),label+" (direct name)");
    }

    private JsonNode submit(Map<String,?> request, String label) throws Exception {
        String response=client.compute().execute(JobTarget.anyNode(client.cluster().nodes()),JOB,JSON.writeValueAsString(request));
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
