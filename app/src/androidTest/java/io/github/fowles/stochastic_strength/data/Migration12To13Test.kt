package io.github.fowles.stochastic_strength.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration12To13Test {

    private val dbName = "migration-12-13-test-db"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        context.deleteDatabase(dbName)
    }

    @After
    fun teardown() {
        context.deleteDatabase(dbName)
    }

    /**
     * Creates a v12 database with a baseline_history row and runs [AppDatabase.MIGRATION_12_13]
     * directly. Returns the migrated [SupportSQLiteDatabase]; caller is responsible for closing it.
     */
    private fun createV12DbAndMigrate(
        seed: (SupportSQLiteDatabase) -> Unit = {},
    ): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(12) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `exercises` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `name` TEXT NOT NULL,
                                `primaryMuscle` TEXT NOT NULL,
                                `secondaryMuscles` TEXT NOT NULL,
                                `equipment` TEXT NOT NULL,
                                `isDisliked` INTEGER NOT NULL,
                                `isUnilateral` INTEGER NOT NULL,
                                `isTimed` INTEGER NOT NULL)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `known_locations` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `name` TEXT NOT NULL,
                                `latitude` REAL NOT NULL,
                                `longitude` REAL NOT NULL)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `location_excluded_exercises` (
                                `locationId` INTEGER NOT NULL,
                                `exerciseId` INTEGER NOT NULL,
                                PRIMARY KEY(`locationId`, `exerciseId`))
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `workout_sessions` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `locationId` INTEGER,
                                `startTime` INTEGER NOT NULL,
                                `endTime` INTEGER,
                                `stravaActivityId` INTEGER)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `workout_sets` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `sessionId` INTEGER NOT NULL,
                                `exerciseId` INTEGER NOT NULL,
                                `setNumber` INTEGER NOT NULL,
                                `targetWeight` REAL NOT NULL,
                                `targetReps` INTEGER NOT NULL,
                                `actualReps` INTEGER,
                                `feedback` TEXT,
                                `completedAt` INTEGER,
                                `durationSeconds` INTEGER)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `user_profile` (
                                `id` INTEGER NOT NULL,
                                `sex` TEXT NOT NULL,
                                `strengthLevel` TEXT NOT NULL,
                                `weightUnit` TEXT NOT NULL,
                                `preferredExerciseCount` INTEGER,
                                PRIMARY KEY(`id`))
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `muscle_group_strength` (
                                `muscleGroup` TEXT NOT NULL,
                                `baselineWeight` REAL NOT NULL,
                                PRIMARY KEY(`muscleGroup`))
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `baseline_history` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `sessionId` INTEGER,
                                `muscleGroup` TEXT NOT NULL,
                                `previousBaseline` REAL NOT NULL,
                                `newBaseline` REAL NOT NULL,
                                `changeReason` TEXT NOT NULL,
                                `feedbacks` TEXT,
                                `sessionReps` INTEGER,
                                `minReductionFraction` REAL,
                                `timestamp` INTEGER NOT NULL)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `coefficient_history` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `exerciseId` INTEGER NOT NULL,
                                `previousCoefficient` REAL,
                                `coefficient` REAL NOT NULL,
                                `heuristicName` TEXT NOT NULL,
                                `heuristicMetadata` TEXT,
                                `computedAt` INTEGER NOT NULL)
                        """.trimIndent())
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_coefficient_history_exerciseId` ON `coefficient_history` (`exerciseId`)")
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `baseline_override` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `sessionId` INTEGER,
                                `muscleGroup` TEXT NOT NULL,
                                `baselineWeight` REAL NOT NULL,
                                `asOf` INTEGER NOT NULL)
                        """.trimIndent())
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `exercise_hurt_state` (
                                `exerciseId` INTEGER PRIMARY KEY NOT NULL,
                                `isHurt` INTEGER NOT NULL,
                                `asOf` INTEGER NOT NULL)
                        """.trimIndent())
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'f418e7a6d574e32f43ffb5384c271060')")
                        seed(db)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_12_13.migrate(db)
        return db
    }

    @Test
    fun migrate12to13_addsNullableColumnsToExistingRows() {
        createV12DbAndMigrate { db ->
            db.execSQL("""
                INSERT INTO baseline_history (
                    sessionId, muscleGroup, previousBaseline, newBaseline,
                    changeReason, feedbacks, sessionReps, minReductionFraction, timestamp
                ) VALUES (
                    7, 'CHEST', 100.0, 105.0, 'PROGRESSION', 'RIR_2_4', 5, NULL, 1000
                )
            """.trimIndent())
        }.use { migrated ->
            migrated.query(
                "SELECT heuristicName, heuristicMetadata FROM baseline_history WHERE sessionId = 7"
            ).use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertNull(c.getString(0))
                assertNull(c.getString(1))
            }
        }
    }

    @Test
    fun migrate12to13_allowsInsertWithHeuristicColumns() {
        createV12DbAndMigrate().use { migrated ->
            // Should not throw: inserting a row with the new columns populated.
            migrated.execSQL("""
                INSERT INTO baseline_history (
                    sessionId, muscleGroup, previousBaseline, newBaseline,
                    changeReason, feedbacks, sessionReps, minReductionFraction, timestamp,
                    heuristicName, heuristicMetadata
                ) VALUES (
                    8, 'BACK', 80.0, 82.5, 'PROGRESSION', NULL, 5, NULL, 2000,
                    'est-baseline-consensus', 'target=85.0,conf=0.7'
                )
            """.trimIndent())
            migrated.query(
                "SELECT heuristicName, heuristicMetadata FROM baseline_history WHERE sessionId = 8"
            ).use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("est-baseline-consensus", c.getString(0))
                assertEquals("target=85.0,conf=0.7", c.getString(1))
            }
        }
    }
}
