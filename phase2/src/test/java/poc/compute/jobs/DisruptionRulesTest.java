package poc.compute.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DisruptionRulesTest {
    // ---- ProcessDisruptionsTask: compensation written for each affected segment

    @ParameterizedTest
    @CsvSource({"true,0,CANCELLED,1", "true,900,CANCELLED,1", "false,720,DELAY_720,0.75", "false,800,DELAY_720,0.75",
                "false,719,DELAY_360,0.5", "false,360,DELAY_360,0.5", "false,359,DELAY_SHORT,0.25", "false,180,DELAY_SHORT,0.25"})
    void processDisruptionsRule(boolean cancelled, int delay, String reason, String share) {
        assertEquals(reason, ProcessDisruptionsJob.reason(cancelled, delay));
        assertEquals(0, new BigDecimal(share).compareTo(ProcessDisruptionsJob.share(cancelled, delay)));
    }

    // ---- DisruptionImpactTask: the suggested (not written) compensation: delays under 180 minutes owe nothing

    @ParameterizedTest
    @CsvSource({"true,0,1", "false,720,0.75", "false,360,0.5", "false,359,0.25", "false,180,0.25", "false,179,0", "false,0,0"})
    void impactShare(boolean cancelled, int delay, String share) {
        assertEquals(0, new BigDecimal(share).compareTo(DisruptionImpactJob.share(cancelled, delay)));
    }

    @ParameterizedTest
    @CsvSource({"PLATINUM,FIRST,95000,439", "PLATINUM,BUSINESS,0,420", "GOLD,ECONOMY,25000,312", "SILVER,ECONOMY,9999,210",
                "BLUE,ECONOMY,0,110", "BLUE,ECONOMY,500000,119", "UNKNOWN,UNKNOWN,0,110"})
    void priorityOrdersTierThenCabinThenSpend(String tier, String cabin, String spend, int expected) {
        assertEquals(expected, DisruptionImpactJob.priority(tier, cabin, new BigDecimal(spend)));
    }

    @Test
    void aHigherTierAlwaysOutranksAHigherCabinOrSpend() {
        int goldFirstBigSpender = DisruptionImpactJob.priority("GOLD", "FIRST", new BigDecimal("900000"));
        int platinumEconomyNoSpend = DisruptionImpactJob.priority("PLATINUM", "ECONOMY", BigDecimal.ZERO);
        assertTrue(platinumEconomyNoSpend > goldFirstBigSpender);
    }
}
