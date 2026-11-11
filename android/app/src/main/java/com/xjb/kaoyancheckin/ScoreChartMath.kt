package com.xjb.kaoyancheckin

internal data class ScoreChartPoint(val x: Float, val y: Float, val score: Double)

internal object ScoreChartMath {
    const val VIEW_WIDTH = 330f
    const val VIEW_HEIGHT = 160f
    const val PLOT_LEFT = 30f
    const val PLOT_RIGHT = 14f
    const val PLOT_TOP = 26f
    const val PLOT_BOTTOM = 24f
    const val MIN_SCORE = 100.0
    const val MAX_SCORE = 300.0
    const val REFERENCE_SCORE = 200.0

    val axisScores = listOf(100, 150, 200, 250, 300)

    fun plotX(index: Int, count: Int, width: Float): Float {
        if (count <= 0) return 0f
        val left = width * PLOT_LEFT / VIEW_WIDTH
        val right = width * PLOT_RIGHT / VIEW_WIDTH
        if (count == 1) return left + (width - left - right) / 2f
        return left + index * (width - left - right) / (count - 1)
    }

    fun plotY(score: Double, height: Float): Float {
        val top = height * PLOT_TOP / VIEW_HEIGHT
        val bottom = height * PLOT_BOTTOM / VIEW_HEIGHT
        val ratio = ((score.coerceIn(MIN_SCORE, MAX_SCORE) - MIN_SCORE) / (MAX_SCORE - MIN_SCORE)).toFloat()
        return top + (1f - ratio) * (height - top - bottom)
    }

    fun points(scores: List<Double>, width: Float, height: Float): List<ScoreChartPoint> =
        scores.mapIndexed { index, score ->
            ScoreChartPoint(plotX(index, scores.size, width), plotY(score, height), score)
        }

    fun meetsReference(score: Double): Boolean = score >= REFERENCE_SCORE
}
