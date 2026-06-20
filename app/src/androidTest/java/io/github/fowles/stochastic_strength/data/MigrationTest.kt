package io.github.fowles.stochastic_strength.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test-db"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val dbName10 = "migration-test-db-10"

    @Before
    fun setup() {
        context.deleteDatabase(dbName)
        context.deleteDatabase(dbName10)
    }

    @After
    fun teardown() {
        context.deleteDatabase(dbName)
        context.deleteDatabase(dbName10)
    }

    @Test
    fun migrate9To10_addsActualRepsColumnAndPreservesRows() {
        // Create DB at v9 schema manually
        val v9Sql = """
            CREATE TABLE IF NOT EXISTS `exercises` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL, `primaryMuscle` TEXT NOT NULL,
                `secondaryMuscles` TEXT NOT NULL, `equipment` TEXT NOT NULL,
                `isDisliked` INTEGER NOT NULL, `hurtFlag` INTEGER NOT NULL,
                `isUnilateral` INTEGER NOT NULL, `isTimed` INTEGER NOT NULL)
        """.trimIndent()
        val v9WorkoutSets = """
            CREATE TABLE IF NOT EXISTS `workout_sets` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL,
                `setNumber` INTEGER NOT NULL, `targetWeight` REAL NOT NULL,
                `targetReps` INTEGER NOT NULL, `feedback` TEXT,
                `completedAt` INTEGER, `durationSeconds` INTEGER)
        """.trimIndent()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(v9Sql)
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `known_locations` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `name` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `location_excluded_exercises` (
                                `locationId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL,
                                PRIMARY KEY(`locationId`, `exerciseId`))
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `workout_sessions` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `locationId` INTEGER, `startTime` INTEGER NOT NULL,
                                `endTime` INTEGER, `stravaActivityId` INTEGER)
                        """.trimIndent())
                        db.execSQL(v9WorkoutSets)
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `user_profile` (
                                `id` INTEGER NOT NULL, `sex` TEXT NOT NULL,
                                `strengthLevel` TEXT NOT NULL, `weightUnit` TEXT NOT NULL,
                                `preferredExerciseCount` INTEGER, PRIMARY KEY(`id`))
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `muscle_group_strength` (
                                `muscleGroup` TEXT NOT NULL, `baselineWeight` REAL NOT NULL,
                                PRIMARY KEY(`muscleGroup`))
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `baseline_change_log` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `sessionId` INTEGER NOT NULL, `muscleGroup` TEXT NOT NULL,
                                `previousBaseline` REAL NOT NULL, `newBaseline` REAL NOT NULL,
                                `changeReason` TEXT NOT NULL, `feedbacks` TEXT,
                                `sessionReps` INTEGER, `minReductionFraction` REAL,
                                `timestamp` INTEGER NOT NULL)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `coefficient_change_log` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `exerciseId` INTEGER NOT NULL, `previousCoefficient` REAL,
                                `coefficient` REAL NOT NULL, `heuristicName` TEXT NOT NULL,
                                `heuristicMetadata` TEXT, `computedAt` INTEGER NOT NULL)
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_coefficient_change_log_exerciseId` ON `coefficient_change_log` (`exerciseId`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'adfe5b472a113b319baa9561f40b10f9')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )

        // Open v9 DB and insert test row
        helper.writableDatabase.use { db ->
            db.execSQL("""
                INSERT INTO workout_sets
                    (id, sessionId, exerciseId, setNumber, targetWeight, targetReps, feedback, completedAt, durationSeconds)
                VALUES
                    (1, 100, 200, 1, 80.0, 5, 'RIR_2_4', 1700000000000, NULL)
            """.trimIndent())
        }
        helper.close()

        // Run the migrations and open at v11 (registering both so Room can step up)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()

        try {
            val dao = db.workoutSetDao()
            val sets = runBlocking { dao.getSetsForSession(100L) }
            assertEquals(1, sets.size)
            val set = sets[0]
            assertEquals(1, set.setNumber)
            assertEquals(5, set.targetReps)
            assertNull(set.actualReps)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrate10To11_addsActualRepsBackfilledAndPreservesRows() {
        // Create DB at v10 schema manually
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName10)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `exercises` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `name` TEXT NOT NULL, `primaryMuscle` TEXT NOT NULL,
                                `secondaryMuscles` TEXT NOT NULL, `equipment` TEXT NOT NULL,
                                `isDisliked` INTEGER NOT NULL, `hurtFlag` INTEGER NOT NULL,
                                `isUnilateral` INTEGER NOT NULL, `isTimed` INTEGER NOT NULL)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `known_locations` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `name` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `location_excluded_exercises` (
                                `locationId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL,
                                PRIMARY KEY(`locationId`, `exerciseId`))
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `workout_sessions` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `locationId` INTEGER, `startTime` INTEGER NOT NULL,
                                `endTime` INTEGER, `stravaActivityId` INTEGER)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `workout_sets` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `sessionId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL,
                                `setNumber` INTEGER NOT NULL, `targetWeight` REAL NOT NULL,
                                `targetReps` INTEGER NOT NULL, `actualReps` INTEGER,
                                `feedback` TEXT, `completedAt` INTEGER, `durationSeconds` INTEGER)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `user_profile` (
                                `id` INTEGER NOT NULL, `sex` TEXT NOT NULL,
                                `strengthLevel` TEXT NOT NULL, `weightUnit` TEXT NOT NULL,
                                `preferredExerciseCount` INTEGER, PRIMARY KEY(`id`))
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `muscle_group_strength` (
                                `muscleGroup` TEXT NOT NULL, `baselineWeight` REAL NOT NULL,
                                PRIMARY KEY(`muscleGroup`))
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `baseline_change_log` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `sessionId` INTEGER NOT NULL, `muscleGroup` TEXT NOT NULL,
                                `previousBaseline` REAL NOT NULL, `newBaseline` REAL NOT NULL,
                                `changeReason` TEXT NOT NULL, `feedbacks` TEXT,
                                `sessionReps` INTEGER, `minReductionFraction` REAL,
                                `timestamp` INTEGER NOT NULL)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `coefficient_change_log` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `exerciseId` INTEGER NOT NULL, `previousCoefficient` REAL,
                                `coefficient` REAL NOT NULL, `heuristicName` TEXT NOT NULL,
                                `heuristicMetadata` TEXT, `computedAt` INTEGER NOT NULL)
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_coefficient_change_log_exerciseId` ON `coefficient_change_log` (`exerciseId`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '631a45fccbafc2a1ef35b7e0bee5c86f')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )

        // Open v10 DB and insert a user_profile row
        helper.writableDatabase.use { db ->
            db.execSQL("""
                INSERT INTO user_profile (id, sex, strengthLevel, weightUnit, preferredExerciseCount)
                VALUES (1, 'MALE', 'MEDIUM', 'KG', 5)
            """.trimIndent())
        }
        helper.close()

        // Run both migrations and open at v11
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName10)
            .addMigrations(AppDatabase.MIGRATION_10_11)
            .allowMainThreadQueries()
            .build()

        try {
            val profile = runBlocking { db.userProfileDao().getProfile() }
            assertNotNull(profile)
            assertEquals(Sex.MALE, profile!!.sex)
            assertEquals(WeightUnit.KG, profile.weightUnit)
            assertEquals(5, profile.preferredExerciseCount)
            assertFalse(profile.actualRepsBackfilled)
        } finally {
            db.close()
        }
    }
}
