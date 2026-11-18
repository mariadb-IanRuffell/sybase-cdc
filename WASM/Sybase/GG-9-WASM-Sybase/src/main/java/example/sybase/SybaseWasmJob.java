package example.sybase;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.dylibso.chicory.wasm.types.ExternalType;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.dylibso.chicory.wasm.types.Instruction;
import com.dylibso.chicory.wasm.types.OpCode;
import com.dylibso.chicory.wasm.types.ValType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.JobExecutionContext;
import org.apache.ignite.sql.SqlRow;

/** Runs a compiled Sybase procedure inside a GridGain compute job with SQL host functions. */
public class SybaseWasmJob implements ComputeJob<String, String> {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Set<String> PROCEDURES = Set.of("restock", "inventory_summary", "inventory_results",
            "sp_recalc_loyalty_tier", "sp_customer_360", "sp_create_order",
            "sp_add_order_item", "sp_complete_order", "sp_score_customers", "typed_roundtrip");

    @Override
    public CompletableFuture<String> executeAsync(JobExecutionContext context, String argument) {
        Trace trace = new Trace();
        try {
            JsonNode request = JSON.readTree(argument);
            if (request == null || !request.isObject()) {
                throw new IllegalArgumentException("Supply a JSON request object");
            }
            String name = request.path("procedure").asText();
            if (request.has("trace") && !request.get("trace").isBoolean()) {
                throw new IllegalArgumentException("trace must be a JSON boolean");
            }
            trace.enabled = request.path("trace").asBoolean(false);
            trace.procedure = name;
            trace.node = context.ignite().name();
            if (!PROCEDURES.contains(name)) {
                throw new IllegalArgumentException("Unknown bundled procedure " + name);
            }
            JsonNode abi = JSON.readTree(resource(name + ".abi.json"));
            if (abi.path("version").asInt() == 2) {
                return CompletableFuture.completedFuture(new TypedWasmRuntime(context).execute(name, request.path("parameters"), trace));
            }
            JsonNode bindings = JSON.readTree(resource(name + ".bindings.json"));
            JsonNode input = request.path("parameters");
            if (!input.isMissingNode() && !input.isObject()) {
                throw new IllegalArgumentException("parameters must be a JSON object");
            }
            List<String> parameters = names(abi.get("parameters"));
            List<String> outputs = names(abi.get("outputs"));
            input.fieldNames().forEachRemaining(key -> {
                if (!parameters.contains(key)) {
                    throw new IllegalArgumentException("Unknown parameter " + key);
                }
            });
            long[] arguments = new long[parameters.size()];
            Map<String, Integer> resolvedParameters = new LinkedHashMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                String key = parameters.get(i);
                JsonNode value = input.get(key);
                if (value == null) {
                    value = abi.path("defaults").get(key);
                }
                if (value == null && outputs.contains(key)) {
                    arguments[i] = 0;
                } else if (value != null && value.isIntegralNumber() && value.canConvertToInt()) {
                    arguments[i] = value.intValue();
                } else {
                    throw new IllegalArgumentException("Supply an INT parameter " + key);
                }
                resolvedParameters.put(key, (int) arguments[i]);
            }
            trace.event("CALL_START", Map.of("parameters", resolvedParameters));

            // ponytail: buffer small demo results; add bounded paging for large procedure results.
            Map<String, List<List<Object>>> snapshots = new LinkedHashMap<>();
            List<Map<String, Object>> resultSets = new ArrayList<>();
            List<HostFunction> functions = new ArrayList<>();
            abi.get("operations").fields().forEachRemaining(entry -> {
                JsonNode operation = entry.getValue();
                int count = operation.get("arguments").size();
                functions.add(new HostFunction("ignite", entry.getKey(),
                        FunctionType.of(Collections.nCopies(count, ValType.I32), List.of(ValType.I32)),
                        (instance, values) -> {
                            if (context.isCancelled()) {
                                throw new IllegalStateException("Compute job cancelled");
                            }
                            String sql = bindings.get(entry.getKey()).asText();
                            List<Object> sqlArguments = new ArrayList<>();
                            for (long value : values) {
                                sqlArguments.add((int) value);
                            }
                            if (operation.has("snapshot")) {
                                List<List<Object>> rows = snapshots.get(operation.get("snapshot").asText());
                                if (rows == null) {
                                    throw new IllegalStateException("Snapshot has not been materialized");
                                }
                                sql = sql.replace("__SNAPSHOT__", snapshotQuery(
                                        rows, names(operation.get("snapshot_columns")), sqlArguments));
                            }

                            int sqlCall = ++trace.sqlCalls;
                            trace.event("SQL_START", Map.of("sql_call", sqlCall, "operation", entry.getKey(),
                                    "kind", operation.get("kind").asText(), "sql", sql, "parameters", sqlArguments));
                            try (var cursor = context.ignite().sql().execute(
                                    null, context.cancellationToken(), sql, sqlArguments.toArray())) {
                                if (operation.get("kind").asText().equals("update")) {
                                    int affected = Math.toIntExact(cursor.affectedRows());
                                    trace.event("SQL_END", Map.of("sql_call", sqlCall, "affected_rows", affected));
                                    trace.event("HOST_RETURN", Map.of("sql_call", sqlCall, "operation", entry.getKey(), "i32", affected));
                                    return new long[] {affected};
                                }
                                List<String> columns = cursor.metadata().columns().stream().map(c -> c.name()).toList();
                                List<List<Object>> rows = new ArrayList<>();
                                while (cursor.hasNext()) {
                                    SqlRow row = cursor.next();
                                    List<Object> cells = new ArrayList<>();
                                    for (int col = 0; col < columns.size(); col++) {
                                        cells.add(row.value(col));
                                    }
                                    rows.add(cells);
                                }
                                trace.event("SQL_END", Map.of("sql_call", sqlCall, "row_count", rows.size()));
                                if (operation.get("kind").asText().equals("snapshot")) {
                                    List<String> expected = names(operation.get("columns"));
                                    if (!columns.stream().map(c -> c.toLowerCase(Locale.ROOT)).toList().equals(expected)) {
                                        throw new IllegalStateException("Snapshot column mismatch " + columns);
                                    }
                                    snapshots.put(operation.get("target").asText(), rows);
                                    trace.event("SNAPSHOT", Map.of("sql_call", sqlCall, "target", operation.get("target").asText(),
                                            "columns", columns, "rows", rows));
                                } else {
                                    resultSets.add(Map.of("columns", columns, "rows", rows));
                                    trace.event("RESULT_SET", Map.of("sql_call", sqlCall, "index", resultSets.size(),
                                            "columns", columns, "rows", rows));
                                }
                                trace.event("HOST_RETURN", Map.of("sql_call", sqlCall, "operation", entry.getKey(), "i32", rows.size()));
                                return new long[] {rows.size()};
                            } catch (Exception error) {
                                trace.event("SQL_ERROR", Map.of("sql_call", sqlCall, "operation", entry.getKey(),
                                        "type", error.getClass().getSimpleName(), "message", String.valueOf(error.getMessage())));
                                throw error;
                            }
                        }));
            });
            WasmModule module = Parser.parse(resource(name + ".wasm"));
            var builder = Instance.builder(module)
                    .withImportValues(ImportValues.builder().addFunction(functions.toArray(HostFunction[]::new)).build());
            if (trace.enabled) {
                Map<Instruction, String> writes = trace.localWrites(module, outputs);
                builder.withUnsafeExecutionListener((instruction, stack) -> {
                    String variable = writes.get(instruction);
                    if (variable != null) {
                        // Chicory calls the listener before LOCAL_SET/LOCAL_TEE consumes the value; peek never changes execution.
                        trace.event(variable.equals("@@rowcount") ? "ROWCOUNT_SET" : "OUTPUT_SET",
                                Map.of("variable", variable, "value", (int) stack.peek()));
                    }
                });
            }
            Instance instance = builder.build();
            trace.event("WASM_CALL", Map.of("arguments", arguments));
            long[] returned = instance.export(name).apply(arguments);
            trace.event("WASM_RETURN", Map.of("values", returned));
            if (returned.length != 1 + outputs.size()) {
                throw new IllegalStateException("Unexpected WASM result arity");
            }
            Map<String, Integer> outputValues = new LinkedHashMap<>();
            for (int i = 0; i < outputs.size(); i++) {
                outputValues.put(outputs.get(i), (int) returned[i + 1]);
                trace.event("OUTPUT", Map.of("parameter", "@" + outputs.get(i), "value", (int) returned[i + 1]));
            }
            String response = JSON.writeValueAsString(Map.of(
                    "node", context.ignite().name(), "status", (int) returned[0],
                    "outputs", outputValues, "result_sets", resultSets));
            trace.event("CALL_END", Map.of("status", (int) returned[0], "outputs", outputValues,
                    "result_set_count", resultSets.size(), "sql_call_count", trace.sqlCalls));
            return CompletableFuture.completedFuture(response);
        } catch (Exception error) {
            trace.event("CALL_ERROR", Map.of("type", error.getClass().getSimpleName(), "message", String.valueOf(error.getMessage())));
            return CompletableFuture.failedFuture(error);
        }
    }

    static class Trace {
        final String callId = UUID.randomUUID().toString();
        boolean enabled;
        String procedure;
        String node;
        private int sequence;
        int sqlCalls;

        void event(String event, Map<String, ?> details) {
            if (!enabled) {
                return;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("call_id", callId);
            record.put("sequence", ++sequence);
            record.put("node", node);
            record.put("procedure", procedure);
            record.put("event", event);
            record.putAll(details);
            try {
                System.out.println("[SYBASE-TRACE] " + JSON.writeValueAsString(record));
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                System.err.println("[SYBASE-TRACE-ERROR] " + callId + " " + error.getMessage());
            }
        }

        Map<Instruction, String> localWrites(WasmModule module, List<String> outputs) {
            Map<Instruction, String> writes = new IdentityHashMap<>();
            if (module.nameSection() == null) {
                throw new IllegalStateException("Local tracing requires WASM names; recompile with prepare.py");
            }
            for (int i = 0; i < module.exportSection().exportCount(); i++) {
                var exported = module.exportSection().getExport(i);
                if (!exported.name().equals(procedure) || exported.exportType() != ExternalType.FUNCTION) {
                    continue;
                }
                int body = exported.index() - module.importSection().count(ExternalType.FUNCTION);
                for (var instruction : module.codeSection().getFunctionBody(body).instructions()) {
                    if (instruction.opcode() == OpCode.LOCAL_SET || instruction.opcode() == OpCode.LOCAL_TEE) {
                        String local = module.nameSection().nameOfLocal(exported.index(), (int) instruction.operand(0));
                        if ("@@rowcount".equals(local)) {
                            writes.put(instruction, local);
                        } else if (outputs.contains(local)) {
                            writes.put(instruction, "@" + local);
                        }
                    }
                }
            }
            return writes;
        }
    }

    static byte[] resource(String name) throws Exception {
        try (InputStream stream = SybaseWasmJob.class.getResourceAsStream("/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing deployment resource " + name);
            }
            return stream.readAllBytes();
        }
    }

    static List<String> names(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(node -> result.add(node.asText()));
        return result;
    }

    private static String snapshotQuery(List<List<Object>> rows, List<String> columns, List<Object> args) {
        if (rows.isEmpty()) {
            return "(SELECT " + String.join(", ", columns.stream()
                    .map(column -> "CAST(NULL AS BIGINT) AS " + column).toList()) + " WHERE 1 = 0)";
        }
        List<String> tuples = new ArrayList<>();
        for (List<Object> row : rows) {
            List<String> placeholders = new ArrayList<>();
            for (Object cell : row) {
                if (cell != null && !(cell instanceof Integer) && !(cell instanceof Long)) {
                    throw new IllegalArgumentException("Snapshot columns require INT or BIGINT values");
                }
                args.add(cell);
                placeholders.add("CAST(? AS BIGINT)");
            }
            tuples.add("(" + String.join(", ", placeholders) + ")");
        }
        return "(SELECT * FROM (VALUES " + String.join(", ", tuples)
                + ") AS snapshot_rows(" + String.join(", ", columns) + "))";
    }
}
