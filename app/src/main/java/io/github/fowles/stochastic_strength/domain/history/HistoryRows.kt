package io.github.fowles.stochastic_strength.domain.history

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

sealed interface HistoryRow {
    data class MonthHeader(val month: YearMonth) : HistoryRow
    data class Entry(val itemIndex: Int, val date: LocalDate) : HistoryRow
}

object HistoryRows {

    fun localDate(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    fun workoutDays(startTimesMs: List<Long>, zone: ZoneId): Set<LocalDate> =
        startTimesMs.map { localDate(it, zone) }.toSet()

    fun buildRows(dates: List<LocalDate>): List<HistoryRow> {
        val rows = mutableListOf<HistoryRow>()
        var currentMonth: YearMonth? = null
        dates.forEachIndexed { index, date ->
            val month = YearMonth.from(date)
            if (month != currentMonth) {
                rows += HistoryRow.MonthHeader(month)
                currentMonth = month
            }
            rows += HistoryRow.Entry(index, date)
        }
        return rows
    }

    fun firstRowIndexForDate(rows: List<HistoryRow>, date: LocalDate): Int? {
        val idx = rows.indexOfFirst { it is HistoryRow.Entry && it.date == date }
        return if (idx >= 0) idx else null
    }
}
