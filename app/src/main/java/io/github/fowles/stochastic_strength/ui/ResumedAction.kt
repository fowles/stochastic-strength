package io.github.fowles.stochastic_strength.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import kotlinx.coroutines.launch

/**
 * Wraps [action] so it runs once the host is RESUMED, running straight away if it already is.
 *
 * Activity-result callbacks — a permission dialog's answer, say — are delivered while the
 * activity is still only STARTED, and the navigation helpers in `AppNavigation` drop anything
 * issued before RESUMED. Navigation driven by a result callback has to wait; a plain tap already
 * happens while resumed and needs none of this.
 */
@Composable
fun rememberResumedAction(action: () -> Unit): () -> Unit {
    val current = rememberUpdatedState(action)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    return remember(lifecycle, scope) {
        {
            scope.launch { lifecycle.withResumed { current.value() } }
            Unit
        }
    }
}
