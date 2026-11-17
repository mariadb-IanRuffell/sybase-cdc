package cdc;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The pure parts of the GridGain 9 sink: where the client connects, and how source values become the Java types GridGain columns want. */
class GridGainSinkTest {
    @Test
    void clientAddressesComeFromTheJdbcUrl() {
        assertArrayEquals(new String[] {"127.0.0.1:11800", "127.0.0.1:11801"}, GridGainSink.addresses("jdbc:ignite:thin://127.0.0.1:11800,127.0.0.1:11801"));
        assertArrayEquals(new String[] {"gridgain1:10800", "gridgain2:10800"}, GridGainSink.addresses("jdbc:ignite:thin://gridgain1:10800, gridgain2"));
        assertArrayEquals(new String[] {"h:10800"}, GridGainSink.addresses("jdbc:ignite:thin://h:10800?schema=PUBLIC&timeout=5"));
    }

    @Test
    void nullStaysNullForEveryType() {
        for (int t : new int[] {Types.INTEGER, Types.BIGINT, Types.DECIMAL, Types.TIMESTAMP, Types.DATE, Types.BOOLEAN, Types.VARCHAR})
            assertNull(GridGainSink.convert(null, t));
    }

    @Test
    void numbersAreConvertedToTheColumnWidth() {
        assertEquals(42, GridGainSink.convert(42L, Types.INTEGER));
        assertEquals(42, GridGainSink.convert("42", Types.INTEGER));
        assertEquals(5_000_000_000L, GridGainSink.convert(5_000_000_000L, Types.BIGINT));
        assertEquals((short) -1, GridGainSink.convert(-1, Types.SMALLINT));
        assertEquals((byte) 7, GridGainSink.convert(7, Types.TINYINT));
        assertEquals(1.5f, GridGainSink.convert(1.5, Types.REAL));
        assertEquals(2.25, GridGainSink.convert(new BigDecimal("2.25"), Types.DOUBLE));
    }

    @Test
    void decimalsKeepTheirValue() {
        assertEquals(new BigDecimal("12.50"), GridGainSink.convert(new BigDecimal("12.50"), Types.DECIMAL));
        assertEquals(new BigDecimal("1200"), GridGainSink.convert(1200, Types.DECIMAL));
        assertEquals(new BigDecimal("0.1"), GridGainSink.convert("0.1", Types.NUMERIC));
    }

    @ParameterizedTest
    @CsvSource({"1,true", "0,false", "5,true"})
    void numericFlagsBecomeBooleans(int in, boolean out) {
        assertEquals(out, GridGainSink.convert(in, Types.BOOLEAN));
        assertEquals(out, GridGainSink.convert(in, Types.BIT));
    }

    @Test
    void booleansPassThroughAndStringsParse() {
        assertEquals(true, GridGainSink.convert(Boolean.TRUE, Types.BOOLEAN));
        assertEquals(false, GridGainSink.convert("false", Types.BOOLEAN));
    }

    @Test
    void timestampsAndDatesBecomeJavaTimeValues() {
        assertEquals(LocalDateTime.of(2026, 10, 15, 12, 30, 5), GridGainSink.convert(Timestamp.valueOf("2026-10-15 12:30:05"), Types.TIMESTAMP));
        assertEquals(LocalDateTime.of(2026, 10, 15, 12, 30, 5), GridGainSink.convert(Timestamp.valueOf("2026-10-15 12:30:05"), Types.TIMESTAMP));
        LocalDateTime ldt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        assertSame(ldt, GridGainSink.convert(ldt, Types.TIMESTAMP));
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), GridGainSink.convert("2026-01-02 03:04:05", Types.TIMESTAMP));
        assertEquals(LocalDate.of(2026, 10, 15), GridGainSink.convert(Date.valueOf("2026-10-15"), Types.DATE));
        assertEquals(LocalDate.of(2026, 10, 15), GridGainSink.convert("2026-10-15 00:00:00", Types.DATE));
    }

    @Test
    void textIsLeftAsItIs() {
        assertEquals("abc  ", GridGainSink.convert("abc  ", Types.VARCHAR));
        assertEquals("7", GridGainSink.convert(7, Types.VARCHAR));
    }
}
