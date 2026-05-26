package io.github.fowles.stochastic_strength.ui.exercises

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(exerciseId: Long, onBack: () -> Unit) {
    val viewModel: ExerciseDetailViewModel =
        viewModel(factory = ExerciseDetailViewModel.factory(exerciseId))
    val state by viewModel.state.collectAsState()
    val exercise = state.exercise

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(exercise?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (exercise == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = {}, label = { Text(exercise.equipment.displayName()) })
                AssistChip(onClick = {}, label = { Text(exercise.primaryMuscle.displayName()) })
            }

            if (exercise.secondaryMuscles.isNotEmpty()) {
                Text(
                    text = "Also works: " + exercise.secondaryMuscles.joinToString(", ") { it.displayName() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = exercise.isDisliked,
                    onClick = { viewModel.toggleDisliked() },
                    label = { Text("Disliked") },
                )
                if (exercise.hurtFlag) {
                    OutlinedButton(onClick = { viewModel.clearHurtFlag() }) {
                        Text("Clear Hurt Flag", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Progress",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )

            if (state.primaryPoints.isEmpty() && state.shadowPoints.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No history yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ExerciseChart(
                    primaryPoints = state.primaryPoints,
                    shadowPoints = state.shadowPoints,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ExerciseChart(
    primaryPoints: List<ChartPoint>,
    shadowPoints: List<ChartPoint>,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(primaryPoints, shadowPoints) {
        modelProducer.runTransaction {
            lineSeries {
                if (primaryPoints.isNotEmpty()) {
                    series(
                        x = primaryPoints.map { it.dateMs / 86_400_000L },
                        y = primaryPoints.map { it.weightKg },
                    )
                }
                if (shadowPoints.isNotEmpty()) {
                    series(
                        x = shadowPoints.map { it.dateMs / 86_400_000L },
                        y = shadowPoints.map { it.weightKg },
                    )
                }
            }
        }
    }

    CartesianChartHost(
        chart = rememberCartesianChart(rememberLineCartesianLayer()),
        modelProducer = modelProducer,
        modifier = modifier,
    )
}

private fun MuscleGroup.displayName(): String =
    name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }

private fun Equipment.displayName(): String =
    name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
