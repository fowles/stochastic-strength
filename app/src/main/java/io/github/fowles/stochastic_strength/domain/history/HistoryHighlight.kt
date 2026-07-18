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
    val exerciseId: Long? = null,
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
        Quip("Diesel Tycho Brahe measured your gains."),
        Quip("The iron never lies, and today it says nice things."),
        Quip("Gravity filed a complaint. Ignore it."),
        Quip("You cannot flex a spreadsheet. Go lift something."),
        Quip("Swole Archimedes said give me a barbell long enough and I will squat the earth."),
        Quip("Jacked Newton's third law: for every rep, an equal and opposite gain."),
        Quip("Ripped Copernicus was right — everything revolves around leg day."),
        Quip("Buff Kepler plotted your progress. It's elliptical. It's beautiful."),
        Quip("Shredded Darwin calls this natural selection. The weak weights got left behind."),
        Quip("Adapt or plateau. — Shredded Darwin, On the Origin of Gains"),
        Quip("Massive Mendel bred these gains over generations. You did it in weeks."),
        Quip("Massive Mendel ran the cross: gains are a dominant trait."),
        Quip("In Massive Mendel's garden, the peas do farmer's carries."),
        Quip("Yoked Euclid proved it: the shortest path to strength is straight through the bar."),
        Quip("Herculean Heisenberg is certain about exactly one thing: you showed up."),
        Quip("The plates whisper among themselves. They fear you now."),
        Quip("Rest days are just gains loading. Trust the process."),
        Quip("Entropy increases. So do your numbers. Coincidence? Burly Boltzmann thinks not."),
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
        Quip("Brawny Brunel has certified your chest as load-bearing.", muscle = MuscleGroup.CHEST),
        Quip("Load-Tested Daedalus programs the reverse fly. The forward fly went poorly for his son.", muscle = MuscleGroup.BACK),
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
        Quip("Newton's first law: a lifter at rest tends to scroll. A lifter in motion tends to gain."),
        Quip("Farm-Strong Fibonacci added the last two sets and got a bigger one."),
        Quip("Swole Pythagoras confirms: the sum of the squats equals the size of the quads."),
        Quip("Ripped Faraday induced a current. You induced a pump."),
        Quip("Buff Pascal's wager: if gains are real, you win everything. If not, you're still jacked. Lift."),
        Quip("Jacked Ada Lovelace wrote the first program: sleep, eat, lift, repeat."),
        Quip("Your pump is glowing. Massive Marie Curie would like a sample."),
        Quip("That post-set glow just registered on Massive Marie Curie's instruments."),
        Quip("The barbell is just a very honest friend."),
        Quip("Somewhere a dumbbell is name-dropping you at a party."),
        Quip("The safety pins are getting restless. Add more weight."),
        Quip("New plates get told about you on their first day."),
        Quip("A dumbbell is writing you into its memoir."),
        Quip("The kettlebells huddle closer when you walk past. They want to be picked."),
        Quip("Yoked Tesla wirelessly transmitted encouragement across time. Received."),
        Quip("Shredded Schrödinger's gains are there and not there until you check the mirror. You checked. They're there."),
        Quip("Dense Descartes: I lift, therefore I am."),
        Quip("The mitochondria is the powerhouse of the cell. You are the powerhouse of the gym."),
        Quip("Stacked Turing solved the halting problem: you don't."),
        Quip("Broscience says the pump lasts forever. Broscience is wrong, but we love it anyway."),
        Quip("Your future self already sent a thank-you note. Time travel is real if you lift."),
        Quip("Gains are a rumor you keep confirming."),
        Quip("Anabolic Avogadro counted your reps. Approximately 6.022 x 10^23, give or take."),
        Quip("The foam roller forgives all sins."),
        Quip("Ripped Ramanujan saw the pattern in a dream: more plates."),
        Quip("Discipline is just motivation with a mortgage."),
        Quip("Swole Sagan: we are all made of star stuff, but yours is denser now."),
        Quip("The scale is a liar and the mirror is a poet. Trust the poet."),
        Quip("Jacked Gauss summed 1 to 100 reps. You can too."),
        Quip("The hardest lift is the front door of the gym. You cleared it."),
        Quip("Vascular da Vinci sketched the perfect proportions. You're editing his draft."),
        Quip("Rest is not a reward, it's a requirement. Burly Boltzmann insists."),
        Quip("Your grip strength is applying for its own zip code."),
        Quip("Buff Hypatia mapped a constellation of your PRs."),
        Quip("If you refuse to lift until the numbers are checked by hand, Colossal Katherine Johnson has you covered."),
        Quip("Every set is a care package addressed to future you."),
        Quip("Beefy al-Khwarizmi created algebra to track your gains."),
        Quip("Even Chiseled Chien-Shiung Wu lifts with both hands."),
        Quip("Titanic Tu Youyou always volunteers for the first set."),
        Quip("Girthy George Washington Carver found a 301st use for the peanut: pre-workout."),
        Quip("One photo told Peak Rosalind Franklin everything about structure. Yours is coming along."),
        Quip("Unbreakable Emmy Noether proved that lifts conserve gains."),
        Quip("Astro-Jacked Mae Jemison calls the bottom of a squat 'low orbit.'"),
        Quip("Granite Ibn al-Haytham invented the experiment. Three sets is a replication study."),
        Quip("Bulletproof Bose and Absolute-Unit Einstein always share a locker. They still fight over who gets to spot you."),
        Quip("Curls for the girls, curls for the boys, curls for the abstract concept of curls.", muscle = MuscleGroup.BICEPS),
        Quip("The sleeve tax is now unaffordable. Congratulations.", muscle = MuscleGroup.BICEPS),
        Quip("Tree trunks don't apologize for taking up space.", muscle = MuscleGroup.QUADS),
        Quip("Leg day: the appointment you can never reschedule.", muscle = MuscleGroup.QUADS),
        Quip("The bench remembers your name. It's frightened, but it remembers.", muscle = MuscleGroup.CHEST),
        Quip("Push day, no push-back.", muscle = MuscleGroup.CHEST),
        Quip("Lats so wide they qualify as a weather system.", muscle = MuscleGroup.BACK),
        Quip("Mountainous Atlas held up the sky. You're just holding the row.", muscle = MuscleGroup.BACK),
        Quip("Cannonball delts, incoming.", muscle = MuscleGroup.SHOULDERS),
        Quip("The overhead press: humbling everyone since gravity was invented.", muscle = MuscleGroup.SHOULDERS),
        Quip("The horseshoe deepens. Luck not required.", muscle = MuscleGroup.TRICEPS),
        Quip("Triceps: two-thirds of the arm, one hundred percent of the attitude.", muscle = MuscleGroup.TRICEPS),
        Quip("The hamstrings whisper only two words: good morning.", muscle = MuscleGroup.HAMSTRINGS),
        Quip("Posterior chain, meet posterior gains.", muscle = MuscleGroup.HAMSTRINGS),
        Quip("The strongest muscle in the body just got a raise.", muscle = MuscleGroup.GLUTES),
        Quip("Glute day is leg day's better-dressed cousin.", muscle = MuscleGroup.GLUTES),
        Quip("Calves grow on a geologic schedule. Today they moved.", muscle = MuscleGroup.CALVES),
        Quip("Relentless Sisyphus finally reached the top. Turns out it was calf raises.", muscle = MuscleGroup.CALVES),
        Quip("The core holds the empire together. Long live the empire.", muscle = MuscleGroup.CORE),
        Quip("Bracing: the most underrated flex in the building.", muscle = MuscleGroup.CORE),
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

    /** Filter series down to lifts/muscles performed in one session. */
    fun scopeToSession(
        series: List<HighlightSeries>,
        exerciseIds: Set<Long>,
        muscles: Set<MuscleGroup>,
    ): List<HighlightSeries> = series.filter { s ->
        when (s.kind) {
            HighlightKind.LIFT -> s.exerciseId in exerciseIds
            HighlightKind.MUSCLE -> s.muscle in muscles
        }
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
