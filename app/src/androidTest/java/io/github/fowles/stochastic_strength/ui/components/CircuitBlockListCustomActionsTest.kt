package io.github.fowles.stochastic_strength.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fowles.stochastic_strength.data.model.CircuitRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [CircuitBlockList]'s own contribution to TalkBack accessibility: the "Move up"/"Move down"
 * custom actions it hands every row, computed from the row's *block* position (not its row
 * index) and omitted at the list's edges. A fake row/row-composable stands in for the two real
 * screens, whose own removal custom actions are covered where those screens' row composables
 * live ([io.github.fowles.stochastic_strength.ui.savedworkouts.EntryRowCustomActionsTest],
 * [io.github.fowles.stochastic_strength.ui.workout.ExercisePreviewRowCustomActionsTest]).
 */
@RunWith(AndroidJUnit4::class)
class CircuitBlockListCustomActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private data class FakeRow(
        val id: Long,
        override val sets: Int = 1,
        override val circuitId: Int? = null,
    ) : CircuitRow<FakeRow> {
        override fun withStructure(sets: Int, circuitId: Int?) = copy(sets = sets, circuitId = circuitId)
    }

    private fun setContent(rows: List<FakeRow>, onMove: (Int, Int) -> Unit) {
        composeRule.setContent {
            CircuitBlockList(
                rows = rows,
                rowId = { it.id },
                onMove = onMove,
                onLink = {},
                onUnlink = {},
            ) { row, _, _, _, _, moveActions ->
                Box(modifier = Modifier.testTag("row-${row.id}").semantics { this.customActions = moveActions })
            }
        }
    }

    private fun labelsOf(tag: String): List<String> =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].map { it.label }

    @Test
    fun firstBlockOmitsMoveUpAndMovingDownCallsOnMoveWithBlockIndices() {
        var moveCall: Pair<Int, Int>? = null
        setContent(
            rows = listOf(FakeRow(1), FakeRow(2), FakeRow(3)),
            onMove = { from, to -> moveCall = from to to },
        )

        assertEquals(listOf("Move down"), labelsOf("row-1"))

        val action = composeRule.onNodeWithTag("row-1").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Move down" }
        action.action.invoke()
        assertEquals(0 to 1, moveCall)
    }

    @Test
    fun lastBlockOmitsMoveDownAndMovingUpCallsOnMoveWithBlockIndices() {
        var moveCall: Pair<Int, Int>? = null
        setContent(
            rows = listOf(FakeRow(1), FakeRow(2), FakeRow(3)),
            onMove = { from, to -> moveCall = from to to },
        )

        assertEquals(listOf("Move up"), labelsOf("row-3"))

        val action = composeRule.onNodeWithTag("row-3").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == "Move up" }
        action.action.invoke()
        assertEquals(2 to 1, moveCall)
    }

    @Test
    fun middleBlockOffersBothDirections() {
        setContent(rows = listOf(FakeRow(1), FakeRow(2), FakeRow(3)), onMove = { _, _ -> })

        assertEquals(listOf("Move up", "Move down"), labelsOf("row-2"))
    }

    @Test
    fun aSoloBlockListHasNeitherAction() {
        setContent(rows = listOf(FakeRow(1)), onMove = { _, _ -> })

        assertEquals(emptyList<String>(), labelsOf("row-1"))
    }
}
