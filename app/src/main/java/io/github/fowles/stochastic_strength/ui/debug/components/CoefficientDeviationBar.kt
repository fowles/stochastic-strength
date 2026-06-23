package io.github.fowles.stochastic_strength.ui.debug.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import io.github.fowles.stochastic_strength.ui.debug.CoefficientDeviationRow

// Fixed coefficient-vs-seed range. Deviations outside ±MAX_DEVIATION saturate.
internal const val MAX_DEVIATION = 0.5f

@Composable
internal fun CoefficientDeviationList(
    rows: List<CoefficientDeviationRow>,
    highlightedName: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { row ->
            DeviationRow(row, highlighted = row.name == highlightedName)
        }
    }
}

@Composable
internal fun DivergingBar(value: Float, maxMagnitude: Float, modifier: Modifier = Modifier) {
    val positiveColor = MaterialTheme.colorScheme.primary
    val negativeColor = MaterialTheme.colorScheme.error
    val guidelineColor = MaterialTheme.colorScheme.outlineVariant
    val tickColor = guidelineColor.copy(alpha = 0.5f)
    BoxWithConstraints(modifier = modifier) {
        val halfWidth = maxWidth / 2
        for (i in 1..5) {
            val offsetDp = halfWidth * (i / 5f)
            Box(Modifier.align(Alignment.Center).offset(x = offsetDp).width(1.dp).fillMaxHeight().background(tickColor))
            Box(Modifier.align(Alignment.Center).offset(x = -offsetDp).width(1.dp).fillMaxHeight().background(tickColor))
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (value < 0f) {
                    val fraction = ((-value) / maxMagnitude).coerceAtMost(1f)
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(fraction).height(10.dp).clip(RoundedCornerShape(2.dp)).background(negativeColor))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (value > 0f) {
                    val fraction = (value / maxMagnitude).coerceAtMost(1f)
                    Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(fraction).height(10.dp).clip(RoundedCornerShape(2.dp)).background(positiveColor))
                }
            }
        }
        Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(guidelineColor))
    }
}

@Composable
private fun DeviationRow(row: CoefficientDeviationRow, highlighted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlighted) FontWeight.Bold else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(140.dp),
        )
        DivergingBar(
            value = row.deviation,
            maxMagnitude = MAX_DEVIATION,
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
                .padding(horizontal = 4.dp),
        )
        Text(
            text = formatDeviation(row.deviation),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

private fun formatDeviation(deviation: Float): String {
    val pct = (deviation * 100f).toInt()
    return if (pct >= 0) "+$pct%" else "$pct%"
}
