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
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
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
    private static final long MAX_BUFFER_BYTES = 64L * 1024 * 1024;
    private static final long MAX_WAIT_MILLIS = 60_000;
    private final JobExecutionContext context;
    private final String resourcePrefix;
    private final String tempPrefix = "WASM_TMP_" + UUID.randomUUID().toString().replace("-", "") + "_";
    private final Map<String, String> temporaryTables = new LinkedHashMap<>();
    private final List<Map<String, Object>> events = new ArrayList<>();
    private long bufferedBytes;
    private long waitedMillis;
    private final List<Object> values = new ArrayList<>();
    private final List<Map<String, Object>> resultSets = new ArrayList<>();
    private final List<Map<String, Object>> messages = new ArrayList<>();
    private Transaction transaction;
    private String transactionName;
    /** True while the whole call runs in one read-write transaction (see execute). */
    private boolean implicitTransaction;
    private boolean explicitBegun;
    private int sqlCalls;
    // Timing breakdown returned with every response (milliseconds), to show where a call spends its time.
    private long sqlNanos, loadNanos, buildNanos, wasmNanos;
    private final List<Map<String, Object>> statementTimes = new ArrayList<>();
    private int depth;

    TypedWasmRuntime(JobExecutionContext context) {
        this(context, "");
    }

    TypedWasmRuntime(JobExecutionContext context, String resourcePrefix) {
        this.context = context;
        this.resourcePrefix = resourcePrefix;
        values.add(null); // SQL NULL has handle zero. Handles never cross a job boundary.
    }

    String execute(String name, JsonNode input, SybaseWasmJob.Trace trace) throws Exception {
        long started = System.nanoTime();
        Exception failure = null;
        try {
            // GridGain autocommit reads can lag a just-committed write by ~1.5 s, so a procedure that has its own transaction
            // (or calls another procedure) would act on stale rows before BEGIN TRAN. Run such a call in one read-write
            // transaction from the start: reads are then current, COMMIT TRAN is deferred to the end of the call, and
            // ROLLBACK TRAN undoes everything the call wrote so far (ASE would keep the statements before BEGIN TRAN).
            for (var operation : JSON.readTree(SybaseWasmJob.resource(resourcePrefix + name + ".abi.json")).path("operations")) {
                String kind = operation.path("kind").asText();
                if (kind.equals("begin_transaction") || kind.equals("execute_procedure")) implicitTransaction = true;
            }
            if (implicitTransaction) {
                transaction = context.ignite().transactions().begin();
                trace.event("TRANSACTION", Map.of("action", "begin_implicit"));
            }
            FrameResult returned = invoke(name, input, trace);
            if (explicitBegun) throw new IllegalStateException("Procedure returned with an open transaction");
            if (implicitTransaction && transaction != null) {
                transaction.commit();
                transaction = null;
                trace.event("TRANSACTION", Map.of("action", "commit_implicit"));
            }
            if (transaction != null) {
                throw new IllegalStateException("Procedure returned with an open transaction");
            }
            Map<String, Object> timing = new LinkedHashMap<>();
            long total = System.nanoTime() - started;
            timing.put("total_ms", total / 1e6);
            timing.put("sql_ms", sqlNanos / 1e6);
            timing.put("sql_statements", sqlCalls);
            timing.put("wasm_load_ms", loadNanos / 1e6);
            timing.put("wasm_instantiate_ms", buildNanos / 1e6);
            timing.put("wasm_run_incl_sql_ms", wasmNanos / 1e6);
            timing.put("other_ms", (total - sqlNanos - loadNanos - buildNanos) / 1e6);
            timing.put("statements", statementTimes);
            Map<String, Object> response = new LinkedHashMap<>(Map.of("node", context.ignite().name(), "status", returned.status(),
                    "outputs", returned.outputs(), "result_sets", resultSets, "messages", messages));
            response.put("timing", timing);
            if (!resourcePrefix.isEmpty()) response.put("events", events);
            return JSON.writeValueAsString(response);
        } catch (Exception error) {
            failure = error;
            throw error;
        } finally {
            // Cleanup uses an uncancelled SQL call so job cancellation cannot skip DROP TABLE.
            RuntimeException cleanupError = null;
            if (transaction != null) {
                try { transaction.rollback(); }
                catch (RuntimeException error) { cleanupError = error; }
                transaction = null;
                trace.event("TRANSACTION", Map.of("action", "rollback_on_exit"));
            }
            for (String table : temporaryTables.values()) {
                try (var ignored = context.ignite().sql().execute((Transaction) null, "DROP TABLE IF EXISTS " + table)) {
                    trace.event("TEMP_CLEANUP", Map.of("table", table));
                } catch (RuntimeException error) {
                    if (cleanupError == null) cleanupError = error; else cleanupError.addSuppressed(error);
                }
            }
            if (cleanupError != null) {
                if (failure != null) failure.addSuppressed(cleanupError); else throw cleanupError;
            }
        }
    }

    private record TextLocator(String text) { }

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
            JsonNode abi = JSON.readTree(SybaseWasmJob.resource(resourcePrefix + name + ".abi.json"));
            if (abi.path("version").asInt() != 2) throw new IllegalArgumentException("Expected typed ABI v2");
            JsonNode bindings = JSON.readTree(SybaseWasmJob.resource(resourcePrefix + name + ".bindings.json"));
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
                Object converted = convert(parameterValue(value, abi.path("types").get("@" + key)), abi.path("types").get("@" + key), true);
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
            long loadStart = System.nanoTime();
            var module = Parser.parse(SybaseWasmJob.resource(resourcePrefix + name + ".wasm"));
            var builder = Instance.builder(module).withImportValues(
                    ImportValues.builder().addFunction(functions.toArray(HostFunction[]::new)).build());
            loadNanos += System.nanoTime() - loadStart;
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
            long buildStart = System.nanoTime();
            var wasmInstance = builder.build();
            buildNanos += System.nanoTime() - buildStart;
            long runStart = System.nanoTime();
            long[] returned = wasmInstance.export(name).apply(arguments);
            wasmNanos += System.nanoTime() - runStart;
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
            case "metadata_count": {
                // Names are data until the catalog confirms one table; identifiers are then quoted, never pasted raw.
                if (args.size() != 2 || !(args.get(0) instanceof String schema) || !(args.get(1) instanceof String table)) {
                    throw new IllegalArgumentException("GG_ROW_COUNT requires schema/table strings");
                }
                Rows found = sql("SELECT TABLE_NAME FROM SYSTEM.TABLES WHERE SCHEMA_NAME = ? AND TABLE_NAME = ?",
                        args, transaction, trace, name + "_lookup");
                if (found.rows().size() != 1) throw new IllegalArgumentException("GG_ROW_COUNT table is undefined");
                String qualified = quoteIdentifier(schema) + "." + quoteIdentifier(table);
                Rows counted = sql("SELECT COUNT(*) FROM " + qualified, List.of(), transaction, trace, name);
                return new long[] {handle(convert(counted.rows().getFirst().getFirst(),
                        abi.path("types").get(op.get("destinations").get(0).asText()), false))};
            }
            case "begin_transaction":
                if (implicitTransaction) {
                    if (explicitBegun) throw new IllegalStateException("Nested BEGIN TRAN is not supported");
                    explicitBegun = true;
                    transactionName = op.path("name").asText();
                    trace.event("TRANSACTION", Map.of("action", "begin", "name", transactionName));
                    return new long[0];
                }
                if (transaction != null) throw new IllegalStateException("Nested BEGIN TRAN is not supported");
                transaction = context.ignite().transactions().begin();
                transactionName = op.path("name").asText();
                trace.event("TRANSACTION", Map.of("action", "begin", "name", transactionName));
                return new long[0];
            case "commit_transaction":
            case "rollback_transaction":
                if (implicitTransaction) {
                    if (!explicitBegun) throw new IllegalStateException("No active transaction");
                    if (!op.path("name").asText().isEmpty() && !op.path("name").asText().equals(transactionName)) {
                        throw new IllegalStateException("Transaction name mismatch");
                    }
                    explicitBegun = false;
                    if (kind.equals("rollback_transaction")) {
                        transaction.rollback();
                        transaction = context.ignite().transactions().begin();
                    }
                    trace.event("TRANSACTION", Map.of("action", kind, "name", transactionName));
                    return new long[0];
                }
                if (transaction == null) throw new IllegalStateException("No active transaction");
                if (!op.path("name").asText().isEmpty() && !op.path("name").asText().equals(transactionName)) {
                    throw new IllegalStateException("Transaction name mismatch");
                }
                if (kind.equals("commit_transaction")) transaction.commit(); else transaction.rollback();
                transaction = null;
                trace.event("TRANSACTION", Map.of("action", kind, "name", transactionName));
                return new long[0];
            case "mock_admin": {
                Map<String, Object> mocked = Map.of("procedure", trace.procedure, "kind", "mock",
                        "operation", op.get("operation").asText(), "message", "Mocked success; no administrative action performed.");
                addMessage(mocked);
                trace.event("MOCK_ADMIN", mocked);
                return new long[0];
            }
            case "raise_error":
                Map<String, Object> message = Map.of("procedure", trace.procedure,
                        "number", op.get("number").asInt(), "message", op.get("message").asText());
                addMessage(message);
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
            identity = nextIdentity(op.get("counter").asText(), trace);
            sqlArgs.add(identity);
        }
        if (kind.equals("create_temp")) {
            if (transaction != null) throw new IllegalStateException("Temporary DDL inside a transaction is unsupported");
            String marker = op.get("temporary").asText();
            if (temporaryTables.containsKey(marker)) throw new IllegalStateException("Temporary table already exists");
            temporaryTables.put(marker, tempPrefix + temporaryTables.size());
        }
        if (kind.equals("drop_temp") && transaction != null) {
            throw new IllegalStateException("Temporary DDL inside a transaction is unsupported");
        }
        String statement = expandTemporaries(bindings.get(name).asText());
        Rows data = sql(statement, sqlArgs, transaction, trace, name);
        switch (kind) {
            case "create_temp":
                trace.event("TEMP_CREATE", Map.of("table", temporaryTables.get(op.get("temporary").asText())));
                return new long[0];
            case "drop_temp":
                trace.event("TEMP_DROP", Map.of("table", temporaryTables.remove(op.get("temporary").asText())));
                return new long[0];
            case "print":
                List<Object> cells = data.rows().getFirst();
                String text = cells.getFirst() == null ? "" : cells.getFirst().toString();
                // ASE's %1!, %2!, ... substitution. Literal percent signs use %%.
                var matcher = Pattern.compile("%%|%([1-9][0-9]*)!").matcher(text);
                StringBuffer formatted = new StringBuffer();
                while (matcher.find()) {
                    String replacement = "%";
                    if (matcher.group(1) != null) {
                        int index = Integer.parseInt(matcher.group(1));
                        if (index >= cells.size()) throw new IllegalArgumentException("Missing PRINT argument");
                        replacement = cells.get(index) == null ? "" : cells.get(index).toString();
                    }
                    matcher.appendReplacement(formatted, java.util.regex.Matcher.quoteReplacement(replacement));
                }
                matcher.appendTail(formatted);
                Map<String, Object> printed = Map.of("procedure", trace.procedure, "kind", "print", "message", formatted.toString());
                addMessage(printed);
                trace.event("PRINT", printed);
                return new long[0];
            case "wait":
                String delay = String.valueOf(data.rows().getFirst().getFirst()).strip();
                if (!delay.matches("[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,3})?")) throw new IllegalArgumentException("WAITFOR requires hh:mm:ss[.fff]");
                long millis = LocalTime.parse(delay).toNanoOfDay() / 1_000_000;
                if (waitedMillis + millis > MAX_WAIT_MILLIS) throw new IllegalArgumentException("WAITFOR budget exceeded: 60000 ms per invocation");
                waitedMillis += millis;
                long deadline = System.nanoTime() + Duration.ofMillis(millis).toNanos();
                trace.event("WAIT_START", Map.of("milliseconds", millis));
                while (System.nanoTime() < deadline) {
                    if (context.isCancelled()) throw new IllegalStateException("Compute job cancelled");
                    Thread.sleep(Math.min(25, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
                }
                trace.event("WAIT_END", Map.of("milliseconds", millis));
                return new long[0];
            case "set_variable":
                return new long[] {handle(convert(data.rows().getFirst().getFirst(),
                        abi.path("types").get(op.get("destinations").get(0).asText()), false))};
            case "condition":
            case "status":
                Object scalar = data.rows().getFirst().getFirst();
                if (scalar == null && kind.equals("status")) {
                    Map<String, Object> warning = Map.of("procedure", trace.procedure, "kind", "warning", "message", "NULL return status normalized to 0");
                    addMessage(warning);
                    trace.event("RETURN_NULL", warning);
                    return new long[] {0};
                }
                return new long[] {new BigDecimal(scalar.toString()).intValueExact()};
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
                events.add(Map.of("kind", "result_set", "index", resultSets.size()));
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
        long start = System.nanoTime();
        try {
            return sqlRun(statement, args, tx, trace, operation);
        } finally {
            long took = System.nanoTime() - start;
            sqlNanos += took;
            if (statementTimes.size() < 60) statementTimes.add(Map.of("op", operation, "ms", took / 1e6));
        }
    }

    private Rows sqlRun(String statement, List<Object> args, Transaction tx, SybaseWasmJob.Trace trace, String operation) {
        // Server-local implicit read-only snapshots can predate the thin client's last write.
        // A short read/write transaction reads the latest committed data across that boundary.
        if (tx == null && statement.stripLeading().startsWith("SELECT")) {
            Transaction read = context.ignite().transactions().begin();
            try {
                Rows result = sqlRun(statement, args, read, trace, operation);
                read.commit();
                return result;
            } catch (RuntimeException error) {
                read.rollback();
                throw error;
            }
        }
        if (++sqlCalls > MAX_SQL_CALLS) throw new IllegalStateException("SQL call limit exceeded");
        args = args.stream().map(v -> v instanceof TextLocator locator ? locator.text() : v).toList();
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
                for (int i = 0; i < columns.size(); i++) {
                    Object cell = row.value(i);
                    account(cell);
                    cells.add(cell);
                }
                rows.add(cells);
            }
            trace.event("SQL_END", Map.of("sql_call", call, "row_count", rows.size()));
            return new Rows(columns, rows, -1);
        } catch (RuntimeException error) {
            trace.event("SQL_ERROR", Map.of("sql_call", call, "message", String.valueOf(error.getMessage())));
            throw error;
        }
    }

    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private String expandTemporaries(String sql) {
        // Do not replace marker-looking text in SQL literals or quoted identifiers.
        var matcher = Pattern.compile("'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|__WASM_TEMP_[0-9]+__").matcher(sql);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String text = matcher.group();
            if (text.startsWith("__WASM_TEMP_")) {
                String table = temporaryTables.get(text);
                if (table == null) throw new IllegalStateException("Temporary table is not available: " + text);
                matcher.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(table));
            } else matcher.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(text));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private void account(Object value) {
        Object cell = value instanceof TextLocator locator ? locator.text() : value;
        bufferedBytes += cell instanceof String text ? text.getBytes(StandardCharsets.UTF_8).length
                : cell instanceof byte[] bytes ? bytes.length : 16;
        if (bufferedBytes > MAX_BUFFER_BYTES) throw new IllegalStateException("Buffered value byte limit exceeded: " + MAX_BUFFER_BYTES);
    }

    private void addMessage(Map<String, Object> message) {
        account(message.get("message"));
        messages.add(message);
        events.add(Map.of("kind", "message", "index", messages.size()));
    }

    private static final long ID_BASE = 1_000_000_000L;

    /**
     * Ids for rows created in GridGain come from the id_seq counter the phase2 jobs use, starting at 1,000,000,000 so they
     * cannot collide with identity values CDC replicates. Each draw is its own short transaction, retried on conflict.
     */
    private int nextIdentity(String counter, SybaseWasmJob.Trace trace) {
        try (var ddl = context.ignite().sql().execute((Transaction) null,
                "CREATE TABLE IF NOT EXISTS id_seq (name VARCHAR(40) PRIMARY KEY, next_id BIGINT) ZONE c360_ref")) {
            ddl.hasNext();
        }
        for (int attempt = 0; ; attempt++) {
            Transaction allocator = context.ignite().transactions().begin();
            try {
                Rows current = sql("SELECT next_id FROM id_seq WHERE name = ?", List.of(counter), allocator, trace, "identity_read");
                boolean exists = !current.rows().isEmpty();
                long next = exists ? new BigDecimal(current.rows().getFirst().getFirst().toString()).longValueExact() : ID_BASE;
                sql(exists ? "UPDATE id_seq SET next_id = ? WHERE name = ?" : "INSERT INTO id_seq (next_id, name) VALUES (?, ?)",
                        List.of(next + 1, counter), allocator, trace, "identity_allocate");
                allocator.commit();
                return Math.toIntExact(next);
            } catch (RuntimeException error) {
                try { allocator.rollback(); } catch (RuntimeException ignored) { }
                if (attempt >= 8) throw error;
                try { Thread.sleep(15L * (attempt + 1)); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw error;
                }
            }
        }
    }

    private int handle(Object value) {
        if (value == null) return 0;
        if (values.size() >= MAX_VALUES) throw new IllegalStateException("Value handle limit exceeded: " + MAX_VALUES);
        account(value);
        values.add(value);
        return values.size() - 1;
    }

    private Object value(long handle) {
        int index = Math.toIntExact(handle);
        if (index < 0 || index >= values.size()) throw new IllegalArgumentException("Invalid WASM value handle " + handle);
        return values.get(index);
    }

    private static Object parameterValue(JsonNode node, JsonNode type) {
        if (node != null && !node.isNull() && type.path("kind").asText().equals("text_locator")) {
            if (!node.isObject() || node.size() != 1 || !node.path("text").isTextual()) {
                throw new IllegalArgumentException("text_locator requires {\"text\":\"materialized value\"}; ASE locator tokens are not accepted");
            }
            return new TextLocator(node.get("text").asText());
        }
        return jsonValue(node);
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
        if (value instanceof TextLocator locator && !kind.equals("text_locator")) value = locator.text();
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
            case "text_locator": {
                if (value instanceof TextLocator) return value;
                if (value instanceof String text) return new TextLocator(text);
                throw new IllegalArgumentException("Expected a materialized text locator");
            }
            case "string": {
                if (!(value instanceof String)) throw new IllegalArgumentException("Expected a string");
                String text = value.toString();
                if (text.isEmpty()) text = " "; // ASE empty character values are a single space.
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
        if (value instanceof TextLocator locator) return Map.of("text", locator.text(), "scope", "invocation");
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof BigInteger integer) return integer.toString();
        if (value instanceof Long number) return number.toString(); // JSON clients must not lose 64-bit precision.
        if (value instanceof TemporalAccessor) return value.toString();
        if (value instanceof byte[] bytes) return "0x" + HexFormat.of().formatHex(bytes);
        if (value instanceof List<?> list) return list.stream().map(TypedWasmRuntime::wire).toList();
        return value;
    }
}
