package io.github.fowles.stochastic_strength.domain.derived

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.domain.belief.Belief
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DerivedStateStoreTest {

    @Test fun emptyStoreReturnsEmptyResults() {
        val store = DerivedStateStore()
        val snap = store.snapshot()
        assertTrue(snap.allMuscleGroupStrengths().isEmpty())
        assertTrue(snap.allBaselineHistory().isEmpty())
        assertNull(snap.muscleGroupStrength(MuscleGroup.CHEST))
        assertTrue(snap.baselineHistoryForMuscle(MuscleGroup.CHEST).isEmpty())
        assertTrue(snap.coefficientHistoryForExercise(7L).isEmpty())
        assertTrue(snap.coefficientHistoryLatestPerExercise().isEmpty())
        assertTrue(snap.coefficientHistoryMostRecent(5).isEmpty())
    }

    @Test fun rebuildPopulatesAllThreeStores() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
            mut.insertBaselineHistory(baselineRow(MuscleGroup.CHEST, ts = 10L))
            mut.insertCoefficientHistory(coefRow(exerciseId = 1L, value = 1.2f, ts = 10L))
        }
        val snap = store.snapshot()
        assertEquals(100f, snap.muscleGroupStrength(MuscleGroup.CHEST)?.baselineWeight)
        assertEquals(1, snap.allBaselineHistory().size)
        assertEquals(1, snap.coefficientHistoryForExercise(1L).size)
    }

    @Test fun rebuildAssignsAutoIncrementIdsStartingAtOne() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            val a = mut.insertBaselineHistory(baselineRow(MuscleGroup.CHEST, ts = 10L))
            val b = mut.insertBaselineHistory(baselineRow(MuscleGroup.CHEST, ts = 20L))
            assertEquals(1L, a)
            assertEquals(2L, b)
        }
        val ids = store.snapshot().allBaselineHistory().map { it.id }
        assertEquals(listOf(1L, 2L), ids)
    }

    @Test fun rebuildIsAtomicOnException() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        }
        val before = store.snapshot()

        try {
            store.rebuild { mut ->
                mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 200f))
                throw IllegalStateException("boom")
            }
            fail("expected exception")
        } catch (_: IllegalStateException) {
            // expected
        }
        val after = store.snapshot()
        assertEquals(100f, after.muscleGroupStrength(MuscleGroup.CHEST)?.baselineWeight)
        assertEquals(before.allMuscleGroupStrengths(), after.allMuscleGroupStrengths())
    }

    @Test fun baselineHistoryForMuscleReturnsTimestampAscending() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.insertBaselineHistory(baselineRow(MuscleGroup.CHEST, ts = 30L))
            mut.insertBaselineHistory(baselineRow(MuscleGroup.CHEST, ts = 10L))
            mut.insertBaselineHistory(baselineRow(MuscleGroup.QUADS, ts = 20L))
        }
        val chest = store.snapshot().baselineHistoryForMuscle(MuscleGroup.CHEST)
        assertEquals(listOf(10L, 30L), chest.map { it.timestamp })
    }

    @Test fun coefficientLatestPerExerciseReturnsHighestComputedAt() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.insertCoefficientHistory(coefRow(exerciseId = 1L, value = 1.0f, ts = 10L))
            mut.insertCoefficientHistory(coefRow(exerciseId = 1L, value = 1.5f, ts = 20L))
            mut.insertCoefficientHistory(coefRow(exerciseId = 2L, value = 0.8f, ts = 15L))
        }
        val latest = store.snapshot().coefficientHistoryLatestPerExercise().associateBy { it.exerciseId }
        assertEquals(1.5f, latest[1L]?.coefficient)
        assertEquals(0.8f, latest[2L]?.coefficient)
        assertEquals(2, latest.size)
    }

    @Test fun coefficientMostRecentSortsDescendingByComputedAt() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.insertCoefficientHistory(coefRow(exerciseId = 1L, value = 1.0f, ts = 10L))
            mut.insertCoefficientHistory(coefRow(exerciseId = 2L, value = 1.0f, ts = 30L))
            mut.insertCoefficientHistory(coefRow(exerciseId = 3L, value = 1.0f, ts = 20L))
        }
        val mostRecent = store.snapshot().coefficientHistoryMostRecent(limit = 2)
        assertEquals(listOf(30L, 20L), mostRecent.map { it.computedAt })
    }

    @Test fun allCoefficientHistoryReturnsInsertionOrder() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.insertCoefficientHistory(coefRow(exerciseId = 1L, value = 1.0f, ts = 30L))
            mut.insertCoefficientHistory(coefRow(exerciseId = 2L, value = 2.0f, ts = 10L))
            mut.insertCoefficientHistory(coefRow(exerciseId = 3L, value = 3.0f, ts = 20L))
        }
        val all = store.snapshot().allCoefficientHistory()
        assertEquals(listOf(1L, 2L, 3L), all.map { it.exerciseId })
    }

    @Test fun snapshotIsImmutableAfterReturn() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        }
        val first = store.snapshot()
        store.rebuild { mut ->
            mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 200f))
        }
        // The previously captured snapshot must still reflect the pre-rebuild value.
        assertEquals(100f, first.muscleGroupStrength(MuscleGroup.CHEST)?.baselineWeight)
        assertEquals(200f, store.snapshot().muscleGroupStrength(MuscleGroup.CHEST)?.baselineWeight)
    }

    @Test fun mutableReadsReflectInProgressWrites() = runTest {
        val store = DerivedStateStore()
        var midRebuildLatest: Float? = null
        store.rebuild { mut ->
            mut.insertCoefficientHistory(coefRow(exerciseId = 1L, value = 1.2f, ts = 10L))
            midRebuildLatest = mut.coefficientHistoryLatestPerExercise()
                .firstOrNull { it.exerciseId == 1L }?.coefficient
        }
        assertEquals(1.2f, midRebuildLatest)
    }

    @Test
    fun beliefsSurviveRebuildAndDefaultEmpty() = runBlocking {
        val store = DerivedStateStore()
        assertTrue(store.snapshot().exerciseBeliefs().isEmpty())
        store.rebuild { it.putExerciseBeliefs(mapOf(3L to Belief(4.6f, 0.01f, 99L))) }
        assertEquals(4.6f, store.snapshot().exerciseBeliefs().getValue(3L).mu, 1e-6f)
    }

    private fun baselineRow(muscle: MuscleGroup, ts: Long) = BaselineHistory(
        sessionId = null,
        muscleGroup = muscle,
        previousBaseline = 0f,
        newBaseline = 100f,
        changeReason = BaselineChangeReason.INITIAL,
        timestamp = ts,
    )

    private fun coefRow(exerciseId: Long, value: Float, ts: Long) = CoefficientHistory(
        exerciseId = exerciseId,
        coefficient = value,
        heuristicName = "test",
        computedAt = ts,
    )
}
