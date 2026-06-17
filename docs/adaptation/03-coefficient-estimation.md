# Per-exercise coefficient estimation

Source: `domain/EstCoefConsensusHeuristic.kt`
Runs via: `WorkoutRepository` `recomputeCoefficients` (a `CoefficientHeuristic`)

A coefficient is the per-exercise multiplier that bridges the muscle baseline to a
specific lift. The baseline says "this muscle is worth roughly *this* much"; the
coefficient says "but for *this particular exercise* you're stronger or weaker than
that by some factor" — a barbell press and a dumbbell fly both draw on the same
chest baseline, but at very different absolute weights. This engine watches your
logged sets and slowly learns each exercise's true coefficient, rather than
trusting the seeded starting guess forever.

It is deliberately conservative and built in layers, because a single set is noisy
and you don't want one good or bad day yanking your weights around.

## Layer 1 — turn each set into a strength estimate (`setSignal`)

Every completed set, based on how it felt, becomes a guess at your one-rep max for
that exercise, tagged with a confidence and a couple of flags:

- **"Lots left in the tank"** is a weak, low-confidence estimate (the engine has to
  extrapolate far).
- **"A couple reps left"** is moderate confidence.
- **"Almost nothing left"** is fairly high confidence.
- **"Too hard, and here's how many reps I actually got"** is the strongest, most
  trustworthy signal — it's a *measured* near-failure, marked as "definite."
- **"Too hard but no rep count recorded"** is treated only as an *upper bound* — it
  tells us you're no stronger than this, not exactly how strong.
- **Pain** is discarded entirely.

## Layer 2 — collapse a session into one estimate per exercise (`aggregateSession`)

Within a session, it confidence-weights those per-set estimates into a single
number. The upper-bound (no-rep-count) signals are special: they're thrown out if
the real, measured signals already point higher, since a fuzzy ceiling shouldn't
drag down a concrete reading. Dividing that session's strength estimate by the
muscle baseline at that time yields a *candidate coefficient* for the session.

## Layer 3 — combine sessions for the exercise, favoring recent ones (`computeH1`)

Multiple sessions' candidate coefficients are blended with a recency weighting
(older sessions fade with a roughly two-week half-life) and combined via a weighted
*median* — a median, not an average, so one freak session can't dominate. It
refuses to propose anything at all unless there's enough accumulated evidence,
*unless* at least one "definite" measured-failure signal is present, which is
trustworthy enough to act on alone. The result is one proposed coefficient for the
exercise, with an overall confidence and a count of how many sessions backed it.

## Layer 4 — sanity-check across exercises in the same muscle (`applyH2`)

This is the engine's namesake "consensus" guard. It looks at all the exercises for
a muscle together, in terms of how far and which way each one's coefficient wants
to move (a signed log-ratio of proposed vs. current, i.e. effectively a signed
percentage change). Two thresholds do distinct jobs: a **~5%** level defines
"calm/quiet," and a **~10%** level defines "loud enough to be a candidate."

The tests run in order:

1. **Group-drift veto.** If *every* exercise for the muscle wants to drift the same
   direction by a meaningful average amount (above ~5%), it suppresses all of them.
   When all exercises pull the same way at once, the muscle *baseline* is almost
   certainly wrong, not every coefficient simultaneously — so the baseline
   machinery and renormalization should handle it, not coefficients.

2. **Single-outlier promotion.** Otherwise it splits exercises into *outlier
   candidates* (desired move above ~10%) and *siblings* (everyone else). It crowns
   a lone outlier **only** when all of these hold:
   - there are at least three exercises in the muscle group (you need a real peer
     group to judge against),
   - there is **exactly one** outlier candidate,
   - **all** siblings are calm (each wanting to move less than ~5%),
   - and that one outlier has appeared across at least two sessions.

   When all that lines up, the outlier is a single loud voice in an otherwise quiet
   room, corroborated over time — a genuine per-exercise correction — and its
   proposal is accepted with full confidence.

3. **Mixed.** Anything else (two exercises both wandering, restless siblings, or
   only two exercises total) is "mixed": every proposal just passes through at its
   own modest confidence.

### What "corroborated over time" means

The corroboration test looks specifically at the **outlier exercise's own session
count** — the number of distinct sessions *that exercise* contributed a usable
signal to (carried up from Layer 3). Calm siblings arriving do **not** help: they
satisfy the "siblings are calm" and "at least three exercises" conditions, but they
do nothing for corroboration. A big-moving exercise seen in only a single session —
no matter how many quiet peers surround it — falls through to the "mixed" path.
(Because Layer 3 recency-fades evidence, two genuinely ancient sessions technically
satisfy the count but would struggle to clear the earlier evidence bar unless one
carried a "definite" measured-failure signal.)

## Layer 5 — dampen the actual move (`damp`)

Even an accepted proposal isn't applied wholesale. The coefficient is nudged only a
fraction of the way toward the proposal, scaled by confidence, and the size of any
single update is capped. Tiny moves below a threshold are ignored entirely to avoid
churn. So coefficients ease toward their learned values over many sessions rather
than snapping.

## Output

A set of updated coefficients (with notes on why), which feed back into how every
future session's weights are computed for those exercises.
