package com.watchtastic.ui

import androidx.compose.runtime.compositionLocalOf
import com.watchtastic.di.AppGraph

/**
 * The object graph, handed down the composition.
 *
 * Screens read flows straight from here instead of each owning a ViewModel: the graph is
 * already application-scoped and every piece of state is a [kotlinx.coroutines.flow.Flow],
 * so a per-screen ViewModel would only forward calls.
 */
val LocalAppGraph = compositionLocalOf<AppGraph> {
    error("AppGraph was not provided")
}
