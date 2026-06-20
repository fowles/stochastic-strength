# Coefficient renormalization

Source: `domain/SeedNormalizer.kt`
Applied by: `WorkoutRepository.applyBaselineNormalization` (the baseline-normalization pass, run from `replayDerivedState`)

This engine exists to fix a slow accounting problem created by the coefficient
estimator ([engine #3](03-coefficient-estimation.md)). That estimator is always
nudging coefficients around, and over many sessions a *whole muscle's* coefficients
can drift the same direction together. When that happens, the drift is really
telling you the muscle's **baseline** is mis-set — but it's been quietly absorbed
into the coefficients instead, where it doesn't belong. Renormalization
periodically sweeps that shared drift out of the coefficients and re-attributes it
to the baseline, **without changing any actual training weights.**

## The guiding idea: seeds carry the shape

Each exercise's coefficient has a *seed* — its original, hand-set starting value.
Those seeds encode the meaningful *shape* of a muscle: which of its exercises are
inherently heavier or lighter relative to each other. Coefficients are allowed to
learn and move, but if they've collectively wandered away from their seeds by a
common factor, that common factor is not real per-exercise information; it's
baseline information in disguise.

## How it decides there's drift

For each muscle, it gathers the exercises you've actually trained that have a live
coefficient (it needs at least two, so there's a shape to compare). It then asks:
"Is there a single scaling factor that, applied to all these current coefficients,
would line them back up with their seeds as well as possible?" That best-fit common
factor is the muscle's drift. The metadata it records even notes how much the fit
improves (the before/after error), so you can see whether the drift was a clean
shared shift or just noise.

## How it re-attributes

Once it has that common factor, it moves it out of the coefficients and into the
baseline, in equal and opposite measure. The baseline shifts to absorb the drift,
and every coefficient in the muscle is scaled by the inverse so it lands back near
its seed. Crucially, the **product of baseline and coefficient — which is what
actually determines the weight you lift — is left unchanged.** Nothing about your
prescribed weights moves; only the *bookkeeping* changes, so that the baseline once
again carries "how strong this muscle is" and the coefficients once again carry
only "how this exercise compares to its siblings."

## Guards against churn

- It only acts if the resulting baseline change is big enough to matter — past a
  unit-aware threshold (about 2 kg / 5 lb) — otherwise it leaves everything alone.
- It writes proper history rows for both the baseline change (reason:
  normalization) and each adjusted coefficient, so the re-attribution is auditable
  rather than silent.

## Net effect

Engine #3 is free to chase per-exercise signals without worrying that a muscle-wide
misjudgment will permanently distort the coefficient *shape*, because this engine
keeps pulling that shape back toward its seeds and parking the real strength change
where it belongs — in the baseline.
