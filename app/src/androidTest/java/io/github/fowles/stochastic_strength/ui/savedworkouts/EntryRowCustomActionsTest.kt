package io.github.fowles.stochastic_strength.ui.savedworkouts

import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutEntry
import io.github.fowles.stochastic_strength.ui.components.RowPlace
import io.github.fowles.stochastic_strength.ui.components.findCustomActionsNode
import io.github.fowles.stochastic_strength.ui.components.isTalkBackFocusable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [EntryRow]'s TalkBack-accessible alternative to swipe-to-remove: a "Remove" custom action that
 * calls the same callback the swipe gesture does, alongside whatever move actions
 * [io.github.fowles.stochastic_strength.ui.components.CircuitBlockList] hands it (covered
 * generically in `CircuitBlockListCustomActionsTest`). Unlike the plan-preview row, this row has
 * no `.clickable` merging its descendants, so the node carrying the custom actions needs its own
 * content description to be a TalkBack stop at all — [carriesTheMoveActionsItWasGiven] et al.
 * would pass even if that node were unreachable, so
 * [theCustomActionsNodeIsReachableByTalkBack] asserts reachability directly.
 */
@RunWith(AndroidJUnit4::class)
class EntryRowCustomActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val exercise = Exercise(
        id = 1L,
        name = "Barbell Squat",
        primaryMuscle = MuscleGroup.QUADS,
        equipment = Equipment.BARBELL,
    )

    private fun setContent(onRemove: () -> Unit, moveActions: List<CustomAccessibilityAction>) {
        composeRule.setContent {
            EntryRow(
                entry = SavedWorkoutEntry(exercise = exercise, reps = 8),
                place = RowPlace.SOLO,
                sets = 3,
                dragHandleModifier = androidx.compose.ui.Modifier,
                suggester = null,
                weightUnit = null,
                onRemove = onRemove,
                onRepsChange = {},
                onWeightChange = {},
                onSetsChange = {},
                reportSwipeOffset = {},
                moveActions = moveActions,
            )
        }
    }

    private fun rowCustomActions(): List<CustomAccessibilityAction> =
        composeRule.onRoot().fetchSemanticsNode().findCustomActionsNode()
            ?.config?.get(SemanticsActions.CustomActions) ?: emptyList()

    @Test
    fun theCustomActionsNodeIsReachableByTalkBack() {
        setContent(onRemove = {}, moveActions = emptyList())

        val node = composeRule.onRoot().fetchSemanticsNode().findCustomActionsNode()
        assertTrue("no node in the tree carries CustomActions", node != null)
        assertTrue(
            "the customActions node has neither a content description, text, nor a click action " +
                "— it's in the semantics tree but TalkBack has nothing to stop on",
            node!!.isTalkBackFocusable(),
        )
    }

    @Test
    fun removeActionCallsTheSameCallbackAsTheSwipe() {
        var removed = false
        setContent(onRemove = { removed = true }, moveActions = emptyList())

        val actions = rowCustomActions()
        val remove = actions.single { it.label == "Remove" }
        remove.action.invoke()

        assertTrue(removed)
    }

    @Test
    fun carriesTheMoveActionsItWasGiven() {
        setContent(
            onRemove = {},
            moveActions = listOf(CustomAccessibilityAction("Move up") { true }),
        )

        val labels = rowCustomActions().map { it.label }
        assertEquals(listOf("Move up", "Remove"), labels)
    }
}
