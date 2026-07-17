package io.github.fowles.stochastic_strength.ui.exercises

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.ui.components.BackTopAppBar
import io.github.fowles.stochastic_strength.ui.components.Sparkline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisesScreen(
    onExerciseTap: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: ExercisesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val hurtMap by viewModel.hurtMap.collectAsState()
    val sparklines by viewModel.sparklines.collectAsState()
    val lastPerformed by viewModel.lastPerformed.collectAsState()

    val filtered = remember(state.exercises, state.selectedFilter, state.selectedEquipmentFilter) {
        state.exercises
            .let { list ->
                if (state.selectedFilter != null) list.filter { it.primaryMuscle == state.selectedFilter }
                else list
            }
            .let { list ->
                if (state.selectedEquipmentFilter != null) list.filter { it.equipment == state.selectedEquipmentFilter }
                else list
            }
    }

    val grouped = remember(filtered) {
        filtered
            .sortedWith(compareBy({ it.primaryMuscle.ordinal }, { it.name }))
            .groupBy { it.primaryMuscle }
    }

    // The 7 most-recently-performed exercises that have a sparkline, drawn from the same
    // filtered pool so Recent stays consistent with the sections below.
    val recentExercises = remember(filtered, sparklines, lastPerformed) {
        filtered
            .filter { sparklines.containsKey(it.id) && lastPerformed.containsKey(it.id) }
            .sortedByDescending { lastPerformed[it.id] }
            .take(7)
    }

    Scaffold(
        topBar = { BackTopAppBar(title = "Exercises", onBack = onBack) },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                LazyRow(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedFilter == null,
                            onClick = { viewModel.setFilter(null) },
                            label = { Text("All") },
                        )
                    }
                    items(MuscleGroup.entries) { muscle ->
                        FilterChip(
                            selected = state.selectedFilter == muscle,
                            onClick = { viewModel.setFilter(muscle) },
                            label = { Text(muscle.displayName()) },
                        )
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = state.selectedEquipmentFilter == null,
                            onClick = { viewModel.setEquipmentFilter(null) },
                            label = { Text("All") },
                        )
                    }
                    items(Equipment.entries) { equipment ->
                        FilterChip(
                            selected = state.selectedEquipmentFilter == equipment,
                            onClick = { viewModel.setEquipmentFilter(equipment) },
                            label = { Text(equipment.displayName()) },
                        )
                    }
                }
            }

            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (recentExercises.isNotEmpty()) {
                    stickyHeader(key = "__recent__") { SectionHeaderBar("Recent") }
                    items(recentExercises, key = { "recent-${it.id}" }) { exercise ->
                        ExerciseRow(
                            exercise = exercise,
                            isHurt = hurtMap[exercise.id] ?: false,
                            sparkline = sparklines[exercise.id],
                            onClick = { onExerciseTap(exercise.id) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }
                for ((muscle, exercises) in grouped) {
                    stickyHeader(key = muscle.name) { SectionHeaderBar(muscle.displayName()) }
                    items(exercises, key = { it.id }) { exercise ->
                        ExerciseRow(
                            exercise = exercise,
                            isHurt = hurtMap[exercise.id] ?: false,
                            sparkline = sparklines[exercise.id],
                            onClick = { onExerciseTap(exercise.id) },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeaderBar(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ExerciseRow(
    exercise: Exercise,
    isHurt: Boolean,
    sparkline: List<Float>?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(exercise.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                exercise.equipment.displayName(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (exercise.isDisliked) {
            StatusBadge(
                label = "Disliked",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.width(4.dp))
        }
        if (isHurt) {
            StatusBadge(
                label = "Hurt",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        if (sparkline != null) {
            Sparkline(
                values = sparkline,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun StatusBadge(
    label: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        modifier = Modifier
            .background(containerColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
