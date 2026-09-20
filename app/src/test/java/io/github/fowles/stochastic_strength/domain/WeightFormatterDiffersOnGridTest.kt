package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WeightFormatter.differsOnGrid] backs [io.github.fowles.stochastic_strength.ui.components.SuggestionNote]:
 * a pinned weight and a live suggestion reached by different arithmetic (round vs roundDown, an
 * overload nudge) must compare equal when they land on the same displayed grid point, even when
 * the underlying floats aren't bit-identical.
 */
class WeightFormatterDiffersOnGridTest {

    @Test
    fun equalKgValues_doNotDiffer() {
        assertFalse(WeightFormatter.differsOnGrid(60f, 60f, WeightUnit.KG))
    }

    @Test
    fun sameNominalLbs_reachedByDifferentArithmetic_doNotDiffer() {
        // Same "45 lb" from two different unit-conversion paths (a single round-trip vs an
        // additive one, as an overload nudge does): not bit-identical, but the same grid point.
        val a = WeightUnit.LBS.toKg(45f)
        val b = WeightUnit.LBS.toKg(40f) + WeightUnit.LBS.toKg(5f)
        assertFalse(WeightFormatter.differsOnGrid(a, b, WeightUnit.LBS))
    }

    @Test
    fun oneGridStepApart_onLbs_differs() {
        val a = WeightUnit.LBS.toKg(45f)
        val b = WeightUnit.LBS.toKg(50f)
        assertTrue(WeightFormatter.differsOnGrid(a, b, WeightUnit.LBS))
    }

    @Test
    fun oneGridStepApart_onKg_differs() {
        assertTrue(WeightFormatter.differsOnGrid(60f, 62.5f, WeightUnit.KG))
    }
}
