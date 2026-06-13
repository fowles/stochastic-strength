package io.github.fowles.stochastic_strength.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.ui.debug.components.DebugLineChart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuscleBaselineDetailScreen(muscleGroup: MuscleGroup, onBack: () -> Unit) {
    val viewModel: MuscleBaselineDetailViewModel =
        viewModel(factory = MuscleBaselineDetailViewModel.factory(muscleGroup))
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.muscleGroup.displayName()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("Baseline over time") }

            item {
                if (state.chartPoints.isEmpty()) {
                    EmptyHistoryPlaceholder()
                } else {
                    DebugLineChart(
                        points = state.chartPoints,
                        yFormatter = { value -> WeightFormatter.format(value, state.weightUnit) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(horizontal = 16.dp, vertical = 0.dp),
                    )
                }
            }

            item { SectionHeader("Coefficient vs seed") }

            item {
                if (state.coefficientDeviations.isEmpty()) {
                    EmptyDeviationsPlaceholder()
                } else {
                    CoefficientDeviationList(state.coefficientDeviations)
                }
            }

            item { SectionHeader("Change events") }

            if (state.events.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No change events yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.events, key = { it.sessionId.toString() + ":" + it.timestamp }) { event ->
                    BaselineEventRow(event, state.weightUnit)
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

// Fixed coefficient-vs-seed range. Deviations outside ±MAX_DEVIATION saturate.
private const val MAX_DEVIATION = 0.5f

@Composable
private fun CoefficientDeviationList(rows: List<CoefficientDeviationRow>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row -> DeviationRow(row) }
    }
}

@Composable
private fun DeviationRow(row: CoefficientDeviationRow) {
    val positiveColor = MaterialTheme.colorScheme.primary
    val negativeColor = MaterialTheme.colorScheme.error
    val guidelineColor = MaterialTheme.colorScheme.outlineVariant
    val tickColor = guidelineColor.copy(alpha = 0.5f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(140.dp),
        )
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
                .padding(horizontal = 4.dp),
        ) {
            val halfWidth = maxWidth / 2
            // Tick marks every 10% from -50% to +50% (i / 5 of half-width per side).
            for (i in 1..5) {
                val offsetDp = halfWidth * (i / 5f)
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = offsetDp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(tickColor),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = -offsetDp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(tickColor),
                )
            }
            Row(modifier = Modifier.fillMaxSize()) {
                // Left half — holds negative bars, anchored to the right edge (center guideline).
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (row.deviation < 0f) {
                        val fraction = ((-row.deviation) / MAX_DEVIATION).coerceAtMost(1f)
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxWidth(fraction)
                                .height(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(negativeColor),
                        )
                    }
                }
                // Right half — holds positive bars, anchored to the left edge (center guideline).
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    if (row.deviation > 0f) {
                        val fraction = (row.deviation / MAX_DEVIATION).coerceAtMost(1f)
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxWidth(fraction)
                                .height(10.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(positiveColor),
                        )
                    }
                }
            }
            // Center guideline drawn on top of the bars so they appear to start flush against it.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(guidelineColor),
            )
        }
        Text(
            text = formatDeviation(row.deviation),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

private fun formatDeviation(deviation: Float): String {
    val pct = (deviation * 100f).toInt()
    return if (pct >= 0) "+$pct%" else "$pct%"
}

@Composable
private fun EmptyDeviationsPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("No weighted exercises", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyHistoryPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("No history yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val DATETIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")

@Composable
private fun BaselineEventRow(event: BaselineEvent, weightUnit: WeightUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = Instant.ofEpochMilli(event.timestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(DATETIME_FORMATTER),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = event.reason.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${WeightFormatter.format(event.previousBaseline, weightUnit)} → " +
                WeightFormatter.format(event.newBaseline, weightUnit),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (event.exerciseNames.isNotEmpty()) {
            Text(
                text = "Exercises: " + event.exerciseNames.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (event.feedbacks.isNotEmpty()) {
            val repsSuffix = event.sessionReps?.let { " · reps: $it" } ?: ""
            Text(
                text = "Feedbacks: " + event.feedbacks.joinToString(", ") { it.name } + repsSuffix,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (event.minReductionFraction != null) {
            Text(
                text = "Reduction floor: %.0f%%".format(event.minReductionFraction * 100f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
