package com.yadaventerprise.phoneserver.data.reliability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class ReliabilityCalculatorTest {

    @Test
    public void calculatesPercentagesSafely() {
        assertEquals(0, ReliabilityCalculator.percent(1, 0));
        assertEquals(25, ReliabilityCalculator.percent(1, 4));
        assertEquals(100, ReliabilityCalculator.percent(9, 4));
        assertEquals(0.0, ReliabilityCalculator.errorRate(1, 0), 0.0001);
        assertEquals(10.0, ReliabilityCalculator.errorRate(1, 10), 0.0001);
    }

    @Test
    public void healthScoreDeductsRealFailures() {
        int score = ReliabilityCalculator.healthScore(false, false, 90, 1, 100, 1, 10);
        assertEquals(0, score);
        assertEquals("offline", ReliabilityCalculator.status(score, false));
    }

    @Test
    public void healthyServerGetsFullScore() {
        int score = ReliabilityCalculator.healthScore(true, true, 40, 50, 100, 0, 100);
        assertEquals(100, score);
        assertEquals("healthy", ReliabilityCalculator.status(score, true));
    }

    @Test
    public void warningsDescribeDegradedConditions() {
        List<String> warnings = ReliabilityCalculator.warnings(true, true, 90, 1, 100, 1, 10);
        assertTrue(warnings.toString().contains("Memory"));
        assertTrue(warnings.toString().contains("Free storage"));
        assertTrue(warnings.toString().contains("HTTP error rate"));
    }
}
