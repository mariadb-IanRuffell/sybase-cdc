package poc.compute.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class LoyaltyTest {
    @Test
    void listKeepsOrderAndAllowsNulls() {
        List<Object> l = Loyalty.list(1, "a", null);
        assertEquals(3, l.size());
        assertEquals(1, l.get(0));
        assertNull(l.get(2));
        l.add("mutable");
        assertEquals(4, l.size());
        assertTrue(Loyalty.list().isEmpty());
    }
}
