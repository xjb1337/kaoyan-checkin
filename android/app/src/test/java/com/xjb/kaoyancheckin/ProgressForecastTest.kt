package com.xjb.kaoyancheckin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProgressForecastTest {
    private val exam = LocalDate.parse("2027-12-25")

    private fun date(value: String): LocalDate = LocalDate.parse(value)

    private fun settings(wordBase: Int, wordGoal: Int): JSONObject =
        JSONObject().put("exam", "2027-12-25").put("wordBase", wordBase).put("wordGoal", wordGoal)

    private fun wordState(
        counts: Map<String, Int>,
        wordBase: Int = 0,
        wordGoal: Int = 1000,
    ): JSONObject {
        val days = JSONObject()
        counts.toSortedMap().forEach { (day, count) ->
            days.put(day, JSONObject().put("w", JSONObject().put("n", count)))
        }
        return JSONObject().put("days", days).put("settings", settings(wordBase, wordGoal))
    }

    private fun subject(
        total: Int = 10,
        start: String = "2026-11-01",
        end: String = "2027-01-17",
    ): JSONObject = JSONObject()
        .put("k", "phys")
        .put("name", "生理")
        .put("s", start)
        .put("e", end)
        .put("ch", JSONArray((1..total).map { "第${it}章" }))

    private fun subjectState(
        done: Int,
        first: String? = null,
        completed: String? = null,
        total: Int = 10,
        dates: Map<Int, String> = emptyMap(),
    ): JSONObject {
        val flags = JSONArray()
        repeat(total) { flags.put(it < done) }
        val state = JSONObject()
            .put("days", JSONObject())
            .put("settings", settings(0, 1000))
            .put("ch", JSONObject().put("phys", flags))
        if (first != null) state.put("cf", JSONObject().put("phys", first))
        if (completed != null) state.put("ce", JSONObject().put("phys", completed))
        if (dates.isNotEmpty()) {
            state.put("cd", JSONObject().put("phys", JSONObject(dates.mapKeys { it.key.toString() })))
        }
        return state
    }

    private fun testPlan(subject: JSONObject = subject()): StudyPlan {
        val data = JSONObject()
            .put("START", "2026-11-02")
            .put("HISTORY_START", "2026-09-14")
            .put(
                "LEGACY_PHASE",
                JSONObject().put("id", "p0").put("s", "2026-09-14").put("e", "2026-11-01")
                    .put("name", "旧计划").put("gh", 2.5).put("tasks", JSONArray()),
            )
            .put(
                "PHASES",
                JSONArray().put(
                    JSONObject().put("id", "p1").put("s", "2026-11-02").put("e", "2027-01-17")
                        .put("name", "阶段1").put("gh", 1.5).put("tasks", JSONArray()),
                ),
            )
            .put("SUBJ", JSONArray().put(subject))
            .put("MILES", JSONArray())
            .put("PH4", JSONObject().put("id", "p4").put("gh", 1.5).put("name", "冲刺").put("tasks", JSONArray()))
            .put("PH5", JSONObject().put("id", "p5").put("gh", 1.5).put("fs", 1).put("name", "复试").put("tasks", JSONArray()))
        return StudyPlan(data)
    }

    private fun hoursState(hours: Map<String, Double>): JSONObject {
        val days = JSONObject()
        hours.toSortedMap().forEach { (day, value) ->
            days.put(day, JSONObject().put("h", value))
        }
        return JSONObject().put("days", days).put("settings", settings(0, 1000))
    }

    private fun combinedState(
        words: Map<String, Int>,
        hours: Map<String, Double>,
        done: Int,
        first: String,
    ): JSONObject {
        val days = JSONObject()
        words.forEach { (day, count) ->
            days.put(day, JSONObject().put("w", JSONObject().put("n", count)))
        }
        hours.forEach { (day, value) ->
            val record = days.optJSONObject(day) ?: JSONObject()
            days.put(day, record.put("h", value))
        }
        val flags = JSONArray()
        repeat(10) { flags.put(it < done) }
        return JSONObject()
            .put("days", days)
            .put("settings", settings(0, 1200))
            .put("ch", JSONObject().put("phys", flags))
            .put("cf", JSONObject().put("phys", first))
    }

    @Test
    fun wordsNeedAtLeastFiveRecordedDays() {
        val state = wordState((7..10).associate { "2026-11-${it.toString().padStart(2, '0')}" to 40 })
        val forecast = forecastWords(state, date("2026-11-10"), exam = exam)

        assertTrue(forecast is WordForecast.Insufficient)
        assertEquals(4, (forecast as WordForecast.Insufficient).activeDays)
        assertEquals("单词：累计 160 / 1000", formatWordsForecast(forecast))
    }

    @Test
    fun wordsAlreadyPastGoalAreComplete() {
        val state = wordState(mapOf("2026-11-09" to 100, "2026-11-10" to 100), wordBase = 1000, wordGoal = 1200)
        val forecast = forecastWords(state, date("2026-11-10"), exam = exam)

        assertTrue(forecast is WordForecast.Completed)
        assertEquals(1200L, (forecast as WordForecast.Completed).total)
        assertTrue(formatWordsForecast(forecast).contains("累计 1200 / 1200"))
    }

    @Test
    fun wordForecastIsEarlyAgainstPlanAndBeforeExam() {
        val state = wordState(
            mapOf(
                "2026-11-01" to 140,
                "2026-11-04" to 140,
                "2026-11-07" to 140,
                "2026-11-10" to 140,
                "2026-11-14" to 140,
            ),
            wordGoal = 1200,
        )
        val forecast = forecastWords(state, date("2026-11-14"), exam = exam) as WordForecast.Estimated

        assertEquals(date("2026-11-01"), forecast.windowStart)
        assertEquals(14L, forecast.windowEnd.toEpochDay() - forecast.windowStart.toEpochDay() + 1)
        assertEquals(5, forecast.activeDays)
        assertEquals(50.0, forecast.averagePerDay, 0.0001)
        assertEquals(date("2026-11-24"), forecast.estimatedDate)
        assertEquals(-96L, forecast.planDeltaDays)
        assertTrue(forecast.examMarginDays > 0)
        assertNull(forecast.requiredDaily)
        val text = formatWordsForecast(forecast)
        assertTrue(text.contains("日均新词 50.0 个"))
        assertTrue(text.contains("预计 11月24日 过完第一轮"))
        assertTrue(text.contains("距初试 "))
    }

    @Test
    fun wordForecastCanBeLateAgainstPlanButStillBeforeExam() {
        val state = wordState(
            mapOf(
                "2026-11-01" to 70,
                "2026-11-04" to 70,
                "2026-11-07" to 70,
                "2026-11-10" to 70,
                "2026-11-14" to 70,
            ),
            wordGoal = 3500,
        )
        val forecast = forecastWords(state, date("2026-11-14"), exam = exam) as WordForecast.Estimated

        assertEquals(date("2027-03-20"), forecast.estimatedDate)
        assertEquals(20L, forecast.planDeltaDays)
        assertTrue(forecast.examMarginDays > 0)
        assertEquals(30, forecast.requiredDaily)
        assertFalse(forecast.requiredDailyForExam)
        assertTrue(formatWordsForecast(forecast).contains("日均需 30 个"))
    }

    @Test
    fun wordForecastLateAgainstTargetButBeforeExamReportsNodePace() {
        val state = wordState(
            mapOf(
                "2026-11-01" to 70,
                "2026-11-04" to 70,
                "2026-11-07" to 70,
                "2026-11-10" to 70,
                "2026-11-14" to 70,
            ),
            wordGoal = 3050,
        )
        val forecast = forecastWords(state, date("2026-11-14"), exam = exam) as WordForecast.Estimated

        assertEquals(date("2027-03-02"), forecast.estimatedDate)
        assertEquals(2L, forecast.planDeltaDays)
        assertTrue(forecast.examMarginDays > 0)
        assertEquals(26, forecast.requiredDaily)
        assertFalse(forecast.requiredDailyForExam)
        val text = formatWordsForecast(forecast)
        assertTrue(text.contains("日均需 26 个"))
        assertTrue(text.contains("距初试 "))
    }

    @Test
    fun wordForecastAfterExamStatesRequiredDailyPace() {
        val state = wordState(
            mapOf(
                "2026-11-01" to 70,
                "2026-11-04" to 70,
                "2026-11-07" to 70,
                "2026-11-10" to 70,
                "2026-11-14" to 70,
            ),
            wordGoal = 12000,
        )
        val forecast = forecastWords(state, date("2026-11-14"), exam = exam) as WordForecast.Estimated

        assertEquals(date("2028-02-23"), forecast.estimatedDate)
        assertEquals(-60L, forecast.examMarginDays)
        assertEquals(110, forecast.requiredDaily)
        assertFalse(forecast.requiredDailyForExam)
        val text = formatWordsForecast(forecast)
        assertTrue(text.contains("日均需 110 个"))
        assertTrue(text.contains("初试前完不成"))
        assertTrue(text.contains("晚于初试 60 天"))
    }

    @Test
    fun zeroWordsTodayEndsWindowYesterdayButEstimatesFromToday() {
        val state = wordState(
            mapOf(
                "2026-10-31" to 100,
                "2026-11-03" to 100,
                "2026-11-06" to 100,
                "2026-11-09" to 100,
                "2026-11-13" to 100,
            ),
            wordGoal = 1200,
        )
        val forecast = forecastWords(state, date("2026-11-14"), exam = exam) as WordForecast.Estimated

        assertEquals(date("2026-11-13"), forecast.windowEnd)
        assertEquals(date("2026-12-04"), forecast.estimatedDate)
        assertEquals(5, forecast.activeDays)
    }

    @Test
    fun wordWindowNeverStartsBeforeFirstPositiveRecord() {
        val state = wordState(
            mapOf(
                "2026-11-10" to 100,
                "2026-11-11" to 100,
                "2026-11-12" to 100,
                "2026-11-13" to 100,
                "2026-11-14" to 100,
            ),
        )
        val forecast = forecastWords(state, date("2026-11-14"), exam = exam) as WordForecast.Estimated

        assertEquals(date("2026-11-10"), forecast.windowStart)
        assertEquals(5L, forecast.windowEnd.toEpochDay() - forecast.windowStart.toEpochDay() + 1)
        assertEquals(100.0, forecast.averagePerDay, 0.0001)
        assertEquals(date("2026-11-19"), forecast.estimatedDate)
    }

    @Test
    fun subjectWithoutFirstDateIsInsufficient() {
        val forecast = forecastSubject(subject(), subjectState(done = 3), date("2026-11-14"))

        assertTrue(forecast is SubjectForecast.Insufficient)
        assertEquals(SubjectForecastGap.MISSING_FIRST_DATE, (forecast as SubjectForecast.Insufficient).reason)
        val text = formatSubjectForecast(forecast)
        assertEquals("生理：3/10 章", text)
    }

    @Test
    fun subjectWithoutChaptersIsInsufficient() {
        val forecast = forecastSubject(subject(), subjectState(done = 0, first = "2026-11-01"), date("2026-11-14"))

        assertTrue(forecast is SubjectForecast.Insufficient)
        assertEquals(SubjectForecastGap.NO_CHAPTERS, (forecast as SubjectForecast.Insufficient).reason)
    }

    @Test
    fun subjectNeedsAtLeastTwoChapters() {
        val forecast = forecastSubject(
            subject(),
            subjectState(done = 1, first = "2026-11-01"),
            date("2026-11-20"),
        )

        assertTrue(forecast is SubjectForecast.Insufficient)
        assertEquals(SubjectForecastGap.TOO_FEW_CHAPTERS, (forecast as SubjectForecast.Insufficient).reason)
        assertEquals("生理：1/10 章", formatSubjectForecast(forecast))
    }

    @Test
    fun subjectNeedsSevenInclusiveDays() {
        val forecast = forecastSubject(
            subject(),
            subjectState(done = 2, first = "2026-11-09"),
            date("2026-11-14"),
        )

        assertTrue(forecast is SubjectForecast.Insufficient)
        val insufficient = forecast as SubjectForecast.Insufficient
        assertEquals(SubjectForecastGap.TOO_SHORT, insufficient.reason)
        assertEquals(6L, insufficient.elapsedDays)
        assertEquals("生理：2/10 章", formatSubjectForecast(forecast))
    }

    @Test
    fun completedSubjectComparesRecordedDateWithPlanEnd() {
        val early = forecastSubject(
            subject(),
            subjectState(done = 10, first = "2026-11-01", completed = "2027-01-10"),
            date("2027-01-10"),
        )
        val late = forecastSubject(
            subject(),
            subjectState(done = 10, first = "2026-11-01", completed = "2027-01-20"),
            date("2027-01-20"),
        )

        assertTrue(early is SubjectForecast.Completed)
        assertEquals(-7L, (early as SubjectForecast.Completed).planDeltaDays)
        assertEquals("生理：已完成 · 1月10日", formatSubjectForecast(early))
        assertTrue(late is SubjectForecast.Completed)
        assertEquals(3L, (late as SubjectForecast.Completed).planDeltaDays)
        assertEquals("生理：已完成 · 1月20日", formatSubjectForecast(late))
    }

    @Test
    fun subjectForecastUsesInclusiveElapsedDaysAndWeeklyPace() {
        val forecast = forecastSubject(
            subject(),
            subjectState(done = 4, first = "2026-11-01"),
            date("2026-11-14"),
        ) as SubjectForecast.Estimated

        assertEquals(14L, forecast.elapsedDays)
        assertEquals(date("2026-12-05"), forecast.estimatedDate)
        assertEquals(-43L, forecast.planDeltaDays)
        assertEquals(6 * 7.0 / 64.0, forecast.weeklyNeeded!!, 0.0001)
        assertFalse(forecast.planOverdue)
        val text = formatSubjectForecast(forecast)
        assertEquals("生理：预计 12月5日 学完 · 每周需 0.7 章", text)
    }

    @Test
    fun subjectForecastCanBeLateAndReportsPlanOverdue() {
        val late = forecastSubject(
            subject(end = "2026-12-01"),
            subjectState(done = 2, first = "2026-11-01"),
            date("2026-11-14"),
        ) as SubjectForecast.Estimated
        assertEquals(date("2027-01-09"), late.estimatedDate)
        assertEquals(39L, late.planDeltaDays)
        assertTrue(formatSubjectForecast(late).contains("预计 1月9日 学完"))

        val overdue = forecastSubject(
            subject(),
            subjectState(done = 4, first = "2026-11-01"),
            date("2027-01-20"),
        ) as SubjectForecast.Estimated
        assertTrue(overdue.planOverdue)
        assertTrue(formatSubjectForecast(overdue).contains("计划已于 1月17日 截止"))
    }

    @Test
    fun subjectForecastUsesRecentChapterDatesWhenAvailable() {
        val forecast = forecastSubject(
            subject(),
            subjectState(
                done = 5,
                first = "2026-11-01",
                dates = mapOf(
                    0 to "2026-11-01",
                    1 to "2026-11-02",
                    2 to "2026-11-03",
                    3 to "2026-11-20",
                    4 to "2026-12-09",
                ),
            ),
            date("2026-12-10"),
        ) as SubjectForecast.Estimated

        assertEquals(SubjectPace.RECENT, forecast.pace)
        assertEquals(5, forecast.remaining)
        assertEquals(date("2027-02-18"), forecast.estimatedDate)
        val text = formatSubjectForecast(forecast)
        assertTrue(text.contains("2月18日"))
        assertFalse(text.contains("按近 28 天速度"))
    }

    @Test
    fun subjectForecastFallsBackToAverageWhenRecentWindowIsEmpty() {
        val forecast = forecastSubject(
            subject(),
            subjectState(
                done = 3,
                first = "2026-11-01",
                dates = mapOf(
                    0 to "2026-11-01",
                    1 to "2026-11-02",
                    2 to "2026-11-10",
                ),
            ),
            date("2026-12-20"),
        ) as SubjectForecast.Estimated

        assertEquals(SubjectPace.AVERAGE, forecast.pace)
        assertEquals(date("2027-04-16"), forecast.estimatedDate)
        assertFalse(formatSubjectForecast(forecast).contains("按近 28 天速度"))
    }

    @Test
    fun subjectForecastNeedsThreeDatedChaptersAndSevenDaySpanForRecentPace() {
        val tooFew = forecastSubject(
            subject(),
            subjectState(
                done = 5,
                first = "2026-11-01",
                dates = mapOf(0 to "2026-11-01", 1 to "2026-11-20"),
            ),
            date("2026-12-10"),
        ) as SubjectForecast.Estimated
        val tooShort = forecastSubject(
            subject(),
            subjectState(
                done = 3,
                first = "2026-11-01",
                dates = mapOf(0 to "2026-11-01", 1 to "2026-11-03", 2 to "2026-11-05"),
            ),
            date("2026-12-10"),
        ) as SubjectForecast.Estimated

        assertEquals(SubjectPace.AVERAGE, tooFew.pace)
        assertEquals(SubjectPace.AVERAGE, tooShort.pace)
    }

    @Test
    fun subjectBeforePlanStartIsNotStarted() {
        val forecast = forecastSubject(subject(), subjectState(done = 0), date("2026-10-31"))

        assertTrue(forecast is SubjectForecast.NotStarted)
        assertEquals(date("2026-11-01"), (forecast as SubjectForecast.NotStarted).planStart)
        assertEquals("生理：11月1日 开始", formatSubjectForecast(forecast))
    }

    @Test
    fun upcomingSubjectsShareOneSummaryLine() {
        val forecasts = listOf(
            SubjectForecast.Completed("生理", 10, 10, date("2027-01-17"), date("2027-01-10"), -7),
            SubjectForecast.NotStarted("生化", 0, 12, date("2027-01-18")),
            SubjectForecast.NotStarted("病理", 0, 15, date("2027-03-01")),
        )

        assertEquals("后续：生化 1月18日、病理 3月1日开始", formatUpcomingSubjects(forecasts))
        assertNull(formatUpcomingSubjects(forecasts.take(1)))
    }

    @Test
    fun hoursAchievementUsesSameWindowAndPlanStart() {
        val state = hoursState(
            mapOf(
                "2026-11-10" to 5.2,
                "2026-11-11" to 5.2,
                "2026-11-12" to 5.2,
                "2026-11-13" to 5.2,
                "2026-11-14" to 5.2,
            ),
        )
        val forecast = forecastHours(state, testPlan(), date("2026-11-14")) as HoursForecast.Estimated

        assertEquals(date("2026-11-02"), forecast.windowStart)
        assertEquals(date("2026-11-14"), forecast.windowEnd)
        assertEquals(5, forecast.activeDays)
        assertEquals(2.0, forecast.averageActual, 0.0001)
        assertEquals(1.5, forecast.averageTarget, 0.0001)
        assertEquals(133.333, forecast.achievementRate, 0.01)
        assertTrue(formatHoursForecast(forecast).contains("日均 2.0h / 目标 1.5h · 达成率 133%"))
    }

    @Test
    fun hoursNeedFiveRecordedDaysAndSkipTodayWhenEmpty() {
        val state = hoursState(
            mapOf(
                "2026-11-10" to 1.0,
                "2026-11-11" to 1.0,
                "2026-11-12" to 1.0,
                "2026-11-13" to 1.0,
            ),
        )
        val forecast = forecastHours(state, testPlan(), date("2026-11-14"))

        assertTrue(forecast is HoursForecast.Insufficient)
        assertEquals(4, (forecast as HoursForecast.Insufficient).activeDays)
        assertEquals(date("2026-11-13"), forecast.windowEnd)
        assertEquals("时长", formatHoursForecast(forecast))
    }

    @Test
    fun summaryFallsBackWhenEverythingIsInsufficient() {
        assertTrue(forecastSummaryItems(testPlan(), subjectState(done = 0), date("2026-11-14")).isEmpty())
    }

    @Test
    fun summaryCombinesCurrentSubjectWordsAndHours() {
        val state = combinedState(
            words = mapOf(
                "2026-11-01" to 140,
                "2026-11-04" to 140,
                "2026-11-07" to 140,
                "2026-11-10" to 140,
                "2026-11-14" to 140,
            ),
            hours = (10..14).associate { "2026-11-$it" to 5.2 },
            done = 4,
            first = "2026-11-01",
        )
        val items = forecastSummaryItems(testPlan(), state, date("2026-11-14"))

        val subject = items.single { it.text.startsWith("生理") }
        assertEquals("生理 · 预计 12月5日 学完", subject.text)
        assertEquals("提前 43 天", subject.status)
        assertEquals("平均", subject.method)
        assertTrue(items.any { it.text == "单词 · 预计 11月24日 过完第一轮" })
        assertTrue(items.any { it.text == "日均学习 2.0h" && it.status == "达成率 133%" })
    }
}
