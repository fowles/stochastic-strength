package io.github.fowles.stochastic_strength.ui.workout

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.domain.ReplacementTier
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.DetrainingModel
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import io.github.fowles.stochastic_strength.domain.progression.ExerciseBelief
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutSessionControllerTest {

    private lateinit var db: AppDatabase
    private lateinit var bus: WorkoutSessionBus
    private lateinit var scope: CoroutineScope
    private lateinit var controller: WorkoutSessionController
    private lateinit var repository: WorkoutRepository

    @Before
    fun setUp() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            db.userProfileDao().insert(
                UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
            )
            db.exerciseDao().insertAll(listOf(
                Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
                Exercise(name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
            ))
            bus = WorkoutSessionBus()
            scope = CoroutineScope(Dispatchers.Default)
            repository = WorkoutRepository(db)
            seedDerivedStrength(db, repository)
            startSession(1)
        }
    }

    private fun startSession(count: Int) = runBlocking {
        controller = WorkoutSessionController(db, repository, bus, scope)
        controller.initializeSession(
            locationId = null, locationName = null,
            preferredExerciseCount = count, preferredRepMin = 5, preferredRepMax = 10,
            weightUnit = WeightUnit.KG,
        )
        controller.adjustExerciseCount(count)
        awaitStateNotLoading()
        controller.startFirstExercise()
        awaitState<WorkoutState.ActiveSet>()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * Seed the derived state the way the live planner reads it under the per-exercise contract:
     * a confident per-exercise estimate (≈100 kg 1RM) per active exercise drives the prescribed
     * weight, plus the muscle_group_strength display projection the detraining prompt reads.
     */
    private suspend fun seedDerivedStrength(database: AppDatabase, repo: WorkoutRepository) {
        val active = database.exerciseDao().getActive()
        val now = System.currentTimeMillis()
        val config = EstimatorConfig()
        repo.derivedState.rebuild { mut ->
            mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
            mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.QUADS, 100f))
            mut.putExerciseBeliefs(
                active.associate { it.id to ExerciseBelief.seed(100f, at = now, config = config) }
            )
        }
    }

    private suspend inline fun <reified T : WorkoutState> awaitState(
        controller: WorkoutSessionController = this.controller,
        timeoutMs: Long = 2000
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = controller.state.value
            if (s is T) return s
            delay(20)
        }
        error("State did not become ${T::class.simpleName} within $timeoutMs ms; was ${controller.state.value}")
    }

    private suspend fun awaitStateNotLoading() {
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && controller.state.value is WorkoutState.Loading) {
            delay(20)
        }
    }

    @Test
    fun finalSetTransitionsThroughResting() = runBlocking<Unit> {
        repeat(3) { setIndex ->
            controller.recordFeedback(SetFeedback.RIR_2_4)
            awaitState<WorkoutState.Resting>()
            delay(100)
            assertEquals(setIndex, db.workoutSetDao().getAll().last().setNumber - 1)
            controller.skipRest()
        }
        awaitState<WorkoutState.Done>()
    }

    @Test
    fun rir24_setsActualRepsEqualToTargetReps() = runBlocking {
        controller.recordFeedback(SetFeedback.RIR_2_4)
        awaitState<WorkoutState.Resting>()
        delay(100)
        val sets = db.workoutSetDao().getAll()
        assertEquals(1, sets.size)
        assertEquals(sets[0].targetReps, sets[0].actualReps)
    }

    @Test
    fun hurt_leavesActualRepsNull() = runBlocking {
        controller.recordFeedback(SetFeedback.HURT)
        awaitState<WorkoutState.Resting>()
        delay(100)
        val sets = db.workoutSetDao().getAll()
        assertEquals(1, sets.size)
        assertNull(sets[0].actualReps)
    }

    @Test
    fun tooHard_initialActualRepsNull_thenSetByReduceExerciseWeight() = runBlocking {
        controller.recordFeedback(SetFeedback.TOO_HARD)
        val resting = awaitState<WorkoutState.Resting>()
        delay(100)
        val before = db.workoutSetDao().getAll().single()
        assertNull(before.actualReps)
        assertTrue(resting.currentSetRowId > 0)

        controller.reduceExerciseWeight(2)
        delay(100)
        val after = db.workoutSetDao().getAll().single()
        assertEquals(2, after.actualReps)
    }

    @Test
    fun tooHardOnNonFinalSet_appliesWeightReduction() = runBlocking {
        val before = (controller.state.value as WorkoutState.ActiveSet).plannedExercise.sessionWeight
        controller.recordFeedback(SetFeedback.TOO_HARD)
        awaitState<WorkoutState.Resting>()
        controller.reduceExerciseWeight(2)
        delay(50)
        val resting = controller.state.value as WorkoutState.Resting
        val after = resting.plan.exercises[resting.exerciseIndex].sessionWeight
        assertTrue("expected weight to drop from $before, got $after", after < before)
    }

    @Test
    fun tooHardOnFinalSetOfExercise_doesNotChangeWeight() = runBlocking {
        controller.recordFeedback(SetFeedback.RIR_2_4); awaitState<WorkoutState.Resting>(); controller.skipRest(); awaitState<WorkoutState.ActiveSet>()
        controller.recordFeedback(SetFeedback.RIR_2_4); awaitState<WorkoutState.Resting>(); controller.skipRest(); awaitState<WorkoutState.ActiveSet>()
        val before = (controller.state.value as WorkoutState.ActiveSet).plannedExercise.sessionWeight

        controller.recordFeedback(SetFeedback.TOO_HARD)
        val resting = awaitState<WorkoutState.Resting>()
        controller.reduceExerciseWeight(2)
        delay(50)
        val updated = controller.state.value as WorkoutState.Resting
        val after = updated.plan.exercises[updated.exerciseIndex].sessionWeight
        assertEquals("weight should be unchanged on final set of exercise", before, after)
        delay(100)
        val sets = db.workoutSetDao().getAll().sortedBy { it.setNumber }
        assertEquals(2, sets.last().actualReps)
    }

    @Test
    fun undoFromResting_deletesRowIncludingActualReps() = runBlocking {
        controller.recordFeedback(SetFeedback.RIR_2_4)
        awaitState<WorkoutState.Resting>()
        delay(100)
        assertEquals(1, db.workoutSetDao().getAll().size)
        controller.undoLastSet()
        awaitState<WorkoutState.ActiveSet>()
        delay(100)
        assertEquals(0, db.workoutSetDao().getAll().size)
    }

    @Test
    fun stopWorkout_landsOnRest_commitFinishes() = runBlocking<Unit> {
        controller.stopWorkout()
        val resting = awaitState<WorkoutState.Resting>()
        assertNotNull(resting.staged)
        assertEquals(StagedKind.STOP_WORKOUT, resting.staged!!.kind)
        assertEquals(WorkoutSessionController.NO_ROW, resting.currentSetRowId)
        controller.skipRest()
        awaitState<WorkoutState.Done>()
    }

    @Test
    fun stopWorkout_undoRestoresActiveSet() = runBlocking<Unit> {
        val before = controller.state.value as WorkoutState.ActiveSet
        controller.stopWorkout()
        awaitState<WorkoutState.Resting>()
        controller.undoLastSet()
        val after = awaitState<WorkoutState.ActiveSet>()
        assertEquals(before.exerciseIndex, after.exerciseIndex)
        assertEquals(before.warmupSetIndex, after.warmupSetIndex)
    }

    private suspend fun toLastWarmup() {
        var s = controller.state.value as? WorkoutState.ActiveSet ?: return
        while (s.warmupSetIndex != null && s.warmupSetIndex!! + 1 < s.plannedExercise.warmupSets.size) {
            controller.completeWarmupSet()
            delay(20)
            s = controller.state.value as? WorkoutState.ActiveSet ?: return
        }
    }

    private suspend fun toWorkingSet() {
        toLastWarmup()
        val s = controller.state.value
        if (s is WorkoutState.ActiveSet && s.warmupSetIndex != null) {
            controller.completeWarmupSet()
            val resting = awaitState<WorkoutState.Resting>()
            if (resting.staged?.kind == StagedKind.WARMUP_DONE) {
                controller.skipRest()
                awaitState<WorkoutState.ActiveSet>()
            }
        }
    }

    @Test
    fun endExercise_noLoggedSets_singleExercise_finishesOnCommit() = runBlocking {
        // Fresh on warmup/set 0 => no logged sets.
        controller.endCurrentExercise()
        val resting = awaitState<WorkoutState.Resting>()
        assertEquals(StagedKind.END_EXERCISE, resting.staged!!.kind)
        controller.skipRest()
        awaitState<WorkoutState.Done>()
        delay(100)
        assertEquals(0, db.workoutSetDao().getAll().size) // nothing logged
    }

    @Test
    fun endExercise_undoRestoresOriginatingSet() = runBlocking<Unit> {
        val before = controller.state.value as WorkoutState.ActiveSet
        controller.endCurrentExercise()
        awaitState<WorkoutState.Resting>()
        controller.undoLastSet()
        val after = awaitState<WorkoutState.ActiveSet>()
        assertEquals(before.exerciseIndex, after.exerciseIndex)
        assertEquals(before.warmupSetIndex, after.warmupSetIndex)
    }

    @Test
    fun endExercise_hasLoggedSets_keepsLoggedAndAdvances() = runBlocking {
        startSession(2) // two exercises in the plan
        toWorkingSet()
        controller.recordFeedback(SetFeedback.RIR_2_4) // logs set 1 of exercise 0
        awaitState<WorkoutState.Resting>()
        controller.skipRest()
        awaitState<WorkoutState.ActiveSet>() // now on exercise 0, set 2 (hasLogged)

        controller.endCurrentExercise()
        val resting = awaitState<WorkoutState.Resting>()
        // commitTarget advances to the second exercise (index 1).
        assertEquals(1, resting.staged!!.commitTarget!!.exerciseIndex)
        controller.skipRest()
        val active = awaitState<WorkoutState.ActiveSet>()
        assertEquals(1, active.exerciseIndex)
        delay(100)
        assertEquals(1, db.workoutSetDao().getAll().size) // the logged set is retained
    }

    @Test
    fun setActiveSetWeight_stagesResumeSameSetAtNewWeight() = runBlocking {
        toWorkingSet()
        val active = controller.state.value as WorkoutState.ActiveSet
        val i = active.exerciseIndex
        val original = active.plannedExercise.sessionWeight
        val target = original + 5f

        controller.setActiveSetWeight(target)
        val resting = awaitState<WorkoutState.Resting>()
        assertEquals(StagedKind.ADJUST_WEIGHT, resting.staged!!.kind)
        val commit = resting.staged!!.commitTarget!!
        // Same set coordinates.
        assertEquals(active.exerciseIndex, commit.exerciseIndex)
        assertEquals(active.setIndex, commit.setIndex)
        assertEquals(active.warmupSetIndex, commit.warmupSetIndex)
        // New weight applied to the plan.
        assertEquals(
            WeightFormatter.round(target, WeightUnit.KG),
            commit.plan.exercises[i].sessionWeight,
        )
        // Baseline override untouched.
        assertTrue(commit.plan.exerciseOverrides.isEmpty())
    }

    @Test
    fun setActiveSetWeight_undoRestoresOriginalWeight() = runBlocking<Unit> {
        toWorkingSet()
        val active = controller.state.value as WorkoutState.ActiveSet
        val original = active.plannedExercise.sessionWeight
        controller.setActiveSetWeight(original + 5f)
        awaitState<WorkoutState.Resting>()
        controller.undoLastSet()
        val after = awaitState<WorkoutState.ActiveSet>()
        assertEquals(original, after.plannedExercise.sessionWeight)
    }

    @Test
    fun endExercise_noLoggedSets_multiExercise_removesAndAdvances() = runBlocking<Unit> {
        startSession(2)
        val firstId = (controller.state.value as WorkoutState.ActiveSet)
            .plannedExercise.exercise.id
        controller.endCurrentExercise() // on warmup/set 0 of exercise 0 => no logged sets
        val resting = awaitState<WorkoutState.Resting>()
        val target = resting.staged!!.commitTarget!!
        // Exercise 0 removed; the second exercise now occupies index 0.
        assertEquals(0, target.exerciseIndex)
        assertTrue(target.plan.exercises.none { it.exercise.id == firstId })
        controller.skipRest()
        awaitState<WorkoutState.ActiveSet>()
    }

    @Test
    fun swap_noLoggedSets_replacesInPlace() = runBlocking {
        val active = controller.state.value as WorkoutState.ActiveSet
        val originalId = active.plannedExercise.exercise.id
        controller.swapCurrentExercise(ExerciseRemovalReason.DISLIKE)
        val resting = awaitState<WorkoutState.Resting>()
        val target = resting.staged!!.commitTarget!!
        assertEquals(StagedKind.SWAP, resting.staged!!.kind)
        assertEquals(0, target.exerciseIndex)
        // Replaced in place: original gone, exactly one exercise, different id.
        assertEquals(1, target.plan.exercises.size)
        assertTrue(target.plan.exercises.none { it.exercise.id == originalId })
    }

    @Test
    fun swap_commitPersistsDislike_undoDoesNot() = runBlocking {
        val originalId = (controller.state.value as WorkoutState.ActiveSet).plannedExercise.exercise.id

        // Undo path: no persistence.
        controller.swapCurrentExercise(ExerciseRemovalReason.DISLIKE)
        awaitState<WorkoutState.Resting>()
        controller.undoLastSet()
        awaitState<WorkoutState.ActiveSet>()
        delay(100)
        assertEquals(false, db.exerciseDao().getById(originalId)!!.isDisliked)

        // Commit path: persists.
        controller.swapCurrentExercise(ExerciseRemovalReason.DISLIKE)
        awaitState<WorkoutState.Resting>()
        controller.skipRest()
        awaitState<WorkoutState.ActiveSet>()
        delay(100)
        assertEquals(true, db.exerciseDao().getById(originalId)!!.isDisliked)
    }

    @Test
    fun initialize_afterLayoff_surfacesDetrainingPromptWithSuggestedDefault() = runBlocking {
        // Fresh controller (setUp already ran startSession, which inserts a recent session).
        // We need a fresh DB with only a 3-weeks-old session.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val freshDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        freshDb.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        freshDb.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
        ))
        val freshRepo = WorkoutRepository(freshDb)
        seedDerivedStrength(freshDb, freshRepo)
        val threeWeeksAgo = System.currentTimeMillis() - 3L * DetrainingModel.WEEK_MILLIS - 60_000
        freshDb.workoutSessionDao().insert(
            WorkoutSession(startTime = threeWeeksAgo, endTime = threeWeeksAgo + 1000)
        )
        val freshController = WorkoutSessionController(freshDb, freshRepo, WorkoutSessionBus(), scope)
        freshController.initializeSession(
            locationId = null, locationName = null, preferredExerciseCount = 5,
            preferredRepMin = 5, preferredRepMax = 10, weightUnit = WeightUnit.KG,
        )
        val preview = freshController.state.value as WorkoutState.PlanPreview
        val prompt = preview.detraining!!
        assertEquals(3, prompt.weeksOff)
        assertEquals(0.15f, prompt.suggestedFraction, 1e-4f)
        assertTrue(prompt.currentStrengths.isNotEmpty())
        freshDb.close()
    }

    @Test
    fun initialize_recentSession_noPrompt() = runBlocking {
        // setUp already ran startSession which inserts a recent session,
        // so any new controller with the same db will see a recent session.
        val freshController = WorkoutSessionController(db, repository, WorkoutSessionBus(), scope)
        freshController.initializeSession(
            locationId = null, locationName = null, preferredExerciseCount = 5,
            preferredRepMin = 5, preferredRepMax = 10, weightUnit = WeightUnit.KG,
        )
        val preview = freshController.state.value as WorkoutState.PlanPreview
        assertNull(preview.detraining)
    }

    @Test
    fun applyDetraining_reducesWeightsAndStoresOverrides() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val freshDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        freshDb.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        freshDb.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
        ))
        val freshRepo = WorkoutRepository(freshDb)
        seedDerivedStrength(freshDb, freshRepo)
        val threeWeeksAgo = System.currentTimeMillis() - 3L * DetrainingModel.WEEK_MILLIS - 60_000
        freshDb.workoutSessionDao().insert(
            WorkoutSession(startTime = threeWeeksAgo, endTime = threeWeeksAgo + 1000)
        )
        val freshController = WorkoutSessionController(freshDb, freshRepo, WorkoutSessionBus(), scope)
        freshController.initializeSession(
            locationId = null, locationName = null, preferredExerciseCount = 5,
            preferredRepMin = 5, preferredRepMax = 10, weightUnit = WeightUnit.KG,
        )
        val before = (freshController.state.value as WorkoutState.PlanPreview)
            .plan.exercises.first { it.sessionWeight > 0f }

        freshController.applyDetraining(0.20f)
        delay(200) // wait for coroutine in applyDetraining

        val after = (freshController.state.value as WorkoutState.PlanPreview)
        assertNull(after.detraining)
        assertTrue(after.plan.detrainOverrides.isNotEmpty())
        val sameExercise = after.plan.exercises.first { it.exercise.id == before.exercise.id }
        assertTrue("expected reduced weight", sameExercise.sessionWeight < before.sessionWeight)
        freshDb.close()
    }

    @Test
    fun skipDetraining_leavesWeightsAndOverridesUntouched() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val freshDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        freshDb.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        freshDb.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
        ))
        val freshRepo = WorkoutRepository(freshDb)
        seedDerivedStrength(freshDb, freshRepo)
        val threeWeeksAgo = System.currentTimeMillis() - 3L * DetrainingModel.WEEK_MILLIS - 60_000
        freshDb.workoutSessionDao().insert(
            WorkoutSession(startTime = threeWeeksAgo, endTime = threeWeeksAgo + 1000)
        )
        val freshController = WorkoutSessionController(freshDb, freshRepo, WorkoutSessionBus(), scope)
        freshController.initializeSession(
            locationId = null, locationName = null, preferredExerciseCount = 5,
            preferredRepMin = 5, preferredRepMax = 10, weightUnit = WeightUnit.KG,
        )
        val before = (freshController.state.value as WorkoutState.PlanPreview).plan.exercises

        freshController.skipDetraining()

        val after = (freshController.state.value as WorkoutState.PlanPreview)
        assertNull(after.detraining)
        assertTrue(after.plan.detrainOverrides.isEmpty())
        assertEquals(before.map { it.sessionWeight }, after.plan.exercises.map { it.sessionWeight })
        freshDb.close()
    }

    @Test
    fun moveExercise_swapsExerciseOrder() = runBlocking {
        // Use a fresh DB so setUp's active session doesn't interfere.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val freshDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        freshDb.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        freshDb.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
        ))
        val freshRepo = WorkoutRepository(freshDb)
        seedDerivedStrength(freshDb, freshRepo)
        val freshController = WorkoutSessionController(freshDb, freshRepo, WorkoutSessionBus(), scope)
        freshController.initializeSession(
            locationId = null, locationName = null,
            preferredExerciseCount = 2, preferredRepMin = 5, preferredRepMax = 10,
            weightUnit = WeightUnit.KG,
        )

        val before = awaitState<WorkoutState.PlanPreview>(freshController).plan.exercises
        assertEquals(2, before.size)
        val firstId = before[0].exercise.id
        val secondId = before[1].exercise.id

        freshController.moveExercise(0, 1)

        val after = (freshController.state.value as WorkoutState.PlanPreview).plan.exercises
        assertEquals(secondId, after[0].exercise.id)
        assertEquals(firstId, after[1].exercise.id)

        freshDb.close()
    }

    @Test
    fun swap_hasLoggedSets_keepsOriginalAndInsertsAfter() = runBlocking {
        toWorkingSet()
        controller.recordFeedback(SetFeedback.RIR_2_4) // log a set for exercise 0
        awaitState<WorkoutState.Resting>()
        controller.skipRest()
        val active = awaitState<WorkoutState.ActiveSet>() // exercise 0, set 2 (hasLogged)
        val originalId = active.plannedExercise.exercise.id

        controller.swapCurrentExercise(ExerciseRemovalReason.DISLIKE)
        val resting = awaitState<WorkoutState.Resting>()
        val target = resting.staged!!.commitTarget!!
        // Original kept at 0, replacement inserted at 1; commit jumps to index 1.
        assertEquals(originalId, target.plan.exercises[0].exercise.id)
        assertEquals(1, target.exerciseIndex)
        assertEquals(2, target.plan.exercises.size)
    }

    @Test
    fun completeWarmupSet_lastWarmup_transitionsToWarmupDoneResting() = runBlocking {
        toLastWarmup()
        val lastWarmupState = controller.state.value as WorkoutState.ActiveSet
        assertNotNull(lastWarmupState.warmupSetIndex)

        controller.completeWarmupSet()
        val resting = awaitState<WorkoutState.Resting>()
        assertEquals(StagedKind.WARMUP_DONE, resting.staged!!.kind)
        assertEquals(lastWarmupState.exerciseIndex, resting.staged!!.commitTarget!!.exerciseIndex)
        assertEquals(0, resting.staged!!.commitTarget!!.setIndex)
        assertNull(resting.staged!!.commitTarget!!.warmupSetIndex)
        assertEquals(WorkoutSessionController.NO_ROW, resting.currentSetRowId)
    }

    @Test
    fun completeWarmupSet_warmupDoneRest_skipAdvancesToFirstWorkingSet() = runBlocking {
        toLastWarmup()
        controller.completeWarmupSet()
        awaitState<WorkoutState.Resting>()

        controller.skipRest()
        val active = awaitState<WorkoutState.ActiveSet>()
        assertEquals(0, active.setIndex)
        assertNull(active.warmupSetIndex)
    }

    @Test
    fun completeWarmupSet_warmupDoneRest_undoReturnsToLastWarmup() = runBlocking {
        toLastWarmup()
        val lastWarmupState = controller.state.value as WorkoutState.ActiveSet
        controller.completeWarmupSet()
        awaitState<WorkoutState.Resting>()

        controller.undoLastSet()
        val after = awaitState<WorkoutState.ActiveSet>()
        assertEquals(lastWarmupState.warmupSetIndex, after.warmupSetIndex)
        assertEquals(lastWarmupState.exerciseIndex, after.exerciseIndex)
    }
}
