package io.github.fowles.stochastic_strength.ui.components

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.CircuitRow
import io.github.fowles.stochastic_strength.domain.Block
import io.github.fowles.stochastic_strength.domain.CircuitStructure
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.roundToInt

/** Material 3's disabled-content alpha, so a dimmed value matches the disabled "−" beside it. */
private const val DISABLED_ALPHA = 0.38f

/**
 * A [Block] with the rows it spans and its list key, all resolved up front.
 *
 * LazyColumn calls its `key` lambda lazily — outside composition, and sometimes against the
 * previous block list after the row list has already shrunk — and it keeps a removed item
 * composed while `animateItem` plays it out. A key or an item body that indexes back into the
 * live row list therefore reads past its end and crashes on a swipe-away. Resolving both here,
 * while the block and its rows still agree, is what makes removal safe.
 */
data class KeyedBlock<T>(val block: Block, val rows: List<T>, val key: Long)

/** [CircuitStructure.blocks], with each block's rows and its list key (its smallest member [id]). */
fun <T : CircuitRow<T>> keyedBlocks(rows: List<T>, id: (T) -> Long): List<KeyedBlock<T>> =
    CircuitStructure.blocks(rows).map { b ->
        KeyedBlock(b, rows.slice(b.indices), b.indices.minOf { id(rows[it]) })
    }

/** Where a row sits in its block; drives the handle column of [ExerciseRowScaffold]. */
enum class RowPlace { SOLO, FIRST, MIDDLE, LAST }

/** SOLO when [block] isn't a circuit; otherwise FIRST/MIDDLE/LAST by [index]'s place in it. */
fun rowPlace(block: Block, index: Int): RowPlace = when {
    !block.isCircuit -> RowPlace.SOLO
    index == block.start -> RowPlace.FIRST
    index == block.last -> RowPlace.LAST
    else -> RowPlace.MIDDLE
}

/**
 * The [LinkNodeHost] wiring for row [i] of [block]: `null` on the list's very first row (there is
 * no boundary above it), otherwise whether row [i] is linked to the row above it, plus a toggle
 * that calls [onUnlink] to split an existing link or [onLink] to create one — both indexed by the
 * row above the boundary, matching [io.github.fowles.stochastic_strength.domain.CircuitEdits].
 *
 * This is a plain function, not a composable, so the toggle lambda it builds gets none of the
 * Compose compiler's lambda memoization — a fresh, never-equal instance on every call. Call it
 * from `remember(block, i) { linkAbove(...) }` so the pair is stable between recompositions;
 * passing an unmemoized toggle straight into [LinkNodeHost] (or into a row composable that takes
 * it as a parameter) defeats skipping for that whole subtree.
 */
fun linkAbove(block: Block, i: Int, onLink: (Int) -> Unit, onUnlink: (Int) -> Unit): Pair<Boolean?, () -> Unit> {
    val linkedAbove = if (i == 0) null else i != block.start
    return linkedAbove to {
        if (i != block.start) onUnlink(i - 1) else onLink(i - 1)
    }
}

/**
 * The [LinkNodeHost] node's content description for a boundary whose current state is
 * [linkedAbove]. Kept short: [LinkNodeHost] reads the node between the two rows it links (a
 * negative `traversalIndex` ahead of its own row content), so which two rows are involved is
 * carried by reading position rather than by naming "the row above" in words.
 */
fun linkNodeDescription(linkedAbove: Boolean): String = if (linkedAbove) "Unlink" else "Link"

/**
 * The 36dp-wide gutter [ExerciseRowScaffold] draws its rail segment in, on its own so a caller
 * that swaps a row's normal body for other content (e.g. a swipe action row) can still draw the
 * rail through that row and keep the gutter width stable — which is what keeps a [LinkNodeHost]
 * node lined up above it, since the node's position is a fixed offset from this gutter's corner.
 */
@Composable
fun CircuitRailGutter(place: RowPlace, modifier: Modifier = Modifier) {
    val railColor = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
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
    )
}

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
    val isBlockHead = place == RowPlace.SOLO || place == RowPlace.FIRST
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircuitRailGutter(place)
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
 * [linkedAbove] is `null` on the list's first row, which has no boundary above it.
 *
 * [swipeOffsetPx] is the later row's own live horizontal swipe translation in px (0 when it
 * isn't being swiped): [content] here is a sibling of the node, not an ancestor, so a swipe
 * offset applied inside [content] (e.g. by a `SwipeToDismissBox`) never reaches this node on its
 * own — without this, the node stays put while the row it's anchored to slides out from under it.
 *
 * The node's two inputs stay separate parameters (rather than one holder) so Compose can compare
 * them: a holder built in composition around a lambda never compares equal, and this host would
 * then recompose on every pass.
 *
 * Visually the node paints above [content] (the later row), but it toggles the link to the row
 * *before* it — so this [Box] is marked [isTraversalGroup] and the node given a negative
 * [traversalIndex], putting it ahead of [content] in TalkBack order within this one host, instead
 * of the default after-content order the later-row ownership would otherwise produce.
 *
 * That fixes ordering *inside* one [LinkNodeHost], but a circuit's rows aren't one-host-per-list-
 * item: at both call sites a `LazyColumn` item is a whole circuit block, and the per-row
 * `LinkNodeHost` calls are siblings inside that item's `Column`, one per row in a `for` loop. This
 * function marks its own group unconditionally (even the `linkedAbove == null` case with a single
 * child, where grouping is a no-op) so those sibling groups behave uniformly; between *different*
 * `LinkNodeHost` calls in that `Column`, none of them sets a `traversalIndex`, so they tie at the
 * default `0f` and fall back to plain layout order (top to bottom) for their relative order. That
 * is what actually produces content₀, node₁, content₁, node₂, content₂, … across a multi-row
 * circuit — not nesting inside a shared group. Adding a group *around* several `LinkNodeHost`
 * siblings (e.g. wrapping the `for` loop's body) would put them in traversalIndex contention with
 * each other and could silently break this ordering; don't add one there without re-deriving the
 * order from scratch.
 */
@Composable
fun LinkNodeHost(
    linkedAbove: Boolean?,
    onToggleLink: () -> Unit,
    modifier: Modifier = Modifier,
    swipeOffsetPx: () -> Float = { 0f },
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.semantics { isTraversalGroup = true }) {
        content()
        if (linkedAbove != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (16 - 18).dp, y = (-18).dp)
                    .offset { IntOffset(swipeOffsetPx().roundToInt(), 0) }
                    .size(36.dp)
                    .clickable(onClick = onToggleLink, role = Role.Button)
                    .semantics {
                        // Read ahead of `content` (see traversalIndex above), between the two rows
                        // this node links, so the description no longer needs to say "above".
                        traversalIndex = -1f
                        contentDescription = linkNodeDescription(linkedAbove)
                    },
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (linkedAbove) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    // `outline`, not `outlineVariant`: the ring bounds a control the user taps, and
                    // outlineVariant (the decorative-divider token) can wash out against `surface`
                    // under some dynamic-colour palettes.
                    border = if (linkedAbove) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.size(20.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            if (linkedAbove) Icons.Filled.Link else Icons.Filled.LinkOff,
                            contentDescription = null,
                            tint = if (linkedAbove) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One row's live horizontal swipe translation, shared between the row — which owns the swipe
 * gesture, and whose two screens swipe for different reasons — and the [LinkNodeHost] node above
 * it. The node is a *sibling* of the row's content, not an ancestor, so it never sees the row's own
 * translation; the row reports it here instead.
 *
 * Deliberately not snapshot state: [read] is only ever called from the node's layout-phase
 * `offset { }`, and a row [report]s its lambda during composition, where writing observable state
 * would be a recomposition hazard for no gain. Both lambdas are allocated once per instance, so
 * handing [report] to a row composable keeps that row skippable — which a mutable holder passed as
 * a parameter would not.
 */
private class SwipeOffsetRelay {
    private var offsetPx: () -> Float = ZERO

    /** The node's side: the row's current offset, or 0 before any row has reported one. */
    val read: () -> Float = { offsetPx() }

    /** The row's side: publish a lambda that reads the row's live offset when asked. */
    val report: (() -> Float) -> Unit = { offsetPx = it }

    private companion object {
        val ZERO: () -> Float = { 0f }
    }
}

/**
 * The reorderable list of circuit blocks shared by Today's workout and the saved-workout editor:
 * one [LazyColumn] item per block (so a drag carries a whole circuit), with the drag elevation,
 * the per-row [LinkNodeHost] wiring and the trailing divider all handled here. Only the row body
 * differs between the two screens, and that is [row].
 *
 * [row] receives its [T], where it sits in its block, the block's rounds (the count the block head
 * displays), the drag-handle modifier, a [SwipeOffsetRelay.report] it calls to keep its link node
 * tracking it while it is swiped, and the block's TalkBack "Move up"/"Move down" custom actions
 * (below) — every row of a block gets the same list, since a move relocates the whole block.
 * [onLink]/[onUnlink] are indexed by the row *above* the boundary, matching
 * [io.github.fowles.stochastic_strength.domain.CircuitEdits]; [onMove] moves a whole block and is
 * also what the move custom actions call — they are the accessible alternative to the drag handle,
 * so a caller wires no separate move logic of its own. A screen's [row] slot is expected to add its
 * own removal action(s) to this list and attach the result as that row's own `customActions`,
 * wherever it puts the semantics for the node TalkBack focuses for the row.
 *
 * Both call sites keep their headers and footers outside this list, in the [Column] around it, so
 * this takes no header/footer slot — add one here rather than growing a second list if that
 * changes.
 */
@Composable
fun <T : CircuitRow<T>> CircuitBlockList(
    rows: List<T>,
    rowId: (T) -> Long,
    onMove: (from: Int, to: Int) -> Unit,
    onLink: (rowIndex: Int) -> Unit,
    onUnlink: (rowIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    row: @Composable (
        row: T,
        place: RowPlace,
        rounds: Int,
        dragHandleModifier: Modifier,
        reportSwipeOffset: (() -> Float) -> Unit,
        moveActions: List<CustomAccessibilityAction>,
    ) -> Unit,
) {
    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onMove(from.index, to.index)
    }
    val blocks = remember(rows) { keyedBlocks(rows, rowId) }
    LazyColumn(state = lazyListState, modifier = modifier) {
        // One item per block, so a drag carries a whole circuit. The smallest member id is a key
        // that survives the drag.
        itemsIndexed(blocks, key = { _, it -> it.key }) { blockIndex, keyed ->
            val block = keyed.block
            // Omitted at the list edge, same as the drag handle simply having nowhere further to
            // go there. Keyed on blockIndex and blocks.size (the only two things that change which
            // actions are right) so the list instance survives unrelated recompositions — a fresh
            // list every pass would stop every row in the block from skipping. onMove itself is
            // deliberately not a key: both screens pass a bound view-model method reference, same
            // as onLink/onUnlink below, so pinning it here can't capture a stale callback.
            val moveActions = remember(blockIndex, blocks.size) {
                buildList {
                    if (blockIndex > 0) {
                        add(CustomAccessibilityAction("Move up") { onMove(blockIndex, blockIndex - 1); true })
                    }
                    if (blockIndex < blocks.size - 1) {
                        add(CustomAccessibilityAction("Move down") { onMove(blockIndex, blockIndex + 1); true })
                    }
                }
            }
            ReorderableItem(reorderState, key = keyed.key) { isDragging ->
                val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp, label = "dragElevation")
                // draggableHandle() builds an unkeyed Modifier.composed { … }, which has no
                // equals — a fresh one per composition is a never-equal parameter that stops the
                // row composable ever skipping. The item scope it captures is itself
                // remember(state, key)-stable, and composed { … } re-materializes its own state at
                // each application site, so one remembered instance serves every row in the block.
                val dragHandle = remember { Modifier.draggableHandle() }
                Column(modifier = Modifier.animateItem().graphicsLayer { shadowElevation = elevation.toPx() }) {
                    for (i in block.indices) {
                        val blockRow = keyed.rows[i - block.start]
                        key(rowId(blockRow)) {
                            // Memoized: `linkAbove` is a plain function, so its toggle lambda would
                            // otherwise be a fresh, never-equal instance every composition and stop
                            // LinkNodeHost ever skipping. `block` is an all-Int data class and `i`
                            // an Int, so they compare properly; both screens pass bound view-model
                            // references for onLink/onUnlink, so pinning them here can't capture a
                            // stale callback.
                            val (linkedAbove, toggleLink) = remember(block, i) {
                                linkAbove(block, i, onLink = onLink, onUnlink = onUnlink)
                            }
                            val swipeOffset = remember { SwipeOffsetRelay() }
                            LinkNodeHost(
                                linkedAbove = linkedAbove,
                                onToggleLink = toggleLink,
                                swipeOffsetPx = swipeOffset.read,
                            ) {
                                row(blockRow, rowPlace(block, i), block.rounds, dragHandle, swipeOffset.report, moveActions)
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Inline − value + [unit] control for a per-row pin (reps, weight); [text] carries formatting the
 * caller chose. A caller that knows its value is at a bound clears [canDecrement]/[canIncrement],
 * so the arrow greys out instead of being a silent no-op.
 */
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
    canDecrement: Boolean = true,
    canIncrement: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        IconButton(onClick = onDecrement, enabled = canDecrement, modifier = Modifier.size(32.dp)) {
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
        IconButton(onClick = onIncrement, enabled = canIncrement, modifier = Modifier.size(32.dp)) {
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

/**
 * The [ExerciseRowScaffold] body shared by every screen's exercise row: the exercise [name],
 * then either [timedText] (a timed exercise's duration, read-only) or the [reps] stepper, then
 * any row-specific [extra] content (e.g. a location/recency flag) below that.
 */
@Composable
fun ExerciseRowBody(
    name: String,
    timedText: String?,
    reps: @Composable () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Text(
        name,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (timedText != null) {
        Text(
            timedText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        reps()
    }
    extra()
}

/**
 * The [ExerciseRowScaffold] trailing slot shared by every screen's exercise row: a weight
 * [stepper] (with an optional [note] below it, e.g. [SuggestionNote]) when [showWeight], else
 * "Bodyweight" text for an unloadable [isBodyweight] exercise, else nothing.
 */
@Composable
fun WeightOrBodyweightTrailing(
    showWeight: Boolean,
    isBodyweight: Boolean,
    stepper: @Composable ColumnScope.() -> Unit,
    note: @Composable ColumnScope.() -> Unit = {},
) {
    when {
        showWeight -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            stepper()
            note()
        }
        isBodyweight -> Text(
            "Bodyweight",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Unit
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
            LinkNodeHost(linkedAbove = null, onToggleLink = {}) {
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
            LinkNodeHost(linkedAbove = linkAfterSquat, onToggleLink = { linkAfterSquat = !linkAfterSquat }) {
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
            LinkNodeHost(linkedAbove = linkAfterCurl, onToggleLink = { linkAfterCurl = !linkAfterCurl }) {
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
            LinkNodeHost(linkedAbove = linkAfterKickback, onToggleLink = { linkAfterKickback = !linkAfterKickback }) {
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
            LinkNodeHost(linkedAbove = linkAfterPress, onToggleLink = { linkAfterPress = !linkAfterPress }) {
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
