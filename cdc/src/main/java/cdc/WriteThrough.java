package cdc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reverse CDC: replays changes made in GridGain into the Sybase ASE source tables (asynchronous write-through).
 *
 * <p>GridGain 9 has no row-level change feed, so changes are found by diffing. Every sweep reads each replicated table
 * from GridGain, hashes each row and compares the hash with {@code wt_shadow}, the hash of the row as last applied to
 * Sybase. New or changed rows are written to Sybase, rows that disappeared are deleted there, and only then is the shadow
 * updated. The shadow lives in GridGain (persistent), so the diff <em>is</em> the queue: a Sybase outage or a rejected row
 * leaves the difference in place and the next sweep retries it. Rows that keep failing back off exponentially
 * ({@code wt_retry} shows attempts and the last error). Parents are written before children and children deleted before
 * parents, so foreign keys hold; anything that still fails is simply retried.
 *
 * <p>Loop suppression: the Sybase connection identifies itself as {@link #APP_NAME}. The change-capture triggers
 * ({@code sybase/sql/04b_cdc_triggers.sql}) skip that application, so write-through does not echo back through the
 * forward CDC reader. Before writing a row the reader compares it with the current Sybase row and skips equal rows,
 * which also absorbs changes that arrived from Sybase through the forward reader.
 */
final class WriteThrough implements AutoCloseable {
    static final String APP_NAME = "gg-writethrough";
    private static final int IN_CHUNK = 200;
    private static final long MAX_BACKOFF_MS = 60_000;
    private static final long TS_TOLERANCE_MS = 4;   // ASE datetime has 1/300 s resolution

    /** Counters for one sweep. */
    record Stats(int inserted, int updated, int unchanged, int deleted, int failed, int backedOff, int blocked) {
        int applied() { return inserted + updated + deleted; }
    }

    private static final class Counter {
        int inserted, updated, unchanged, deleted, failed, backedOff, blocked;
        Stats done() { return new Stats(inserted, updated, unchanged, deleted, failed, backedOff, blocked); }
    }

    /** Per-table mapping between the GridGain columns (TableSpec.columns) and the Sybase columns. */
    private static final class Meta {
        final List<String> sybCols = new ArrayList<>();
        final List<Integer> gridIdx = new ArrayList<>();   // index into TableSpec.columns for each Sybase column
        int[] types;
        int pkSyb;
        boolean identityPk;   // ASE identity key: explicit ids need SET IDENTITY_INSERT
    }

    private final String aseUrl, aseUser, asePass, ggUrl;
    private final boolean allowMassDelete;
    private Connection ase;
    private GridGainSink sink;
    private final Map<String, Meta> meta = new HashMap<>();
    private final Map<String, long[]> retry = new HashMap<>();   // table\0pk -> {attempts, nextAttemptMs}

    WriteThrough(String aseUrl, String aseUser, String asePass, String ggUrl, boolean allowMassDelete) throws SQLException {
        this.aseUrl = aseUrl;
        this.aseUser = aseUser;
        this.asePass = asePass;
        this.ggUrl = ggUrl;
        this.allowMassDelete = allowMassDelete;
        connect();
    }

    /** Reconnects both sides. GridGain is opened first, so a Sybase outage never leaves the queue state unreachable. */
    void connect() throws SQLException {
        close();
        sink = new GridGainSink(ggUrl);
        ensureTables();
        meta.clear();
        loadRetry();
        openAse();
    }

    private void openAse() throws SQLException {
        String url = aseUrl + (aseUrl.contains(";APPNAME=") ? "" : ";APPNAME=" + APP_NAME);
        ase = DriverManager.getConnection(url, aseUser, asePass);
        ase.setAutoCommit(true);
    }

    @Override
    public void close() {
        try { if (ase != null) ase.close(); } catch (SQLException ignored) { }
        try { if (sink != null) sink.close(); } catch (Exception ignored) { }
        ase = null;
        sink = null;
    }

    // ------------------------------------------------------------------ setup

    private void ensureTables() throws SQLException {
        try (Statement st = sink.jdbc().createStatement()) {
            st.execute("CREATE ZONE IF NOT EXISTS c360 (PARTITIONS 25, REPLICAS 3) STORAGE PROFILES ['default']");
            st.execute("CREATE TABLE IF NOT EXISTS wt_shadow (table_name VARCHAR(30), pk_value VARCHAR(40), "
                + "row_hash BIGINT, PRIMARY KEY (table_name, pk_value)) ZONE c360");
            st.execute("CREATE TABLE IF NOT EXISTS wt_retry (table_name VARCHAR(30), pk_value VARCHAR(40), "
                + "op VARCHAR(1), attempts INT, next_attempt BIGINT, last_error VARCHAR(400), "
                + "PRIMARY KEY (table_name, pk_value)) ZONE c360");
        }
        sink.forgetSchema();
    }

    private void loadRetry() throws SQLException {
        retry.clear();
        try (Statement st = sink.jdbc().createStatement();
                ResultSet rs = st.executeQuery("SELECT table_name, pk_value, attempts, next_attempt FROM wt_retry")) {
            while (rs.next()) {
                retry.put(rs.getString(1) + '\0' + rs.getString(2), new long[] {rs.getInt(3), rs.getLong(4)});
            }
        }
    }

    private Meta meta(TableSpec t) throws SQLException {
        Meta m = meta.get(t.name);
        if (m != null) return m;
        m = new Meta();
        for (int i = 0; i < t.columns.size(); i++) {
            if (t.sourceExprs.get(i).startsWith("o.")) continue;   // customer_id on child tables exists only in GridGain
            m.sybCols.add(t.columns.get(i));
            m.gridIdx.add(i);
        }
        m.pkSyb = m.sybCols.indexOf(t.pk);
        m.types = new int[m.sybCols.size()];
        try (Statement st = ase.createStatement();
                ResultSet rs = st.executeQuery("select " + String.join(", ", m.sybCols) + " from " + t.name + " where 1 = 0")) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 0; i < m.types.length; i++) m.types[i] = md.getColumnType(i + 1);
        }
        try (PreparedStatement ps = ase.prepareStatement(
                "select 1 from syscolumns where id = object_id(?) and name = ? and (status & 128) = 128")) {
            ps.setString(1, t.name);
            ps.setString(2, t.pk);
            try (ResultSet rs = ps.executeQuery()) {
                m.identityPk = rs.next();
            }
        }
        meta.put(t.name, m);
        return m;
    }

    // ------------------------------------------------------------------ one sweep

    /** Diffs every table against the shadow and applies the differences to Sybase. */
    Stats sweep() throws SQLException {
        if (sink == null) connect();
        if (ase == null || ase.isClosed()) openAse();
        Counter c = new Counter();
        Map<String, Map<String, Object[]>> grid = new HashMap<>();
        Map<String, Map<String, Long>> shadow = new HashMap<>();
        for (TableSpec t : Tables.ALL) {
            grid.put(t.name, readGrid(t));
            shadow.put(t.name, readShadow(t));
        }
        for (TableSpec t : Tables.ALL) pushChanged(t, grid.get(t.name), shadow.get(t.name), c);
        List<TableSpec> children = new ArrayList<>(Tables.ALL);
        Collections.reverse(children);
        for (TableSpec t : children) pushDeleted(t, grid.get(t.name), shadow.get(t.name), c);
        return c.done();
    }

    private Map<String, Object[]> readGrid(TableSpec t) throws SQLException {
        Map<String, Object[]> rows = new LinkedHashMap<>();
        int n = t.columns.size();
        try (Statement st = sink.jdbc().createStatement(); ResultSet rs = st.executeQuery(t.gridSelectSql())) {
            while (rs.next()) {
                Object[] v = new Object[n];
                for (int i = 0; i < n; i++) v[i] = rs.getObject(i + 1);
                rows.put(SybaseCdc.norm(v[t.pkIndex]), v);
            }
        }
        return rows;
    }

    private Map<String, Long> readShadow(TableSpec t) throws SQLException {
        Map<String, Long> m = new HashMap<>();
        try (PreparedStatement ps = sink.jdbc().prepareStatement("SELECT pk_value, row_hash FROM wt_shadow WHERE table_name = ?")) {
            ps.setString(1, t.name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) m.put(rs.getString(1), rs.getLong(2));
            }
        }
        return m;
    }

    private void pushChanged(TableSpec t, Map<String, Object[]> grid, Map<String, Long> shadow, Counter c) throws SQLException {
        Meta m = meta(t);
        long now = System.currentTimeMillis();
        List<String> todo = new ArrayList<>();
        Map<String, Long> hashes = new HashMap<>();
        for (Map.Entry<String, Object[]> e : grid.entrySet()) {
            long h = hash(e.getValue());
            Long old = shadow.get(e.getKey());
            if (old != null && old == h) continue;
            long[] r = retry.get(t.name + '\0' + e.getKey());
            if (r != null && r[1] > now) { c.backedOff++; continue; }
            todo.add(e.getKey());
            hashes.put(e.getKey(), h);
        }
        for (int from = 0; from < todo.size(); from += IN_CHUNK) {
            List<String> chunk = todo.subList(from, Math.min(todo.size(), from + IN_CHUNK));
            Map<String, Object[]> existing = readSybase(t, m, chunk);
            List<Object[]> synced = new ArrayList<>();
            for (String pk : chunk) {
                Object[] g = grid.get(pk);
                Object[] cur = existing.get(pk);
                try {
                    if (cur != null && sameRow(m, g, cur)) {
                        c.unchanged++;
                    } else if (cur != null) {
                        update(t, m, g);
                        c.updated++;
                    } else {
                        insert(t, m, g);
                        c.inserted++;
                    }
                    synced.add(new Object[] {t.name, pk, hashes.get(pk)});
                    clearRetry(t.name, pk);
                } catch (SQLException ex) {
                    if (connectionLost(ex)) throw ex;
                    failure(t.name, pk, "U", ex);
                    c.failed++;
                }
            }
            if (!synced.isEmpty()) {
                GridGainSink.Upserter up = sink.upserter("wt_shadow", List.of("table_name", "pk_value", "row_hash"), 1000);
                for (Object[] row : synced) up.add(row);
                up.flush();
            }
        }
    }

    private void pushDeleted(TableSpec t, Map<String, Object[]> grid, Map<String, Long> shadow, Counter c) throws SQLException {
        List<String> gone = new ArrayList<>();
        for (String pk : shadow.keySet()) if (!grid.containsKey(pk)) gone.add(pk);
        if (gone.isEmpty()) return;
        if (!allowMassDelete && gone.size() > 100 && gone.size() > shadow.size() / 2) {
            System.err.printf("BLOCKED: %s would delete %d of %d rows in Sybase (GridGain table looks empty or reset); "
                + "set WT_ALLOW_MASS_DELETE=true to apply%n", t.name, gone.size(), shadow.size());
            c.blocked += gone.size();
            return;
        }
        long now = System.currentTimeMillis();
        try (PreparedStatement del = ase.prepareStatement(t.deleteSql());
                PreparedStatement shadowDel = sink.jdbc().prepareStatement("DELETE FROM wt_shadow WHERE table_name = ? AND pk_value = ?")) {
            for (String pk : gone) {
                long[] r = retry.get(t.name + '\0' + pk);
                if (r != null && r[1] > now) { c.backedOff++; continue; }
                try {
                    SybaseCdc.setPk(del, 1, t, pk);
                    del.executeUpdate();
                    shadowDel.setString(1, t.name);
                    shadowDel.setString(2, pk);
                    shadowDel.executeUpdate();
                    clearRetry(t.name, pk);
                    c.deleted++;
                } catch (SQLException ex) {
                    if (connectionLost(ex)) throw ex;
                    failure(t.name, pk, "D", ex);
                    c.failed++;
                }
            }
        }
    }

    // ------------------------------------------------------------------ Sybase access

    private Map<String, Object[]> readSybase(TableSpec t, Meta m, List<String> pks) throws SQLException {
        Map<String, Object[]> rows = new HashMap<>();
        String in = String.join(",", Collections.nCopies(pks.size(), "?"));
        String sql = "select " + String.join(", ", m.sybCols) + " from " + t.name + " where " + t.pk + " in (" + in + ")";
        try (PreparedStatement ps = ase.prepareStatement(sql)) {
            for (int i = 0; i < pks.size(); i++) SybaseCdc.setPk(ps, i + 1, t, pks.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Object[] v = new Object[m.sybCols.size()];
                    for (int i = 0; i < v.length; i++) v[i] = rs.getObject(i + 1);
                    rows.put(SybaseCdc.norm(v[m.pkSyb]), v);
                }
            }
        }
        return rows;
    }

    private void insert(TableSpec t, Meta m, Object[] grid) throws SQLException {
        String sql = "insert into " + t.name + " (" + String.join(", ", m.sybCols) + ") values ("
            + String.join(", ", Collections.nCopies(m.sybCols.size(), "?")) + ")";
        if (m.identityPk) {
            try (Statement st = ase.createStatement()) {
                st.execute("set identity_insert " + t.name + " on");
            }
        }
        try (PreparedStatement ps = ase.prepareStatement(sql)) {
            for (int i = 0; i < m.sybCols.size(); i++) bind(ps, i + 1, grid[m.gridIdx.get(i)], m.types[i]);
            ps.executeUpdate();
        } finally {
            if (m.identityPk) {
                try (Statement st = ase.createStatement()) {
                    st.execute("set identity_insert " + t.name + " off");
                } catch (SQLException ignored) {
                    // the connection is gone; the next connect starts with the option off
                }
            }
        }
    }

    private void update(TableSpec t, Meta m, Object[] grid) throws SQLException {
        List<String> sets = new ArrayList<>();
        for (int i = 0; i < m.sybCols.size(); i++) if (i != m.pkSyb) sets.add(m.sybCols.get(i) + " = ?");
        if (sets.isEmpty()) return;   // key-only table
        try (PreparedStatement ps = ase.prepareStatement(
                "update " + t.name + " set " + String.join(", ", sets) + " where " + t.pk + " = ?")) {
            int p = 1;
            for (int i = 0; i < m.sybCols.size(); i++) {
                if (i != m.pkSyb) bind(ps, p++, grid[m.gridIdx.get(i)], m.types[i]);
            }
            SybaseCdc.setPk(ps, p, t, SybaseCdc.norm(grid[t.pkIndex]));
            ps.executeUpdate();
        }
    }

    private static void bind(PreparedStatement ps, int idx, Object v, int sqlType) throws SQLException {
        if (v == null) ps.setNull(idx, sqlType);
        else if (v instanceof LocalDateTime ldt) ps.setTimestamp(idx, Timestamp.valueOf(ldt));   // the JVM runs in UTC
        else if (v instanceof LocalDate ld) ps.setDate(idx, java.sql.Date.valueOf(ld));
        else ps.setObject(idx, v);
    }

    private boolean connectionLost(SQLException e) {
        try {
            if (ase == null || ase.isClosed()) return true;
        } catch (SQLException ignored) {
            return true;
        }
        String state = e.getSQLState();
        String msg = String.valueOf(e.getMessage());
        return state != null && state.startsWith("08") || msg.contains("Connection reset") || msg.contains("Broken pipe")
            || msg.contains("closed") || msg.contains("Unable to connect");
    }

    // ------------------------------------------------------------------ retry bookkeeping

    private void failure(String table, String pk, String op, SQLException e) throws SQLException {
        String key = table + '\0' + pk;
        long[] r = retry.computeIfAbsent(key, k -> new long[] {0, 0});
        r[0]++;
        r[1] = System.currentTimeMillis() + Math.min(MAX_BACKOFF_MS, 1000L << Math.min(r[0], 10));
        String err = String.valueOf(e.getMessage()).replace('\n', ' ');
        System.err.printf("write-through %s %s pk=%s failed (attempt %d, retry in %d ms): %s%n", op.equals("D") ? "DELETE" : "UPSERT",
            table, pk, r[0], r[1] - System.currentTimeMillis(), err.length() > 200 ? err.substring(0, 200) : err);
        GridGainSink.Upserter up = sink.upserter("wt_retry",
            List.of("table_name", "pk_value", "op", "attempts", "next_attempt", "last_error"), 1);
        up.add(table, pk, op, (int) r[0], r[1], err.length() > 400 ? err.substring(0, 400) : err);
        up.flush();
    }

    private void clearRetry(String table, String pk) throws SQLException {
        if (retry.remove(table + '\0' + pk) == null) return;
        try (PreparedStatement ps = sink.jdbc().prepareStatement("DELETE FROM wt_retry WHERE table_name = ? AND pk_value = ?")) {
            ps.setString(1, table);
            ps.setString(2, pk);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ hashing and comparison

    static long hash(Object[] row) {
        StringBuilder sb = new StringBuilder();
        for (Object o : row) sb.append(SybaseCdc.norm(o)).append('|');
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xff);
            return h;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Long millis(Object o) {
        if (o instanceof Timestamp ts) return ts.getTime();
        if (o instanceof LocalDateTime ldt) return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
        return null;
    }

    static boolean sameValue(Object g, Object s) {
        if (g == null || s == null) return g == s;
        Long a = millis(g), b = millis(s);
        if (a != null && b != null) return Math.abs(a - b) <= TS_TOLERANCE_MS;
        if (g instanceof Number && s instanceof Number) {
            return new BigDecimal(g.toString()).compareTo(new BigDecimal(s.toString())) == 0;
        }
        return SybaseCdc.norm(g).equals(SybaseCdc.norm(s));
    }

    private boolean sameRow(Meta m, Object[] grid, Object[] syb) {
        for (int i = 0; i < syb.length; i++) {
            if (!sameValue(grid[m.gridIdx.get(i)], syb[i])) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ status / verify

    void status() throws SQLException {
        System.out.printf("%-18s %8s %8s %8s %8s %8s%n", "table", "gridgain", "sybase", "shadow", "pending", "retrying");
        long failing = 0;
        for (TableSpec t : Tables.ALL) {
            Map<String, Object[]> grid = readGrid(t);
            Map<String, Long> shadow = readShadow(t);
            int pending = 0;
            for (Map.Entry<String, Object[]> e : grid.entrySet()) {
                Long old = shadow.get(e.getKey());
                if (old == null || old != hash(e.getValue())) pending++;
            }
            for (String pk : shadow.keySet()) if (!grid.containsKey(pk)) pending++;
            int retrying = 0;
            for (String k : retry.keySet()) if (k.startsWith(t.name + '\0')) retrying++;
            failing += retrying;
            long sybase = 0;
            try (Statement st = ase.createStatement(); ResultSet rs = st.executeQuery("select count(*) from " + t.name)) {
                if (rs.next()) sybase = rs.getLong(1);
            }
            System.out.printf("%-18s %8d %8d %8d %8d %8d%n", t.name, grid.size(), sybase, shadow.size(), pending, retrying);
        }
        if (failing > 0) {
            try (Statement st = sink.jdbc().createStatement();
                    ResultSet rs = st.executeQuery("SELECT table_name, pk_value, op, attempts, last_error FROM wt_retry")) {
                System.out.println("rows in retry:");
                while (rs.next()) {
                    System.out.printf("  %s pk=%s %s attempts=%d  %s%n", rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getInt(4), rs.getString(5));
                }
            }
        }
    }

    boolean verify() throws SQLException {
        boolean ok = true;
        System.out.printf("%-18s %8s %8s %8s %8s %8s%n", "table", "gridgain", "sybase", "missing", "extra", "differ");
        for (TableSpec t : Tables.ALL) {
            Meta m = meta(t);
            Map<String, Object[]> grid = readGrid(t);
            Map<String, Object[]> syb = new HashMap<>();
            try (Statement st = ase.createStatement();
                    ResultSet rs = st.executeQuery("select " + String.join(", ", m.sybCols) + " from " + t.name)) {
                while (rs.next()) {
                    Object[] v = new Object[m.sybCols.size()];
                    for (int i = 0; i < v.length; i++) v[i] = rs.getObject(i + 1);
                    syb.put(SybaseCdc.norm(v[m.pkSyb]), v);
                }
            }
            int missing = 0, extra = 0, differ = 0;
            List<String> samples = new ArrayList<>();
            for (Map.Entry<String, Object[]> e : grid.entrySet()) {
                Object[] s = syb.get(e.getKey());
                if (s == null) { missing++; if (samples.size() < 3) samples.add("missing in Sybase " + e.getKey()); }
                else if (!sameRow(m, e.getValue(), s)) { differ++; if (samples.size() < 3) samples.add("differ " + e.getKey()); }
            }
            Set<String> extras = new HashSet<>(syb.keySet());
            extras.removeAll(grid.keySet());
            extra = extras.size();
            if (extra > 0 && samples.size() < 3) samples.add("extra in Sybase " + extras.iterator().next());
            System.out.printf("%-18s %8d %8d %8d %8d %8d%n", t.name, grid.size(), syb.size(), missing, extra, differ);
            samples.forEach(s -> System.out.println("   " + s));
            ok &= (missing + extra + differ) == 0;
        }
        System.out.println(ok ? "VERIFY OK: GridGain and Sybase are identical" : "VERIFY FAILED");
        return ok;
    }
}
