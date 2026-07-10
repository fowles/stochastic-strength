package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot

/**
 * Pure per-session core: sequential per-set belief folds (spec §2) → projection of each affected
 * muscle. Mutates [ReplaySnapshot.currentBeliefs] and [ReplaySnapshot.muscleLastObs] in place.
 * HURT never touches beliefs (policy-only). Drift during the folds is keyed on the muscle clock
 * BEFORE this session; the clock advances after all of the session's folds.
 */
class SessionProgressionStepper(
    private val updater: BeliefUpdater = BeliefUpdater(),
    private val projector: MuscleStrengthProjector = MuscleStrengthProjector(),
    private val config: EstimatorConfig = EstimatorConfig(),
    private val scorer: PredictiveScoreAccumulator? = null,
) {
    data class MuscleStep(val muscle: MuscleGroup, val projection: MuscleProjection)
    data class StepResult(val steps: List<MuscleStep>)

    fun step(sets: List<WorkoutSet>, snapshot: ReplaySnapshot, asOf: Long): StepResult {
        if (sets.isEmpty()) return StepResult(emptyList())

        val affectedMuscles = mutableSetOf<MuscleGroup>()
        sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
            if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@forEach
            var belief = snapshot.currentBeliefs[id] ?: return@forEach
            val muscleLast = snapshot.exerciseMuscle[id]?.let { snapshot.muscleLastObs[it] }
            // Pre-fold pooled prediction for scoring (spec §2): half-blended mean μ̃ from the projector,
            // clean own variance aged to asOf. Computed once per exercise, before its own sets fold.
            var predMeanLn: Float? = null
            var predCleanVar = 0f
            if (scorer != null) {
                val muscle = snapshot.exerciseMuscle[id]
                val ids = muscle?.let { snapshot.muscleExerciseIds[it] }
                if (ids != null) {
                    val proj = projector.project(
                        beliefs = snapshot.currentBeliefs, seedCoef = snapshot.seedCoefficients,
                        muscleExerciseIds = ids, now = asOf,
                        muscleLastObs = snapshot.muscleLastObs[muscle], equipment = snapshot.exerciseEquipment,
                    )
                    predMeanLn = proj.effectiveE1rm[id]?.let { kotlin.math.ln(it) }
                    predCleanVar = updater.age(belief, asOf, muscleLast).evidenceVar
                }
            }
            var folded = false
            exSets.sortedBy { it.setNumber }.forEachIndexed { i, set ->
                val obs = SetObservation.from(set, fatigueRank = i + 1, config = config) ?: return@forEachIndexed
                if (scorer != null && predMeanLn != null) scorer.accumulate(obs, predMeanLn, predCleanVar)
                belief = if (obs.gaussianLn != null) {
                    updater.foldGaussian(belief, obs.gaussianLn, obs.noiseSd, asOf, muscleLast)
                } else {
                    updater.foldCensored(belief, obs.lowerLn, obs.upperLn, obs.noiseSd, asOf, muscleLast)
                }
                folded = true
            }
            if (folded) {
                snapshot.currentBeliefs[id] = belief
                snapshot.exerciseMuscle[id]?.let { affectedMuscles.add(it) }
            }
        }
        for (m in affectedMuscles) snapshot.muscleLastObs[m] = asOf

        val steps = affectedMuscles.mapNotNull { m ->
            val exerciseIds = snapshot.muscleExerciseIds[m] ?: return@mapNotNull null
            MuscleStep(
                muscle = m,
                projection = projector.project(
                    beliefs = snapshot.currentBeliefs,
                    seedCoef = snapshot.seedCoefficients,
                    muscleExerciseIds = exerciseIds,
                    now = asOf,
                    muscleLastObs = snapshot.muscleLastObs[m],
                    equipment = snapshot.exerciseEquipment,
                ),
            )
        }
        return StepResult(steps)
    }
}
