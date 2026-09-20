package io.github.fowles.stochastic_strength.ui.savedworkouts

import android.app.Application
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.WorkoutRepository
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutEntry
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutNaming
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedWorkoutsViewModelsTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: WorkoutRepository
    private lateinit var app: Application
    private lateinit var bench: Exercise

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        app = context.applicationContext as Application
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = WorkoutRepository(db)
        val benchId = db.exerciseDao().insert(
            Exercise(name = "Bench", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL)
        )
        bench = db.exerciseDao().getById(benchId)!!
    }

    @After
    fun tearDown() = db.close()

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun await(what: String, predicate: suspend () -> Boolean) = runBlocking {
        repeat(100) {
            if (predicate()) return@runBlocking
            delay(20)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun savedCount(): Int = runBlocking { db.savedWorkoutDao().getAll().size }

    /** `viewModel()` uses AndroidViewModelFactory, which reflects on an exact (Application) constructor. */
    @Test
    fun savedWorkoutsViewModel_keepsTheApplicationOnlyConstructor() {
        assertNotNull(SavedWorkoutsViewModel::class.java.getConstructor(Application::class.java))
    }

    private fun newEditor() = onMain { SavedWorkoutEditViewModel(app, SavedWorkoutEditViewModel.NEW_WORKOUT_ID, repo) }

    @Test
    fun newWorkout_startsLoadedAndEmpty() {
        val vm = newEditor()
        assertEquals(LoadStatus.LOADED, vm.state.value.status)
        assertEquals("", vm.state.value.name)
        assertEquals(0, vm.state.value.entries.size)
    }

    @Test
    fun hasUnsavedChanges_tracksEditsAndSaves() {
        val vm = newEditor()
        assertEquals(false, vm.hasUnsavedChanges())
        runBlocking { vm.allExercises.first { it.isNotEmpty() } }

        onMain { vm.addExercise(bench.id) }
        assertEquals(true, vm.hasUnsavedChanges())

        onMain { vm.save() }
        await("saved") { savedCount() == 1 && !vm.hasUnsavedChanges() }

        onMain { vm.setName("Push day") }
        assertEquals(true, vm.hasUnsavedChanges())
    }

    @Test
    fun hasUnsavedChanges_falseForFreshlyLoadedExistingWorkout() = runBlocking {
        val id = repo.saveWorkout(null, "Push day", listOf(SavedWorkoutEntry(bench, 8)))
        val vm = onMain { SavedWorkoutEditViewModel(app, id, repo) }
        await("loaded") { vm.state.value.status == LoadStatus.LOADED }

        assertEquals(false, vm.hasUnsavedChanges())
        onMain { vm.removeExercise(bench.id) }
        assertEquals(true, vm.hasUnsavedChanges())
    }

    @Test
    fun newWorkout_untouchedSave_writesNoRow() {
        val vm = newEditor()

        onMain { vm.save(); vm.save() }
        runBlocking { delay(200) } // let any write land

        assertEquals(0, savedCount())
    }

    @Test
    fun newWorkout_firstSaveCreatesRow_laterSavesUpdateIt() {
        val vm = newEditor()
        // WhileSubscribed: the exercise flow only fills once something collects it.
        runBlocking { vm.allExercises.first { it.isNotEmpty() } }

        onMain { vm.addExercise(bench.id); vm.save() }
        await("created") { savedCount() == 1 }
        val created = runBlocking { db.savedWorkoutDao().getAll().single() }

        onMain { vm.setName("Push day"); vm.save() }
        await("renamed") { runBlocking { repo.getSavedWorkout(created.id)?.name } == "Push day" }

        assertEquals("a second save must update, not insert", 1, savedCount())
        assertEquals(listOf(bench.id), runBlocking { repo.getSavedWorkout(created.id)!!.entries.map { it.exercise.id } })
    }

    @Test
    fun save_onExistingEmptyUnnamedWorkout_keepsIt() = runBlocking {
        val id = repo.saveWorkout(null, "", emptyList())
        val vm = onMain { SavedWorkoutEditViewModel(app, id, repo) }
        await("loaded") { vm.state.value.status == LoadStatus.LOADED }

        onMain { vm.save() }
        runBlocking { delay(200) } // let any delete land

        assertNotNull("an existing row is never silently deleted", repo.getSavedWorkout(id))
    }

    @Test
    fun edit_onDeletedWorkout_reportsMissing() = runBlocking {
        val id = repo.saveWorkout(null, SavedWorkoutNaming.UNTITLED, emptyList())
        repo.deleteSavedWorkout(id)

        val vm = onMain { SavedWorkoutEditViewModel(app, id, repo) }

        await("missing") { vm.state.value.status == LoadStatus.MISSING }
    }

    @Test
    fun save_onMissingWorkout_writesNothing() = runBlocking {
        val id = repo.saveWorkout(null, SavedWorkoutNaming.UNTITLED, emptyList())
        repo.deleteSavedWorkout(id)
        val vm = onMain { SavedWorkoutEditViewModel(app, id, repo) }
        await("missing") { vm.state.value.status == LoadStatus.MISSING }

        onMain { vm.setName("Push day"); vm.save() }
        runBlocking { delay(200) } // let any write land

        assertNull(repo.getSavedWorkout(id))
        assertEquals(0, savedCount())
    }

    @Test
    fun save_onNamedEmptyWorkout_keepsIt() = runBlocking {
        val id = repo.saveWorkout(null, SavedWorkoutNaming.UNTITLED, emptyList())
        val vm = onMain { SavedWorkoutEditViewModel(app, id, repo) }
        await("loaded") { vm.state.value.status == LoadStatus.LOADED }

        onMain { vm.setName("Push day"); vm.save() }
        await("renamed") { repo.getSavedWorkout(id)?.name == "Push day" }

        assertNotNull(repo.getSavedWorkout(id))
    }

    @Test
    fun save_onDefaultNamedWorkoutWithExercises_keepsItUnderADerivedName() = runBlocking {
        val id = repo.saveWorkout(null, SavedWorkoutNaming.UNTITLED, listOf(SavedWorkoutEntry(bench, 8)))
        val vm = onMain { SavedWorkoutEditViewModel(app, id, repo) }
        await("loaded") { vm.state.value.status == LoadStatus.LOADED }

        onMain { vm.save() }
        runBlocking { delay(200) }

        val detail = repo.getSavedWorkout(id)
        assertNotNull(detail)
        assertEquals(listOf(bench.id), detail!!.entries.map { it.exercise.id })
        assertEquals("stored unnamed so the derived name keeps tracking the exercises", "", detail.name)
        assertEquals(bench.name, detail.displayName)
    }

    @Test
    fun structureEdits_trackUnsavedChanges_andRoundTripThroughSave() = runBlocking {
        val squatId = db.exerciseDao().insert(
            Exercise(name = "Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
        )
        val rowId = db.exerciseDao().insert(
            Exercise(name = "Row", primaryMuscle = MuscleGroup.BACK, equipment = Equipment.BARBELL)
        )
        val a = bench.id
        val b = squatId
        val c = rowId
        val repository = repo
        val vm = newEditor()
        runBlocking { vm.allExercises.first { it.size >= 3 } }

        onMain { vm.addExercise(a); vm.addExercise(b); vm.addExercise(c) }
        onMain { vm.link(0) }
        onMain { vm.setSets(b, 2) }
        assertEquals(listOf(2, 2, 3), vm.state.value.entries.map { it.sets })
        assertEquals(listOf(0, 0, null), vm.state.value.entries.map { it.circuitId })
        assertEquals(true, vm.hasUnsavedChanges())

        onMain { vm.move(1, 0) } // block 1 (solo c) above block 0 (the circuit)
        assertEquals(listOf(c, a, b), vm.state.value.entries.map { it.exercise.id })

        onMain { vm.removeExercise(a) }
        assertEquals("a circuit of one is a solo row", listOf(null, null), vm.state.value.entries.map { it.circuitId })

        onMain { vm.save() }
        await("saved") { savedCount() == 1 }
        val saved = repository.observeSavedWorkouts().first().single()
        assertEquals(listOf(3, 2), saved.entries.map { it.sets })
    }

    @Test
    fun setWeight_isAnUnsavedEdit_andPersistsOnSave() {
        val vm = newEditor()
        runBlocking { vm.allExercises.first { it.isNotEmpty() } }
        onMain { vm.addExercise(bench.id) }
        await("row") { vm.state.value.entries.size == 1 }
        onMain { vm.setWeight(bench.id, 40f) }
        assertTrue(vm.hasUnsavedChanges())
        onMain { vm.save() }
        await("saved") { savedCount() == 1 }
        val saved = runBlocking { repo.getSavedWorkout(db.savedWorkoutDao().getAll().single().id)!! }
        assertEquals(40f, saved.entries.single().weight)
        onMain { vm.setWeight(bench.id, null) }
        assertNull(vm.state.value.entries.single().weight)
    }

    @Test
    fun suggester_loads_andPricesAtPinnedReps() {
        val vm = newEditor()
        await("suggester") { vm.suggester.value != null }
        val s = vm.suggester.value!!
        assertTrue(s.weight(bench, 3) >= s.weight(bench, null))
    }
}
