package io.github.fowles.stochastic_strength.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.Block
import io.github.fowles.stochastic_strength.domain.CircuitStructure
import io.github.fowles.stochastic_strength.domain.WeightFormatter

/** Material 3's disabled-content alpha, so a dimmed value matches the disabled "−" beside it. */
private const val DISABLED_ALPHA = 0.38f

/** Where a row sits in its block; drives the handle column of [ExerciseRowScaffold]. */
enum class RowPlace { SOLO, FIRST, MIDDLE, LAST }

/** SOLO when [block] isn't a circuit; otherwise FIRST/MIDDLE/LAST by [index]'s place in it. */
fun rowPlace(block: Block, index: Int): RowPlace = when {
    !block.isCircuit -> RowPlace.SOLO
    index == block.start -> RowPlace.FIRST
    index == block.last -> RowPlace.LAST
    else -> RowPlace.MIDDLE
}

/** Whether the ⛓ node below a row is linked, and what to do when it's tapped. */
data class LinkState(val linked: Boolean, val onToggle: () -> Unit)

/**
 * The shared row: [drag handle | circuit rail] [sets chip] name/subtitle … trailing.
 * The handle column shows a drag icon on SOLO/FIRST rows and a vertical rail tracing the
 * circuit's run through MIDDLE/LAST rows; the sets chip (sets on SOLO, rounds on FIRST) is
 * blank on MIDDLE/LAST since those rows share the block's single chip.
 */
@Composable
fun ExerciseRowScaffold(
    place: RowPlace,
    sets: Int,
    onSetsChange: (Int) -> Unit,
    dragHandleModifier: Modifier,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
    body: @Composable ColumnScope.() -> Unit,
) {
    val railColor = MaterialTheme.colorScheme.primary
    val isBlockHead = place == RowPlace.SOLO || place == RowPlace.FIRST
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(36.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = 16.dp.toPx()
                    val strokeWidth = 2.dp.toPx()
                    when (place) {
                        RowPlace.FIRST -> drawLine(railColor, Offset(x, size.height / 2 + 12.dp.toPx()), Offset(x, size.height), strokeWidth)
                        RowPlace.MIDDLE -> drawLine(railColor, Offset(x, 0f), Offset(x, size.height), strokeWidth)
                        RowPlace.LAST -> drawLine(railColor, Offset(x, 0f), Offset(x, size.height / 2), strokeWidth)
                        RowPlace.SOLO -> {}
                    }
                },
        ) {
            if (isBlockHead) {
                Icon(
                    Icons.Filled.DragIndicator,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandleModifier.padding(start = 4.dp, end = 8.dp).size(24.dp),
                )
            }
        }

        if (isBlockHead) {
            var open by remember { mutableStateOf(false) }
            Box(modifier = Modifier.padding(end = 8.dp).width(44.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { open = true },
                    modifier = Modifier.semantics {
                        contentDescription = if (place == RowPlace.SOLO) "Sets: $sets" else "Rounds: $sets"
                    },
                ) {
                    Text(
                        "$sets ×",
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(44.dp).padding(vertical = 6.dp),
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (n in CircuitStructure.MIN_SETS..CircuitStructure.MAX_SETS) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    n.toString(),
                                    color = if (n == sets) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                )
                            },
                            onClick = { onSetsChange(n); open = false },
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.width(52.dp))
        }

        Column(modifier = Modifier.weight(1f), content = body)
        trailing()
    }
}

/**
 * Overlays the node for the boundary above [content] on its top edge; the later row owns the
 * node so it paints, and is hit, above the row before it. Put the swipe box inside [content].
 * [linkAbove] is `null` on the list's first row.
 */
@Composable
fun LinkNodeHost(linkAbove: LinkState?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier) {
        content()
        if (linkAbove != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (16 - 18).dp, y = (-18).dp)
                    .size(36.dp)
                    .clickable(onClick = linkAbove.onToggle, role = Role.Button)
                    .semantics {
                        contentDescription = if (linkAbove.linked) "Split the circuit here" else "Link into a circuit"
                    },
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (linkAbove.linked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = if (linkAbove.linked) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(20.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            if (linkAbove.linked) Icons.Filled.Link else Icons.Filled.LinkOff,
                            contentDescription = null,
                            tint = if (linkAbove.linked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Inline − value + [unit] control for a per-row pin (reps, weight); [text] carries formatting the caller chose. */
@Composable
fun ValueStepper(
    text: String,
    pinned: Boolean,
    unit: String?,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onReset: () -> Unit,
    fewerDescription: String,
    moreDescription: String,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        IconButton(onClick = onDecrement, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = fewerDescription, modifier = Modifier.size(16.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (pinned) 1f else DISABLED_ALPHA),
            modifier = Modifier.widthIn(min = 28.dp).then(
                if (pinned) Modifier.clickable(onClickLabel = "Reset to suggested", onClick = onReset) else Modifier
            ),
        )
        IconButton(onClick = onIncrement, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Add, contentDescription = moreDescription, modifier = Modifier.size(16.dp))
        }
        unit?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** "suggests 35 lb" under a pinned weight that differs from the suggestion; nothing otherwise. */
@Composable
fun SuggestionNote(pinnedKg: Float?, suggestedKg: Float, unit: WeightUnit, modifier: Modifier = Modifier) {
    if (pinnedKg != null && suggestedKg > 0f && WeightFormatter.differsOnGrid(pinnedKg, suggestedKg, unit)) {
        Text(
            "suggests ${WeightFormatter.format(suggestedKg, unit)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun ExerciseRowScaffoldPreview() {
    MaterialTheme {
        var soloSets by remember { mutableStateOf(3) }
        var circuitRounds by remember { mutableStateOf(2) }
        var tailSoloSets by remember { mutableStateOf(4) }
        // One state per row boundary, named for the boundary it sits at (row above ⇄ row below).
        var linkAfterSquat by remember { mutableStateOf(false) }
        var linkAfterCurl by remember { mutableStateOf(true) }
        var linkAfterKickback by remember { mutableStateOf(true) }
        var linkAfterPress by remember { mutableStateOf(false) }

        Column {
            LinkNodeHost(linkAbove = null) {
                ExerciseRowScaffold(
                    place = RowPlace.SOLO,
                    sets = soloSets,
                    onSetsChange = { soloSets = it },
                    dragHandleModifier = Modifier,
                    trailing = {
                        ValueStepper(
                            text = "40 lb", pinned = true, unit = null,
                            onDecrement = {}, onIncrement = {}, onReset = {},
                            fewerDescription = "5 lb less", moreDescription = "5 lb more",
                        )
                    },
                ) {
                    Text("Barbell squat", style = MaterialTheme.typography.bodyLarge)
                    ValueStepper(
                        text = "8", pinned = false, unit = "reps",
                        onDecrement = {}, onIncrement = {}, onReset = {},
                        fewerDescription = "One rep fewer", moreDescription = "One rep more",
                    )
                }
            }
            LinkNodeHost(linkAbove = LinkState(linkAfterSquat) { linkAfterSquat = !linkAfterSquat }) {
                ExerciseRowScaffold(
                    place = RowPlace.FIRST,
                    sets = circuitRounds,
                    onSetsChange = { circuitRounds = it },
                    dragHandleModifier = Modifier,
                    trailing = {
                        ValueStepper(
                            text = "25 lb", pinned = true, unit = null,
                            onDecrement = {}, onIncrement = {}, onReset = {},
                            fewerDescription = "5 lb less", moreDescription = "5 lb more",
                        )
                    },
                ) {
                    Text("Dumbbell curl", style = MaterialTheme.typography.bodyLarge)
                    ValueStepper(
                        text = "10", pinned = true, unit = "reps",
                        onDecrement = {}, onIncrement = {}, onReset = {},
                        fewerDescription = "One rep fewer", moreDescription = "One rep more",
                    )
                }
            }
            LinkNodeHost(linkAbove = LinkState(linkAfterCurl) { linkAfterCurl = !linkAfterCurl }) {
                ExerciseRowScaffold(
                    place = RowPlace.MIDDLE,
                    sets = circuitRounds,
                    onSetsChange = {},
                    dragHandleModifier = Modifier,
                    trailing = {
                        ValueStepper(
                            text = "–", pinned = false, unit = null,
                            onDecrement = {}, onIncrement = {}, onReset = {},
                            fewerDescription = "5 lb less", moreDescription = "5 lb more",
                        )
                    },
                ) {
                    Text("Tricep kickback", style = MaterialTheme.typography.bodyLarge)
                    ValueStepper(
                        text = "5–10", pinned = false, unit = "reps",
                        onDecrement = {}, onIncrement = {}, onReset = {},
                        fewerDescription = "One rep fewer", moreDescription = "One rep more",
                    )
                }
            }
            LinkNodeHost(linkAbove = LinkState(linkAfterKickback) { linkAfterKickback = !linkAfterKickback }) {
                ExerciseRowScaffold(
                    place = RowPlace.LAST,
                    sets = circuitRounds,
                    onSetsChange = {},
                    dragHandleModifier = Modifier,
                    trailing = {
                        ValueStepper(
                            text = "20 lb", pinned = true, unit = null,
                            onDecrement = {}, onIncrement = {}, onReset = {},
                            fewerDescription = "5 lb less", moreDescription = "5 lb more",
                        )
                    },
                ) {
                    Text("Overhead press", style = MaterialTheme.typography.bodyLarge)
                    ValueStepper(
                        text = "8", pinned = true, unit = "reps",
                        onDecrement = {}, onIncrement = {}, onReset = {},
                        fewerDescription = "One rep fewer", moreDescription = "One rep more",
                    )
                }
            }
            LinkNodeHost(linkAbove = LinkState(linkAfterPress) { linkAfterPress = !linkAfterPress }) {
                ExerciseRowScaffold(
                    place = RowPlace.SOLO,
                    sets = tailSoloSets,
                    onSetsChange = { tailSoloSets = it },
                    dragHandleModifier = Modifier,
                    trailing = {
                        ValueStepper(
                            text = "45 lb", pinned = false, unit = null,
                            onDecrement = {}, onIncrement = {}, onReset = {},
                            fewerDescription = "5 lb less", moreDescription = "5 lb more",
                        )
                    },
                ) {
                    Text("Lat pulldown", style = MaterialTheme.typography.bodyLarge)
                    ValueStepper(
                        text = "12", pinned = false, unit = "reps",
                        onDecrement = {}, onIncrement = {}, onReset = {},
                        fewerDescription = "One rep fewer", moreDescription = "One rep more",
                    )
                }
            }
        }
    }
}
