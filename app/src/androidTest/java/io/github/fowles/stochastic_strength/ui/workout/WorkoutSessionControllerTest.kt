package io.github.fowles.stochastic_strength.ui.workout

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.KnownLocation
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
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutEntry
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.ui.loadWorkoutSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Every extra in-memory database and gated executor a test body builds, closed centrally in
     * [tearDown]. Closing them inline at the end of each test body was the old habit, and it leaked
     * on any test that threw first: the database stayed open, its invalidation-tracker work stayed
     * queued on the shared executor pool, and it surfaced later as "attempt to re-open an
     * already-closed object" attributed to whatever test happened to run next.
     */
    private val fixtureDbs = mutableListOf<AppDatabase>()
    private val fixtureExecutors = mutableListOf<GatedDbExecutor>()

    private fun track(database: AppDatabase): AppDatabase = database.also { fixtureDbs += it }

    private fun gatedExecutor(): GatedDbExecutor = GatedDbExecutor().also { fixtureExecutors += it }

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
        // Every controller in the test (including each previewFixture's) runs in this scope, so a
        // coroutine still in flight would keep querying a closed database and fail whichever test
        // runs next. Cancel first, then close.
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        // Databases first, then the executors they may still post invalidation work to.
        fixtureDbs.forEach { runCatching { it.close() } }
        fixtureExecutors.forEach { runCatching { it.shutdown() } }
        db.close()
    }

    /**
     * Seed the derived state the way the live planner reads it under the belief-stack contract:
     * a confident per-exercise belief (≈100 kg 1RM, tight uncertainty) per active exercise drives the
     * prescribed weight, plus the muscle_group_strength display projection the detraining prompt
     * reads.
     */
    private suspend fun seedDerivedStrength(database: AppDatabase, repo: WorkoutRepository) {
        val active = database.exerciseDao().getActive()
        val now = System.currentTimeMillis()
        repo.derivedState.rebuild { mut ->
            mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
            mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.QUADS, 100f))
            mut.putExerciseBeliefs(
                active.associate { it.id to Belief(bestGuessLn = kotlin.math.ln(100f), uncertainty = 4e-4f, updatedAt = now) }
            )
        }
    }

    /** Fresh DB with three loaded exercises and a controller parked on PlanPreview at [count]. */
    private data class PreviewFixture(
        val db: AppDatabase, val repo: WorkoutRepository, val controller: WorkoutSessionController,
    )

    /**
     * A single-threaded Room query/transaction executor that can be armed to block one specific
     * call — by 1-based ordinal since the last [resetCount] — until [unblock] is called.
     *
     * This is the deterministic seam the interleaving tests use to land a concurrent edit inside
     * a controller method's suspend window: arm the executor at the ordinal of the method's last
     * DB call (measured, not guessed — see the onLocationRefreshed test), trigger the method,
     * wait for [entered] (proof the method is now parked on that exact call), perform the
     * concurrent edit synchronously, then [unblock]. Because the executor is single-threaded and
     * FIFO, no amount of real-thread scheduling jitter can reorder this — the method's remaining
     * work provably cannot run until after the edit has landed.
     */
    private class GatedDbExecutor : Executor {
        private val delegate = Executors.newSingleThreadExecutor()
        private val count = AtomicInteger(0)
        @Volatile private var blockAt: Int? = null
        private val release = Semaphore(0)
        val entered = Semaphore(0)

        fun resetCount() {
            blockAt = null
            count.set(0)
        }

        fun callCount(): Int = count.get()

        fun armBlockAt(ordinal: Int) {
            blockAt = ordinal
        }

        fun unblock() = release.release()

        /** Releases the delegate's thread. Call once the test is done with this executor. */
        fun shutdown() = delegate.shutdown()

        override fun execute(command: Runnable) {
            val ordinal = count.incrementAndGet()
            delegate.execute {
                if (ordinal == blockAt) {
                    entered.release()
                    release.acquire()
                }
                command.run()
            }
        }
    }

    /**
     * Waits for [GatedDbExecutor.entered] with a bounded timeout, so a gate that's never reached
     * (an early return, a changed call count, a regression that removes the suspend entirely)
     * fails the test instead of hanging the instrumentation run.
     */
    private fun GatedDbExecutor.awaitEntered(ordinal: Int) {
        assertTrue(
            "Gate at ordinal $ordinal was never entered — the method may have returned early " +
                "or its DB-call count changed",
            entered.tryAcquire(2, TimeUnit.SECONDS),
        )
    }

    /**
     * Measures how many DB calls [action] makes in this exact fixture/state by running it once
     * un-gated, then arms [gated] to block on that same (now known) ordinal, rather than on a
     * hardcoded count that could drift out of sync with the method's internals and silently stop
     * testing anything.
     *
     * The ordinal is **measured, not guaranteed**. Room routes `InvalidationTracker` refresh
     * runnables through the query executor too, so the count includes asynchronous invalidation
     * work whose timing isn't guaranteed to be identical between the dry run and the gated run.
     * Undershooting (the gated run makes fewer calls than measured) fails loudly — the gate is
     * never entered and [awaitEntered] times out after 2s. Overshooting (the gated run makes
     * *more* calls) is the quiet failure mode: the gate then lands on a call that isn't the
     * method's last, so the concurrent edit is staged earlier in the method than intended and the
     * test can pass without actually covering the suspend window it names. If one of these tests
     * starts passing suspiciously easily after the method under test grows new DB work, re-check
     * the measured ordinal before trusting it.
     */
    private suspend fun armGateAtLastCall(gated: GatedDbExecutor, action: suspend () -> Unit): Int {
        gated.resetCount()
        action()
        val total = gated.callCount()
        gated.resetCount()
        gated.armBlockAt(total)
        return total
    }

    private suspend fun previewFixture(
        count: Int,
        // Loaded alongside the three staples, for plans that need a fourth row or a swap candidate.
        extraExercises: List<Exercise> = emptyList(),
        // Routes every Room suspend DAO call through this executor instead of Room's default pool —
        // lets a test gate a specific call deterministically (see GatedDbExecutor).
        queryExecutor: Executor? = null,
        // Runs once the exercises exist; returns the location to start the session at.
        locationSetup: (suspend (AppDatabase, WorkoutRepository) -> Long)? = null,
    ): PreviewFixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val builder = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
        if (queryExecutor != null) builder.setQueryExecutor(queryExecutor).setTransactionExecutor(queryExecutor)
        val freshDb = track(builder.build())
        freshDb.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        freshDb.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
            Exercise(name = "Barbell Row", primaryMuscle = MuscleGroup.BACK, equipment = Equipment.BARBELL),
        ) + extraExercises)
        val freshRepo = WorkoutRepository(freshDb)
        val active = freshDb.exerciseDao().getActive()
        val now = System.currentTimeMillis()
        val muscles = listOf(MuscleGroup.CHEST, MuscleGroup.QUADS, MuscleGroup.BACK) +
            extraExercises.map { it.primaryMuscle }
        freshRepo.derivedState.rebuild { mut ->
            for (m in muscles.distinct()) {
                mut.upsertMuscleGroupStrength(MuscleGroupStrength(m, 100f))
            }
            mut.putExerciseBeliefs(
                active.associate { it.id to Belief(bestGuessLn = kotlin.math.ln(100f), uncertainty = 4e-4f, updatedAt = now) }
            )
        }
        val locationId = locationSetup?.invoke(freshDb, freshRepo)
        val c = WorkoutSessionController(freshDb, freshRepo, WorkoutSessionBus(), scope)
        c.initializeSession(
            locationId = locationId, locationName = null,
            preferredExerciseCount = count, preferredRepMin = 5, preferredRepMax = 10,
            weightUnit = WeightUnit.KG,
        )
        awaitPreviewSize(c, count)
        return PreviewFixture(freshDb, freshRepo, c)
    }

    private suspend fun awaitPreviewSize(c: WorkoutSessionController, size: Int, timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = c.state.value
            if (s is WorkoutState.PlanPreview && s.plan.exercises.size == size) return
            delay(20)
        }
        error("Preview did not reach $size exercises; was ${c.state.value}")
    }

    private suspend fun awaitPreview(
        c: WorkoutSessionController,
        timeoutMs: Long = 2000,
        predicate: (WorkoutState.PlanPreview) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = c.state.value
            if (s is WorkoutState.PlanPreview && predicate(s)) return
            delay(20)
        }
        error("Preview never satisfied the condition; was ${c.state.value}")
    }

    private fun preview(c: WorkoutSessionController) = c.state.value as WorkoutState.PlanPreview

    private suspend fun awaitActive(
        c: WorkoutSessionController, timeoutMs: Long = 2000,
        predicate: (WorkoutState.ActiveSet) -> Boolean = { true },
    ): WorkoutState.ActiveSet {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = c.state.value
            if (s is WorkoutState.ActiveSet && predicate(s)) return s
            delay(20)
        }
        error("No matching ActiveSet; was ${c.state.value}")
    }

    private suspend fun awaitLoggedRest(c: WorkoutSessionController, timeoutMs: Long = 2000): WorkoutState.Resting {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = c.state.value
            if (s is WorkoutState.Resting && s.staged == null) return s
            delay(20)
        }
        error("No logged-set rest; was ${c.state.value}")
    }

    /** Walks any warmups, logs one working set, and leaves the controller resting. Returns the exercise id. */
    private suspend fun performSet(c: WorkoutSessionController, feedback: SetFeedback = SetFeedback.RIR_2_4): Long {
        var s = awaitActive(c)
        while (s.warmupSetIndex != null) {
            c.completeWarmupSet()
            s = when (val now = c.state.value) {
                is WorkoutState.Resting -> { c.skipRest(); awaitActive(c) { it.warmupSetIndex == null } }
                else -> now as WorkoutState.ActiveSet
            }
        }
        val id = s.plannedExercise.exercise.id
        c.recordFeedback(feedback)
        awaitLoggedRest(c)
        return id
    }

    /** Preview of 3 rows → rows 0+1 linked as a [rounds]-round circuit, row 2 solo with 1 set → started. */
    private suspend fun circuitSession(
        rounds: Int,
        extraExercises: List<Exercise> = emptyList(),
    ): Pair<PreviewFixture, List<Long>> {
        val f = previewFixture(count = 3, extraExercises = extraExercises)
        f.controller.linkExercises(0)
        val rows = preview(f.controller).plan.exercises
        f.controller.setExerciseSets(rows[0].exercise.id, rounds)
        f.controller.setExerciseSets(rows[2].exercise.id, 1)
        f.controller.startFirstExercise()
        awaitActive(f.controller)
        return f to rows.map { it.exercise.id }
    }

    @Test
    fun circuit_runsRoundRobin_thenTheNextBlock_andTagsLoggedRows() = runBlocking {
        val (f, ids) = circuitSession(rounds = 2)
        val order = mutableListOf<Long>()
        repeat(5) { order += performSet(f.controller); f.controller.skipRest() }
        assertEquals(listOf(ids[0], ids[1], ids[0], ids[1], ids[2]), order)

        val rows = f.db.workoutSetDao().getSetsForSession(
            f.db.workoutSessionDao().getAll().single().id
        )
        assertEquals(listOf(1, 1, 2, 2, 1), rows.map { it.setNumber })
        assertEquals(listOf(0, 0, 0, 0, null), rows.map { it.circuitId })
    }

    @Test
    fun soloRow_honoursItsSetCount() = runBlocking {
        val f = previewFixture(count = 1)
        f.controller.setExerciseSets(preview(f.controller).plan.exercises[0].exercise.id, 5)
        f.controller.startFirstExercise()
        repeat(4) { performSet(f.controller); f.controller.skipRest() }
        val last = awaitActive(f.controller)
        assertEquals(4, last.setIndex)
        assertEquals(5, last.totalSets)
        assertEquals("Set 5 of 5", last.positionLabel)
    }

    @Test
    fun hurtMidCircuit_dropsThatMemberFromLaterRounds() = runBlocking {
        val (f, ids) = circuitSession(rounds = 2)
        performSet(f.controller, SetFeedback.HURT); f.controller.skipRest() // ids[0] out
        val order = mutableListOf<Long>()
        repeat(3) { order += performSet(f.controller); f.controller.skipRest() }
        assertEquals(listOf(ids[1], ids[1], ids[2]), order)
    }

    @Test
    fun undoAcrossCircuitMembers_returnsToTheSetJustLogged() = runBlocking {
        val (f, ids) = circuitSession(rounds = 2)
        performSet(f.controller); f.controller.skipRest()      // ids[0] round 1
        performSet(f.controller)                               // ids[1] round 1, now resting
        f.controller.undoLastSet()
        val back = awaitActive(f.controller) { it.plannedExercise.exercise.id == ids[1] }
        assertEquals(0, back.setIndex)
        assertEquals("Round 1 of 2", back.positionLabel)
        assertEquals(1, back.done[ids[0]])
        assertEquals(0, back.done[ids[1]] ?: 0)
    }

    @Test
    fun endExercise_inACircuitWithLoggedSets_finishesOnlyThatMember() = runBlocking {
        val (f, ids) = circuitSession(rounds = 2)
        performSet(f.controller); f.controller.skipRest()      // ids[0] r1
        performSet(f.controller); f.controller.skipRest()      // ids[1] r1
        awaitActive(f.controller) { it.plannedExercise.exercise.id == ids[0] && it.setIndex == 1 }
        f.controller.endCurrentExercise()
        f.controller.skipRest()                                 // commit the staged end
        val next = awaitActive(f.controller) { it.plannedExercise.exercise.id != ids[0] }
        assertEquals(ids[1], next.plannedExercise.exercise.id)
        assertEquals(1, next.setIndex)
    }

    /** A fourth loaded exercise, so a 3-row plan still has a swap candidate. */
    private fun spareExercise() = listOf(
        Exercise(name = "Overhead Press", primaryMuscle = MuscleGroup.SHOULDERS, equipment = Equipment.BARBELL)
    )

    @Test
    fun swap_inACircuit_roundOne_replacesInPlace_inheritingTheSlot() = runBlocking {
        val (f, ids) = circuitSession(rounds = 2, extraExercises = spareExercise())
        val before = awaitActive(f.controller) { it.plannedExercise.exercise.id == ids[0] }
        f.controller.swapCurrentExercise(ExerciseRemovalReason.SKIP_TODAY)
        val target = awaitState<WorkoutState.Resting>(f.controller).staged!!.commitTarget!!

        assertEquals(0, target.exerciseIndex)
        assertEquals(0, target.setIndex)
        assertEquals(3, target.plan.exercises.size)
        val row = target.plan.exercises[0]
        assertTrue("the original is gone", row.exercise.id != ids[0])
        assertEquals(before.plannedExercise.circuitId, row.circuitId)
        assertEquals(before.plannedExercise.sets, row.sets)
    }

    @Test
    fun swap_inACircuit_laterRound_insertsReplacementWithRemainingRounds() = runBlocking {
        val (f, ids) = circuitSession(rounds = 3, extraExercises = spareExercise())
        performSet(f.controller); f.controller.skipRest()      // ids[0] r1
        performSet(f.controller); f.controller.skipRest()      // ids[1] r1
        awaitActive(f.controller) { it.plannedExercise.exercise.id == ids[0] && it.setIndex == 1 }

        f.controller.swapCurrentExercise(ExerciseRemovalReason.SKIP_TODAY)
        val target = awaitState<WorkoutState.Resting>(f.controller).staged!!.commitTarget!!
        val inserted = target.plan.exercises[1]
        assertEquals(0, inserted.circuitId)
        assertEquals("1 round done of 3 → 2 owed", 2, inserted.sets)
        assertEquals(3, target.done[ids[0]])

        f.controller.skipRest()
        val order = mutableListOf<Long>()
        repeat(4) { order += performSet(f.controller); f.controller.skipRest() }
        assertEquals(
            listOf(inserted.exercise.id, ids[1], inserted.exercise.id, ids[1]),
            order,
        )
    }

    @Test
    fun endExercise_inACircuitWithNoLoggedSets_removesOnlyThatMember() = runBlocking {
        val (f, ids) = circuitSession(rounds = 2)
        awaitActive(f.controller) { it.plannedExercise.exercise.id == ids[0] }
        f.controller.endCurrentExercise()
        f.controller.skipRest()                                 // commit the staged end

        val next = awaitActive(f.controller) { it.plannedExercise.exercise.id == ids[1] }
        assertEquals(0, next.setIndex)
        assertTrue(next.plan.exercises.none { it.exercise.id == ids[0] })
        assertEquals("a lone member is a solo block", "Set 1 of 2", next.positionLabel)

        performSet(f.controller); f.controller.skipRest()
        val second = awaitActive(f.controller) {
            it.plannedExercise.exercise.id == ids[1] && it.setIndex == 1
        }
        assertEquals("Set 2 of 2", second.positionLabel)
    }

    @Test
    fun endExercise_midSession_keepsLaterCircuitIdsDistinctFromLoggedOnes() = runBlocking {
        val f = previewFixture(count = 4, extraExercises = spareExercise())
        f.controller.linkExercises(0) // rows 0+1
        f.controller.linkExercises(2) // rows 2+3
        val rows = preview(f.controller).plan.exercises
        assertEquals(listOf(0, 0, 1, 1), rows.map { it.circuitId })
        f.controller.setExerciseSets(rows[0].exercise.id, 2)
        f.controller.setExerciseSets(rows[2].exercise.id, 2)
        val ids = rows.map { it.exercise.id }
        f.controller.startFirstExercise()

        performSet(f.controller); f.controller.skipRest()        // A round 1, tagged with A's circuit
        awaitActive(f.controller) { it.plannedExercise.exercise.id == ids[1] }
        f.controller.endCurrentExercise()                        // B, nothing logged
        f.controller.skipRest()
        // A round 2, then C and D for two rounds.
        repeat(5) { performSet(f.controller); f.controller.skipRest() }
        awaitState<WorkoutState.Done>(f.controller)

        val sessionId = f.db.workoutSessionDao().getAll().single().id
        val logged = f.db.workoutSetDao().getSetsForSession(sessionId)
        val aCircuit = logged.first { it.exerciseId == ids[0] }.circuitId
        val cd = logged.filter { it.exerciseId == ids[2] || it.exerciseId == ids[3] }
        assertEquals("C and D ran two rounds each", 4, cd.size)
        val cdCircuit = cd.map { it.circuitId }.distinct().single()
        assertNotNull("C and D are still a circuit", cdCircuit)
        assertTrue(
            "removing B renumbered the second circuit onto A's logged id ($aCircuit)",
            cdCircuit != aCircuit,
        )

        val circuits = loadWorkoutSummary(f.db, sessionId).blocks.filter { it.isCircuit }
        assertEquals(1, circuits.size)
        assertEquals(listOf(ids[2], ids[3]), circuits.single().exercises.map { it.exerciseId })
    }

    @Test
    fun swap_withLoggedSets_givesTheReplacementOnlyTheRemainingSets() = runBlocking {
        toWorkingSet()
        controller.recordFeedback(SetFeedback.RIR_2_4)
        awaitState<WorkoutState.Resting>()
        controller.skipRest()
        val active = awaitState<WorkoutState.ActiveSet>() // set 2 of 3
        controller.swapCurrentExercise(ExerciseRemovalReason.SKIP_TODAY)
        val target = awaitState<WorkoutState.Resting>().staged!!.commitTarget!!
        assertEquals(1, target.exerciseIndex)
        assertEquals(0, target.setIndex)
        assertEquals("1 done of 3 → 2 owed", 2, target.plannedExercise.sets)
        assertEquals(3, target.done[active.plannedExercise.exercise.id])
    }

    @Test
    fun locationRefresh_keepsExplicitlyAddedExcludedRow() = runBlocking {
        var excludedId = 0L
        var locationId = 0L
        val f = previewFixture(count = 2) { freshDb, freshRepo ->
            locationId = freshDb.knownLocationDao().insert(
                KnownLocation(name = "Home", latitude = 0.0, longitude = 0.0)
            )
            excludedId = freshDb.exerciseDao().getActive().first { it.name == "Barbell Row" }.id
            freshRepo.excludeExercise(locationId, excludedId)
            locationId
        }
        assertTrue(preview(f.controller).plan.exercises.none { it.exercise.id == excludedId })

        f.controller.addExercise(excludedId)
        awaitPreview(f.controller) { p -> p.plan.exercises.any { it.exercise.id == excludedId } }
        assertEquals(RowFlag.NOT_AT_LOCATION, preview(f.controller).rowFlags[excludedId])

        // The rename gives the refresh an observable completion signal.
        f.db.knownLocationDao().updateName(locationId, "Home 2")
        f.controller.onLocationRefreshed()
        awaitPreview(f.controller) { it.locationName == "Home 2" }

        val p = preview(f.controller)
        assertTrue(
            "Explicitly added, location-excluded row was dropped by the refresh",
            p.plan.exercises.any { it.exercise.id == excludedId },
        )
        assertEquals(RowFlag.NOT_AT_LOCATION, p.rowFlags[excludedId])
    }

    @Test
    fun locationRefresh_recomputesRowFlags() = runBlocking {
        var locationId = 0L
        val f = previewFixture(count = 2) { freshDb, _ ->
            locationId = freshDb.knownLocationDao().insert(
                KnownLocation(name = "Home", latitude = 0.0, longitude = 0.0)
            )
            locationId
        }
        val addedId = f.db.exerciseDao().getActive().first { ex ->
            preview(f.controller).plan.exercises.none { it.exercise.id == ex.id }
        }.id
        f.controller.addExercise(addedId)
        awaitPreview(f.controller) { p -> p.plan.exercises.any { it.exercise.id == addedId } }
        assertNull("nothing excludes it yet", preview(f.controller).rowFlags[addedId])

        f.repo.excludeExercise(locationId, addedId)
        // The rename gives the refresh an observable completion signal.
        f.db.knownLocationDao().updateName(locationId, "Gym")
        f.controller.onLocationRefreshed()
        awaitPreview(f.controller) { it.locationName == "Gym" }

        val p = preview(f.controller)
        assertEquals(RowFlag.NOT_AT_LOCATION, p.rowFlags[addedId])
        assertTrue(
            "Explicitly added row must survive the refresh",
            p.plan.exercises.any { it.exercise.id == addedId },
        )
    }

    // --- Task 6: plan-preview edits must survive a concurrent suspend --------------------------
    //
    // Each of these gates the controller method's true LAST DB call — always measured by running
    // the method once un-gated first and reading GatedDbExecutor.callCount(), never hardcoded —
    // so a concurrent, synchronous edit can be proven to land strictly inside the method's
    // suspend window, then verifies that edit is still present once the method finishes, never
    // silently overwritten by a pre-suspend snapshot. Measuring (rather than hardcoding an
    // internal DAO call count) keeps the gate tracking the method's internals as they grow — but
    // it is a measurement, not a guarantee; see armGateAtLastCall's KDoc for the overshoot case.

    @Test
    fun addExercise_survivesAConcurrentStructureEdit_duringItsSuspend() = runBlocking {
        var locationId = 0L
        val gated = gatedExecutor()
        val f = previewFixture(count = 1, queryExecutor = gated) { freshDb, _ ->
            locationId = freshDb.knownLocationDao().insert(
                KnownLocation(name = "Home", latitude = 0.0, longitude = 0.0)
            )
            locationId
        }
        try {
            val existingId = preview(f.controller).plan.exercises[0].exercise.id
            val candidates = f.db.exerciseDao().getActive().filter { it.id != existingId }
            val dryRunId = candidates[0].id
            val newId = candidates[1].id

            val ordinal = armGateAtLastCall(gated) {
                f.controller.addExercise(dryRunId)
                awaitPreviewSize(f.controller, 2)
            }

            f.controller.addExercise(newId)
            gated.awaitEntered(ordinal)
            f.controller.setExerciseSets(existingId, 4) // concurrent structural edit, mid-suspend
            gated.unblock()

            awaitPreviewSize(f.controller, 3)
            val p = preview(f.controller)
            assertTrue("the appended row must still land", p.plan.exercises.any { it.exercise.id == newId })
            assertEquals(
                "a structure edit made during addExercise's suspend must survive",
                4, p.plan.exercises.first { it.exercise.id == existingId }.sets,
            )
        } finally {
            // Idempotent safety net: if an assertion above threw before the real unblock() ran,
            // the gate's dedicated thread would otherwise sit on release.acquire() forever —
            // cancelling the coroutine in tearDown() can't interrupt that raw blocking call, so
            // tearDown's cancelAndJoin() would hang the whole instrumentation run instead of
            // just failing this one test.
            gated.unblock()
        }
    }

    @Test
    fun loadSavedWorkout_survivesAConcurrentLocationNameEdit_duringItsSuspend() = runBlocking {
        var locationId = 0L
        val gated = gatedExecutor()
        // count = 1, not 2: the dry run's completion signal is "the plan reached 2 rows", which
        // must not already be true before the dry run's load actually runs.
        val f = previewFixture(count = 1, queryExecutor = gated) { freshDb, _ ->
            locationId = freshDb.knownLocationDao().insert(
                KnownLocation(name = "Home", latitude = 0.0, longitude = 0.0)
            )
            locationId
        }
        try {
            val all = f.db.exerciseDao().getActive()
            // Two distinct saved workouts (not the same one loaded twice) so the second, gated
            // load has its own observable completion signal — the plan growing from 2 rows to 3
            // — rather than relying on a load that would leave the exercise list unchanged.
            val savedDuo = f.repo.saveWorkout(null, "Duo", listOf(SavedWorkoutEntry(all[0], null), SavedWorkoutEntry(all[1], null)))
            val savedTrio = f.repo.saveWorkout(null, "Trio", all.map { SavedWorkoutEntry(it, null) })

            val ordinal = armGateAtLastCall(gated) {
                f.controller.loadSavedWorkout(savedDuo)
                awaitPreviewSize(f.controller, 2)
            }

            f.controller.loadSavedWorkout(savedTrio)
            gated.awaitEntered(ordinal)
            // A load replaces the exercise list wholesale by design — what must survive is state
            // OUTSIDE the list, so the concurrent edit here targets the location label, not a row.
            f.controller.updateLocationName("Concurrent Gym")
            gated.unblock()

            awaitPreviewSize(f.controller, all.size)
            val p = preview(f.controller)
            assertEquals(all.map { it.id }, p.plan.exercises.map { it.exercise.id })
            assertEquals(
                "a locationName update made during the load's suspend must survive",
                "Concurrent Gym", p.locationName,
            )
        } finally {
            gated.unblock() // safety net — see the comment in addExercise's version of this test
        }
    }

    @Test
    fun onLocationRefreshed_survivesAConcurrentStructureEdit_duringItsSuspend() = runBlocking {
        var locationId = 0L
        val gated = gatedExecutor()
        val f = previewFixture(count = 2, queryExecutor = gated) { freshDb, _ ->
            locationId = freshDb.knownLocationDao().insert(
                KnownLocation(name = "Home", latitude = 0.0, longitude = 0.0)
            )
            locationId
        }
        try {
            val existingId = preview(f.controller).plan.exercises[0].exercise.id

            val ordinal = armGateAtLastCall(gated) {
                f.db.knownLocationDao().updateName(locationId, "Home 2")
                f.controller.onLocationRefreshed()
                awaitPreview(f.controller) { it.locationName == "Home 2" }
            }

            f.db.knownLocationDao().updateName(locationId, "Home 3")
            f.controller.onLocationRefreshed()
            gated.awaitEntered(ordinal)
            f.controller.setExerciseSets(existingId, 4) // concurrent structural edit, mid-suspend
            gated.unblock()

            awaitPreview(f.controller) { it.locationName == "Home 3" }
            val p = preview(f.controller)
            assertEquals(
                "a structure edit made during onLocationRefreshed's suspend must survive",
                4, p.plan.exercises.first { it.exercise.id == existingId }.sets,
            )
        } finally {
            gated.unblock() // safety net — see the comment in addExercise's version of this test
        }
    }

    @Test
    fun replaceExercise_survivesAConcurrentStructureEdit_duringItsSuspend() = runBlocking {
        val gated = gatedExecutor()
        val f = previewFixture(count = 3, queryExecutor = gated)
        try {
            val rows = preview(f.controller).plan.exercises
            val dryRunId = rows[0].exercise.id
            val rejectedId = rows[1].exercise.id
            val survivorId = rows[2].exercise.id

            val ordinal = armGateAtLastCall(gated) {
                f.controller.replaceExercise(dryRunId, ExerciseRemovalReason.DISLIKE)
                awaitPreview(f.controller) { p -> p.plan.exercises.none { it.exercise.id == dryRunId } }
            }

            f.controller.replaceExercise(rejectedId, ExerciseRemovalReason.DISLIKE)
            gated.awaitEntered(ordinal)
            f.controller.setExerciseSets(survivorId, 4) // concurrent structural edit, mid-suspend
            gated.unblock()

            awaitPreview(f.controller) { p -> p.plan.exercises.none { it.exercise.id == rejectedId } }
            val p = preview(f.controller)
            assertEquals(
                "a structure edit made during replaceExercise's suspend must survive",
                4, p.plan.exercises.first { it.exercise.id == survivorId }.sets,
            )
        } finally {
            gated.unblock() // safety net — see the comment in addExercise's version of this test
        }
    }

    @Test
    fun maybeNoteDetraining_survivesAConcurrentStructureEdit_duringItsSuspend() = runBlocking {
        val gated = gatedExecutor()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val freshDb = track(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(gated)
            .setTransactionExecutor(gated)
            .build())
        try {
            freshDb.userProfileDao().insert(
                UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
            )
            freshDb.exerciseDao().insertAll(listOf(
                Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
                Exercise(name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
            ))
            val freshRepo = WorkoutRepository(freshDb)
            seedDerivedStrength(freshDb, freshRepo)
            // Old enough that DetrainingModel.qualifies is true, so maybeNoteDetraining actually
            // reaches its setState instead of returning early.
            val threeWeeksAgo = System.currentTimeMillis() - 3L * DetrainingModel.WEEK_MILLIS - 60_000
            freshDb.workoutSessionDao().insert(
                WorkoutSession(startTime = threeWeeksAgo, endTime = threeWeeksAgo + 1000)
            )

            // Dry run on a throwaway controller over the same DB, to measure initializeSession's
            // DB-call count in this exact fixture (see armGateAtLastCall).
            val ordinal = armGateAtLastCall(gated) {
                WorkoutSessionController(freshDb, freshRepo, WorkoutSessionBus(), scope)
                    .initializeSession(
                        locationId = null, locationName = null, preferredExerciseCount = 1,
                        preferredRepMin = 5, preferredRepMax = 10, weightUnit = WeightUnit.KG,
                    )
            }

            val c = WorkoutSessionController(freshDb, freshRepo, WorkoutSessionBus(), scope)
            val init = scope.launch {
                c.initializeSession(
                    locationId = null, locationName = null, preferredExerciseCount = 1,
                    preferredRepMin = 5, preferredRepMax = 10, weightUnit = WeightUnit.KG,
                )
            }
            gated.awaitEntered(ordinal)
            // The preview is already on screen at this point (initializeSession publishes it
            // before the detraining query), so a user edit here is exactly what the notice's
            // write-back used to clobber.
            val editedId = preview(c).plan.exercises[0].exercise.id
            c.setExerciseSets(editedId, 4)
            gated.unblock()
            init.join()

            awaitPreview(c) { it.detraining != null }
            val p = preview(c)
            assertEquals(3, p.detraining!!.weeksOff)
            assertEquals(
                "a structure edit made during maybeNoteDetraining's suspend must survive",
                4, p.plan.exercises.first { it.exercise.id == editedId }.sets,
            )
        } finally {
            gated.unblock() // safety net — see the comment in addExercise's version of this test
        }
    }

    @Test
    fun addExercise_dropsItsUpdate_whenThePreviewIsGoneByTheTimeItsSuspendReturns() = runBlocking {
        val gated = gatedExecutor()
        val f = previewFixture(count = 1, queryExecutor = gated)
        try {
            val newId = f.db.exerciseDao().getActive().first { ex ->
                preview(f.controller).plan.exercises.none { it.exercise.id == ex.id }
            }.id

            // No location is set here, so addExercise makes exactly one DB call
            // (getExerciseById); it's trivially the "last" call, so there's nothing to measure
            // first.
            gated.resetCount()
            gated.armBlockAt(1)
            f.controller.addExercise(newId)
            gated.awaitEntered(1)
            f.controller.startFirstExercise() // leaves PlanPreview entirely, mid-suspend
            // startFirstExercise's own DB insert is queued behind addExercise's still-blocked
            // call on this same single-threaded executor, so it cannot complete — and the state
            // cannot advance to ActiveSet — until we unblock here, before awaiting it.
            gated.unblock()
            val active = awaitActive(f.controller)

            // Give addExercise's coroutine a moment to resume past the gate and, correctly, drop
            // its now-stale update instead of forcing the state back to a PlanPreview.
            delay(100)
            assertEquals(
                "addExercise must drop its update once the preview is gone, not force the state back",
                active, f.controller.state.value,
            )
        } finally {
            gated.unblock() // safety net — see the comment in the first test in this section
        }
    }

    @Test
    fun trim_prunesRowFlags() = runBlocking {
        var excludedId = 0L
        val f = previewFixture(count = 2) { freshDb, freshRepo ->
            val locationId = freshDb.knownLocationDao().insert(
                KnownLocation(name = "Home", latitude = 0.0, longitude = 0.0)
            )
            excludedId = freshDb.exerciseDao().getActive().first { it.name == "Barbell Row" }.id
            freshRepo.excludeExercise(locationId, excludedId)
            locationId
        }
        f.controller.addExercise(excludedId)
        awaitPreview(f.controller) { p -> p.plan.exercises.any { it.exercise.id == excludedId } }
        assertEquals(RowFlag.NOT_AT_LOCATION, preview(f.controller).rowFlags[excludedId])

        f.controller.adjustExerciseCount(2)
        awaitPreviewSize(f.controller, 2)

        val p = preview(f.controller)
        assertTrue("trimmed row still in plan", p.plan.exercises.none { it.exercise.id == excludedId })
        assertNull("flag for a trimmed row was not pruned", p.rowFlags[excludedId])
    }

    @Test
    fun lowerCount_trimsFromTailRegardlessOfOrigin() = runBlocking {
        val f = previewFixture(count = 1)
        val all = f.db.exerciseDao().getActive()
        f.controller.loadSavedWorkout(f.repo.saveWorkout(null, "Trio", all.map { SavedWorkoutEntry(it, null) }))
        awaitPreviewSize(f.controller, 3)
        assertEquals(all.map { it.id }, preview(f.controller).plan.exercises.map { it.exercise.id })

        f.controller.adjustExerciseCount(2)
        awaitPreviewSize(f.controller, 2)

        assertEquals(
            "lowering the slider drops the tail row, explicit or not",
            all.take(2).map { it.id },
            preview(f.controller).plan.exercises.map { it.exercise.id },
        )
    }

    @Test
    fun replace_atTarget_restocks() = runBlocking {
        val f = previewFixture(count = 2)
        val removedId = preview(f.controller).plan.exercises[0].exercise.id
        f.controller.replaceExercise(removedId, ExerciseRemovalReason.SKIP_TODAY)
        awaitPreview(f.controller) { p ->
            p.plan.exercises.size == 2 && p.plan.exercises.none { it.exercise.id == removedId }
        }
        val ids = preview(f.controller).plan.exercises.map { it.exercise.id }
        assertTrue(removedId !in ids)
        assertEquals(2, ids.size)
    }

    @Test
    fun replace_aboveTarget_removesWithoutRestock() = runBlocking {
        val f = previewFixture(count = 2)
        val third = f.db.exerciseDao().getActive().first { ex ->
            preview(f.controller).plan.exercises.none { it.exercise.id == ex.id }
        }
        f.controller.addExercise(third.id)
        awaitPreviewSize(f.controller, 3)
        assertEquals(2, preview(f.controller).targetCount)

        f.controller.replaceExercise(third.id, ExerciseRemovalReason.SKIP_TODAY)
        awaitPreviewSize(f.controller, 2)
        delay(100)
        assertEquals(2, preview(f.controller).plan.exercises.size)
    }

    @Test
    fun replaceExercise_leavesOtherRowsPinsUntouched() = runBlocking {
        val f = previewFixture(count = 2)
        val rows = preview(f.controller).plan.exercises
        val keptId = rows[1].exercise.id
        val removedId = rows[0].exercise.id

        f.controller.adjustExerciseWeight(keptId, +1)
        f.controller.setExerciseReps(keptId, 3)
        val pinned = preview(f.controller).plan.exercises.first { it.exercise.id == keptId }
        assertTrue(pinned.weightPinned); assertTrue(pinned.repsPinned)

        f.controller.replaceExercise(removedId, ExerciseRemovalReason.SKIP_TODAY)
        awaitPreview(f.controller) { p ->
            p.plan.exercises.none { it.exercise.id == removedId }
        }

        val kept = preview(f.controller).plan.exercises.first { it.exercise.id == keptId }
        assertTrue("weight pin must survive replacing a sibling row", kept.weightPinned)
        assertTrue("reps pin must survive replacing a sibling row", kept.repsPinned)
        assertEquals(pinned.sessionWeight, kept.sessionWeight)
        assertEquals(pinned.sessionReps, kept.sessionReps)
    }

    @Test
    fun addExercise_appends_marksEdited_andIgnoresDuplicates() = runBlocking {
        val f = previewFixture(count = 1)
        assertTrue(!preview(f.controller).edited)
        val existing = preview(f.controller).plan.exercises[0].exercise.id
        val other = f.db.exerciseDao().getActive().first { it.id != existing }
        f.controller.addExercise(other.id)
        awaitPreviewSize(f.controller, 2)
        val p = preview(f.controller)
        assertEquals(other.id, p.plan.exercises[1].exercise.id)
        assertTrue(p.plan.exercises[1].sessionWeight > 0f)
        assertTrue(p.edited)

        f.controller.addExercise(other.id)
        delay(150)
        assertEquals(2, preview(f.controller).plan.exercises.size)
    }

    @Test
    fun loadSavedWorkout_replacesRows_clearsWeightPins_keepsTarget() = runBlocking {
        val f = previewFixture(count = 2)
        val first = preview(f.controller).plan.exercises[0]
        f.controller.adjustExerciseWeight(first.exercise.id, +1)
        assertTrue(preview(f.controller).plan.exercises[0].weightPinned)

        val all = f.db.exerciseDao().getActive()
        val savedId = f.repo.saveWorkout(null, "Trio", all.map { SavedWorkoutEntry(it, 6) })
        f.controller.loadSavedWorkout(savedId)
        awaitPreviewSize(f.controller, 3)
        val p = preview(f.controller)
        assertEquals(all.map { it.id }, p.plan.exercises.map { it.exercise.id })
        assertEquals(listOf(6, 6, 6), p.plan.exercises.map { it.sessionReps })
        assertTrue(p.plan.exercises.none { it.weightPinned })
        assertTrue(p.plan.exercises.all { it.repsPinned })
        assertEquals(2, p.targetCount)
        assertTrue(p.edited)
    }

    @Test
    fun weightNudge_pinsTheWeight_andSurvivesTheRepSlider() = runBlocking {
        val f = previewFixture(count = 2)
        val row = preview(f.controller).plan.exercises[0]
        f.controller.adjustExerciseWeight(row.exercise.id, +1)
        val nudged = preview(f.controller).plan.exercises[0]
        assertTrue(nudged.weightPinned)
        assertEquals(WeightFormatter.step(row.sessionWeight, +1, WeightUnit.KG), nudged.sessionWeight)
        f.controller.setRepRange(3, 3)
        val after = preview(f.controller).plan.exercises
        assertEquals(nudged.sessionWeight, after[0].sessionWeight)
        assertEquals(listOf(3, 3), after.map { it.sessionReps })
    }

    @Test
    fun setExerciseReps_pins_repricesAutoWeight_andResetFollowsTheSession() = runBlocking {
        val f = previewFixture(count = 2)
        f.controller.setRepRange(10, 10)
        val before = preview(f.controller).plan.exercises[0]
        f.controller.setExerciseReps(before.exercise.id, 3)
        val pinned = preview(f.controller).plan.exercises[0]
        assertTrue(pinned.repsPinned); assertEquals(3, pinned.sessionReps)
        assertTrue(pinned.sessionWeight > before.sessionWeight)
        f.controller.setRepRange(8, 8)
        assertEquals(listOf(3, 8), preview(f.controller).plan.exercises.map { it.sessionReps })
        f.controller.resetExerciseReps(before.exercise.id)
        val reset = preview(f.controller).plan.exercises[0]
        assertFalse(reset.repsPinned); assertEquals(8, reset.sessionReps)
    }

    @Test
    fun resetExerciseWeight_returnsToThePrescription() = runBlocking {
        val f = previewFixture(count = 1)
        val row = preview(f.controller).plan.exercises[0]
        f.controller.adjustExerciseWeight(row.exercise.id, +1)
        f.controller.resetExerciseWeight(row.exercise.id)
        val reset = preview(f.controller).plan.exercises[0]
        assertFalse(reset.weightPinned); assertEquals(row.sessionWeight, reset.sessionWeight)
    }

    /** Adds [exerciseId] unless generation already picked it (a small pool often does). */
    private suspend fun ensureInPlan(f: PreviewFixture, exerciseId: Long) {
        val size = preview(f.controller).plan.exercises.size
        if (preview(f.controller).plan.exercises.any { it.exercise.id == exerciseId }) return
        f.controller.addExercise(exerciseId)
        awaitPreviewSize(f.controller, size + 1)
    }

    @Test
    fun adjustExerciseWeight_isANoOpOnARowWithNoWeight() = runBlocking {
        val bodyweight = Exercise(
            name = "Push-Up", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BODYWEIGHT,
        )
        val f = previewFixture(count = 1, extraExercises = listOf(bodyweight))
        val added = f.db.exerciseDao().getActive().first { it.name == "Push-Up" }
        ensureInPlan(f, added.id)
        val row = preview(f.controller).plan.exercises.first { it.exercise.id == added.id }
        assertEquals(0f, row.sessionWeight)

        f.controller.adjustExerciseWeight(added.id, +1)
        val after = preview(f.controller).plan.exercises.first { it.exercise.id == added.id }
        assertEquals(0f, after.sessionWeight)
        assertFalse(after.weightPinned)
    }

    @Test
    fun setExerciseReps_isANoOpOnATimedRow() = runBlocking {
        val plank = Exercise(
            name = "Plank", primaryMuscle = MuscleGroup.CORE, equipment = Equipment.BODYWEIGHT, isTimed = true,
        )
        val f = previewFixture(count = 1, extraExercises = listOf(plank))
        val added = f.db.exerciseDao().getActive().first { it.name == "Plank" }
        ensureInPlan(f, added.id)
        val row = preview(f.controller).plan.exercises.first { it.exercise.id == added.id }
        assertEquals(60, row.sessionReps)

        f.controller.setExerciseReps(added.id, 12)
        val after = preview(f.controller).plan.exercises.first { it.exercise.id == added.id }
        assertEquals(60, after.sessionReps)
        assertFalse(after.repsPinned)
    }

    @Test
    fun savedWeight_loadsPinned_andSaveCurrentPlanWritesOnlyPins() = runBlocking {
        val f = previewFixture(count = 1)
        val all = f.db.exerciseDao().getActive()
        val savedId = f.repo.saveWorkout(null, "W", listOf(
            SavedWorkoutEntry(all[0], reps = 6, weight = 40f),
            SavedWorkoutEntry(all[1], reps = null),
        ))
        f.controller.loadSavedWorkout(savedId)
        awaitPreviewSize(f.controller, 2)
        val rows = preview(f.controller).plan.exercises
        assertEquals(40f, rows[0].sessionWeight); assertTrue(rows[0].weightPinned)
        assertFalse(rows[1].weightPinned); assertFalse(rows[1].repsPinned)
        val resaved = f.controller.saveCurrentPlan("Again")!!.entries
        assertEquals(listOf(6, null), resaved.map { it.reps })
        assertEquals(listOf(40f, null), resaved.map { it.weight })
    }

    @Test
    fun appendSavedWorkout_keepsExisting_andReplacesDuplicateWithLoadedRow() = runBlocking {
        val f = previewFixture(count = 2)
        val before = preview(f.controller).plan.exercises
        val dup = before[0].exercise
        val other = f.db.exerciseDao().getActive().first { ex -> before.none { it.exercise.id == ex.id } }
        val savedId = f.repo.saveWorkout(null, "Two", listOf(SavedWorkoutEntry(other, 12), SavedWorkoutEntry(dup, 3)))
        f.controller.appendSavedWorkout(savedId)
        awaitPreviewSize(f.controller, 3)
        val ids = preview(f.controller).plan.exercises.map { it.exercise.id }
        assertEquals(listOf(before[1].exercise.id, other.id, dup.id), ids)
        assertEquals(3, preview(f.controller).plan.exercises.last().sessionReps)
    }

    @Test
    fun saveCurrentPlan_writesOrderWithNullReps() = runBlocking {
        val f = previewFixture(count = 2)
        val ids = preview(f.controller).plan.exercises.map { it.exercise.id }
        val detail = f.controller.saveCurrentPlan("Snapshot")!!
        assertEquals("Snapshot", detail.name)
        assertEquals(ids, detail.entries.map { it.exercise.id })
        assertTrue(detail.entries.all { it.reps == null })
    }

    @Test
    fun linkExercises_makesCircuit_setExerciseSetsWritesEveryMember_andDurationFollows() = runBlocking {
        val f = previewFixture(count = 3)
        val before = preview(f.controller).plan.estimatedDurationSeconds
        f.controller.linkExercises(0)
        val linked = preview(f.controller).plan.exercises
        assertEquals(listOf(0, 0, null), linked.map { it.circuitId })

        f.controller.setExerciseSets(linked[1].exercise.id, 1)
        val p = preview(f.controller)
        assertEquals(listOf(1, 1, 3), p.plan.exercises.map { it.sets })
        assertTrue("fewer sets must shorten the estimate", p.plan.estimatedDurationSeconds < before)
        assertTrue(p.edited)

        f.controller.unlinkExercises(0)
        assertEquals(listOf(null, null, null), preview(f.controller).plan.exercises.map { it.circuitId })
    }

    @Test
    fun moveExercise_movesAWholeCircuit() = runBlocking {
        val f = previewFixture(count = 3)
        f.controller.linkExercises(1) // rows 1+2 are a circuit; blocks = [row0], [row1,row2]
        val ids = preview(f.controller).plan.exercises.map { it.exercise.id }
        f.controller.moveExercise(1, 0)
        val after = preview(f.controller).plan.exercises
        assertEquals(listOf(ids[1], ids[2], ids[0]), after.map { it.exercise.id })
        assertEquals(listOf(0, 0, null), after.map { it.circuitId })
    }

    @Test
    fun lowerCount_trimmingACircuitToOneMember_collapsesItToSolo() = runBlocking {
        val f = previewFixture(count = 3)
        f.controller.linkExercises(1)
        f.controller.adjustExerciseCount(2)
        awaitPreviewSize(f.controller, 2)
        assertEquals(listOf(null, null), preview(f.controller).plan.exercises.map { it.circuitId })
    }

    @Test
    fun saveThenLoad_roundTripsStructure_andAppendKeepsCircuitsDistinct() = runBlocking {
        val f = previewFixture(count = 2)
        f.controller.linkExercises(0)
        f.controller.setExerciseSets(preview(f.controller).plan.exercises[0].exercise.id, 2)
        val saved = f.controller.saveCurrentPlan("Pair")!!
        assertEquals(listOf(2, 2), saved.entries.map { it.sets })
        assertEquals(listOf(0, 0), saved.entries.map { it.circuitId })

        // A second saved circuit built from the third exercise plus one of the first two would
        // violate one-per-plan, so append a circuit-free workout and check the kept circuit survives.
        val third = f.db.exerciseDao().getActive().first { ex -> saved.entries.none { it.exercise.id == ex.id } }
        val soloId = f.repo.saveWorkout(null, "Solo", listOf(SavedWorkoutEntry(third, reps = null, sets = 5)))

        // Break the structure first, so the load is what restores it.
        f.controller.unlinkExercises(0)
        assertEquals(listOf(null, null), preview(f.controller).plan.exercises.map { it.circuitId })
        f.controller.loadSavedWorkout(saved.id)
        awaitPreview(f.controller) { p -> p.plan.exercises.map { it.circuitId } == listOf(0, 0) }
        assertEquals(listOf(2, 2), preview(f.controller).plan.exercises.map { it.sets })

        f.controller.appendSavedWorkout(soloId)
        awaitPreviewSize(f.controller, 3)
        val p = preview(f.controller).plan.exercises
        assertEquals(listOf(0, 0, null), p.map { it.circuitId })
        assertEquals(listOf(2, 2, 5), p.map { it.sets })
    }

    @Test
    fun replace_inACircuit_replacementInheritsTheSlot() = runBlocking {
        val f = previewFixture(count = 2) // third exercise is the only replacement candidate
        f.controller.linkExercises(0)
        f.controller.setExerciseSets(preview(f.controller).plan.exercises[0].exercise.id, 2)
        val victim = preview(f.controller).plan.exercises[1].exercise.id
        f.controller.replaceExercise(victim, ExerciseRemovalReason.SKIP_TODAY)
        awaitPreview(f.controller) { p -> p.plan.exercises.none { it.exercise.id == victim } }
        val p = preview(f.controller).plan.exercises
        assertEquals(2, p.size)
        assertEquals(listOf(0, 0), p.map { it.circuitId })
        assertEquals(listOf(2, 2), p.map { it.sets })
    }

    @Test
    fun adjustExerciseCount_updatesTargetCountOnPreview() = runBlocking {
        val f = previewFixture(count = 1)
        f.controller.adjustExerciseCount(3)
        awaitPreviewSize(f.controller, 3)
        assertEquals(3, preview(f.controller).targetCount)
        f.controller.adjustExerciseCount(1)
        awaitPreviewSize(f.controller, 1)
        assertEquals(1, preview(f.controller).targetCount)
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
        while (s.warmupSetIndex != null && s.warmupSetIndex + 1 < s.plannedExercise.warmupSets.size) {
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
        val staged = resting.staged!!
        assertEquals(StagedKind.ADJUST_WEIGHT, staged.kind)
        val commit = staged.commitTarget!!
        // Same set coordinates.
        assertEquals(active.exerciseIndex, commit.exerciseIndex)
        assertEquals(active.setIndex, commit.setIndex)
        assertEquals(active.warmupSetIndex, commit.warmupSetIndex)
        // New weight applied to the plan.
        assertEquals(
            WeightFormatter.round(target, WeightUnit.KG),
            commit.plan.exercises[i].sessionWeight,
        )
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
        val staged = resting.staged!!
        val target = staged.commitTarget!!
        assertEquals(StagedKind.SWAP, staged.kind)
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
    fun initialize_afterLayoff_surfacesDetrainingNotice() = runBlocking {
        // Fresh controller (setUp already ran startSession, which inserts a recent session).
        // We need a fresh DB with only a 3-weeks-old session.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val freshDb = track(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build())
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
        val notice = preview.detraining!!
        assertEquals(3, notice.weeksOff)
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
    fun dismissDetrainingNotice_clearsNoticeWithoutTouchingWeights() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val freshDb = track(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build())
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
        assertNotNull((freshController.state.value as WorkoutState.PlanPreview).detraining)

        freshController.dismissDetrainingNotice()

        val after = (freshController.state.value as WorkoutState.PlanPreview)
        assertNull(after.detraining)
        assertEquals(before.map { it.sessionWeight }, after.plan.exercises.map { it.sessionWeight })
    }

    @Test
    fun moveExercise_swapsExerciseOrder() = runBlocking {
        // Use a fresh DB so setUp's active session doesn't interfere.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val freshDb = track(Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build())
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
        val staged = resting.staged!!
        val commitTarget = staged.commitTarget!!
        assertEquals(StagedKind.WARMUP_DONE, staged.kind)
        assertEquals(lastWarmupState.exerciseIndex, commitTarget.exerciseIndex)
        assertEquals(0, commitTarget.setIndex)
        assertNull(commitTarget.warmupSetIndex)
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
