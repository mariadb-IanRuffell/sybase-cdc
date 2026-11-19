package example.sybase;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.apache.ignite.Ignite;

/**
 * Pushes the node's current hybrid clock into the timestamp trackers behind a compute job's {@code sql()} and
 * {@code transactions()}.
 *
 * A plain (autocommit or read-only) read runs as a snapshot at the tracker's timestamp. Nothing feeds the thin client's last write
 * into the job's trackers, so the snapshot can predate a write that finished before the job was called. Advancing the trackers to
 * the node clock first makes the snapshot at least as new as anything committed before this call. It reaches into GridGain
 * internals by reflection (there is no public API for it), so it is tied to the 9.2.0-b1 object layout and turns itself off, with one
 * log line, if the layout is different.
 */
final class ObservableTime {
    private record Handles(Object clock, Method current, Object[] trackers, Method update) { }

    private static volatile Handles handles;
    private static volatile boolean unavailable;

    private ObservableTime() { }

    /** Advance the job's trackers to the node clock. Returns false when the internals could not be reached. */
    static boolean advance(Ignite ignite) {
        if (unavailable) return false;
        try {
            Handles h = handles;
            if (h == null) {
                h = resolve(ignite);
                handles = h;
            }
            long now = (long) h.current.invoke(h.clock);
            for (Object tracker : h.trackers) h.update.invoke(tracker, now);
            return true;
        } catch (Throwable error) {
            unavailable = true;
            System.err.println("ObservableTime: GridGain internals not reachable, reads stay plain snapshots: " + error);
            return false;
        }
    }

    private static Handles resolve(Ignite ignite) throws Exception {
        Object clock = field(field(field(ignite, "delegate"), "delegate"), "clockService");
        Method current = clock.getClass().getMethod("currentLong");
        Object txTracker = field(field(ignite.transactions(), "transactions"), "observableTimestampTracker");
        Object sqlTracker = field(field(ignite.sql(), "delegate"), "observableTimestampTracker");
        Method update = txTracker.getClass().getDeclaredMethod("update", long.class);
        update.setAccessible(true);
        Object[] trackers = txTracker == sqlTracker ? new Object[] {txTracker} : new Object[] {txTracker, sqlTracker};
        return new Handles(clock, current, trackers, update);
    }

    private static Object field(Object owner, String name) throws Exception {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException next) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(owner.getClass().getName() + "." + name);
    }
}
