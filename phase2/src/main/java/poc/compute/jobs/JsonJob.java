package poc.compute.jobs;

import java.util.concurrent.CompletableFuture;
import org.apache.ignite.Ignite;
import org.apache.ignite.compute.ComputeJob;
import org.apache.ignite.compute.JobExecutionContext;
import java.util.Map;

/**
 * Base class of every job: the argument is a JSON object and the result is JSON (a string both ways, see Json). Subclasses implement
 * {@link #run} and may block on SQL: jobs run on the node's compute threads, not on a network thread.
 * Business errors are returned as {rc, error_no, error} with the same numbers the stored procedures raised; the caller maps them to HTTP.
 */
public abstract class JsonJob implements ComputeJob<String, String> {
    @Override
    public CompletableFuture<String> executeAsync(JobExecutionContext ctx, String arg) {
        Ignite ignite = ctx.ignite();
        Map<String, Object> args = Json.readMap(arg);
        if (!transactional()) return CompletableFuture.completedFuture(Json.write(run(ignite, args)));
        // GridGain 9 reads outside a transaction are a snapshot about 1.5 s old; a read-write transaction sees what was committed a moment ago
        Object result = ignite.transactions().runInTransaction(tx -> {
            Sql.CURRENT.set(tx);
            try {
                return run(ignite, args);
            } finally {
                Sql.CURRENT.remove();
            }
        });
        return CompletableFuture.completedFuture(Json.write(result));
    }

    /** True (the default): the whole job runs in one read-write transaction. Jobs that write override this and manage their own transaction. */
    protected boolean transactional() {
        return true;
    }

    protected abstract Object run(Ignite ignite, Map<String, Object> arg);

    /** The stored-procedure style error row. */
    protected static Map<String, Object> fail(int rc, int errorNo, String message) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("rc", rc);
        m.put("error_no", errorNo);
        m.put("error", message);
        return m;
    }

    protected static int intArg(Map<String, Object> a, String k) {
        return ((Number) a.get(k)).intValue();
    }
}
