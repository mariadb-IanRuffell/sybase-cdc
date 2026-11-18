package example.sybase;

import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.types.ValType;
import com.dylibso.chicory.wasm.types.FunctionType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ignite.compute.JobExecutionContext;
import org.apache.ignite.tx.Transaction;

/** ABI v2 host. WASM owns execution order and variable handles; GridGain owns SQL and transactions. */
final class TypedWasmRuntime {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_VALUES = 100_000;
    private static final int MAX_SQL_CALLS = 20_000;
    private final JobExecutionContext context;
    private final List<Object> values = new ArrayList<>();
    private final List<Map<String, Object>> resultSets = new ArrayList<>();
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private Transaction transaction;
    private String transactionName;
    private int sqlCalls;
    private int depth;

    TypedWasmRuntime(JobExecutionContext context) {
        this.context = context;
        values.add(null); // SQL NULL has handle zero. Handles never cross a job boundary.
    }

    String execute(String name, JsonNode input, SybaseWasmJob.Trace trace) throws Exception {
        try {
            FrameResult returned = invoke(name, input, trace);
            if (transaction != null) {
                throw new IllegalStateException("Procedure returned with an open transaction");
            }
            return JSON.writeValueAsString(Map.of("node", context.ignite().name(), "status", returned.status(),
                    "outputs", returned.outputs(), "result_sets", resultSets, "messages", messages));
        } finally {
            if (transaction != null) {
                transaction.rollback();
                transaction = null;
                trace.event("TRANSACTION", Map.of("action", "rollback_on_exit"));
            }
        }
    }

    private record FrameResult(int status, Map<String, Object> outputs) { }
    private record Rows(List<String> columns, List<List<Object>> rows, long affected) { }
    private static final class Cursor {
        private final Rows data;
        private int position;
        Cursor(Rows data) { this.data = data; }
    }

    private FrameResult invoke(String name, JsonNode input, SybaseWasmJob.Trace trace) throws Exception {
        if (++depth > 32) throw new IllegalStateException("Maximum procedure call depth exceeded");
        try {
            JsonNode abi = JSON.readTree(SybaseWasmJob.resource(name + ".abi.json"));
            if (abi.path("version").asInt() != 2) throw new IllegalArgumentException("Expected typed ABI v2");
            JsonNode bindings = JSON.readTree(SybaseWasmJob.resource(name + ".bindings.json"));
            List<String> parameters = SybaseWasmJob.names(abi.get("parameters"));
            List<String> outputs = SybaseWasmJob.names(abi.get("outputs"));
            if (!input.isMissingNode() && !input.isObject()) throw new IllegalArgumentException("parameters must be an object");
            input.fieldNames().forEachRemaining(key -> {
                if (!parameters.contains(key)) throw new IllegalArgumentException("Unknown parameter " + key);
            });
            long[] arguments = new long[parameters.size()];
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                String key = parameters.get(i);
                JsonNode value = input.get(key);
                if (value == null) value = abi.path("defaults").get(key);
                if (value == null && !outputs.contains(key)) throw new IllegalArgumentException("Missing parameter " + key);
                Object converted = convert(jsonValue(value), abi.path("types").get("@" + key), true);
                arguments[i] = handle(converted);
                resolved.put(key, wire(converted));
            }
            trace.event("CALL_START", Map.of("abi_version", 2, "parameters", resolved));
            Map<String, Cursor> cursors = new LinkedHashMap<>();
            List<HostFunction> functions = new ArrayList<>();
            abi.get("operations").fields().forEachRemaining(entry -> {
                String operationName = entry.getKey();
                JsonNode op = entry.getValue();
                functions.add(new HostFunction("ignite", operationName,
                        FunctionType.of(Collections.nCopies(op.get("arguments").size(), ValType.I32),
                                Collections.nCopies(op.get("results").asInt(), ValType.I32)),
                        (instance, raw) -> {
                            try {
                                if (context.isCancelled()) throw new IllegalStateException("Compute job cancelled");
                                List<Object> args = new ArrayList<>();
                                for (int i = 0; i < raw.length; i++) {
                                    String variable = op.get("arguments").get(i).asText();
                                    args.add(variable.equals("@@rowcount") || variable.equals("@@sqlstatus")
                                            ? (int) raw[i] : value(raw[i]));
                                }
                                long[] returned = host(operationName, op, bindings, abi, args, raw, cursors, trace);
                                trace.event("HOST_RETURN", Map.of("operation", operationName, "wasm_values", returned));
                                return returned;
                            } catch (Exception error) {
                                throw new IllegalStateException(name + "/" + operationName + ": " + error.getMessage(), error);
                            }
                        }));
            });
            var module = Parser.parse(SybaseWasmJob.resource(name + ".wasm"));
            var builder = Instance.builder(module).withImportValues(
                    ImportValues.builder().addFunction(functions.toArray(HostFunction[]::new)).build());
            // The listener also checks cancellation during CPU-only WASM execution.
            var writes = trace.enabled ? trace.localWrites(module, outputs) : Map.<com.dylibso.chicory.wasm.types.Instruction, String>of();
            builder.withUnsafeExecutionListener((instruction, stack) -> {
                if (context.isCancelled()) throw new IllegalStateException("Compute job cancelled");
                String variable = writes.get(instruction);
                if (variable != null) {
                    int raw = (int) stack.peek();
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("variable", variable);
                    event.put("value", variable.equals("@@rowcount") ? raw : wire(value(raw)));
                    if (!variable.equals("@@rowcount")) event.put("handle", raw);
                    trace.event(variable.equals("@@rowcount") ? "ROWCOUNT_SET" : "OUTPUT_SET", event);
                }
            });
            trace.event("WASM_CALL", Map.of("argument_handles", arguments));
            long[] returned = builder.build().export(name).apply(arguments);
            if (returned.length != outputs.size() + 1) throw new IllegalStateException("Wrong WASM return arity");
            trace.event("WASM_RETURN", Map.of("values", returned));
            Map<String, Object> output = new LinkedHashMap<>();
            for (int i = 0; i < outputs.size(); i++) {
                Object cell = wire(value(returned[i + 1]));
                output.put(outputs.get(i), cell);
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("parameter", "@" + outputs.get(i));
                event.put("value", cell);
                event.put("handle", returned[i + 1]);
                trace.event("OUTPUT", event);
            }
            trace.event("CALL_END", Map.of("status", (int) returned[0], "outputs", output,
                    "result_set_count", resultSets.size(), "sql_call_count", trace.sqlCalls));
            return new FrameResult((int) returned[0], output);
        } finally {
            depth--;
        }
    }

    private long[] host(String name, JsonNode op, JsonNode bindings, JsonNode abi, List<Object> args,
            long[] raw, Map<String, Cursor> cursors, SybaseWasmJob.Trace trace) throws Exception {
        String kind = op.get("kind").asText();
        switch (kind) {
            case "begin_transaction":
                if (transaction != null) throw new IllegalStateException("Nested BEGIN TRAN is not supported");
                transaction = context.ignite().transactions().begin();
                transactionName = op.path("name").asText();
                trace.event("TRANSACTION", Map.of("action", "begin", "name", transactionName));
                return new long[0];
            case "commit_transaction":
            case "rollback_transaction":
                if (transaction == null) throw new IllegalStateException("No active transaction");
                if (!op.path("name").asText().isEmpty() && !op.path("name").asText().equals(transactionName)) {
                    throw new IllegalStateException("Transaction name mismatch");
                }
                if (kind.equals("commit_transaction")) transaction.commit(); else transaction.rollback();
                transaction = null;
                trace.event("TRANSACTION", Map.of("action", kind, "name", transactionName));
                return new long[0];
            case "raise_error":
                Map<String, Object> message = Map.of("procedure", trace.procedure,
                        "number", op.get("number").asInt(), "message", op.get("message").asText());
                messages.add(message);
                trace.event("RAISERROR", message);
                return new long[0]; // ASE RAISERROR here is followed by an explicit RETURN in the source.
            case "execute_procedure":
                String callee = op.get("procedure").asText();
                ObjectNode input = JSON.createObjectNode();
                for (int i = 0; i < args.size(); i++) {
                    input.set(op.get("parameters").get(i).asText(), JSON.valueToTree(wire(args.get(i))));
                }
                SybaseWasmJob.Trace child = new SybaseWasmJob.Trace();
                child.enabled = trace.enabled;
                child.node = trace.node;
                child.procedure = callee;
                trace.event("EXECUTE_PROCEDURE", Map.of("callee", callee, "child_call_id", child.callId));
                FrameResult nested = invoke(callee, input, child);
                trace.event("EXECUTE_RETURN", Map.of("callee", callee, "status", nested.status()));
                return new long[0];
            case "fetch_cursor":
                Cursor cursor = cursors.get(op.get("cursor").asText());
                if (cursor == null) throw new IllegalStateException("Cursor is not open");
                List<String> destinations = SybaseWasmJob.names(op.get("destinations"));
                if (cursor.data.columns().size() != destinations.size()) throw new IllegalArgumentException("FETCH arity mismatch");
                boolean found = cursor.position < cursor.data.rows().size();
                long[] fetch = Arrays.copyOf(raw, raw.length + 2);
                if (found) {
                    List<Object> row = cursor.data.rows().get(cursor.position++);
                    for (int i = 0; i < destinations.size(); i++) {
                        fetch[i] = handle(convert(row.get(i), abi.path("types").get(destinations.get(i)), false));
                    }
                }
                fetch[raw.length] = found ? 0 : 2; // ASE @@sqlstatus: success / no more rows.
                fetch[raw.length + 1] = found ? 1 : 0;
                trace.event("CURSOR_FETCH", Map.of("cursor", op.get("cursor").asText(),
                        "sqlstatus", fetch[raw.length], "rowcount", fetch[raw.length + 1]));
                return fetch;
            case "close_cursor":
            case "deallocate_cursor":
                cursors.remove(op.get("cursor").asText());
                trace.event("CURSOR", Map.of("action", kind, "cursor", op.get("cursor").asText()));
                return new long[0];
            default:
                break;
        }
        int parameterCount = op.path("sql_arguments").asInt(args.size());
        List<Object> sqlArgs = new ArrayList<>(args.subList(0, parameterCount));
        Integer identity = null;
        if (kind.equals("insert") && !op.path("identity").isNull()) {
            identity = nextIdentity(op.get("table").asText(), trace);
            sqlArgs.add(identity);
        }
        Rows data = sql(bindings.get(name).asText(), sqlArgs, transaction, trace, name);
        switch (kind) {
            case "condition":
            case "status":
                return new long[] {new BigDecimal(data.rows().getFirst().getFirst().toString()).intValueExact()};
            case "dml":
            case "insert":
                int affected = Math.toIntExact(data.affected());
                if (identity != null) {
                    trace.event("IDENTITY", Map.of("table", op.get("table").asText(), "value", identity));
                    return new long[] {affected, handle(identity)};
                }
                return new long[] {affected};
            case "assign":
                List<String> destinations = SybaseWasmJob.names(op.get("destinations"));
                long[] assigned = new long[destinations.size() + 1];
                for (int i = 0; i < destinations.size(); i++) {
                    // SELECT assignment leaves the previous value intact if there are no rows.
                    Object cell = data.rows().isEmpty() ? args.get(parameterCount + i) : data.rows().getLast().get(i);
                    assigned[i] = handle(convert(cell, abi.path("types").get(destinations.get(i)), false));
                }
                assigned[destinations.size()] = data.rows().size();
                return assigned;
            case "result_set":
                Map<String, Object> result = Map.of("columns", data.columns(), "rows", wire(data.rows()));
                resultSets.add(result);
                trace.event("RESULT_SET", Map.of("index", resultSets.size(), "columns", data.columns(), "rows", wire(data.rows())));
                return new long[] {data.rows().size()};
            case "open_cursor":
                cursors.put(op.get("cursor").asText(), new Cursor(data));
                trace.event("CURSOR", Map.of("action", "open", "cursor", op.get("cursor").asText(), "rows", data.rows().size()));
                return new long[] {data.rows().size()};
            default:
                throw new IllegalArgumentException("Unknown operation " + kind);
        }
    }

    private Rows sql(String statement, List<Object> args, Transaction tx, SybaseWasmJob.Trace trace, String operation) {
        if (++sqlCalls > MAX_SQL_CALLS) throw new IllegalStateException("SQL call limit exceeded");
        int call = ++trace.sqlCalls;
        trace.event("SQL_START", Map.of("sql_call", call, "operation", operation, "sql", statement,
                "parameters", wire(args), "explicit_transaction", tx != null));
        try (var rs = context.ignite().sql().execute(tx, context.cancellationToken(), statement, args.toArray())) {
            if (!rs.hasRowSet()) {
                trace.event("SQL_END", Map.of("sql_call", call, "affected_rows", rs.affectedRows()));
                return new Rows(List.of(), List.of(), rs.affectedRows());
            }
            List<String> columns = rs.metadata().columns().stream().map(c -> c.name()).toList();
            List<List<Object>> rows = new ArrayList<>();
            while (rs.hasNext()) {
                if (context.isCancelled()) throw new IllegalStateException("Compute job cancelled");
                if (rows.size() >= MAX_ROWS) throw new IllegalStateException("Buffered row limit exceeded: " + MAX_ROWS);
                var row = rs.next();
                List<Object> cells = new ArrayList<>();
                for (int i = 0; i < columns.size(); i++) cells.add(row.value(i));
                rows.add(cells);
            }
            trace.event("SQL_END", Map.of("sql_call", call, "row_count", rows.size()));
            return new Rows(columns, rows, -1);
        } catch (RuntimeException error) {
            trace.event("SQL_ERROR", Map.of("sql_call", call, "message", String.valueOf(error.getMessage())));
            throw error;
        }
    }

    private int nextIdentity(String table, SybaseWasmJob.Trace trace) {
        // A dedicated committed counter transaction serializes allocations across nodes/jobs.
        // IDs may have gaps after a failed insert, as with ASE IDENTITY. Never use MAX(id)+1 per insert.
        Transaction allocator = context.ignite().transactions().begin();
        try {
            Rows changed = sql("UPDATE WASM_IDENTITY SET next_id = next_id + 1 WHERE table_name = ?",
                    List.of(table), allocator, trace, "identity_allocate");
            if (changed.affected() != 1) throw new IllegalStateException("Missing identity counter for " + table);
            int id = new BigDecimal(sql("SELECT next_id FROM WASM_IDENTITY WHERE table_name = ?",
                    List.of(table), allocator, trace, "identity_read").rows().getFirst().getFirst().toString()).intValueExact();
            allocator.commit();
            return id;
        } catch (RuntimeException error) {
            allocator.rollback();
            throw error;
        }
    }

    private int handle(Object value) {
        if (value == null) return 0;
        if (values.size() >= MAX_VALUES) throw new IllegalStateException("Value handle limit exceeded: " + MAX_VALUES);
        values.add(value);
        return values.size() - 1;
    }

    private Object value(long handle) {
        int index = Math.toIntExact(handle);
        if (index < 0 || index >= values.size()) throw new IllegalArgumentException("Invalid WASM value handle " + handle);
        return values.get(index);
    }

    private static Object jsonValue(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isIntegralNumber()) return node.bigIntegerValue();
        if (node.isFloatingPointNumber()) return node.decimalValue();
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        throw new IllegalArgumentException("Expected a scalar parameter");
    }

    private static Object convert(Object value, JsonNode type, boolean input) {
        if (value == null) return null;
        String kind = type.get("kind").asText();
        switch (kind) {
            case "integer": {
                if (value instanceof Boolean flag && type.path("ase").asText().equals("bit")) value = flag ? 1 : 0;
                BigDecimal decimal = new BigDecimal(value.toString());
                BigInteger integer = input ? decimal.toBigIntegerExact() : decimal.toBigInteger();
                if (integer.compareTo(new BigInteger(type.get("minimum").asText())) < 0
                        || integer.compareTo(new BigInteger(type.get("maximum").asText())) > 0) {
                    throw new IllegalArgumentException("Out of range for " + type.get("ase").asText() + ": " + value);
                }
                return switch (type.get("sql").asText()) {
                    case "SMALLINT" -> integer.shortValueExact();
                    case "INT" -> integer.intValueExact();
                    case "BIGINT" -> integer.longValueExact();
                    default -> new BigDecimal(integer);
                };
            }
            case "decimal": {
                BigDecimal decimal = new BigDecimal(value.toString()).setScale(type.get("scale").asInt(),
                        input ? RoundingMode.UNNECESSARY : RoundingMode.HALF_UP);
                if (decimal.precision() > type.get("precision").asInt()) throw new IllegalArgumentException("Decimal overflow: " + value);
                if (type.has("minimum") && (decimal.compareTo(new BigDecimal(type.get("minimum").asText())) < 0
                        || decimal.compareTo(new BigDecimal(type.get("maximum").asText())) > 0)) {
                    throw new IllegalArgumentException("Money overflow: " + value);
                }
                return decimal;
            }
            case "float": {
                double number = Double.parseDouble(value.toString());
                if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite float");
                if (type.get("sql").asText().equals("REAL")) {
                    float narrowed = (float) number;
                    if (!Float.isFinite(narrowed)) throw new IllegalArgumentException("REAL overflow");
                    return narrowed;
                }
                return number;
            }
            case "string": {
                if (!(value instanceof String)) throw new IllegalArgumentException("Expected a string");
                String text = value.toString();
                int length = type.get("length").asInt();
                if (text.length() > length) throw new IllegalArgumentException("String exceeds declared length " + length);
                return type.path("fixed").asBoolean() ? text + " ".repeat(length - text.length()) : text;
            }
            case "binary": {
                byte[] bytes;
                if (value instanceof byte[] raw) bytes = raw;
                else if (value instanceof String text && text.startsWith("0x")) bytes = HexFormat.of().parseHex(text.substring(2));
                else throw new IllegalArgumentException("Binary parameters require 0x hexadecimal strings");
                int length = type.get("length").asInt();
                if (bytes.length > length) throw new IllegalArgumentException("Binary exceeds declared length " + length);
                return type.path("fixed").asBoolean() ? Arrays.copyOf(bytes, length) : bytes;
            }
            case "date": return value instanceof LocalDate ? value : LocalDate.parse(value.toString());
            case "time": return value instanceof LocalTime ? value : LocalTime.parse(value.toString());
            case "datetime": return value instanceof LocalDateTime ? value : LocalDateTime.parse(value.toString().replace(' ', 'T'));
            default: throw new IllegalArgumentException("Unknown type " + kind);
        }
    }

    private static Object wire(Object value) {
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof BigInteger integer) return integer.toString();
        if (value instanceof Long number) return number.toString(); // JSON clients must not lose 64-bit precision.
        if (value instanceof TemporalAccessor) return value.toString();
        if (value instanceof byte[] bytes) return "0x" + HexFormat.of().formatHex(bytes);
        if (value instanceof List<?> list) return list.stream().map(TypedWasmRuntime::wire).toList();
        return value;
    }
}
