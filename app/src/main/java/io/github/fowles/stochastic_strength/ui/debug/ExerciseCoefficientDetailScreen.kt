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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.ui.components.LoadingBox
import io.github.fowles.stochastic_strength.ui.components.SectionHeader
import io.github.fowles.stochastic_strength.ui.components.formatDateTime
import io.github.fowles.stochastic_strength.ui.debug.components.CoefficientDeviationList
import io.github.fowles.stochastic_strength.ui.debug.components.DebugLineChart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseCoefficientDetailScreen(exerciseId: Long, onBack: () -> Unit) {
    val viewModel: ExerciseCoefficientDetailViewModel =
        viewModel(factory = ExerciseCoefficientDetailViewModel.factory(exerciseId))
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.exercise?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            LoadingBox(contentPadding = padding)
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("Coefficient over time", verticalPadding = 4.dp) }

            item {
                if (state.chartPoints.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No coefficient changes yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    DebugLineChart(
                        points = state.chartPoints,
                        yFormatter = { value -> "%.3f".format(value) },
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
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No weighted exercises", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    CoefficientDeviationList(
                        rows = state.coefficientDeviations,
                        highlightedName = state.exercise?.name,
                    )
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
