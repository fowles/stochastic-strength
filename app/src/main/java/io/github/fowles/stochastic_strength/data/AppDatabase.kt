package io.github.fowles.stochastic_strength.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.fowles.stochastic_strength.data.dao.BaselineOverrideDao
import io.github.fowles.stochastic_strength.data.dao.ExerciseDao
import io.github.fowles.stochastic_strength.data.dao.ExerciseHurtStateDao
import io.github.fowles.stochastic_strength.data.dao.KnownLocationDao
import io.github.fowles.stochastic_strength.data.dao.LocationExcludedExerciseDao
import io.github.fowles.stochastic_strength.data.dao.UserProfileDao
import io.github.fowles.stochastic_strength.data.dao.WorkoutSessionDao
import io.github.fowles.stochastic_strength.data.dao.WorkoutSetDao
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlinx.coroutines.CoroutineScope

@Database(
    entities = [
        Exercise::class,
        KnownLocation::class,
        LocationExcludedExercise::class,
        WorkoutSession::class,
        WorkoutSet::class,
        UserProfile::class,
        BaselineOverride::class,
        ExerciseHurtState::class,
    ],
    version = 14,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun knownLocationDao(): KnownLocationDao
    abstract fun locationExcludedExerciseDao(): LocationExcludedExerciseDao
    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun workoutSetDao(): WorkoutSetDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun baselineOverrideDao(): BaselineOverrideDao
    abstract fun exerciseHurtStateDao(): ExerciseHurtStateDao

    companion object {
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE exercises SET equipment = 'BARBELL' WHERE name = 'Stiff-Leg Deadlift'")
                db.execSQL("UPDATE exercises SET isUnilateral = 1 WHERE name IN ('Lunge', 'Pallof Press', 'Kettlebell Clean and Press')")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN isTimed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE exercises SET isTimed = 1 WHERE name IN ('Plank', 'Mountain Climber')")
                db.execSQL("ALTER TABLE workout_sets ADD COLUMN durationSeconds INTEGER")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN stravaActivityId INTEGER")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE exercises SET primaryMuscle = 'HAMSTRINGS', secondaryMuscles = 'BACK,GLUTES' WHERE name = 'Deadlift'")
                db.execSQL("UPDATE exercises SET primaryMuscle = 'HAMSTRINGS', secondaryMuscles = 'BACK' WHERE name = 'Good Morning'")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Convert baselineWeight from 10RM to 1RM: 1RM = 10RM * (1 + 10/30) = 10RM * 4/3
                // Rounded to nearest 0.5 kg increment to match internal storage convention.
                db.execSQL("""
                    UPDATE muscle_group_strength
                    SET baselineWeight = ROUND(baselineWeight * 4.0 / 3.0 / 0.5) * 0.5
                """)
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `baseline_change_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER NOT NULL,
                        `muscleGroup` TEXT NOT NULL,
                        `previousBaseline` REAL NOT NULL,
                        `newBaseline` REAL NOT NULL,
                        `changeReason` TEXT NOT NULL,
                        `feedbacks` TEXT,
                        `sessionReps` INTEGER,
                        `minReductionFraction` REAL,
                        `timestamp` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `coefficient_change_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `exerciseId` INTEGER NOT NULL,
                        `previousCoefficient` REAL,
                        `coefficient` REAL NOT NULL,
                        `heuristicName` TEXT NOT NULL,
                        `heuristicMetadata` TEXT,
                        `computedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_coefficient_change_log_exerciseId` ON `coefficient_change_log` (`exerciseId`)")
            }
        }

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_sets ADD COLUMN actualReps INTEGER")
            }
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN actualRepsBackfilled INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Reshapes v11 → v12:
        //   - user_profile: drop actualRepsBackfilled (and prior derivedStateVersion column that was
        //     added in an old v12 migration); replace with the clean 5-column schema.
        //   - baseline_override: new input table (user-authored initial baselines + manual overrides).
        //   - exercise_hurt_state: new input table (hurt flag lifted out of exercises).
        //   - exercises: drop hurtFlag (recreate-table, since SQLite 3.32 lacks DROP COLUMN).
        //   - baseline_change_log → baseline_history: rename derived-state table.
        //   - coefficient_change_log → coefficient_history: rename derived-state table.
        //   - Migrate MANUAL_OVERRIDE rows from baseline_change_log to baseline_override.
        //   - Synthesize initial baseline rows per muscle into baseline_override.
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. user_profile: drop actualRepsBackfilled, recreate-table.
                db.execSQL("""
                    CREATE TABLE `user_profile_new` (
                        `id` INTEGER NOT NULL,
                        `sex` TEXT NOT NULL,
                        `strengthLevel` TEXT NOT NULL,
                        `weightUnit` TEXT NOT NULL,
                        `preferredExerciseCount` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO user_profile_new (id, sex, strengthLevel, weightUnit, preferredExerciseCount)
                        SELECT id, sex, strengthLevel, weightUnit, preferredExerciseCount FROM user_profile
                """.trimIndent())
                db.execSQL("DROP TABLE user_profile")
                db.execSQL("ALTER TABLE user_profile_new RENAME TO user_profile")

                // 2. baseline_override (new input table).
                db.execSQL("""
                    CREATE TABLE `baseline_override` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER,
                        `muscleGroup` TEXT NOT NULL,
                        `baselineWeight` REAL NOT NULL,
                        `asOf` INTEGER NOT NULL
                    )
                """.trimIndent())

                // 3. Move MANUAL_OVERRIDE rows from baseline_change_log to baseline_override.
                db.execSQL("""
                    INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf)
                        SELECT sessionId, muscleGroup, newBaseline, timestamp
                        FROM baseline_change_log
                        WHERE changeReason = 'MANUAL_OVERRIDE'
                """.trimIndent())

                // 4. Synthesize initial baselines per muscle.
                db.execSQL("""
                    INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf)
                        SELECT NULL, b1.muscleGroup, b1.previousBaseline, 0
                        FROM baseline_change_log b1
                        WHERE b1.id = (
                            SELECT MIN(b2.id) FROM baseline_change_log b2
                            WHERE b2.muscleGroup = b1.muscleGroup
                        )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf)
                        SELECT NULL, muscleGroup, baselineWeight, 0
                        FROM muscle_group_strength
                        WHERE muscleGroup NOT IN (SELECT DISTINCT muscleGroup FROM baseline_change_log)
                """.trimIndent())

                // 5. exercise_hurt_state (new input table).
                db.execSQL("""
                    CREATE TABLE `exercise_hurt_state` (
                        `exerciseId` INTEGER PRIMARY KEY NOT NULL,
                        `isHurt` INTEGER NOT NULL,
                        `asOf` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO exercise_hurt_state (exerciseId, isHurt, asOf)
                        SELECT id, hurtFlag, 0 FROM exercises WHERE hurtFlag = 1
                """.trimIndent())

                // 6. exercises: drop hurtFlag (recreate-table).
                // Column list from v11 11.json: id, name, primaryMuscle, secondaryMuscles, equipment,
                // isDisliked, hurtFlag, isUnilateral, isTimed
                db.execSQL("""
                    CREATE TABLE `exercises_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `primaryMuscle` TEXT NOT NULL,
                        `secondaryMuscles` TEXT NOT NULL,
                        `equipment` TEXT NOT NULL,
                        `isDisliked` INTEGER NOT NULL,
                        `isUnilateral` INTEGER NOT NULL,
                        `isTimed` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO exercises_new (id, name, primaryMuscle, secondaryMuscles, equipment, isDisliked, isUnilateral, isTimed)
                        SELECT id, name, primaryMuscle, secondaryMuscles, equipment, isDisliked, isUnilateral, isTimed FROM exercises
                """.trimIndent())
                db.execSQL("DROP TABLE exercises")
                db.execSQL("ALTER TABLE exercises_new RENAME TO exercises")

                // 7. Remove migrated MANUAL_OVERRIDE rows from the history table.
                db.execSQL("DELETE FROM baseline_change_log WHERE changeReason = 'MANUAL_OVERRIDE'")

                // 8. Recreate derived-state tables with correct schema.
                // baseline_change_log had sessionId NOT NULL; baseline_history needs it nullable.
                // A simple RENAME would preserve the NOT NULL constraint, so we must recreate.
                db.execSQL("""
                    CREATE TABLE `baseline_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sessionId` INTEGER,
                        `muscleGroup` TEXT NOT NULL,
                        `previousBaseline` REAL NOT NULL,
                        `newBaseline` REAL NOT NULL,
                        `changeReason` TEXT NOT NULL,
                        `feedbacks` TEXT,
                        `sessionReps` INTEGER,
                        `minReductionFraction` REAL,
                        `timestamp` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO baseline_history (id, sessionId, muscleGroup, previousBaseline, newBaseline, changeReason, feedbacks, sessionReps, minReductionFraction, timestamp)
                        SELECT id, sessionId, muscleGroup, previousBaseline, newBaseline, changeReason, feedbacks, sessionReps, minReductionFraction, timestamp
                        FROM baseline_change_log
                """.trimIndent())
                db.execSQL("DROP TABLE baseline_change_log")

                // coefficient_change_log → coefficient_history: RENAME would leave the old index
                // name (index_coefficient_change_log_exerciseId); Room v12 expects
                // index_coefficient_history_exerciseId. Recreate to get the right index name.
                db.execSQL("""
                    CREATE TABLE `coefficient_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `exerciseId` INTEGER NOT NULL,
                        `previousCoefficient` REAL,
                        `coefficient` REAL NOT NULL,
                        `heuristicName` TEXT NOT NULL,
                        `heuristicMetadata` TEXT,
                        `computedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_coefficient_history_exerciseId` ON `coefficient_history` (`exerciseId`)")
                db.execSQL("""
                    INSERT INTO coefficient_history (id, exerciseId, previousCoefficient, coefficient, heuristicName, heuristicMetadata, computedAt)
                        SELECT id, exerciseId, previousCoefficient, coefficient, heuristicName, heuristicMetadata, computedAt
                        FROM coefficient_change_log
                """.trimIndent())
                db.execSQL("DROP TABLE coefficient_change_log")
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE baseline_history ADD COLUMN heuristicName TEXT")
                db.execSQL("ALTER TABLE baseline_history ADD COLUMN heuristicMetadata TEXT")
            }
        }

        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS muscle_group_strength")
                db.execSQL("DROP TABLE IF EXISTS baseline_history")
                db.execSQL("DROP TABLE IF EXISTS coefficient_history")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context, scope: CoroutineScope): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, scope).also { INSTANCE = it }
            }

        fun reset(context: Context, scope: CoroutineScope): AppDatabase {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
            context.deleteDatabase("stochastic_strength.db")
            return getInstance(context, scope)
        }

        private fun buildDatabase(context: Context, scope: CoroutineScope) =
            Room.databaseBuilder(context, AppDatabase::class.java, "stochastic_strength.db")
                .addMigrations(
                    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                )
                .build()
    }
}
