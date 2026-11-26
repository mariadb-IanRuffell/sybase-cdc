package cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WriteThroughTest {
    @Test
    void hashIsStableAndSensitiveToEveryColumn() {
        Object[] a = {1, "Ada", new BigDecimal("75.250"), true, null};
        Object[] same = {1, "Ada", new BigDecimal("75.25"), true, null};
        assertEquals(WriteThrough.hash(a), WriteThrough.hash(same), "scale of a decimal is not a change");
        assertNotEquals(WriteThrough.hash(a), WriteThrough.hash(new Object[] {1, "Ada", new BigDecimal("75.26"), true, null}));
        assertNotEquals(WriteThrough.hash(a), WriteThrough.hash(new Object[] {1, "Ada", new BigDecimal("75.25"), false, null}));
        assertNotEquals(WriteThrough.hash(a), WriteThrough.hash(new Object[] {1, "Ada", new BigDecimal("75.25"), true, "x"}));
    }

    @Test
    void timestampsMatchWithinSybaseResolution() {
        LocalDateTime grid = LocalDateTime.of(2026, 11, 26, 13, 30, 49, 123_000_000);
        long ms = grid.toInstant(ZoneOffset.UTC).toEpochMilli();
        assertTrue(WriteThrough.sameValue(grid, new Timestamp(ms + 3)), "datetime rounds to 1/300 s");
        assertFalse(WriteThrough.sameValue(grid, new Timestamp(ms + 50)));
    }

    @Test
    void valuesCompareAcrossNumericTypesAndNulls() {
        assertTrue(WriteThrough.sameValue(7525, new BigDecimal("7525.00")));
        assertTrue(WriteThrough.sameValue(null, null));
        assertFalse(WriteThrough.sameValue(null, "x"));
        assertTrue(WriteThrough.sameValue("Dubai", "Dubai   "), "ASE pads char columns");
        assertTrue(WriteThrough.sameValue(true, true));
        assertFalse(WriteThrough.sameValue(true, false));
    }
}
