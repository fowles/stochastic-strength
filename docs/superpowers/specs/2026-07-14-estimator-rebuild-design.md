# Estimator Rebuild: Data-Trusting Belief Core + Structural Safety

**Date:** 2026-07-14
**Status:** Approved design
**Supersedes:** the abandoned `fable-fail` branch (135 commits; never merges)

## Context

The `fable-fail` branch built a per-set Kalman/Tobit belief filter with pooling, a policy
layer, per-user MAP fitting, and a held-out CV harness. It was abandoned because:

1. The math became too heavy to verify against the code in any practical way.
2. ~30 constants never settled — each was pinned against a synthetic simulated lifter and
   re-tuned whenever a pin broke.
3. The Bulgarian Split Squat (BSS) over-prescription kept regressing, and each fix was a new
   estimator mechanism (adaptive attention, dual variance tracks, failures-sharp carve-outs,
   a dormant day-effect) layered on to protect one demonstrated data point.

Root cause: a broken trust hierarchy. The constants were made up, the simulator was made up,
and "don't lower the weight, it was just an off day" was unmotivated — while the only real
data, the user's actual history (`app/src/test/resources/backtest/history.json`, local-only,
gitignored: 24 sessions, 360 sets, 87 TOO_HARD / 45 RIR_0_1 / 69 RIR_2_4 / 159 RIR_5_PLUS),
was consulted last. When the honest data disagreed with the simulator-pinned constants, the
resolution each time was a hand adjudication plus a new mechanism.

Concepts worth keeping from the branch (re-derived, not merged):

- every set is its own piece of feedback,
- fatigue modeling across the sets of an exercise,
- explicit guess + uncertainty, with uncertainty driving the blend from sibling exercises,
- a policy layer separate from the estimator,
- a forward-chaining held-out harness over real history (the one part that told the truth).

## The constitution (binding on all phases)

1. **One tuning authority.** Forward-chaining held-out prediction over `history.json`.
   Nothing else may justify a constant's value — not a simulator, not a hand-crafted
   scenario, not aesthetics.
2. **Constant admission rule.** Every constant is labeled:
   - `fitted` — the harness shows an interior optimum on real data (sensitivity curve
     recorded when the constant is introduced);
   - `flat` — the harness is shown insensitive over a wide range; frozen, never revisited;
   - `semantic` — a plain gym-language policy choice ("failure caps expire after 28 days"),
     never touched by tuning.
   A constant that fits none of these is deleted, not defaulted.
3. **Safety is structural.** Invariants are prescription-time clamps computed from raw log
   facts. They are not tuned, not tunable, and invisible to the fitness function. When a
   safety behavior looks wrong, the fix is a policy rule — never a new estimator mechanism.
4. **Estimator health is monitored.** Every backtest run reports clamp-bind rate. A clamp
   that binds frequently is declared an estimator bug and fixed in the estimator.
5. **No synthetic authority.** The simulator is not rebuilt. Code correctness is established
   by small deterministic unit tests; behavioral quality only by the real-history harness.
6. **Boundary criterion.** Policy rules are direct restatements of set-log facts with plain
   arithmetic — no decay curves, no uncertainty, no blending, no learned constants. The
   moment a rule needs inference, it belongs in the estimator. The estimator owns all
   inference and is scored raw (pre-clamp).

## Phase 0 — The harness (authority first)

Pure test-tree code; zero prod changes. Replays `history.json` forward-chained: for each
session *k*, run the full prod stack on sessions 1..k−1, then predict session *k*'s sets
before seeing them.

**Metric (model-free target).** Each logged set (weight, target reps, feedback) implies an
interval of ln(1RM) using only the rep-max formula (`DefaultProgressionEngine.rawToOneRepMax`)
and the feedback bucket — no fatigue correction, no estimator concepts:

| feedback | implied ln(1RM) interval at weight w, target reps r |
|---|---|
| TOO_HARD, actualReps = a | narrow interval around 1RM(w, a + ½) |
| TOO_HARD, no rep count | (−∞, 1RM(w, r)] |
| RIR_0_1 | [1RM(w, r), 1RM(w, r + 2)] |
| RIR_2_4 | [1RM(w, r + 2), 1RM(w, r + 5)] |
| RIR_5_PLUS | [1RM(w, r + 5), ∞) |
| HURT / no feedback | not scored |

A stack's prediction for a set is a point: its effective 1RM estimate for that exercise at
that moment (per-set, so a stack that models fatigue may predict lower for later sets).
**Score = distance from the point to the interval (0 if inside), in log units, summed over
held-out sets.** This scores main's estimator and the new stack identically and cannot be
gamed by a stack's own modeling assumptions.

Supplementary reports (not authorities):

- interval coverage / NLL for the new stack only — used when fitting its σ constants;
- safety report — invariant violations (hard fail) and per-clamp bind rate (health).

**Exit:** a recorded held-out score for main's estimator, unmodified — the number to beat.
The harness skips cleanly when `history.json` is absent; baseline numbers are recorded in
the plan, not asserted in CI.

## Phase 1 — Policy layer (log-fact clamps)

A pure function at prescription time: `prescribe(rawTarget, PolicyFacts) → weight`.
`PolicyFacts` is rebuilt on replay from the set log alone. Three rules:

1. **Demonstrated-capacity cap** (generalizes the failure ceiling). Within the expiry window
   (semantic: 28 days), prescription on an exercise is capped by the upper bound demonstrated
   in its **most recent session** (and only that session — newer sessions supersede older
   ones entirely):
   - any TOO_HARD set in that session → cap = min over failed sets of the failure's implied
     1RM, rounded **down** at the grid (fixes the round-up-at-grid-multiple edge bug logged
     in CLAUDE_TODO);
   - otherwise → cap = max over the session's sets of the set's implied upper bound
     (RIR_0_1 → 1RM(w, r+2); RIR_2_4 → 1RM(w, r+5); RIR_5_PLUS → no bound);
   - all-RIR_5_PLUS session → no cap.

   This closes the "fail 35 → narrowly succeed at 20 → engine prescribes 35 again" hole:
   the narrow success replaces the failure ceiling with a cap of ~1RM(20, r+2), so weight
   creeps rather than jumps. Caps fade in proportion to what the user actually demonstrated,
   and a clean easy session uncaps entirely.
2. **HURT backoff.** A HURT set on a muscle multiplies that muscle's prescriptions by a
   backoff fading over a half-life (semantic: depth 15%, half-life 14 days, floor 0.6 —
   today's numbers restated as policy).
3. **Rest cooldown.** Recently-stressed muscle → planner cooldown (semantic: 2 days), as
   today.

**Invariant tests** replay real history and the BSS prod fixture asserting clamp *behavior*
("never prescribe ≥ a weight failed in the most recent session") — the magic-number 20 lb
pin retires; its scenario survives as an invariant case.

## Phase 2 — Belief core (the whole estimator)

Per exercise: `Belief(mu, sigma2, updatedAt)` on ln(fresh 1RM). In-memory derived state,
rebuilt by full replay (idempotent), no schema change. `ExerciseStrengthOverride` rows
seed/override beliefs with σ_seed/σ_override.

- **Set → interval.** The same bounds table as the harness metric.
- **Fatigue shift.** Set *k* (1-based rank among the exercise's rows in the session,
  including feedback-less/HURT rows) observes fresh capacity reduced by φ·(k−1); the
  interval is shifted up by −ln(1 − φ·(k−1)) before folding. One constant φ, `fitted`.
- **Boundary-pull Gaussian fold.** If μ lies inside the set's (shifted) interval, the set
  confirms: μ unchanged, σ shrinks as a Gaussian fold at the nearer boundary would shrink
  it. If μ lies outside, fold a Gaussian observation at the violated boundary — one Kalman
  line. Observation σ per bucket type (1–2 constants, `fitted` or `flat`). **Symmetric
  up/down**: no off-day damping, no down-snap, no asymmetric weights. A failure moves the
  belief down exactly as hard as a strong success moves it up; immediate protection against
  re-prescribing a failure is the policy cap, not fold asymmetry.
- **Aging.** σ² grows by q per idle day (`fitted` — the branch's honest CV already showed
  main's equivalent was ~16× too small, so a data gradient exists). No μ drift: detraining
  remains the user-confirmed dialog on main writing override rows.
- **Nothing else.** No innovation runs, no dual variance tracks, no day-effect, no adaptive
  inflation, no obs-noise scaling. Any such mechanism may later apply for admission under
  the constitution, but each starts deleted.
- HURT sets carry no load observation (policy handles HURT).

Every update must be explainable in one gym sentence ("you left 2–4 reps in reserve at 20,
so your ceiling is at least ~24; we were below, so we moved up toward it").

## Phase 2 — Pooling (read-time, never mutates beliefs)

- **Muscle level.** Each loaded exercise votes `μ_j − ln(coef_j)` with precision
  `1/(σ_j² + τ²)`; τ = transfer noise, **one constant** (`fitted` or `flat`; the branch's
  three per-equipment τs were simulator-tuned and start collapsed). No separate seed-anchor
  constant: cold exercises sit at seed with σ_seed and anchor the level automatically.
- **Effective belief.** Precision-weighted blend of own `(μ_i, σ_i²)` with the sibling
  prediction `(ln coef_i + L₋ᵢ, σ²_level + τ²)`, leave-one-out so an exercise never borrows
  its own evidence back. Fresh two-sided evidence (small own σ) mathematically outvotes
  siblings — the principled replacement for the branch's `evidenceVar`/`siblingExcess`
  gate. Stale exercises (aged σ) lean on siblings.

## Phase 2 — Prescription

`target = exp(μ_eff − z·σ_eff)` → scale to the session's rep target via the existing 1RM
formula → policy caps → grid round. Two semantic constants:

- **z** — risk percentile ("prescribe at roughly the 30th percentile of believed capacity").
  Cold starts are automatically humble (large σ), and shrinking σ raises weights toward μ.
- **Overload nudge** — if the exercise's most recent session was all in-band-or-easier
  (RIR ≥ 2 throughout, no failures), nudge the target up one grid increment ("you handled
  it; add the smallest plate"). Covers the steady-state stall where in-band feedback
  legitimately leaves μ unmoved and σ is at its floor. The demonstrated-capacity cap
  applies on top.

## Phase 3 — Swap, trace, delete

- **Wiring.** Replay drives the new fold; `DerivedStateStore` holds beliefs + `PolicyFacts`.
  Same replay-from-scratch idempotence; no schema change. Charts/debug read μ/σ where they
  read lnE/confidence today; the progression chart gains an uncertainty band.
- **"Why this weight" trace.** Debug-screen breakdown per prescription: own μ±σ, sibling
  pull (level prediction + blend weight), percentile shading, nudge, which cap bound (if
  any), rounding — each line citing the sets that produced it.
- **Deletions.** `ExerciseEstimate`, `ExerciseEstimateUpdater`, `SessionSignalExtractor`,
  old projector internals, `ExerciseEstimatorSimulationTest` and other simulation pins, and
  the old constants. `fable-fail` stays abandoned; its specs/plans are historical documents.

## Constant ledger (target)

Estimator (~7): σ_seed, σ_override (semantic-ish priors), q (fitted), φ (fitted), 1–2
obs-σ (fitted/flat), σ floor/cap (flat guards).
Policy/prescription (~8, all semantic): cap expiry 28 d; HURT depth/half-life/floor;
cooldown 2 d; z; overload nudge = one grid increment.
Every constant ships with its label and (for fitted/flat) its sensitivity curve.

## Testing story & ship gate

- **Phase 0:** harness unit tests on tiny synthetic histories with hand-computable held-out
  scores; then the recorded baseline of main's stack on real history.
- **Phases 1–2:** TDD per pure component (bounds table, fold, aging, pooling, caps).
  Invariant suite on real history + BSS fixture. Sensitivity curves recorded per constant
  at introduction.
- **Ship gate (Phase 3):** new stack ≥ main's baseline on the held-out score; invariants
  green; clamp-bind report reviewed; constant census matches the ledger with every label
  justified; full JVM + instrumented suites green.

## Decisions log (from design review)

- Authority = held-out backtest + structural invariants; BSS becomes an invariant, not a
  tuning target.
- Anti-divergence: the backtest scores the raw estimator (clamps invisible to the fitness
  function); clamp-bind rate is a tracked health metric; policy limited to log-fact
  arithmetic by the boundary criterion.
- Start fresh from main; consult `fable-fail` as reference only.
- Fold math: boundary-pull Gaussian (not midpoint, not exact Tobit).
- Ship criterion: beat main on held-out CV + invariants (no tolerance band).
- Failure ceiling generalized to the demonstrated-capacity cap after the
  "fail 35 → narrow success at 20 → 35 again" hole was identified.
- Sequencing: Approach A, authority first.
