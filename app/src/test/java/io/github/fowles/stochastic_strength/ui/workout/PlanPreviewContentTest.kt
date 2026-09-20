package io.github.fowles.stochastic_strength.ui.workout

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanPreviewContentTest {
    @Test
    fun linkNodeSwipeOffsetPx_midSwipe_tracksTheRawOffset() {
        // Not yet showing the action row: the node must follow the live swipe translation so it
        // stays anchored to the row sliding underneath it.
        assertEquals(-120f, linkNodeSwipeOffsetPx(showingActions = false, rawOffsetPx = -120f))
        assertEquals(0f, linkNodeSwipeOffsetPx(showingActions = false, rawOffsetPx = 0f))
    }

    @Test
    fun linkNodeSwipeOffsetPx_actionRowShowing_alwaysZero() {
        // Once the swipe box is replaced by the action row, the row's content is no longer
        // translated, so the node must reset to 0 rather than carry the stale swiped-away offset.
        assertEquals(0f, linkNodeSwipeOffsetPx(showingActions = true, rawOffsetPx = -400f))
        assertEquals(0f, linkNodeSwipeOffsetPx(showingActions = true, rawOffsetPx = 0f))
    }
}
