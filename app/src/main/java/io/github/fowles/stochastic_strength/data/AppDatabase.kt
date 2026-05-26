package io.github.fowles.stochastic_strength.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.github.fowles.stochastic_strength.data.dao.ExerciseDao
import io.github.fowles.stochastic_strength.data.dao.ExerciseStateDao
import io.github.fowles.stochastic_strength.data.dao.KnownLocationDao
import io.github.fowles.stochastic_strength.data.dao.LocationEquipmentDao
import io.github.fowles.stochastic_strength.data.dao.MuscleGroupStrengthDao
import io.github.fowles.stochastic_strength.data.dao.UserProfileDao
import io.github.fowles.stochastic_strength.data.dao.WorkoutSessionDao
import io.github.fowles.stochastic_strength.data.dao.WorkoutSetDao
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseState
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationEquipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope

@Database(
    entities = [
        Exercise::class,
        KnownLocation::class,
        LocationEquipment::class,
        WorkoutSession::class,
        WorkoutSet::class,
        ExerciseState::class,
        UserProfile::class,
        MuscleGroupStrength::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun knownLocationDao(): KnownLocationDao
    abstract fun locationEquipmentDao(): LocationEquipmentDao
    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun workoutSetDao(): WorkoutSetDao
    abstract fun exerciseStateDao(): ExerciseStateDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun muscleGroupStrengthDao(): MuscleGroupStrengthDao

    companion object {
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN preferredExerciseCount INTEGER")
            }
        }

        private fun buildDatabase(context: Context, scope: CoroutineScope) =
            Room.databaseBuilder(context, AppDatabase::class.java, "stochastic_strength.db")
                .addMigrations(MIGRATION_3_4)
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
