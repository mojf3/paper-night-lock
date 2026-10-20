package com.paper.nightlock

import java.text.Normalizer
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class Score(val distance: Int, val length: Int) {
    val accuracy: Double get() = if (length == 0) 0.0 else 1.0 - distance.toDouble() / length
    // Compare integers so rounding never promotes 89.9% to a pass.
    val passed: Boolean get() = length > 0 && distance.toLong() * 10 <= length.toLong()
    val display: String get() {
        val tenths = if (length == 0) 0 else ((length - distance).toLong() * 1000 / length).coerceIn(0, 1000).toInt()
        return String.format(Locale.ROOT, "%.1f%%", tenths / 10.0)
    }
}

object TextScorer {
    fun normalized(text: String): IntArray = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).codePoints().filter { cp ->
            !Character.isWhitespace(cp) && !Character.isSpaceChar(cp) && Character.getType(cp) !in setOf(
                Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt()
            )
        }.toArray()

    fun score(reference: String, answer: String): Score {
        val a = normalized(reference)
        val b = normalized(answer)
        val length = max(a.size, b.size)
        if (a.isEmpty() || b.isEmpty()) return Score(length, length)
        var prefix = 0
        while (prefix < min(a.size, b.size) && a[prefix] == b[prefix]) prefix++
        var endA = a.size
        var endB = b.size
        while (endA > prefix && endB > prefix && a[endA - 1] == b[endB - 1]) { endA--; endB-- }
        var previous = IntArray(endB - prefix + 1) { it }
        var current = IntArray(previous.size)
        for (i in 1..(endA - prefix)) {
            current[0] = i
            for (j in 1..(endB - prefix)) {
                current[j] = min(min(current[j - 1] + 1, previous[j] + 1),
                    previous[j - 1] + if (a[prefix + i - 1] == b[prefix + j - 1]) 0 else 1)
            }
            val swap = previous; previous = current; current = swap
        }
        return Score(previous.last(), length)
    }
}

data class ActiveWindow(val key: String, val end: ZonedDateTime)

data class NightWindow(val startMinute: Int = 60, val endMinute: Int = 420) {
    init { require(startMinute in 0..1439 && endMinute in 0..1439 && startMinute != endMinute) }
    private fun boundary(day: ZonedDateTime, minute: Int): ZonedDateTime = day.toLocalDate()
        .atTime(minute / 60, minute % 60).atZone(day.zone)
    fun active(now: ZonedDateTime): ActiveWindow? {
        // Compare instants of concrete daily intervals, including overnight and DST transitions.
        for (offset in -1..0) {
            val day = now.plusDays(offset.toLong())
            val start = boundary(day, startMinute)
            val end = boundary(if (startMinute > endMinute) day.plusDays(1) else day, endMinute)
            if (!now.isBefore(start) && now.isBefore(end)) return ActiveWindow(start.toInstant().toString(), end)
        }
        return null
    }
    fun next(now: ZonedDateTime, start: Boolean): ZonedDateTime = (-1..3)
        .map { boundary(now.plusDays(it.toLong()), if (start) startMinute else endMinute) }
        .filter { it.isAfter(now) }.minBy { it.toInstant() }
    companion object { fun label(minute: Int) = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60) }
}
