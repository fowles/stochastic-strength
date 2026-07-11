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
    private val config: EstimatorConfig = EstimatorConfig(),
    // updater and projector DERIVE from [config] by default so a candidate/fitted config actually
    // reaches the belief folds (processNoise, detrain, adaptive, sigma bounds) and the pooling (τ),
    // not just SetObservation's fatigue. Constructing them with default config here silently made the
    // fitter blind to every parameter except fatiguePerSet.
    private val updater: BeliefUpdater = BeliefUpdater(config),
    private val projector: MuscleStrengthProjector = MuscleStrengthProjector(config),
    private val scorer: PredictiveScoreAccumulator? = null,
) {
    data class MuscleStep(val muscle: MuscleGroup, val projection: MuscleProjection)
    data class StepResult(val steps: List<MuscleStep>)

    fun step(sets: List<WorkoutSet>, snapshot: ReplaySnapshot, asOf: Long): StepResult {
        if (sets.isEmpty()) return StepResult(emptyList())

        val byExercise = sets.groupBy { it.exerciseId }
            .filter { (id, _) -> (snapshot.seedCoefficients[id] ?: 0f) > 0f && snapshot.currentBeliefs[id] != null }

        // Pre-session pooled prediction per exercise (offset-free), computed once from start-of-session
        // beliefs — the basis for both the day-offset residual (Pass 1) and predictive scoring.
        data class Pred(val meanLn: Float, val cleanVar: Float)
        val pred: Map<Long, Pred> = byExercise.keys.mapNotNull { id ->
            val muscle = snapshot.exerciseMuscle[id] ?: return@mapNotNull null
            val ids = snapshot.muscleExerciseIds[muscle] ?: return@mapNotNull null
            val proj = projector.project(
                beliefs = snapshot.currentBeliefs, seedCoef = snapshot.seedCoefficients,
                muscleExerciseIds = ids, now = asOf,
                muscleLastObs = snapshot.muscleLastObs[muscle], equipment = snapshot.exerciseEquipment,
            )
            val meanLn = proj.effectiveE1rm[id]?.let { kotlin.math.ln(it) } ?: return@mapNotNull null
            val cleanVar = updater.age(snapshot.currentBeliefs[id]!!, asOf, snapshot.muscleLastObs[muscle]).evidenceVar
            id to Pred(meanLn, cleanVar)
        }.toMap()

        // Pass 1: estimate the shared session day-offset from all load-bearing residuals.
        val residuals = mutableListOf<SessionDayEffect.Residual>()
        for ((id, exSets) in byExercise) {
            val p = pred[id] ?: continue
            exSets.sortedBy { it.setNumber }.forEachIndexed { i, set ->
                val obs = SetObservation.from(set, fatigueRank = i + 1, config = config) ?: return@forEachIndexed
                residuals += SessionDayEffect.Residual(
                    value = obsLocation(obs) - p.meanLn,
                    obsVar = p.cleanVar + obs.noiseSd * obs.noiseSd,
                )
            }
        }
        val day = SessionDayEffect.estimate(config.sessionDayEffectSd, residuals)

        // Pass 2: fold each exercise, shifting the observation by −day.mean and marginalizing day.variance
        // into the observation noise. day = (0,0) when σ_day = 0 ⇒ identical to the prior model.
        val affectedMuscles = mutableSetOf<MuscleGroup>()
        for ((id, exSets) in byExercise) {
            var belief = snapshot.currentBeliefs[id]!!
            val muscleLast = snapshot.exerciseMuscle[id]?.let { snapshot.muscleLastObs[it] }
            val p = pred[id]
            var folded = false
            exSets.sortedBy { it.setNumber }.forEachIndexed { i, set ->
                val obs = SetObservation.from(set, fatigueRank = i + 1, config = config) ?: return@forEachIndexed
                if (scorer != null && p != null) {
                    scorer.accumulate(shiftObs(obs, -day.mean), p.meanLn, p.cleanVar + day.variance)
                }
                val infNoise = kotlin.math.sqrt(obs.noiseSd * obs.noiseSd + day.variance)
                belief = if (obs.gaussianLn != null) {
                    updater.foldGaussian(belief, obs.gaussianLn - day.mean, infNoise, asOf, muscleLast)
                } else {
                    updater.foldCensored(belief, obs.lowerLn?.minus(day.mean), obs.upperLn?.minus(day.mean), infNoise, asOf, muscleLast)
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
                    beliefs = snapshot.currentBeliefs, seedCoef = snapshot.seedCoefficients,
                    muscleExerciseIds = exerciseIds, now = asOf,
                    muscleLastObs = snapshot.muscleLastObs[m], equipment = snapshot.exerciseEquipment,
                ),
            )
        }
        return StepResult(steps)
    }

    /** Point location of an observation on ln(fresh-1RM): counted point, else interval midpoint, else the finite bound. */
    private fun obsLocation(obs: SetObservation): Float = when {
        obs.gaussianLn != null -> obs.gaussianLn
        obs.lowerLn != null && obs.upperLn != null -> (obs.lowerLn + obs.upperLn) / 2f
        obs.lowerLn != null -> obs.lowerLn
        obs.upperLn != null -> obs.upperLn
        else -> 0f
    }

    /** An observation with every populated bound/point shifted by [delta] (day-offset removal for scoring). */
    private fun shiftObs(obs: SetObservation, delta: Float): SetObservation = obs.copy(
        lowerLn = obs.lowerLn?.plus(delta),
        upperLn = obs.upperLn?.plus(delta),
        gaussianLn = obs.gaussianLn?.plus(delta),
    )
}
