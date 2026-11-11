package com.xjb.kaoyancheckin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreChartMathTest {
    @Test fun yAxisMapsOneHundredToBottomAndThreeHundredToTop() {
        val height = ScoreChartMath.VIEW_HEIGHT
        assertEquals(136f, ScoreChartMath.plotY(100.0, height), 0.001f)
        assertEquals(81f, ScoreChartMath.plotY(200.0, height), 0.001f)
        assertEquals(26f, ScoreChartMath.plotY(300.0, height), 0.001f)
        assertEquals(136f, ScoreChartMath.plotY(0.0, height), 0.001f)
        assertEquals(26f, ScoreChartMath.plotY(400.0, height), 0.001f)
    }

    @Test fun singleScoreIsCenteredInPlotArea() {
        assertEquals(173f, ScoreChartMath.plotX(0, 1, ScoreChartMath.VIEW_WIDTH), 0.001f)
    }

    @Test fun multipleScoresUseFullPlotArea() {
        val width = ScoreChartMath.VIEW_WIDTH
        assertEquals(30f, ScoreChartMath.plotX(0, 3, width), 0.001f)
        assertEquals(316f, ScoreChartMath.plotX(2, 3, width), 0.001f)
    }

    @Test fun chartUsesFiveTicksAndGreenAtReferenceScore() {
        assertEquals(listOf(100, 150, 200, 250, 300), ScoreChartMath.axisScores)
        assertFalse(ScoreChartMath.meetsReference(199.9))
        assertTrue(ScoreChartMath.meetsReference(200.0))
        assertTrue(ScoreChartMath.meetsReference(250.0))
    }
}
