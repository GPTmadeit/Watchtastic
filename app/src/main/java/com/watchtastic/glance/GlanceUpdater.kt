package com.watchtastic.glance

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.watchtastic.mesh.MeshRepository
import com.watchtastic.mesh.MeshStore
import com.watchtastic.platform.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Feeds the tile and the complication, and tells the system when they're stale.
 *
 * Both surfaces are *pulled* by the system, which only asks again when told something
 * changed. So this watches the store, reduces it to a [MeshGlance], and requests an
 * update only when that value actually differs — the link state alone emits dozens of
 * times during a config sync, none of which change what the tile shows.
 */
class GlanceUpdater(
    private val context: Context,
    private val store: MeshStore,
    private val repository: MeshRepository,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "GlanceUpdater"

        /**
         * The system throttles tiles that ask too often, and a burst of messages should
         * cost one refresh rather than one per message.
         */
        const val SETTLE_MS = 2_000L

        /** Upper bound on waiting for the snapshot when the request started the process. */
        const val LOAD_WAIT_MS = 3_000L
    }

    /** The glance as of now, after giving a cold start a moment to load its snapshot. */
    suspend fun current(): MeshGlance {
        withTimeoutOrNull(LOAD_WAIT_MS) { store.awaitLoaded() }
        return snapshot()
    }

    private fun snapshot(): MeshGlance = MeshGlance.of(
        link = repository.link.value,
        hasSavedRadio = prefs.radioAddress.value != null,
        messages = store.messages.value,
        lastRead = store.lastRead.value,
        nodes = store.nodes.value.values,
        myNodeNum = store.myNodeNum.value,
        nowMs = System.currentTimeMillis(),
    )

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(
                repository.link,
                store.messages,
                store.lastRead,
                store.nodes,
                prefs.radioAddress,
            ) { _, _, _, _, _ -> snapshot() }
                .distinctUntilChanged()
                // The first value is whatever the system already rendered at startup.
                .drop(1)
                .debounce(SETTLE_MS)
                .collect { requestRefresh() }
        }
    }

    private fun requestRefresh() {
        runCatching {
            TileService.getUpdater(context).requestUpdate(MeshTileService::class.java)
        }.onFailure { Log.w(TAG, "tile refresh request failed", it) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, MeshComplicationService::class.java))
                .requestUpdateAll()
        }.onFailure { Log.w(TAG, "complication refresh request failed", it) }
    }
}
