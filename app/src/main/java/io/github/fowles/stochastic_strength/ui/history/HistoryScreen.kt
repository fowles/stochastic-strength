package io.github.fowles.stochastic_strength.ui.history

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onSessionTap: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
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
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                SectionHeader("Current Strength")
            }

            item {
                StrengthGrid(
                    strengths = state.muscleStrengths,
                    weightUnit = state.weightUnit,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            item {
                SectionHeader("Sessions")
            }

            if (state.sessions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No sessions yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.sessions, key = { it.session.id }) { item ->
                    SessionRow(item = item, onClick = { onSessionTap(item.session.id) })
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun StrengthGrid(
    strengths: List<MuscleGroupStrength>,
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        strengths.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pair.forEach { strength ->
                    StrengthCard(
                        strength = strength,
                        weightUnit = weightUnit,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StrengthCard(
    strength: MuscleGroupStrength,
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = strength.muscleGroup.name.split('_')
                    .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = WeightFormatter.format(strength.baselineWeight, weightUnit),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SessionRow(item: SessionListItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatDate(item.session.startTime),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (item.locationName != null) {
                Text(
                    text = item.locationName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.exerciseNames.isNotEmpty()) {
                Text(
                    text = item.exerciseNames.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatDuration(item.durationSeconds),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${item.exerciseNames.size} exercises",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy")

private fun formatDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DATE_FORMATTER)

private fun formatDuration(seconds: Long): String =
    "%d:%02d".format(seconds / 60, seconds % 60)
