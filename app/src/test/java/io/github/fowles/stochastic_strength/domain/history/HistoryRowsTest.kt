package io.github.fowles.stochastic_strength.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class HistoryRowsTest {

    private val zone = ZoneId.of("America/New_York")

    private fun ms(y: Int, m: Int, d: Int, h: Int = 12): Long =
        ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `workoutDays collapses multiple sessions on one day and uses the zone`() {
        val days = HistoryRows.workoutDays(listOf(ms(2026, 7, 4, 9), ms(2026, 7, 4, 18), ms(2026, 7, 1)), zone)
        assertEquals(setOf(LocalDate.of(2026, 7, 4), LocalDate.of(2026, 7, 1)), days)
    }

    @Test
    fun `buildRows inserts a header at each month boundary, newest first`() {
        val dates = listOf(
            LocalDate.of(2026, 7, 17),
            LocalDate.of(2026, 7, 2),
            LocalDate.of(2026, 6, 28),
        )
        val rows = HistoryRows.buildRows(dates)
        assertEquals(
            listOf(
                HistoryRow.MonthHeader(YearMonth.of(2026, 7)),
                HistoryRow.Entry(0, dates[0]),
                HistoryRow.Entry(1, dates[1]),
                HistoryRow.MonthHeader(YearMonth.of(2026, 6)),
                HistoryRow.Entry(2, dates[2]),
            ),
            rows,
        )
    }

    @Test
    fun `firstRowIndexForDate finds the entry row position`() {
        val dates = listOf(LocalDate.of(2026, 7, 17), LocalDate.of(2026, 7, 2))
        val rows = HistoryRows.buildRows(dates)
        assertEquals(2, HistoryRows.firstRowIndexForDate(rows, LocalDate.of(2026, 7, 2)))
        assertNull(HistoryRows.firstRowIndexForDate(rows, LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `buildRows on empty input is empty`() {
        assertEquals(emptyList<HistoryRow>(), HistoryRows.buildRows(emptyList()))
    }
}
