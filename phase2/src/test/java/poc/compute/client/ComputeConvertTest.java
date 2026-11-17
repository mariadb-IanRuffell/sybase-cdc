package poc.compute.client;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import poc.compute.jobs.Json;

/** What the parity harness gets back from a job: ints as Integer, ISO timestamps as java.sql.Timestamp, decimals untouched. */
class ComputeConvertTest {
    @SuppressWarnings("unchecked")
    @Test
    void convertsJobJsonToWhatTheHarnessCompares() {
        Object r = Compute.convert(Json.read("{\"rc\":0,\"n\":5000000000,\"id\":7,\"amt\":12.50,\"t\":\"2026-11-17T13:00:00\",\"t2\":\"2026-11-17T13:00:00.250\","
            + "\"s\":\"text\",\"rows\":[{\"x\":1,\"when\":\"2026-01-02T03:04:05\"}]}"));
        Map<String, Object> m = (Map<String, Object>) r;
        assertEquals(0, m.get("rc"));
        assertEquals(7, m.get("id"));
        assertEquals(5_000_000_000L, m.get("n"));
        assertEquals(new BigDecimal("12.50"), m.get("amt"));
        assertEquals(Timestamp.valueOf("2026-11-17 13:00:00"), m.get("t"));
        assertEquals(Timestamp.valueOf("2026-11-17 13:00:00.25"), m.get("t2"));
        assertEquals("text", m.get("s"));
        Map<String, Object> row = (Map<String, Object>) ((List<?>) m.get("rows")).get(0);
        assertEquals(1, row.get("x"));
        assertEquals(Timestamp.valueOf("2026-01-02 03:04:05"), row.get("when"));
    }

    @Test
    void textThatLooksLikeADateButIsNotOneStaysText() {
        assertEquals("2026-11-17", Compute.convert("2026-11-17"));
        assertEquals("note 2026-11-17T13:00:00", Compute.convert("note 2026-11-17T13:00:00"));
    }

    @Test
    void theDeploymentUnitIsReferencedByNameAndLatest() {
        assertEquals("customer360-jobs", Jobs.UNIT.name());
        assertEquals("latest", Jobs.UNIT.version().render());
    }
}
