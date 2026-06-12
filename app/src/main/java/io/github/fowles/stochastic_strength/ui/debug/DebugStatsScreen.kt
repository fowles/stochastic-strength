package io.github.fowles.stochastic_strength.ui.debug

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import io.github.fowles.stochastic_strength.domain.CoefficientRow
import io.github.fowles.stochastic_strength.ui.components.StrengthGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugStatsScreen(
    onMuscleTap: (MuscleGroup) -> Unit,
    onExerciseTap: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: DebugStatsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug and Advanced Stats") },
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
            item { SectionHeader("Muscle Baselines") }
            item {
                StrengthGrid(
                    strengths = state.muscleStrengths,
                    tapTargets = MuscleGroup.entries.associateWith { it },
                    weightUnit = state.weightUnit,
                    onTap = onMuscleTap,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (state.recentCoefficientChanges.isNotEmpty()) {
                item { SectionHeader("Recently Changed Coefficients") }
                items(state.recentCoefficientChanges, key = { "recent-" + it.exerciseId }) { row ->
                    RecentCoefficientRow(row, onClick = { onExerciseTap(row.exerciseId) })
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }

            item { SectionHeader("All Exercises") }
            items(state.allCoefficients, key = { "all-" + it.exerciseId }) { row ->
                AlphabeticalCoefficientRow(row, onClick = { onExerciseTap(row.exerciseId) })
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun AlphabeticalCoefficientRow(row: CoefficientRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row {
            Text(row.exerciseName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("%.3f".format(row.currentCoefficient), style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = row.heuristicName ?: "not yet computed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentCoefficientRow(row: CoefficientRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row {
            Text(row.exerciseName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("%.3f".format(row.currentCoefficient), style = MaterialTheme.typography.bodyLarge)
        }
        val timestamp = row.computedAt?.let {
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        } ?: ""
        val prev = row.previousCoefficient?.let { "%.3f → %.3f".format(it, row.currentCoefficient) }
            ?: "%.3f".format(row.currentCoefficient)
        Text(
            text = if (timestamp.isNotEmpty()) "$prev · $timestamp" else prev,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val heuristicLine = listOfNotNull(row.heuristicName, row.heuristicMetadataPreview).joinToString(" · ")
        if (heuristicLine.isNotEmpty()) {
            Text(
                text = heuristicLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
