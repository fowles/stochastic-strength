package io.github.fowles.stochastic_strength.domain.history

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint
import kotlin.math.roundToInt
import kotlin.random.Random

enum class HighlightKind { LIFT, MUSCLE }

data class HighlightSeries(
    val subject: String,
    val muscle: MuscleGroup?,
    val points: List<ProgressionPoint>,
    val kind: HighlightKind,
)

data class HighlightConfig(
    val monthWindowMs: Long = 30L * 24 * 3600 * 1000,
    val quarterWindowMs: Long = 90L * 24 * 3600 * 1000,
    val liftMinGainKg: Float = 2f,
    val muscleMinGainFraction: Float = 0.03f,
    val quipOnlyProbability: Float = 0.25f,
    val appendQuipProbability: Float = 0.4f,
)

/** A gym non-sequitur in the Yoked-Galileo voice. Muscle-keyed quips only attach to that muscle. */
data class Quip(val text: String, val muscle: MuscleGroup? = null)

private data class Window(val ms: Long, val label: String)
private data class Candidate(val text: String, val muscle: MuscleGroup?)

object HistoryHighlight {

    val QUIPS: List<Quip> = listOf(
        Quip("The bar does not care about your feelings. Add weight to it anyway."),
        Quip("Somewhere, Yoked Galileo is proud of you."),
        Quip("Diesel Tycho Brahe measured the heavens. You measure the gains."),
        Quip("The iron never lies, and today it says nice things."),
        Quip("Gravity filed a complaint. Ignore it."),
        Quip("You cannot flex a spreadsheet. Go lift something."),
        Quip("Way to nail the vanity lifts!", muscle = MuscleGroup.BICEPS),
        Quip("Beach muscles, activated.", muscle = MuscleGroup.BICEPS),
        Quip("Nobody skips this day. Respect.", muscle = MuscleGroup.QUADS),
    )

    private val genericQuips = QUIPS.filter { it.muscle == null }

    fun pick(
        series: List<HighlightSeries>,
        weightUnit: WeightUnit,
        nowMs: Long,
        random: Random,
        config: HighlightConfig = HighlightConfig(),
    ): String {
        val windows = listOf(
            Window(config.monthWindowMs, "this month"),
            Window(config.quarterWindowMs, "this quarter"),
        )
        val candidates = series.flatMap { s ->
            windows.mapNotNull { w -> candidate(s, w, weightUnit, nowMs, config) }
        }

        // Playful: sometimes (or always, when nothing qualifies) just show a standalone quip.
        if (candidates.isEmpty() || random.nextFloat() < config.quipOnlyProbability) {
            return genericQuips.random(random).text
        }

        val chosen = candidates.random(random)
        if (random.nextFloat() < config.appendQuipProbability) {
            val eligible = QUIPS.filter { it.muscle == null || it.muscle == chosen.muscle }
            return "${chosen.text} ${eligible.random(random).text}"
        }
        return chosen.text
    }

    private fun candidate(
        s: HighlightSeries,
        w: Window,
        unit: WeightUnit,
        nowMs: Long,
        config: HighlightConfig,
    ): Candidate? {
        val latest = s.points.lastOrNull { it.timestampMs <= nowMs } ?: return null
        val baseline = s.points.lastOrNull { it.timestampMs <= nowMs - w.ms } ?: return null
        val gain = latest.value - baseline.value
        return when (s.kind) {
            HighlightKind.LIFT -> {
                if (gain < config.liftMinGainKg) return null
                Candidate("Your ${s.subject} is up ${WeightFormatter.format(gain, unit)} ${w.label}.", s.muscle)
            }
            HighlightKind.MUSCLE -> {
                if (baseline.value <= 0f) return null
                val frac = gain / baseline.value
                if (frac < config.muscleMinGainFraction) return null
                val pct = (frac * 100f).roundToInt()
                Candidate("Your ${s.subject} is up $pct% ${w.label}.", s.muscle)
            }
        }
    }
}
