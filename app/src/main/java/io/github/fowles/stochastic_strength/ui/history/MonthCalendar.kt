package io.github.fowles.stochastic_strength.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import androidx.compose.ui.platform.LocalConfiguration

// Fixed per-day cell; months are packed at their natural width and scroll horizontally
// rather than stretching to fit the screen.
private val DAY_CELL_SIZE = 34.dp
// Small inset inside each cell → the gap between adjacent days.
private val DAY_CELL_INSET = 1.dp
// Gap between adjacent months in the horizontal strip.
private val MONTH_GAP = 32.dp
private const val DAYS_PER_WEEK = 7

/**
 * A horizontally scrolling calendar showing every month from the earliest workout to the
 * latest (oldest on the left), packed at a fixed width. Opens scrolled to the newest month.
 * Workout days are marked with a filled circle; tapping one emits its date.
 */
@Composable
fun MonthCalendar(
    workoutDays: Set<LocalDate>,
    onDayTap: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val months = monthsToShow(workoutDays)
    val scrollState = rememberScrollState()
    // Open at the newest month (right edge): wait for the first layout to give a real
    // maxValue, jump there once, then never again — so the user's own scrolling sticks.
    LaunchedEffect(Unit) {
        val end = snapshotFlow { scrollState.maxValue }.first { it > 0 }
        scrollState.scrollTo(end)
    }

    Row(
        modifier = modifier
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp),
    ) {
        months.forEachIndexed { index, month ->
            if (index > 0) Spacer(Modifier.width(MONTH_GAP))
            MonthColumn(month = month, workoutDays = workoutDays, onDayTap = onDayTap)
        }
    }
}

/** Every month from the earliest to the latest workout, inclusive; today's month if none. */
private fun monthsToShow(workoutDays: Set<LocalDate>): List<YearMonth> {
    if (workoutDays.isEmpty()) return listOf(YearMonth.now())
    val monthsWithWorkouts = workoutDays.map { YearMonth.from(it) }
    val start = monthsWithWorkouts.min()
    val end = monthsWithWorkouts.max()
    return generateSequence(start) { if (it < end) it.plusMonths(1) else null }.toList()
}

@Composable
private fun MonthColumn(
    month: YearMonth,
    workoutDays: Set<LocalDate>,
    onDayTap: (LocalDate) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier = Modifier.width(DAY_CELL_SIZE * DAYS_PER_WEEK)) {
        Text(
            text = "${month.month.getDisplayName(TextStyle.SHORT, locale)} ${month.year}",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(DAY_CELL_SIZE * DAYS_PER_WEEK).padding(bottom = 2.dp),
        )
        WeekdayHeader()
        MonthGrid(month = month, workoutDays = workoutDays, onDayTap = onDayTap)
    }
}

@Composable
private fun WeekdayHeader() {
    val locale = LocalConfiguration.current.locales[0]
    Row {
        // Week starts Monday to match ISO DayOfWeek ordering used in the grid.
        // DayOfWeek is a Java enum → values(), not the Kotlin-only .entries.
        for (dow in DayOfWeek.values()) {
            Text(
                text = dow.getDisplayName(TextStyle.NARROW, locale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.size(DAY_CELL_SIZE, 18.dp),
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    workoutDays: Set<LocalDate>,
    onDayTap: (LocalDate) -> Unit,
) {
    val firstOfMonth = month.atDay(1)
    // Monday=1..Sunday=7 → blanks before day 1.
    val leadingBlanks = firstOfMonth.dayOfWeek.value - 1
    val daysInMonth = month.lengthOfMonth()
    val rows = (leadingBlanks + daysInMonth + 6) / DAYS_PER_WEEK

    for (week in 0 until rows) {
        Row {
            for (dowIndex in 0 until DAYS_PER_WEEK) {
                val dayNumber = week * DAYS_PER_WEEK + dowIndex - leadingBlanks + 1
                Box(
                    modifier = Modifier.size(DAY_CELL_SIZE).padding(DAY_CELL_INSET),
                    contentAlignment = Alignment.Center,
                ) {
                    if (dayNumber in 1..daysInMonth) {
                        val date = month.atDay(dayNumber)
                        DayCell(
                            dayNumber = dayNumber,
                            isWorkout = date in workoutDays,
                            onTap = { onDayTap(date) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(dayNumber: Int, isWorkout: Boolean, onTap: () -> Unit) {
    if (isWorkout) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dayNumber.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    } else {
        Text(
            text = dayNumber.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
