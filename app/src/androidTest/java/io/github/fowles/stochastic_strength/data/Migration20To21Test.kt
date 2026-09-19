package io.github.fowles.stochastic_strength.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration20To21Test {
    private val dbName = "migration-20-21-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate20To21_addsStructureColumns_andKeepsExistingRowsAsThreeStraightSets() {
        helper.createDatabase(dbName, 20).use { v20 ->
            v20.execSQL("INSERT INTO saved_workout (name, createdAt) VALUES ('Push', 5)")
            v20.execSQL(
                "INSERT INTO saved_workout_exercise (workoutId, exerciseId, position, reps) VALUES (1, 7, 0, NULL)"
            )
            v20.execSQL(
                "INSERT INTO workout_sets (sessionId, exerciseId, setNumber, targetWeight, targetReps) " +
                    "VALUES (1, 7, 1, 40.0, 5)"
            )
        }
        val v21 = helper.runMigrationsAndValidate(dbName, 21, true, AppDatabase.MIGRATION_20_21)
        v21.query("SELECT sets, circuitId FROM saved_workout_exercise").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
            assertTrue(c.isNull(1))
        }
        v21.query("SELECT circuitId FROM workout_sets").use { c ->
            assertTrue(c.moveToFirst()); assertTrue(c.isNull(0))
        }
        v21.close()
    }
}
