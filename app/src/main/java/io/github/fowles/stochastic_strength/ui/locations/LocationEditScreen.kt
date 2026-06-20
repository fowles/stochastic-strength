package io.github.fowles.stochastic_strength.ui.locations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.ui.components.BackTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationEditScreen(
    locationId: Long,
    onBack: () -> Unit,
    viewModel: LocationEditViewModel = viewModel(factory = LocationEditViewModel.factory(locationId)),
) {
    val state by viewModel.state.collectAsState()
    var collapsedSections by remember { mutableStateOf(emptySet<Equipment>()) }

    LaunchedEffect(state.exercisesByEquipment) {
        if (state.exercisesByEquipment.isNotEmpty() && collapsedSections.isEmpty()) {
            collapsedSections = state.exercisesByEquipment.keys.toSet()
        }
    }

    LaunchedEffect(state.navigateBack) {
        if (state.navigateBack) onBack()
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelDelete() },
            title = { Text("Delete location?") },
            text = {
                Text("\"${state.location?.name}\" and its exercise settings will be removed.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelDelete() }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = { BackTopAppBar(title = state.location?.name ?: "Location", onBack = onBack) },
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.padding(paddingValues)) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    OutlinedTextField(
                        value = state.location?.name ?: "",
                        onValueChange = { viewModel.updateName(it) },
                        label = { Text("Location name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { viewModel.requestDelete() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Delete Location")
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                }
            }

            for ((equipment, entries) in state.exercisesByEquipment.entries.sortedBy { it.key.name }) {
                val isCollapsed = equipment in collapsedSections
                item(key = equipment.name) {
                    val anyEnabled = entries.any { it.available }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                collapsedSections = if (equipment in collapsedSections)
                                    collapsedSections - equipment
                                else
                                    collapsedSections + equipment
                            }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (isCollapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                            contentDescription = if (isCollapsed) "Expand" else "Collapse",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = equipment.displayName(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp),
                        )
                        Switch(
                            checked = anyEnabled,
                            onCheckedChange = { viewModel.toggleEquipmentType(equipment) },
                        )
                    }
                }
                if (!isCollapsed) {
                    items(entries, key = { it.exercise.id }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 32.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.exercise.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = entry.available,
                                onCheckedChange = { viewModel.toggleExercise(entry.exercise.id) },
                            )
                        }
                    }
                }
                item(key = "${equipment.name}_divider") {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
