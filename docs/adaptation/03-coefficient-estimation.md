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

It learns each exercise's strength **relative to its peers** in the same muscle,
not against the stored baseline. That makes it immune to a wrong baseline: if the
whole muscle's baseline is off, every exercise is off by the same factor, and a
relative comparison cancels it out. Correcting that shared, systemic error is the
job of baseline renormalization (see
[04-coefficient-renormalization](04-coefficient-renormalization.md)), which runs on
the very next step and re-attributes any common scale factor back into the baseline.
The two engines are partners: this one sets the *relative shape* of a muscle's
coefficients, renormalization sets their *global scale*.

## Layer 1 — turn each set into a strength estimate (`setSignal`)

Every completed set, based on how it felt, becomes a guess at your one-rep max for
that exercise, tagged with a confidence and a couple of flags:

- **"Lots left in the tank"** is a weak, low-confidence estimate (the engine has to
  extrapolate far).
- **"A couple reps left"** is moderate confidence.
- **"Almost nothing left"** is fairly high confidence.
- **"Too hard, and here's how many reps I actually got"** is the strongest, most
  trustworthy signal — it's a *measured* near-failure.
- **"Too hard but no rep count recorded"** is treated only as an *upper bound* — it
  tells us you're no stronger than this, not exactly how strong.
- **Pain** is discarded entirely.

## Layer 2 — collapse a session into one estimate per exercise (`aggregateSession`)

Within a session, it confidence-weights those per-set estimates into a single
estimated one-rep max. The upper-bound (no-rep-count) signals are special: they're
thrown out if the real, measured signals already point higher, since a fuzzy ceiling
shouldn't drag down a concrete reading.

## Layer 3 — combine an exercise's sessions, favoring recent ones (`computeEstimate`)

Each exercise's recent sessions are blended with a recency weighting (older sessions
fade with a roughly two-week half-life) and combined via a weighted *median* — a
median, not an average, so one freak session can't dominate. The result is a single
recency-biased strength estimate for the exercise, `E`, plus an evidence weight and
an overall confidence.

There is deliberately **no per-exercise evidence threshold**: a single recent
session yields a usable (if low-weight) estimate. This is on purpose. The workout
planner maximizes variety — it samples a handful of exercises from a large library
each session — so any one exercise is rarely repeated enough to accumulate strong
solo evidence. Robustness therefore comes not from one exercise repeating, but from
comparing many exercises across the muscle (Layer 4) and from heavy damping
(Layer 5).

## Layer 4 — compare against the muscle's peers (`applyPeerConsensus`)

For each exercise *i* the engine asks: "given everyone else's recent lifting, what
should *i*'s coefficient be?" It computes, for every other exercise *j* in the
muscle, the muscle baseline that *j*'s performance implies (`E_j / c_j`), and takes
an **interpolated weighted median** of those — it blends the two straddling peers at
the half-weight crossing, so it degrades gracefully when only two or three peers are
present rather than hard-selecting one. The proposed coefficient is then simply
`E_i / peer_consensus_baseline`. (Peer-support attenuation — scaling down the
proposal when total peer evidence weight is thin — exists as an optional knob but
is off by default.)

Because the comparison is relative:

- **A wrong baseline is invisible.** If the muscle baseline shifts, every `E_j`
  shifts with it, the consensus shifts too, and the ratio cancels — coefficients
  don't move. (The baseline's own level is handled by the baseline machinery and
  renormalization.)
- **A couple of mis-set coefficients self-heal.** Each exercise's reference excludes
  itself and medians over its peers, so one or two wrong neighbors barely move the
  consensus, and they converge over subsequent sessions rather than dragging
  everyone into a stuck state.

If an exercise has **fewer than three peers** with recent evidence (`minPeers = 3`)
— the cold-start case for a brand-new user, or a muscle with very few logged
exercises — there is no trustworthy reference, so the engine proposes nothing and
the coefficient stays put at its seed. As soon as enough peers accumulate evidence,
learning begins. Zero-coefficient (unloadable: bodyweight/banded/wall-sit) exercises
are excluded both as peers and as candidates — they have no load relationship to the
baseline.

## Layer 5 — dampen the actual move (`damp`)

Even a proposed coefficient isn't applied wholesale. The coefficient is nudged only
a fraction (`alpha = 0.6`) of the way toward the proposal, scaled by confidence and
recency (older sessions fade with a 21-day half-life). Tiny moves below
`minRelativeChange = 0.002` are ignored entirely to avoid churn. The size of any
single update is capped at `maxLogStep = ln(1.10)` (~10% per session). So
coefficients ease toward their learned values over many sessions rather than
snapping.

## Output

A set of updated coefficients (with notes on why), which feed back into how every
future session's weights are computed for those exercises. Any systemic, whole-muscle
component is then reconciled into the baseline by renormalization.
