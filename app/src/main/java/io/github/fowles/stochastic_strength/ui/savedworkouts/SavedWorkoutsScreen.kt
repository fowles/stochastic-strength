package io.github.fowles.stochastic_strength.ui.savedworkouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutDetail
import io.github.fowles.stochastic_strength.ui.components.BackTopAppBar
import io.github.fowles.stochastic_strength.ui.components.LoadingBox
import io.github.fowles.stochastic_strength.ui.components.workoutSubtitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedWorkoutsScreen(
    onWorkoutTap: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: SavedWorkoutsViewModel = viewModel(),
) {
    val workouts by viewModel.workouts.collectAsStateWithLifecycle()
    var deleteCandidate by remember { mutableStateOf<SavedWorkoutDetail?>(null) }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete \"${candidate.displayName}\"?") },
            text = { Text("This saved workout will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    viewModel.delete(candidate.id)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = { BackTopAppBar(title = "Workouts", onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onWorkoutTap(SavedWorkoutEditViewModel.NEW_WORKOUT_ID) }) {
                Icon(Icons.Default.Add, contentDescription = "New workout")
            }
        },
    ) { paddingValues ->
        val list = workouts
        when {
            list == null -> LoadingBox(contentPadding = paddingValues)
            list.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No saved workouts yet.\nTap + to build one, or use \"Save as workout...\" on a plan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { w ->
                    Card(onClick = { onWorkoutTap(w.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(w.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    workoutSubtitle(w.entries),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { deleteCandidate = w }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete ${w.displayName}")
                            }
                        }
                    }
                }
            }
        }
    }
}
