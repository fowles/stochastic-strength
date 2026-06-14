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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test-db"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val dbName10 = "migration-test-db-10"
    private val dbName11 = "migration-test-db-11"

    private val dbNameMigration = "migration-test-direct-db"

    @Before
    fun setup() {
        context.deleteDatabase(dbName)
        context.deleteDatabase(dbName10)
        context.deleteDatabase(dbName11)
        context.deleteDatabase(dbNameMigration)
    }

    @After
    fun teardown() {
        context.deleteDatabase(dbName)
        context.deleteDatabase(dbName10)
        context.deleteDatabase(dbName11)
        context.deleteDatabase(dbNameMigration)
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

        // Run the migrations and open at the current entity version (register every step so Room
        // can walk up from v9).
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
            )
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
    fun migrate10To11Plus_preservesUserProfileRow() {
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

        // Walk all migrations forward to the current entity version.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName10)
            .addMigrations(AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12)
            .allowMainThreadQueries()
            .build()

        try {
            val profile = runBlocking { db.userProfileDao().getProfile() }
            assertNotNull(profile)
            assertEquals(Sex.MALE, profile!!.sex)
            assertEquals(WeightUnit.KG, profile.weightUnit)
            assertEquals(5, profile.preferredExerciseCount)
            // derivedStateVersion is dropped in Phase 3 — no longer asserted here.
            // TODO Task 21 (Phase 6): add assertions for new replay-based behavior if needed.
        } finally {
            db.close()
        }
    }

    // TODO Task 21 (Phase 6): The old migrate11To12_dropsActualRepsBackfilledAndAddsDerivedStateVersionAtZero
    // test is replaced because derivedStateVersion is removed from UserProfile in Phase 3.
    // The new migration tests below cover the Phase 3 schema changes.

    /**
     * Creates a v11 database manually and runs [AppDatabase.MIGRATION_11_12] directly, returning
     * the migrated [SupportSQLiteDatabase]. Does not open through Room.databaseBuilder, so Room
     * schema validation is skipped — this is intentional for the targeted per-behavior tests below.
     *
     * NOTE: callers are responsible for calling [SupportSQLiteDatabase.close] (or using `.use {}`).
     */
    private fun createV11DbAndMigrate(
        dbName: String,
        seed: (SupportSQLiteDatabase) -> Unit = {},
    ): SupportSQLiteDatabase {
        val helper11 = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(11) {
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
                                `preferredExerciseCount` INTEGER,
                                `actualRepsBackfilled` INTEGER NOT NULL DEFAULT 0,
                                PRIMARY KEY(`id`))
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
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '7e12723a6449032d63c8aaeca7f96a83')")
                        seed(db)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        // Touch the DB to trigger onCreate, then run the migration directly.
        val db = helper11.writableDatabase
        AppDatabase.MIGRATION_11_12.migrate(db)
        return db
    }

    @Test
    fun migrate11To12_movesManualOverrideRowsIntoBaselineOverride() {
        createV11DbAndMigrate(dbNameMigration) { db ->
            // Insert a workout_session row referenced by the override (sessionId=7).
            db.execSQL("""
                INSERT INTO workout_sessions (id, startTime, endTime)
                    VALUES (7, 1700000000000, 1700000010000)
            """.trimIndent())
            db.execSQL("""
                INSERT INTO baseline_change_log
                    (id, sessionId, muscleGroup, previousBaseline, newBaseline, changeReason, feedbacks, sessionReps, minReductionFraction, timestamp)
                    VALUES (1, 7, 'CHEST', 80.0, 82.5, 'MANUAL_OVERRIDE', NULL, NULL, NULL, 1700000005000)
            """.trimIndent())
        }.use { migrated ->
            migrated.query("SELECT sessionId, muscleGroup, baselineWeight, asOf FROM baseline_override").use { c ->
                val rows = mutableListOf<List<Any?>>()
                while (c.moveToNext()) rows += listOf(
                    if (c.isNull(0)) null else c.getLong(0),
                    c.getString(1),
                    c.getFloat(2),
                    c.getLong(3),
                )
                assertTrue("expected the MANUAL_OVERRIDE row migrated; rows=$rows",
                    rows.any { it[0] == 7L && it[1] == "CHEST" && it[2] == 82.5f })
            }
            migrated.query(
                "SELECT COUNT(*) FROM baseline_history WHERE changeReason = 'MANUAL_OVERRIDE'"
            ).use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun migrate11To12_synthesizesInitialBaselineFromEarliestLogPreviousBaseline() {
        createV11DbAndMigrate(dbNameMigration) { db ->
            db.execSQL("""
                INSERT INTO workout_sessions (id, startTime, endTime)
                    VALUES (1, 1, 2), (2, 3, 4)
            """.trimIndent())
            db.execSQL("""
                INSERT INTO baseline_change_log
                    (id, sessionId, muscleGroup, previousBaseline, newBaseline, changeReason, feedbacks, sessionReps, minReductionFraction, timestamp)
                    VALUES
                        (10, 1, 'CHEST', 50.0, 52.5, 'PROGRESSION', 'RIR_2_4', 5, NULL, 1),
                        (20, 2, 'CHEST', 52.5, 55.0, 'PROGRESSION', 'RIR_2_4', 5, NULL, 3)
            """.trimIndent())
        }.use { migrated ->
            migrated.query(
                "SELECT baselineWeight FROM baseline_override WHERE sessionId IS NULL AND muscleGroup = 'CHEST'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(50.0f, c.getFloat(0))
            }
        }
    }

    @Test
    fun migrate11To12_copiesCurrentBaselineForMuscleWithNoLog() {
        createV11DbAndMigrate(dbNameMigration) { db ->
            db.execSQL("""
                INSERT INTO muscle_group_strength (muscleGroup, baselineWeight)
                    VALUES ('TRICEPS', 30.0)
            """.trimIndent())
        }.use { migrated ->
            migrated.query(
                "SELECT baselineWeight FROM baseline_override WHERE sessionId IS NULL AND muscleGroup = 'TRICEPS'"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(30.0f, c.getFloat(0))
            }
        }
    }

    @Test
    fun migrate11To12_migratesHurtFlagToExerciseHurtState() {
        createV11DbAndMigrate(dbNameMigration) { db ->
            // Insert one hurt exercise and one not-hurt; only the hurt one should appear in the new table.
            db.execSQL("""
                INSERT INTO exercises (id, name, primaryMuscle, secondaryMuscles, equipment, isDisliked, hurtFlag, isUnilateral, isTimed)
                    VALUES
                        (1, 'Bench', 'CHEST', '', 'BARBELL', 0, 1, 0, 0),
                        (2, 'Squat', 'QUADS', '', 'BARBELL', 0, 0, 0, 0)
            """.trimIndent())
        }.use { migrated ->
            migrated.query("SELECT exerciseId, isHurt FROM exercise_hurt_state").use { c ->
                val rows = mutableListOf<Pair<Long, Int>>()
                while (c.moveToNext()) rows += c.getLong(0) to c.getInt(1)
                assertEquals(listOf(1L to 1), rows)
            }
            migrated.query("PRAGMA table_info(exercises)").use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"))
                assertFalse("hurtFlag should be dropped from exercises", names.contains("hurtFlag"))
            }
        }
    }

    @Test
    fun migrate11To12_renamesHistoryTables() {
        createV11DbAndMigrate(dbNameMigration).use { migrated ->
            // Old names should be gone, new names should exist. Using sqlite_master to enumerate.
            val tables = mutableListOf<String>()
            migrated.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            assertFalse(tables.contains("baseline_change_log"))
            assertFalse(tables.contains("coefficient_change_log"))
            assertTrue(tables.contains("baseline_history"))
            assertTrue(tables.contains("coefficient_history"))
        }
    }

    @Test
    fun migrate11To12_dropsActualRepsBackfilledFromUserProfile() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName11)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(11) {
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
                                `preferredExerciseCount` INTEGER,
                                `actualRepsBackfilled` INTEGER NOT NULL DEFAULT 0,
                                PRIMARY KEY(`id`))
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
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '7e12723a6449032d63c8aaeca7f96a83')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )

        helper.writableDatabase.use { db ->
            db.execSQL("""
                INSERT INTO user_profile
                    (id, sex, strengthLevel, weightUnit, preferredExerciseCount, actualRepsBackfilled)
                VALUES (1, 'MALE', 'NOVICE', 'KG', NULL, 1)
            """.trimIndent())
        }
        helper.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName11)
            .addMigrations(AppDatabase.MIGRATION_11_12)
            .allowMainThreadQueries()
            .build()

        try {
            db.openHelper.readableDatabase.query("PRAGMA table_info(user_profile)").use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"))
                assertFalse(names.contains("actualRepsBackfilled"))
                assertFalse(names.contains("derivedStateVersion"))
            }
        } finally {
            db.close()
        }
    }
}
