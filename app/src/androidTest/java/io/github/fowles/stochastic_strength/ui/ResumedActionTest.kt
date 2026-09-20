package io.github.fowles.stochastic_strength.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ResumedActionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /**
     * The case that matters: a permission dialog's result is delivered while the host is only
     * STARTED. The action must still run, once the host reaches RESUMED.
     */
    @Test
    fun actionInvokedBeforeResume_runsOnceResumed() {
        val owner = TestOwner()
        var ran = 0
        lateinit var action: () -> Unit
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                action = rememberResumedAction { ran++ }
            }
        }
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.runOnUiThread { action() }
        composeRule.waitForIdle()
        assertEquals(0, ran)

        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.waitForIdle()
        assertEquals(1, ran)
    }

    @Test
    fun actionInvokedWhileResumed_runsOnce() {
        val owner = TestOwner()
        var ran = 0
        lateinit var action: () -> Unit
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                action = rememberResumedAction { ran++ }
            }
        }
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.runOnUiThread { action() }
        composeRule.waitForIdle()
        assertEquals(1, ran)
    }
}
