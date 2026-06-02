package io.github.fowles.stochastic_strength.ui.strava

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StravaExportButton(
    onExportToStrava: () -> Unit,
    stravaState: StravaExportState,
    modifier: Modifier = Modifier,
) {
    val exported = stravaState is StravaExportState.Success
    OutlinedButton(
        onClick = onExportToStrava,
        enabled = !stravaState.isBusy && !exported,
        modifier = modifier,
    ) {
        when {
            stravaState.isBusy -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            exported -> Text("Exported to Strava")
            else -> Text("Export to Strava")
        }
    }
}
