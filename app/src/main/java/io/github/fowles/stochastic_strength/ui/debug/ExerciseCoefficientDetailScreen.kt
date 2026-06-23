package io.github.fowles.stochastic_strength.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.ui.components.BackTopAppBar
import io.github.fowles.stochastic_strength.ui.components.LoadingBox
import io.github.fowles.stochastic_strength.ui.components.SectionHeader
import io.github.fowles.stochastic_strength.ui.components.formatDateTime
import io.github.fowles.stochastic_strength.ui.debug.components.CrossTuningSection
import io.github.fowles.stochastic_strength.ui.debug.components.ExerciseProgressionChart
import io.github.fowles.stochastic_strength.ui.debug.components.ProgressionChartSeries
import io.github.fowles.stochastic_strength.ui.debug.components.progressionColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseCoefficientDetailScreen(exerciseId: Long, onBack: () -> Unit) {
    val viewModel: ExerciseCoefficientDetailViewModel =
        viewModel(factory = ExerciseCoefficientDetailViewModel.factory(exerciseId))
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = { BackTopAppBar(title = state.exercise?.name ?: "", onBack = onBack) },
    ) { padding ->
        if (state.loading) {
            LoadingBox(contentPadding = padding)
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("Estimated 1RM over time", verticalPadding = 4.dp) }

            item {
                val hasData = state.progressionSeries.any { it.points.isNotEmpty() }
                if (!hasData) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("No sessions yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    ExerciseProgressionChart(
                        series = state.progressionSeries,
                        yFormatter = { value -> WeightFormatter.format(value, state.weightUnit) },
                        modifier = Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 16.dp),
                    )
                    ProgressionLegend(state.progressionSeries)
                }
            }

            item { SectionHeader("Cross-tuning", verticalPadding = 4.dp) }

            item {
                if (state.crossTuning.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("No weighted exercises", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    CrossTuningSection(rows = state.crossTuning, highlightedName = state.exercise?.name)
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
                items(state.events, key = { "${it.computedAt}_${it.heuristicName}" }) { event ->
                    CoefficientEventRow(event)
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressionLegend(series: List<ProgressionChartSeries>) {
    val colors = progressionColors()
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        series.filter { it.points.isNotEmpty() }.forEach { s ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(colors.getValue(s.colorRole), RoundedCornerShape(2.dp))
                )
                Text(s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CoefficientEventRow(event: CoefficientEvent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatDateTime(event.computedAt),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = event.heuristicName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val transition = if (event.previousCoefficient != null) {
            "%.3f → %.3f".format(event.previousCoefficient, event.coefficient)
        } else {
            "%.3f".format(event.coefficient)
        }
        Text(text = transition, style = MaterialTheme.typography.bodyLarge)
        if (event.heuristicMetadata != null) {
            Text(
                text = event.heuristicMetadata,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
