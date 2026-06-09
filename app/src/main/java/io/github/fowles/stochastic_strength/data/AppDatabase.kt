package io.github.fowles.stochastic_strength.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.fowles.stochastic_strength.data.dao.ExerciseDao
import io.github.fowles.stochastic_strength.data.dao.KnownLocationDao
import io.github.fowles.stochastic_strength.data.dao.LocationExcludedExerciseDao
import io.github.fowles.stochastic_strength.data.dao.MuscleGroupStrengthDao
import io.github.fowles.stochastic_strength.data.dao.UserProfileDao
import io.github.fowles.stochastic_strength.data.dao.WorkoutSessionDao
import io.github.fowles.stochastic_strength.data.dao.WorkoutSetDao
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
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
        MuscleGroupStrength::class,
    ],
    version = 7,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun knownLocationDao(): KnownLocationDao
    abstract fun locationExcludedExerciseDao(): LocationExcludedExerciseDao
    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun workoutSetDao(): WorkoutSetDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun muscleGroupStrengthDao(): MuscleGroupStrengthDao

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
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
    }
}
