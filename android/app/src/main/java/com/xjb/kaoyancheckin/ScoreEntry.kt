package com.xjb.kaoyancheckin

import org.json.JSONObject
import java.math.BigDecimal

internal data class ScoreSubject(val key: String, val label: String)

internal object ScoreEntry {
    val subjects = listOf(
        ScoreSubject("phys", "生理"),
        ScoreSubject("bioc", "生化"),
        ScoreSubject("path", "病理"),
        ScoreSubject("med", "内科"),
        ScoreSubject("surg", "外科"),
    )

    fun normalizeParts(values: Map<String, String>, total: Double): JSONObject {
        require(total.isFinite() && total in 0.0..300.0) { "请填 0-300 的总分" }
        val parts = JSONObject()
        var sum = 0.0
        subjects.forEach { subject ->
            val raw = values[subject.key]?.trim().orEmpty()
            if (raw.isEmpty()) return@forEach
            val value = raw.toDoubleOrNull()
            if (value == null || !value.isFinite() || value < 0.0 || value > 300.0) {
                throw IllegalArgumentException("分科得分须为 0–300 的数字")
            }
            parts.put(subject.key, value)
            sum += value
        }
        require(sum <= total) { "分科得分合计不能超过总分" }
        return parts
    }

    fun label(key: String): String = subjects.firstOrNull { it.key == key }?.label ?: key

    fun describe(parts: JSONObject): String = subjects.mapNotNull { subject ->
        val value = parts.opt(subject.key)
        if (value is Number && value.toDouble().isFinite()) {
            "${subject.label} ${format(value.toDouble())}"
        } else {
            null
        }
    }.joinToString(" / ")

    private fun format(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString()
        else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
