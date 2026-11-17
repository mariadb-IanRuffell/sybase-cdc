package cdc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MetricsTest {
    CdcSource src;
    Connection gg;
    GridGainSink sink;
    GridGainSink.Upserter counts;

    @BeforeEach
    void setUp() throws Exception {
        src = mock(CdcSource.class);
        gg = mock(Connection.class);
        sink = mock(GridGainSink.class);
        counts = mock(GridGainSink.Upserter.class);
        when(src.gg()).thenReturn(gg);
        when(src.sink()).thenReturn(sink);
        when(src.consumer()).thenReturn("oracle");
        when(src.position()).thenReturn(777L);
        when(src.pending()).thenReturn(4L);
        when(src.tableNames()).thenReturn(List.of("flight", "booking"));
        when(src.countRows("flight")).thenReturn(238L);
        when(src.countRows("booking")).thenReturn(650L);
        when(sink.upserter(eq("cdc_table_counts"), anyList(), anyInt())).thenReturn(counts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publishedStatus() throws Exception {
        ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
        verify(sink).upsertRow(eq("cdc_status"), row.capture());
        return row.getValue();
    }

    @Test
    void recordsNothingForEmptyBatches() throws Exception {
        Metrics m = new Metrics(src);
        m.record(new Result(0, 0, 0, 5, 0, 3));
        m.tick();          // heartbeat is due on the first tick
        Map<String, Object> row = publishedStatus();
        assertEquals(0L, row.get("total_events"));
        assertEquals(0L, row.get("batches"));
    }

    @Test
    void accumulatesCountersAndPublishesThem() throws Exception {
        Metrics m = new Metrics(src);
        long ago = System.currentTimeMillis() - 1500;
        m.record(new Result(10, 8, 2, 100, ago, 25));
        m.record(new Result(5, 5, 0, 105, ago, 12));
        m.tick();

        Map<String, Object> row = publishedStatus();
        assertEquals("oracle", row.get("consumer"));
        assertEquals(777L, row.get("last_change_id"));    // position
        assertEquals(4L, row.get("pending_events"));
        assertEquals(15L, row.get("total_events"));
        assertEquals(13L, row.get("total_upserts"));
        assertEquals(2L, row.get("total_deletes"));
        assertEquals(2L, row.get("batches"));
        assertEquals(5, row.get("last_batch_events"));
        assertEquals(12L, row.get("last_batch_ms"));
        long latency = (Long) row.get("last_latency_ms");
        assertTrue(latency >= 1500 && latency < 10_000, "latency " + latency);
        assertTrue(row.get("started_at") instanceof Timestamp);
        assertTrue(row.get("updated_at") instanceof Timestamp);
    }

    @Test
    void latencyIsZeroWhenTheBatchHasNoTimestamp() throws Exception {
        Metrics m = new Metrics(src);
        m.record(new Result(1, 1, 0, 1, 0, 1));
        m.tick();
        assertEquals(0L, publishedStatus().get("last_latency_ms"));
    }

    @Test
    void publishesSourceRowCountsInOneBatch() throws Exception {
        new Metrics(src).tick();
        verify(counts).add(eq("flight"), eq(238L), any(Timestamp.class));
        verify(counts).add(eq("booking"), eq(650L), any(Timestamp.class));
        verify(counts).flush();
    }

    @Test
    void secondTickWithinTheIntervalDoesNotWriteAgain() throws Exception {
        Metrics m = new Metrics(src);
        m.tick();
        m.record(new Result(1, 1, 0, 1, 0, 1));   // dirty, but the busy interval (1 s) has not elapsed
        m.tick();
        verify(sink, times(1)).upsertRow(eq("cdc_status"), anyMap());
        verify(counts, times(1)).flush();
    }

    @Test
    void ensureTablesCreatesTheZoneAndBothTablesAndRestoresCounters() throws Exception {
        Statement st = mock(Statement.class);
        when(gg.createStatement()).thenReturn(st);
        PreparedStatement sel = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(gg.prepareStatement(startsWith("SELECT total_events"))).thenReturn(sel);
        when(sel.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(100L);
        when(rs.getLong(2)).thenReturn(90L);
        when(rs.getLong(3)).thenReturn(10L);
        when(rs.getLong(4)).thenReturn(7L);

        Metrics m = new Metrics(src);
        m.ensureTables();
        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(st, times(3)).execute(ddl.capture());
        assertTrue(ddl.getAllValues().get(0).startsWith("CREATE ZONE IF NOT EXISTS c360"));
        assertTrue(ddl.getAllValues().get(1).contains("cdc_status") && ddl.getAllValues().get(1).endsWith("ZONE c360"));
        assertTrue(ddl.getAllValues().get(2).contains("cdc_table_counts") && ddl.getAllValues().get(2).contains("source_rows"));
        verify(sel).setString(1, "oracle");
        verify(sink).forgetSchema();

        m.record(new Result(1, 1, 0, 1, 0, 1));   // continues from the restored totals
        m.tick();
        Map<String, Object> row = publishedStatus();
        assertEquals(101L, row.get("total_events"));
        assertEquals(91L, row.get("total_upserts"));
        assertEquals(10L, row.get("total_deletes"));
        assertEquals(8L, row.get("batches"));
    }

    @Test
    void ensureTablesWithNoPreviousRowStartsAtZero() throws Exception {
        when(gg.createStatement()).thenReturn(mock(Statement.class));
        PreparedStatement sel = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(gg.prepareStatement(startsWith("SELECT total_events"))).thenReturn(sel);
        when(sel.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        Metrics m = new Metrics(src);
        m.ensureTables();
        m.record(new Result(3, 3, 0, 1, 0, 1));
        m.tick();
        Map<String, Object> row = publishedStatus();
        assertEquals(3L, row.get("total_events"));
        assertEquals(1L, row.get("batches"));
    }

    @Test
    void startedAtIsTheReadersStartTime() throws Exception {
        new Metrics(src).tick();
        Timestamp started = (Timestamp) publishedStatus().get("started_at");
        assertTrue(Math.abs(started.getTime() - System.currentTimeMillis()) < 5_000);
    }
}
