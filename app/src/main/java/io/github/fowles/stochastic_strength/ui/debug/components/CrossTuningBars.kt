package io.github.fowles.stochastic_strength.ui.debug.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.domain.progression.CrossTuningRow

@Composable
internal fun CrossTuningSection(rows: List<CrossTuningRow>, highlightedName: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { row ->
            CrossTuningItem(row, highlighted = row.name == highlightedName)
        }
    }
}

@Composable
private fun CrossTuningItem(row: CrossTuningRow, highlighted: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlighted) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(140.dp),
            )
            DivergingBar(
                value = row.agreement,
                maxMagnitude = MAX_DEVIATION,
                modifier = Modifier.weight(1f).height(16.dp).padding(horizontal = 4.dp),
            )
            Text(
                text = formatSignedPercent(row.agreement),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp),
            )
        }
        ContributionBar(row.contribution)
    }
}

@Composable
private fun ContributionBar(contribution: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(140.dp)) {
            Text(
                text = "contribution",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier.weight(1f).height(6.dp).padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier.fillMaxWidth(contribution.coerceIn(0f, 1f)).height(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.secondary),
            )
        }
        Text(
            text = "%.0f%%".format(contribution * 100f),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

private fun formatSignedPercent(value: Float): String {
    val pct = (value * 100f).toInt()
    return if (pct >= 0) "+$pct%" else "$pct%"
}
