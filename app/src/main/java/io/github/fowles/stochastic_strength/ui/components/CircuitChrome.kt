package io.github.fowles.stochastic_strength.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.domain.CircuitStructure

/** Material 3's disabled-content alpha, so a dimmed value matches the disabled "−" beside it. */
private const val DISABLED_ALPHA = 0.38f

/** Inline − n + control. With [dimAtMin] the minimum reads as "off" rather than as a live number. */
@Composable
fun CountStepper(
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    fewerDescription: String,
    moreDescription: String,
    modifier: Modifier = Modifier,
    dimAtMin: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = fewerDescription)
        }
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
                .copy(alpha = if (dimAtMin && value == range.first) DISABLED_ALPHA else 1f),
            modifier = Modifier.widthIn(min = 24.dp),
        )
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, contentDescription = moreDescription)
        }
    }
}

/** The ⛓ between two adjacent rows: filled when they are in one circuit, outlined when they are not. */
@Composable
fun LinkToggle(linked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(
                if (linked) Icons.Filled.Link else Icons.Filled.LinkOff,
                contentDescription = if (linked) "Split the circuit here" else "Link into a circuit",
                tint = if (linked) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Top edge of a circuit card: the handle that drags the whole circuit, and its rounds. */
@Composable
fun CircuitHeader(
    rounds: Int,
    onRoundsChange: (Int) -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        Icon(
            Icons.Filled.DragIndicator,
            contentDescription = "Drag to reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = dragHandleModifier.padding(start = 4.dp, end = 8.dp).size(24.dp),
        )
        Text(
            "Circuit",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text("rounds", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CountStepper(
            value = rounds,
            range = CircuitStructure.MIN_SETS..CircuitStructure.MAX_SETS,
            onChange = onRoundsChange,
            fewerDescription = "One round fewer",
            moreDescription = "One round more",
        )
    }
}
