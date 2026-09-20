package io.github.fowles.stochastic_strength.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull

/**
 * Depth-first search (including [this]) for the first node whose config carries
 * [SemanticsActions.CustomActions] — shared by the row-level customActions tests
 * (`CircuitBlockListCustomActionsTest`, `EntryRowCustomActionsTest`,
 * `ExercisePreviewRowCustomActionsTest`) so each doesn't reimplement the walk.
 */
fun SemanticsNode.findCustomActionsNode(): SemanticsNode? {
    config.getOrNull(SemanticsActions.CustomActions)?.let { return this }
    children.forEach { child -> child.findCustomActionsNode()?.let { return it } }
    return null
}

/**
 * Whether TalkBack would actually stop on this node while exploring by touch/swipe. TalkBack's
 * importance filter looks for a content description, visible text, or a primary action (click,
 * in practice, for these rows) — a node exposing only [SemanticsActions.CustomActions] and
 * nothing else is present in the semantics tree but not reachable, which is exactly the gap this
 * whole feature exists to close, so a customActions assertion alone doesn't prove TalkBack can
 * get there.
 */
fun SemanticsNode.isTalkBackFocusable(): Boolean =
    !config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() ||
        !config.getOrNull(SemanticsProperties.Text).isNullOrEmpty() ||
        config.getOrNull(SemanticsActions.OnClick) != null
