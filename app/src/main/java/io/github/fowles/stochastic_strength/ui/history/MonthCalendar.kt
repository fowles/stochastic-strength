package io.github.fowles.stochastic_strength.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// Raw-pixel drag distance (not dp) required to register a month swipe.
private const val MONTH_SWIPE_THRESHOLD_PX = 60f

// Compact grid metrics: rows are a fixed height (not square cells, which made the
// month fill the whole screen and read as sparse), and the workout circle is a fixed
// size so it is always round and occupies most of the cell.
private val WEEK_ROW_HEIGHT = 40.dp
private val DAY_CIRCLE_SIZE = 34.dp

// How many months are stacked, newest first. Two keeps the view dense without
// crowding out the session list below.
private const val MONTHS_SHOWN = 2

/**
 * A compact, multi-month calendar. Shows [MONTHS_SHOWN] months ending at [shownMonth]
 * (newest at top), marks workout days with a large filled circle, and pages the whole
 * window by one month via the arrows or a horizontal swipe. Tapping a workout day emits
 * its date.
 */
@Composable
fun MonthCalendar(
    shownMonth: YearMonth,
    workoutDays: Set<LocalDate>,
    onMonthChange: (YearMonth) -> Unit,
    onDayTap: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .pointerInput(shownMonth) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (totalDrag > MONTH_SWIPE_THRESHOLD_PX) onMonthChange(shownMonth.minusMonths(1))
                        else if (totalDrag < -MONTH_SWIPE_THRESHOLD_PX) onMonthChange(shownMonth.plusMonths(1))
                        totalDrag = 0f
                    },
                ) { _, drag -> totalDrag += drag }
            },
    ) {
        for (offset in 0 until MONTHS_SHOWN) {
            val month = shownMonth.minusMonths(offset.toLong())
            MonthHeader(
                month = month,
                // Arrows live on the top (newest) month only; they page the whole window.
                onPrevMonth = if (offset == 0) ({ onMonthChange(shownMonth.minusMonths(1)) }) else null,
                onNextMonth = if (offset == 0) ({ onMonthChange(shownMonth.plusMonths(1)) }) else null,
            )
            WeekdayHeader()
            MonthGrid(month = month, workoutDays = workoutDays, onDayTap = onDayTap)
        }
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    onPrevMonth: (() -> Unit)?,
    onNextMonth: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (onPrevMonth != null) {
            IconButton(onClick = onPrevMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
        } else {
            Box(Modifier.size(48.dp))
        }
        Text(
            text = "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}",
            style = MaterialTheme.typography.titleMedium,
        )
        if (onNextMonth != null) {
            IconButton(onClick = onNextMonth) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        } else {
            Box(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun WeekdayHeader() {
    Row(modifier = Modifier.fillMaxWidth()) {
        // Week starts Monday to match ISO DayOfWeek ordering used in the grid.
        // DayOfWeek is a Java enum → values(), not the Kotlin-only .entries.
        for (dow in DayOfWeek.values()) {
            Text(
                text = dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
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
    val rows = (leadingBlanks + daysInMonth + 6) / 7

    for (week in 0 until rows) {
        Row(modifier = Modifier.fillMaxWidth().height(WEEK_ROW_HEIGHT)) {
            for (dowIndex in 0 until 7) {
                val dayNumber = week * 7 + dowIndex - leadingBlanks + 1
                Box(
                    modifier = Modifier.weight(1f).height(WEEK_ROW_HEIGHT),
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
                .size(DAY_CIRCLE_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dayNumber.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    } else {
        Text(
            text = dayNumber.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
