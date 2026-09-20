package io.github.fowles.stochastic_strength.ui.history

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.domain.history.HistoryRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * Drives [HistoryScreenContent] — the real screen, minus only its view model — over a hand-built
 * state, so the delete flow is covered without the app's Room database.
 *
 * What this covers: the end-to-end delete interaction. Tapping a row's delete icon raises the
 * confirm dialog, confirming it removes that session, and the list re-renders with the survivors
 * and their month headers intact.
 *
 * What this does NOT cover: the stale-key crash this screen once had, where the `LazyColumn` key
 * lambda indexed a fresher `sessions` list than the memoized `rows` it was built from. That bug
 * needed a live `collectAsState` delegate *inside* the composable; [HistoryScreenContent] now takes
 * a plain `HistoryState` value and collection lives in the `HistoryScreen` wrapper, which is not
 * drivable without the database — exactly what this test exists to avoid. So the hazard is
 * structurally absent rather than caught here. This test would still pass if the read-once local
 * in [HistoryScreenContent] were removed.
 */
@RunWith(AndroidJUnit4::class)
class HistoryDeleteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone: ZoneId = ZoneId.systemDefault()

    /** Deletes land from a coroutine on the main dispatcher, the way `confirmDelete` does. */
    private val scope = CoroutineScope(Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private fun startMs(date: LocalDate): Long =
        date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun item(id: Long, date: LocalDate, exercise: String): SessionListItem {
        val start = startMs(date)
        return SessionListItem(
            session = WorkoutSession(id = id, startTime = start, endTime = start + 1_800_000L),
            locationName = null,
            exerciseNames = listOf(exercise),
            durationSeconds = 1_800L,
        )
    }

    /** Mirrors `HistoryViewModel.reloadInternal`: the calendar is rebuilt from the sessions. */
    private fun stateOf(sessions: List<SessionListItem>) = HistoryState(
        highlight = "Keep going.",
        workoutDays = HistoryRows.workoutDays(sessions.map { it.session.startTime }, zone),
        sessions = sessions,
        loading = false,
    )

    @Test
    fun deletingTheFirstSessionRemovesItAndKeepsTheRest() {
        // Two months, so the list carries month headers and entry indices are not row indices.
        val all = listOf(
            item(1L, LocalDate.of(2026, 3, 10), "Squat"),
            item(2L, LocalDate.of(2026, 2, 20), "Bench Press"),
            item(3L, LocalDate.of(2026, 2, 5), "Deadlift"),
        )
        val flow = MutableStateFlow(stateOf(all))

        composeRule.setContent {
            val state by flow.collectAsState()
            HistoryScreenContent(
                state = state,
                onSessionTap = {},
                onBack = {},
                onInspireMe = {},
                onClearMessage = { flow.value = flow.value.copy(message = null) },
                onExport = {},
                onImport = { _, _ -> },
                onRequestDelete = { id ->
                    flow.value = flow.value.copy(pendingDeleteSessionId = id)
                },
                onCancelDelete = {
                    flow.value = flow.value.copy(pendingDeleteSessionId = null)
                },
                onConfirmDelete = {
                    val id = flow.value.pendingDeleteSessionId ?: return@HistoryScreenContent
                    flow.value = flow.value.copy(pendingDeleteSessionId = null)
                    scope.launch {
                        flow.value = stateOf(flow.value.sessions.filterNot { it.session.id == id })
                    }
                },
            )
        }

        composeRule.onNodeWithText("Squat").assertIsDisplayed()
        assertEquals(3, composeRule.onAllNodesWithContentDescription("Delete session").fetchSemanticsNodes().size)

        // Delete the newest session: every surviving entry's itemIndex shifts, so the rendered
        // rows have to be rebuilt against the shortened list.
        composeRule.onAllNodesWithContentDescription("Delete session")[0].performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Bench Press").assertIsDisplayed()
        composeRule.onNodeWithText("Deadlift").assertIsDisplayed()
        assertEquals(2, composeRule.onAllNodesWithContentDescription("Delete session").fetchSemanticsNodes().size)
        assertEquals(listOf(2L, 3L), flow.value.sessions.map { it.session.id })
    }
}
