package io.github.fowles.stochastic_strength.data.seed

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup

object ExerciseLibrary {
    val exercises = listOf(
        // CHEST
        Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, secondaryMuscles = listOf(MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS), equipment = Equipment.BARBELL),
        Exercise(name = "Incline Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, secondaryMuscles = listOf(MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS), equipment = Equipment.BARBELL),
        Exercise(name = "Dumbbell Fly", primaryMuscle = MuscleGroup.CHEST, secondaryMuscles = emptyList(), equipment = Equipment.DUMBBELL),
        Exercise(name = "Push-Up", primaryMuscle = MuscleGroup.CHEST, secondaryMuscles = listOf(MuscleGroup.SHOULDERS, MuscleGroup.TRICEPS), equipment = Equipment.BODYWEIGHT),
        Exercise(name = "Cable Chest Fly", primaryMuscle = MuscleGroup.CHEST, secondaryMuscles = emptyList(), equipment = Equipment.CABLE_MACHINE),
        // BACK
        Exercise(name = "Barbell Row", primaryMuscle = MuscleGroup.BACK, secondaryMuscles = listOf(MuscleGroup.BICEPS), equipment = Equipment.BARBELL),
        Exercise(name = "Pull-Up", primaryMuscle = MuscleGroup.BACK, secondaryMuscles = listOf(MuscleGroup.BICEPS), equipment = Equipment.BODYWEIGHT),
        Exercise(name = "Lat Pulldown", primaryMuscle = MuscleGroup.BACK, secondaryMuscles = listOf(MuscleGroup.BICEPS), equipment = Equipment.CABLE_MACHINE),
        Exercise(name = "Seated Cable Row", primaryMuscle = MuscleGroup.BACK, secondaryMuscles = listOf(MuscleGroup.BICEPS), equipment = Equipment.CABLE_MACHINE),
        Exercise(name = "Dumbbell Row", primaryMuscle = MuscleGroup.BACK, secondaryMuscles = listOf(MuscleGroup.BICEPS), equipment = Equipment.DUMBBELL),
        Exercise(name = "Face Pull", primaryMuscle = MuscleGroup.BACK, secondaryMuscles = listOf(MuscleGroup.SHOULDERS), equipment = Equipment.CABLE_MACHINE),
        // SHOULDERS
        Exercise(name = "Overhead Press", primaryMuscle = MuscleGroup.SHOULDERS, secondaryMuscles = listOf(MuscleGroup.TRICEPS), equipment = Equipment.BARBELL),
        Exercise(name = "Dumbbell Lateral Raise", primaryMuscle = MuscleGroup.SHOULDERS, secondaryMuscles = emptyList(), equipment = Equipment.DUMBBELL),
        Exercise(name = "Dumbbell Overhead Press", primaryMuscle = MuscleGroup.SHOULDERS, secondaryMuscles = listOf(MuscleGroup.TRICEPS), equipment = Equipment.DUMBBELL),
        Exercise(name = "Arnold Press", primaryMuscle = MuscleGroup.SHOULDERS, secondaryMuscles = emptyList(), equipment = Equipment.DUMBBELL),
        // BICEPS
        Exercise(name = "Barbell Curl", primaryMuscle = MuscleGroup.BICEPS, secondaryMuscles = emptyList(), equipment = Equipment.BARBELL),
        Exercise(name = "Dumbbell Curl", primaryMuscle = MuscleGroup.BICEPS, secondaryMuscles = emptyList(), equipment = Equipment.DUMBBELL),
        Exercise(name = "Cable Curl", primaryMuscle = MuscleGroup.BICEPS, secondaryMuscles = emptyList(), equipment = Equipment.CABLE_MACHINE),
        // TRICEPS
        Exercise(name = "Tricep Pushdown", primaryMuscle = MuscleGroup.TRICEPS, secondaryMuscles = emptyList(), equipment = Equipment.CABLE_MACHINE),
        Exercise(name = "Skull Crusher", primaryMuscle = MuscleGroup.TRICEPS, secondaryMuscles = emptyList(), equipment = Equipment.BARBELL),
        Exercise(name = "Dips", primaryMuscle = MuscleGroup.TRICEPS, secondaryMuscles = listOf(MuscleGroup.CHEST, MuscleGroup.SHOULDERS), equipment = Equipment.BODYWEIGHT),
        Exercise(name = "Overhead Tricep Extension", primaryMuscle = MuscleGroup.TRICEPS, secondaryMuscles = emptyList(), equipment = Equipment.DUMBBELL),
        // QUADS
        Exercise(name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, secondaryMuscles = listOf(MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS), equipment = Equipment.BARBELL),
        Exercise(name = "Leg Press", primaryMuscle = MuscleGroup.QUADS, secondaryMuscles = listOf(MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS), equipment = Equipment.MACHINE),
        Exercise(name = "Lunge", primaryMuscle = MuscleGroup.QUADS, secondaryMuscles = listOf(MuscleGroup.GLUTES, MuscleGroup.HAMSTRINGS), equipment = Equipment.BODYWEIGHT),
        Exercise(name = "Goblet Squat", primaryMuscle = MuscleGroup.QUADS, secondaryMuscles = listOf(MuscleGroup.GLUTES), equipment = Equipment.DUMBBELL),
        // HAMSTRINGS
        Exercise(name = "Romanian Deadlift", primaryMuscle = MuscleGroup.HAMSTRINGS, secondaryMuscles = listOf(MuscleGroup.GLUTES), equipment = Equipment.BARBELL),
        Exercise(name = "Leg Curl", primaryMuscle = MuscleGroup.HAMSTRINGS, secondaryMuscles = emptyList(), equipment = Equipment.MACHINE),
        Exercise(name = "Stiff-Leg Deadlift", primaryMuscle = MuscleGroup.HAMSTRINGS, secondaryMuscles = listOf(MuscleGroup.GLUTES), equipment = Equipment.DUMBBELL),
        // GLUTES
        Exercise(name = "Hip Thrust", primaryMuscle = MuscleGroup.GLUTES, secondaryMuscles = listOf(MuscleGroup.HAMSTRINGS), equipment = Equipment.BARBELL),
        Exercise(name = "Cable Kickback", primaryMuscle = MuscleGroup.GLUTES, secondaryMuscles = emptyList(), equipment = Equipment.CABLE_MACHINE),
        // CALVES
        Exercise(name = "Standing Calf Raise", primaryMuscle = MuscleGroup.CALVES, secondaryMuscles = emptyList(), equipment = Equipment.BODYWEIGHT),
        Exercise(name = "Seated Calf Raise", primaryMuscle = MuscleGroup.CALVES, secondaryMuscles = emptyList(), equipment = Equipment.MACHINE),
        // CORE
        Exercise(name = "Plank", primaryMuscle = MuscleGroup.CORE, secondaryMuscles = emptyList(), equipment = Equipment.BODYWEIGHT),
        Exercise(name = "Hanging Leg Raise", primaryMuscle = MuscleGroup.CORE, secondaryMuscles = emptyList(), equipment = Equipment.BODYWEIGHT),
        Exercise(name = "Cable Crunch", primaryMuscle = MuscleGroup.CORE, secondaryMuscles = emptyList(), equipment = Equipment.CABLE_MACHINE),
    )
}
