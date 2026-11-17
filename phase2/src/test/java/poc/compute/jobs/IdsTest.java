package poc.compute.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IdsTest {
    @Test
    void needBuildsAnOrderedRequest() {
        Map<String, Integer> m = Ids.need("order_id", 1, "order_item_id", 3);
        assertEquals(List.of("order_id", "order_item_id"), List.copyOf(m.keySet()));
        assertEquals(3, m.get("order_item_id"));
    }

    @Test
    void idsStartFarAboveWhatTheSourcesGenerate() {
        assertEquals(1_000_000_000L, Ids.BASE);
        assertTrue(Ids.BASE < Integer.MAX_VALUE, "ids must still fit the INT key columns");
    }
}
