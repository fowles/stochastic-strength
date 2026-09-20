package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.ui.components.RowPlace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ExercisePreviewRow]'s TalkBack-accessible alternative to swipe-to-reject: one custom action
 * per removal reason, labelled the same as [ExerciseActionRow]'s buttons, alongside whatever move
 * actions [io.github.fowles.stochastic_strength.ui.components.CircuitBlockList] hands it (covered
 * generically in `CircuitBlockListCustomActionsTest`).
 */
@RunWith(AndroidJUnit4::class)
class ExercisePreviewRowCustomActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val exercise = Exercise(
        id = 1L,
        name = "Barbell Squat",
        primaryMuscle = MuscleGroup.QUADS,
        equipment = Equipment.BARBELL,
    )

    private fun setContent(onReplace: (ExerciseRemovalReason) -> Unit, moveActions: List<CustomAccessibilityAction>) {
        composeRule.setContent {
            ExercisePreviewRow(
                planned = PlannedExercise(exercise = exercise, sessionWeight = 40f, sessionReps = 8),
                weightUnit = WeightUnit.KG,
                place = RowPlace.SOLO,
                sets = 3,
                dragHandleModifier = androidx.compose.ui.Modifier,
                suggestedWeight = 0f,
                onReplace = onReplace,
                onAdjustWeight = {},
                onRepsChange = {},
                onResetReps = {},
                onResetWeight = {},
                onTap = {},
                onSetsChange = {},
                flag = null,
                reportSwipeOffset = {},
                moveActions = moveActions,
            )
        }
    }

    private fun rowCustomActions(): List<CustomAccessibilityAction> =
        composeRule.onRoot().fetchSemanticsNode().let { root ->
            fun find(node: SemanticsNode): List<CustomAccessibilityAction>? {
                node.config.getOrNull(SemanticsActions.CustomActions)?.let { return it }
                node.children.forEach { child -> find(child)?.let { return it } }
                return null
            }
            find(root)
        } ?: emptyList()

    @Test
    fun offersOneActionPerRemovalReasonLabelledLikeTheActionRowButtons() {
        val reasons = mutableListOf<ExerciseRemovalReason>()
        setContent(onReplace = { reasons.add(it) }, moveActions = emptyList())

        val actions = rowCustomActions()
        assertEquals(listOf("No gear", "Hate it", "Not today"), actions.map { it.label })

        actions.single { it.label == "No gear" }.action.invoke()
        actions.single { it.label == "Hate it" }.action.invoke()
        actions.single { it.label == "Not today" }.action.invoke()
        assertEquals(
            listOf(ExerciseRemovalReason.NO_EQUIPMENT, ExerciseRemovalReason.DISLIKE, ExerciseRemovalReason.SKIP_TODAY),
            reasons,
        )
    }

    @Test
    fun carriesTheMoveActionsItWasGivenAheadOfTheRemovalReasons() {
        setContent(
            onReplace = {},
            moveActions = listOf(CustomAccessibilityAction("Move down") { true }),
        )

        val labels = rowCustomActions().map { it.label }
        assertEquals(listOf("Move down", "No gear", "Hate it", "Not today"), labels)
    }
}
