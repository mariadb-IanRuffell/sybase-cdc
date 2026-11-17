package poc.compute.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void scalarsAndNesting() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", 42);
        m.put("big", 5_000_000_000L);
        m.put("d", new BigDecimal("12.50"));
        m.put("s", "a \"quoted\" line\nbreak \\ tab\t");
        m.put("t", true);
        m.put("nothing", null);
        m.put("list", List.of(1, "two", List.of(3)));
        m.put("map", Map.of("k", "v"));
        assertEquals("{\"n\":42,\"big\":5000000000,\"d\":12.50,\"s\":\"a \\\"quoted\\\" line\\nbreak \\\\ tab\\t\",\"t\":true,\"nothing\":null,"
            + "\"list\":[1,\"two\",[3]],\"map\":{\"k\":\"v\"}}", Json.write(m));
    }

    @Test
    void decimalsKeepTheirExactDigits() {
        assertEquals("0.10", Json.write(new BigDecimal("0.10")));
        assertEquals("100000000000000000000.01", Json.write(new BigDecimal("100000000000000000000.01")));
        assertEquals(new BigDecimal("0.10"), ((List<?>) Json.read("[0.10]")).get(0));
    }

    @Test
    void doublesAreWrittenAsPlainDecimals() {
        assertEquals("0.5", Json.write(0.5d));
        assertEquals("null", Json.write(Double.NaN));
        assertEquals("1.25", Json.write(1.25f));
    }

    @Test
    void dateTimesAlwaysCarrySeconds() {
        assertEquals("\"2026-10-15T12:30:00\"", Json.write(LocalDateTime.of(2026, 10, 15, 12, 30)));
        assertEquals("\"2026-10-15T12:30:05.25\"", Json.write(LocalDateTime.of(2026, 10, 15, 12, 30, 5, 250_000_000)));
        assertEquals("\"2026-10-15\"", Json.write(LocalDate.of(2026, 10, 15)));
    }

    @Test
    void roundTripTypes() {
        Object v = Json.read("{\"a\":1,\"b\":-2,\"c\":1.5,\"d\":1e2,\"e\":[true,false,null],\"f\":\"x\\u0041y\",\"g\":{}}");
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) v;
        assertEquals(1L, m.get("a"));
        assertEquals(-2L, m.get("b"));
        assertEquals(new BigDecimal("1.5"), m.get("c"));
        assertEquals(new BigDecimal("1e2"), m.get("d"));
        assertEquals(java.util.Arrays.asList(true, false, null), m.get("e"));
        assertEquals("xAy", m.get("f"));
        assertTrue(((Map<?, ?>) m.get("g")).isEmpty());
        assertEquals(List.of("a", "b", "c", "d", "e", "f", "g"), new ArrayList<>(m.keySet()));    // key order is kept
    }

    @Test
    void readMapTreatsBlankAsEmpty() {
        assertTrue(Json.readMap(null).isEmpty());
        assertTrue(Json.readMap("  ").isEmpty());
        assertEquals(Map.of("a", 1L), Json.readMap(" { \"a\" : 1 } "));
    }

    @Test
    void malformedInputIsRejected() {
        assertThrows(RuntimeException.class, () -> Json.read("{\"a\":}"));
        assertThrows(RuntimeException.class, () -> Json.read("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> Json.read("1 2"));
        assertThrows(RuntimeException.class, () -> Json.read(""));
    }

    @Test
    void whatTheJobsReturnSurvivesAJsonRoundTrip() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rc", 0);
        out.put("order_id", 1_000_000_000);
        out.put("total_amount", new BigDecimal("11860.00"));
        out.put("when", LocalDateTime.of(2026, 11, 17, 13, 0, 0));
        out.put("items", List.of(Map.of("product_id", 4, "qty", 2)));
        Map<String, Object> back = Json.readMap(Json.write(out));
        assertEquals(0L, back.get("rc"));
        assertEquals(1_000_000_000L, back.get("order_id"));
        assertEquals(new BigDecimal("11860.00"), back.get("total_amount"));
        assertEquals("2026-11-17T13:00:00", back.get("when"));
        assertEquals(2L, ((Map<?, ?>) ((List<?>) back.get("items")).get(0)).get("qty"));
    }
}
