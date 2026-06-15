package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryContent
import io.github.fowles.stochastic_strength.ui.WorkoutSummaryData
import io.github.fowles.stochastic_strength.ui.strava.StravaExportButton
import io.github.fowles.stochastic_strength.ui.strava.StravaExportState

@Composable
internal fun DoneContent(
    doneSummary: WorkoutSummaryData?,
    stravaState: StravaExportState,
    onExportToStrava: () -> Unit,
    onDone: () -> Unit,
) {
    WorkoutSummaryContent(
        summary = doneSummary,
        header = {
            Text("Workout Complete!", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
        },
        footer = {
            StravaExportButton(
                onExportToStrava = onExportToStrava,
                stravaState = stravaState,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        },
    )
}
