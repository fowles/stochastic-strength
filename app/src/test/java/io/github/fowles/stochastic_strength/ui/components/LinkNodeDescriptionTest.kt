package io.github.fowles.stochastic_strength.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [LinkNodeHost] now relies on TalkBack reading position (a negative `traversalIndex`, between
 * the two rows a node links) to convey which rows are involved, so the description itself only
 * needs to say what the tap does.
 */
class LinkNodeDescriptionTest {
    @Test
    fun linkNodeDescription_whenAlreadyLinked_offersToUnlink() {
        assertEquals("Unlink", linkNodeDescription(linkedAbove = true))
    }

    @Test
    fun linkNodeDescription_whenNotLinked_offersToLink() {
        assertEquals("Link", linkNodeDescription(linkedAbove = false))
    }
}
