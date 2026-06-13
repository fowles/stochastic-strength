package io.github.fowles.stochastic_strength.ui.debug.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * Pins the chart x-axis to the local calendar day so that dots and tick
 * labels agree with the event-row dates (which are rendered in the system
 * default zone).
 *
 * Without this, timestamps whose UTC date differs from their local date
 * land on the wrong day on the chart — a one-day visual offset relative
 * to the raw-data list.
 */
class DebugLineChartTimezoneTest {

    @Test
    fun `late evening east-coast timestamp maps to the local calendar day`() {
        val zone = ZoneId.of("America/New_York")
        // 2026-06-11 23:00 EDT = 2026-06-12 03:00 UTC
        // Local date is Jun 11; UTC date is Jun 12.
        val ts = ZonedDateTime.of(2026, 6, 11, 23, 0, 0, 0, zone)
            .toInstant().toEpochMilli()

        assertEquals(
            LocalDate.of(2026, 6, 11).toEpochDay(),
            timestampToLocalEpochDay(ts, zone),
        )
    }

    @Test
    fun `early morning sydney timestamp maps to the local calendar day`() {
        val zone = ZoneId.of("Australia/Sydney")
        // 2026-06-12 06:00 AEST = 2026-06-11 20:00 UTC
        // Local date is Jun 12; UTC date is Jun 11.
        val ts = ZonedDateTime.of(2026, 6, 12, 6, 0, 0, 0, zone)
            .toInstant().toEpochMilli()

        assertEquals(
            LocalDate.of(2026, 6, 12).toEpochDay(),
            timestampToLocalEpochDay(ts, zone),
        )
    }

    @Test
    fun `formatting a local epoch day renders the same calendar day in that zone`() {
        val zone = ZoneId.of("America/New_York")
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(zone)
        }
        val day = LocalDate.of(2026, 6, 11).toEpochDay()

        assertEquals("2026-06-11", epochDayLabel(day, sdf))
    }
}
