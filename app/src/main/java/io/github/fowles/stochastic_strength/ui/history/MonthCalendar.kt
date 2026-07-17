package io.github.fowles.stochastic_strength.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
                        if (totalDrag > 60f) onMonthChange(shownMonth.minusMonths(1))
                        else if (totalDrag < -60f) onMonthChange(shownMonth.plusMonths(1))
                        totalDrag = 0f
                    },
                ) { _, drag -> totalDrag += drag }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { onMonthChange(shownMonth.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                text = "${shownMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${shownMonth.year}",
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = { onMonthChange(shownMonth.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            // Week starts Monday to match ISO DayOfWeek ordering used below.
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

        val firstOfMonth = shownMonth.atDay(1)
        // Monday=1..Sunday=7 → blanks before day 1.
        val leadingBlanks = firstOfMonth.dayOfWeek.value - 1
        val daysInMonth = shownMonth.lengthOfMonth()
        val cells = leadingBlanks + daysInMonth
        val rows = (cells + 6) / 7

        for (week in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (dowIndex in 0 until 7) {
                    val cellIndex = week * 7 + dowIndex
                    val dayNumber = cellIndex - leadingBlanks + 1
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..daysInMonth) {
                            val date = shownMonth.atDay(dayNumber)
                            val isWorkout = date in workoutDays
                            val cellModifier = if (isWorkout) {
                                Modifier
                                    .padding(3.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable { onDayTap(date) }
                            } else Modifier
                            Box(
                                modifier = cellModifier.aspectRatio(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = dayNumber.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isWorkout) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isWorkout) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
