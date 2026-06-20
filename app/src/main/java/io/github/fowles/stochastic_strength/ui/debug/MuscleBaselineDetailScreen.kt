package io.github.fowles.stochastic_strength.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import io.github.fowles.stochastic_strength.ui.components.BackTopAppBar
import io.github.fowles.stochastic_strength.ui.components.LoadingBox
import io.github.fowles.stochastic_strength.ui.components.SectionHeader
import io.github.fowles.stochastic_strength.ui.components.formatDateTime
import io.github.fowles.stochastic_strength.ui.debug.components.CoefficientDeviationList
import io.github.fowles.stochastic_strength.ui.debug.components.DebugLineChart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuscleBaselineDetailScreen(muscleGroup: MuscleGroup, onBack: () -> Unit) {
    val viewModel: MuscleBaselineDetailViewModel =
        viewModel(factory = MuscleBaselineDetailViewModel.factory(muscleGroup))
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = { BackTopAppBar(title = state.muscleGroup.displayName(), onBack = onBack) },
    ) { padding ->
        if (state.loading) {
            LoadingBox(contentPadding = padding)
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("Baseline over time", verticalPadding = 4.dp) }

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

            item { SectionHeader("Coefficient vs seed", verticalPadding = 4.dp) }

            item {
                if (state.coefficientDeviations.isEmpty()) {
                    EmptyDeviationsPlaceholder()
                } else {
                    CoefficientDeviationList(state.coefficientDeviations)
                }
            }

            item { SectionHeader("Change events", verticalPadding = 4.dp) }

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
                text = formatDateTime(event.timestamp),
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
        event.exercises.forEach { exercise ->
            Text(
                text = "Exercise: " + exercise.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Feedback: " + exercise.setLines.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (event.heuristicMetadata != null) {
            Text(
                text = event.heuristicMetadata,
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
