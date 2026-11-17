package poc.compute.jobs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ScoreCustomersJobTest {
    @ParameterizedTest
    // recency, orders12m, openCases, sentiment, expected
    @CsvSource({"10,5,0,0.5,0", "90,4,0,0,0", "91,4,0,0,20", "180,2,0,0,30", "181,1,0,0,60", "365,1,0,0,60",
                "366,1,0,0,80", "9999,0,0,0,90", "9999,0,5,-1,100", "30,4,1,0,5", "30,4,2,0,10", "30,4,9,0,10",
                "30,4,0,-0.01,10", "30,4,0,0.0,0"})
    void churnScoreComponents(long recency, long orders, long open, double sentiment, int expected) {
        assertEquals(expected, ScoreCustomersJob.churnScore(recency, orders, open, sentiment));
    }

    @Test
    void scoreNeverExceedsOneHundred() {
        assertEquals(100, ScoreCustomersJob.churnScore(100_000, 0, 1_000, -1));
    }

    @ParameterizedTest
    @CsvSource({"0,LOW", "29,LOW", "30,MEDIUM", "59,MEDIUM", "60,HIGH", "100,HIGH"})
    void riskBands(int score, String band) {
        assertEquals(band, ScoreCustomersJob.riskBand(score));
    }
}
