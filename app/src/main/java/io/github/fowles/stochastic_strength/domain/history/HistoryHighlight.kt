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
        Quip("Swole Archimedes said give me a barbell long enough and I will squat the earth."),
        Quip("Jacked Newton's third law: for every rep, an equal and opposite gain."),
        Quip("Ripped Copernicus was right — everything revolves around leg day."),
        Quip("Buff Kepler plotted your progress. It's elliptical. It's beautiful."),
        Quip("Shredded Darwin calls this natural selection. The weak weights got left behind."),
        Quip("Massive Mendel bred these gains over generations. You did it in weeks."),
        Quip("Yoked Euclid proved it: the shortest path to strength is straight through the bar."),
        Quip("Herculean Heisenberg is certain about exactly one thing: you showed up."),
        Quip("The plates whisper among themselves. They fear you now."),
        Quip("Rest days are just gains loading. Trust the process."),
        Quip("Entropy increases. So do your numbers. Coincidence? Diesel Boltzmann thinks not."),
        Quip("Your muscles wrote this. We just typed it."),
        Quip("Consistency is a flex nobody can see. Until they can."),
        Quip("The gym owes you nothing and paid you anyway."),
        Quip("Statistically significant gains, p < 0.05, bro."),
        Quip("Stacked Galois did his best work under pressure. So do you."),
        Quip("Somewhere a barbell is telling its friends about you."),
        Quip("Way to nail the vanity lifts!", muscle = MuscleGroup.BICEPS),
        Quip("Beach muscles, activated.", muscle = MuscleGroup.BICEPS),
        Quip("The gun show has extended its residency.", muscle = MuscleGroup.BICEPS),
        Quip("Two tickets validated.", muscle = MuscleGroup.BICEPS),
        Quip("Nobody skips this day. Respect.", muscle = MuscleGroup.QUADS),
        Quip("The squat rack remembers everything. Today it remembers greatness.", muscle = MuscleGroup.QUADS),
        Quip("Stairs are about to get personal.", muscle = MuscleGroup.QUADS),
        Quip("Pecs appeal, quantified.", muscle = MuscleGroup.CHEST),
        Quip("Brawny Brunel built bridges. You built a chest. Same discipline.", muscle = MuscleGroup.CHEST),
        Quip("Wings acquired. Flight pending.", muscle = MuscleGroup.BACK),
        Quip("Rows today, no rows to hoe tomorrow.", muscle = MuscleGroup.BACK),
        Quip("Boulder shoulders: geology in progress.", muscle = MuscleGroup.SHOULDERS),
        Quip("Doorframes fear you now.", muscle = MuscleGroup.SHOULDERS),
        Quip("Horseshoes without the horse. Swole Lavoisier approves of the conservation.", muscle = MuscleGroup.TRICEPS),
        Quip("Three heads are better than one.", muscle = MuscleGroup.TRICEPS),
        Quip("The posterior chain is undefeated.", muscle = MuscleGroup.HAMSTRINGS),
        Quip("Hinge like nobody's watching.", muscle = MuscleGroup.HAMSTRINGS),
        Quip("The engine room got an upgrade.", muscle = MuscleGroup.GLUTES),
        Quip("Powerhouse status: confirmed.", muscle = MuscleGroup.GLUTES),
        Quip("Calves of steel, patience of a saint.", muscle = MuscleGroup.CALVES),
        Quip("The most stubborn muscle finally blinked.", muscle = MuscleGroup.CALVES),
        Quip("Abs are made in the kitchen but forged right here.", muscle = MuscleGroup.CORE),
        Quip("Your trunk is now load-bearing architecture.", muscle = MuscleGroup.CORE),
    )

    private val genericQuips = QUIPS.filter { it.muscle == null }

    private val PLURAL_MUSCLES = setOf(
        MuscleGroup.SHOULDERS, MuscleGroup.BICEPS, MuscleGroup.TRICEPS,
        MuscleGroup.QUADS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES,
    )

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

        // A stat always brings a quip along.
        val chosen = candidates.random(random)
        val eligible = QUIPS.filter { it.muscle == null || it.muscle == chosen.muscle }
        return "${chosen.text} ${eligible.random(random).text}"
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
                val verb = if (s.muscle in PLURAL_MUSCLES) "are" else "is"
                Candidate("Your ${s.subject.lowercase()} $verb up $pct% ${w.label}.", s.muscle)
            }
        }
    }
}
