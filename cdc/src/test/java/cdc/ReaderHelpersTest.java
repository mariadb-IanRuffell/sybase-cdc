package cdc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import org.junit.jupiter.api.Test;

/** Pure helpers shared by both readers (value normalisation used by verify, statement binding, key encoding). */
class ReaderHelpersTest {
    @Test
    void normTreatsNullDecimalsAndPaddingConsistently() {
        for (java.util.function.Function<Object, String> norm : java.util.List.<java.util.function.Function<Object, String>>of(SybaseCdc::norm, OracleCdc::norm)) {
            assertEquals("~", norm.apply(null));
            assertEquals("0", norm.apply(new BigDecimal("0.00")));
            assertEquals("12.5", norm.apply(new BigDecimal("12.500")));
            assertEquals(norm.apply(new BigDecimal("1200")), norm.apply(new BigDecimal("1200.00")));
            assertEquals("1200", norm.apply(new BigDecimal("1.2E+3")));
            assertEquals("abc", norm.apply("abc   "));
            assertEquals("42", norm.apply(42));
            assertEquals("1700000000000", norm.apply(new Timestamp(1_700_000_000_000L)));
            assertEquals("2026-10-15", norm.apply(Date.valueOf("2026-10-15")));
            assertEquals("1700000000000", norm.apply(java.time.LocalDateTime.of(2023, 11, 14, 22, 13, 20)));   // GridGain 9 JDBC returns java.time values
            assertEquals("2026-10-15", norm.apply(java.time.LocalDate.of(2026, 10, 15)));
        }
    }

    @Test
    void stripCommentsRemovesWholeLineCommentsOnly() {
        String in = "-- header\nCREATE TABLE a (x INT); -- trailing stays\n   -- indented\nSELECT 1;";
        assertEquals("CREATE TABLE a (x INT); -- trailing stays\nSELECT 1;", SybaseCdc.stripComments(in));
        assertEquals(OracleCdc.stripComments(in), SybaseCdc.stripComments(in));
    }

    @Test
    void schemaResourcesAreOnTheClasspath() throws Exception {
        assertTrue(SybaseCdc.readResource("/gridgain-schema-sybase.sql").contains("CREATE TABLE IF NOT EXISTS customer"));
        assertTrue(OracleCdc.readResource("/gridgain-schema-oracle.sql").contains("CREATE TABLE IF NOT EXISTS flight_inventory"));
    }

    @Test
    void sybasePkBindingFollowsTheKeyType() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        SybaseCdc.setPk(ps, 1, Tables.BY_NAME.get("customer"), "42");
        SybaseCdc.setPk(ps, 2, Tables.BY_NAME.get("loyalty_tier_rule"), "GOLD");
        verify(ps).setInt(1, 42);
        verify(ps).setString(2, "GOLD");
    }

    @Test
    void oraclePkPredicate() {
        assertEquals("t.booking_id in (?, ?, ?)", OracleCdc.pkPredicate(OracleCdc.BY_NAME.get("booking"), 3));
        assertEquals("t.booking_id in (?)", OracleCdc.pkPredicate(OracleCdc.BY_NAME.get("booking"), 1));
        assertEquals("(t.flight_id, t.cabin) in ((?, ?), (?, ?))", OracleCdc.pkPredicate(OracleCdc.BY_NAME.get("flight_inventory"), 2));
    }

    @Test
    void oracleKeyEncoding() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        OracleCdc.Tbl inv = OracleCdc.BY_NAME.get("flight_inventory");
        when(rs.getLong(inv.cols.indexOf("flight_id") + 1)).thenReturn(134L);
        when(rs.getString(inv.cols.indexOf("cabin") + 1)).thenReturn("BUSINESS  ");
        assertEquals("134|BUSINESS", OracleCdc.keyOf(rs, inv));

        OracleCdc.Tbl ap = OracleCdc.BY_NAME.get("airport");
        when(rs.getString(1)).thenReturn("DXB");
        assertEquals("DXB", OracleCdc.keyOf(rs, ap));
    }

    @Test
    void oracleSetTypedParsesIntegersOnly() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        OracleCdc.setTyped(ps, 1, 'I', "99");
        OracleCdc.setTyped(ps, 2, 'S', "ECONOMY");
        verify(ps).setLong(1, 99L);
        verify(ps).setString(2, "ECONOMY");
        assertThrows(NumberFormatException.class, () -> OracleCdc.setTyped(ps, 3, 'I', "abc"));
    }

}
