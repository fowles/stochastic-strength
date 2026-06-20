# Gauge conservation — why coefficients never drift

Source: `domain/ProgressionController.kt` (`RollingConservingProgressionController`)
Replaces: the former separate renormalization pass (`SeedNormalizer`), now deleted.

Every prescribed weight is `baseline × coefficient`. That split has a built-in
ambiguity: multiply every coefficient in a muscle by some factor and divide the
baseline by the same factor, and every prescribed weight comes out **identical**. The
data you generate by lifting can never tell the two apart — there is a free scaling
knob on a muscle's coefficients. Physicists call that kind of free knob a **gauge**.

Left unmanaged, the knob drifts: a coefficient estimator slowly inflates or deflates a
whole muscle's coefficients together, and the baseline silently compensates so the
weights still look right. Nothing appears wrong — until a brand-new exercise is added
with a sensible seed coefficient and gets the *wrong* weight, because the baseline it
multiplies against has secretly wandered off. The old design fixed this with a
separate cleanup pass that periodically detected the drift and swept it back into the
baseline. The current controller removes the need entirely: it **conserves the gauge
continuously, for free.**

## How it falls out of the differential

The coefficient update (the [differential mode](03-coefficient-estimation.md)) moves
each exercise by `e_i − common`, where `common` is the pool's weighted-average
innovation. Because every exercise is nudged by *its own gap minus the shared
average*, and that update is applied to **all** the pooled exercises with the same
weighting the average was taken with, the changes sum to zero:

```
Σ (weighted Δ log coefficient) = 0
```

Up-nudges and down-nudges cancel exactly. In plain terms: the **geometric mean of a
muscle's coefficients never moves.** Only their *relative shape* changes; their
overall *scale* is pinned. The baseline owns "how strong this muscle is" (the scale);
the coefficients own only "how each exercise compares to its siblings" (the shape);
and the two lanes cannot bleed into each other — so there is nothing left for a
renormalizer to fix.

## Why "all the pooled exercises" matters

The cancellation is exact only because the differential is applied to *every* exercise
the common mode averaged over — not just the ones you trained today. If only today's
exercise were adjusted, the common mode would still be computed over the whole pool,
so the leftover imbalance would be dumped onto that one coefficient. Under a
strengthening lifter, each fresh session slightly beats the staler pool, and that gap
would be attributed to the trained exercise's coefficient — ratcheting the coefficient
scale upward session after session, with the baseline correspondingly under-tracking.
Updating the whole recent pool, weighted, is what keeps the sum at zero. (This was a
real trap caught during implementation: a version that updated only the trained
exercise reintroduced exactly this creep.)

## The honest caveat

Conservation is exact *before* two safety limits act: the per-session cap on how far
any value may move, and the "skip moves smaller than 0.2%" filter. When those fire
unevenly within a muscle, the cancellation is slightly imperfect and the scale can
wobble a hair. The wobble is bounded and small — the simulation lock
(`ProgressionControllerSimulationTest`) holds the coefficient geomean inside roughly
±3% across a wide range of strengthening rates — and it is self-correcting, so no
periodic cleanup pass is needed.
