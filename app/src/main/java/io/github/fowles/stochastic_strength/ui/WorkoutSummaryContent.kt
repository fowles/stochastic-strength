package io.github.fowles.stochastic_strength.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WorkoutSummaryContent(
    summary: WorkoutSummaryData?,
    modifier: Modifier = Modifier,
    onExerciseTap: ((Long) -> Unit)? = null,
    belowDuration: (@Composable ColumnScope.() -> Unit)? = null,
    header: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        header()
        if (summary == null) {
            CircularProgressIndicator()
        } else {
            val minutes = summary.durationSeconds / 60
            val seconds = summary.durationSeconds % 60
            Text(
                "Duration: ${minutes}m ${seconds}s",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (belowDuration != null) {
                Spacer(Modifier.height(12.dp))
                belowDuration()
            }
            Spacer(Modifier.height(24.dp))
            summary.exercises.forEach { ex ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onExerciseTap != null) Modifier.clickable { onExerciseTap(ex.exerciseId) }
                            else Modifier
                        ),
                ) {
                    ExerciseSetSection(ex.name, ex.sets, summary.weightUnit)
                    Spacer(Modifier.height(4.dp))
                }
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        footer()
    }
}
