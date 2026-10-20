package com.paper.nightlock

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class RulesTest {
    @Test fun exactBoundary() {
        assertFalse(TextScorer.score("a".repeat(1000), "a".repeat(899)).passed)
        assertTrue(TextScorer.score("a".repeat(1000), "a".repeat(900)).passed)
        assertEquals(0.899, TextScorer.score("a".repeat(1000), "a".repeat(899)).accuracy, 0.0000001)
        val below = TextScorer.score("a".repeat(11000), "a".repeat(9899))
        assertFalse(below.passed)
        assertEquals("89.9%", below.display)
    }
    @Test fun normalizationAndUnicode() {
        assertEquals(1.0, TextScorer.score("ＡＢＣ，中文。\n test!", "abc 中文 TEST").accuracy, 0.0)
        assertEquals(1.0, TextScorer.score("研究🙂", "研究🙂").accuracy, 0.0)
        assertFalse(TextScorer.score("", "").passed)
        assertFalse(TextScorer.score("摘要", "， \n").passed)
        assertFalse(TextScorer.score("，", "有效内容").passed)
    }
    @Test fun insertionsDeletionsAndReplacements() {
        assertEquals(1, TextScorer.score("abcdefghij", "abcdefghi").distance)
        assertEquals(1, TextScorer.score("abcdefghij", "abcdefghijk").distance)
        assertEquals(1, TextScorer.score("abcdefghij", "abcdefghiX").distance)
        assertFalse(TextScorer.score("abcdefghij", "X".repeat(100)).passed)
        assertTrue(TextScorer.score("研究方法".repeat(1000), "研究方法".repeat(900)).passed)
    }
    @Test fun levenshteinKnownCases() {
        assertEquals(3, TextScorer.score("kitten", "sitting").distance)
        assertEquals(3, TextScorer.score("saturday", "sunday").distance)
    }
    private fun time(value: String) = ZonedDateTime.parse("2026-10-20T${value}+08:00[Asia/Shanghai]")
    @Test fun dailyWindow() {
        val window = NightWindow()
        assertNull(window.active(time("00:59:59")))
        assertNotNull(window.active(time("01:00:00")))
        assertNotNull(window.active(time("06:59:59")))
        assertNull(window.active(time("07:00:00")))
        assertEquals(21, window.next(time("01:00:00"), true).dayOfMonth)
        assertEquals(7, window.next(time("01:00:00"), false).hour)
    }
    @Test fun overnightAndTimeZone() {
        val window = NightWindow(22 * 60, 7 * 60)
        val late = window.active(time("23:00:00"))!!
        val next = window.active(time("03:00:00").plusDays(1))!!
        assertEquals(late.key, next.key)
        assertNull(window.active(time("12:00:00")))
        assertNotNull(NightWindow().active(ZonedDateTime.parse("2026-10-20T02:00:00-04:00[America/New_York]")))
    }
    @Test fun daylightSavingInterval() {
        val window = NightWindow(60, 240)
        assertNotNull(window.active(ZonedDateTime.parse("2026-11-01T01:30:00-05:00[America/New_York]")))
        assertNull(window.active(ZonedDateTime.parse("2026-11-01T04:00:00-05:00[America/New_York]")))
    }
    @Test(expected = IllegalArgumentException::class) fun identicalTimesRejected() { NightWindow(60, 60) }
}
