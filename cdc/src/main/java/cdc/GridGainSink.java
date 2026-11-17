package cdc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.ignite.client.IgniteClient;
import org.apache.ignite.table.RecordView;
import org.apache.ignite.table.Tuple;

/**
 * The GridGain 9 side of a reader: a thin client for upserts (GridGain 9 SQL has no single-row upsert, and the key-value API is also much
 * faster than row-by-row MERGE) and a JDBC connection for DDL, deletes, counts and the verify queries. Both reach the cluster through the
 * same addresses, taken from the JDBC URL. Values read from the source are converted to the Java type the GridGain column wants.
 */
final class GridGainSink implements AutoCloseable {
    private final IgniteClient client;
    private final Connection jdbc;
    private final Map<String, Map<String, Integer>> columnTypes = new HashMap<>();   // TABLE -> COLUMN -> java.sql.Types

    GridGainSink(String jdbcUrl) throws SQLException {
        this.jdbc = DriverManager.getConnection(jdbcUrl);
        this.client = IgniteClient.builder().addresses(addresses(jdbcUrl)).build();
    }

    Connection jdbc() { return jdbc; }

    /** "jdbc:ignite:thin://a:10800,b:10800" -> ["a:10800", "b:10800"] (a missing port means 10800). */
    static String[] addresses(String jdbcUrl) {
        String hosts = jdbcUrl.substring(jdbcUrl.indexOf("//") + 2);
        int q = hosts.indexOf('?');
        if (q >= 0) hosts = hosts.substring(0, q);
        String[] parts = hosts.split(",");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].contains(":") ? parts[i].trim() : parts[i].trim() + ":10800";
        return parts;
    }

    /** Drop what the readers cache about table definitions (after DDL). */
    void forgetSchema() {
        columnTypes.clear();
    }

    private Map<String, Integer> types(String table) throws SQLException {
        String t = table.toUpperCase(Locale.ROOT);
        Map<String, Integer> m = columnTypes.get(t);
        if (m != null) return m;
        m = new HashMap<>();
        try (ResultSet rs = jdbc.getMetaData().getColumns(null, null, t, null)) {
            while (rs.next()) m.put(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT), rs.getInt("DATA_TYPE"));
        }
        if (m.isEmpty()) throw new SQLException("table " + t + " not found in GridGain");
        columnTypes.put(t, m);
        return m;
    }

    /** Converts a value read through JDBC from a source system to the Java type a GridGain column of this SQL type expects. */
    static Object convert(Object v, int sqlType) {
        if (v == null) return null;
        switch (sqlType) {
            case Types.INTEGER: return v instanceof Number n ? n.intValue() : Integer.valueOf(v.toString().trim());
            case Types.BIGINT: return v instanceof Number n ? n.longValue() : Long.valueOf(v.toString().trim());
            case Types.SMALLINT: return v instanceof Number n ? n.shortValue() : Short.valueOf(v.toString().trim());
            case Types.TINYINT: return v instanceof Number n ? n.byteValue() : Byte.valueOf(v.toString().trim());
            case Types.DECIMAL: case Types.NUMERIC:
                return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
            case Types.REAL: case Types.FLOAT: return v instanceof Number n ? n.floatValue() : Float.valueOf(v.toString());
            case Types.DOUBLE: return v instanceof Number n ? n.doubleValue() : Double.valueOf(v.toString());
            case Types.BOOLEAN: case Types.BIT:
                return v instanceof Boolean b ? b : v instanceof Number n ? n.intValue() != 0 : Boolean.valueOf(v.toString());
            case Types.TIMESTAMP:
                if (v instanceof Timestamp ts) return ts.toLocalDateTime();     // the JVM runs in UTC, like the source and the nodes
                if (v instanceof java.util.Date d) return new Timestamp(d.getTime()).toLocalDateTime();
                return v instanceof LocalDateTime ? v : LocalDateTime.parse(v.toString().replace(' ', 'T'));
            case Types.DATE:
                if (v instanceof Date d) return d.toLocalDate();
                return v instanceof LocalDate ? v : LocalDate.parse(v.toString().substring(0, 10));
            default: return v instanceof String s ? s : v.toString();
        }
    }

    /** Batched upserts into one table; {@code add} takes the source columns in the same order as {@code columns}. */
    final class Upserter {
        private final RecordView<Tuple> view;
        private final String[] cols;
        private final int[] sqlTypes;
        private final List<Tuple> batch = new ArrayList<>();
        private final int batchSize;

        private Upserter(String table, List<String> columns, int batchSize) throws SQLException {
            Map<String, Integer> t = types(table);
            this.cols = columns.stream().map(c -> c.toUpperCase(Locale.ROOT)).toArray(String[]::new);
            this.sqlTypes = new int[cols.length];
            for (int i = 0; i < cols.length; i++) {
                Integer ty = t.get(cols[i]);
                if (ty == null) throw new SQLException("column " + cols[i] + " not found in GridGain table " + table);
                sqlTypes[i] = ty;
            }
            this.view = client.tables().table(table.toUpperCase(Locale.ROOT)).recordView();
            this.batchSize = batchSize;
        }

        /** Add the current row of a result set whose columns 1..n are this table's columns, in order. */
        int add(ResultSet rs) throws SQLException {
            Tuple row = Tuple.create(cols.length);
            for (int i = 0; i < cols.length; i++) row.set(cols[i], convert(rs.getObject(i + 1), sqlTypes[i]));
            batch.add(row);
            return batch.size() >= batchSize ? flush() : 0;
        }

        int add(Object... values) {
            Tuple row = Tuple.create(cols.length);
            for (int i = 0; i < cols.length; i++) row.set(cols[i], convert(values[i], sqlTypes[i]));
            batch.add(row);
            return batch.size() >= batchSize ? flush() : 0;
        }

        /** Write what is buffered; returns the number of rows written. */
        int flush() {
            int n = batch.size();
            if (n > 0) view.upsertAll(null, batch);
            batch.clear();
            return n;
        }
    }

    Upserter upserter(String table, List<String> columns, int batchSize) throws SQLException {
        return new Upserter(table, columns, batchSize);
    }

    /** Upsert one row given as column -> value. */
    void upsertRow(String table, Map<String, Object> values) throws SQLException {
        Upserter u = new Upserter(table, new ArrayList<>(values.keySet()), 1);
        u.add(values.values().toArray());
        u.flush();
    }

    @Override
    public void close() {
        try { client.close(); } catch (Exception ignored) { }
        try { jdbc.close(); } catch (SQLException ignored) { }
    }
}
