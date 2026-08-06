package com.watchtastic.di

import android.content.Context
import com.watchtastic.mesh.MeshRepository
import com.watchtastic.mesh.MeshStore
import com.watchtastic.platform.Haptics
import com.watchtastic.platform.LocationProvider
import com.watchtastic.platform.Prefs
import com.watchtastic.service.Notifier
import com.watchtastic.update.GitHubReleaseClient
import com.watchtastic.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application-scoped object graph, assembled by hand.
 *
 * This app has exactly one of each collaborator and one lifetime for all of them, so a
 * DI framework would add an annotation processor to the build for no behavioural gain.
 * The wiring below is the entire dependency graph, readable top to bottom.
 */
class AppGraph private constructor(context: Context) {

    private val appContext = context.applicationContext

    /**
     * The radio link outlives any one screen or service binding, so it hangs off a
     * supervisor scope tied to the process rather than a lifecycle.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs = Prefs(appContext)
    val store = MeshStore(appContext, scope, prefs.mutedChannels)
    val haptics = Haptics(appContext, prefs)
    val notifier = Notifier(appContext)
    val location = LocationProvider(appContext)
    val repository = MeshRepository(appContext, store, prefs, scope)

    /**
     * Self-update channel, reading published GitHub Releases. Public repositories need no
     * credentials, and publishing a release is a deliberate act — unlike a shared folder,
     * where dropping in a test APK would have offered it to every watch immediately.
     */
    val updates = UpdateManager(
        appContext,
        GitHubReleaseClient(owner = UPDATE_OWNER, repo = UPDATE_REPO),
        scope,
    )

    init {
        scope.launch { store.load() }
    }

    companion object {
        /**
         * Where updates come from: the project's own GitHub Releases.
         *
         * Anyone forking this should point these at their own repo — but note the
         * signature pinning in [UpdateManager] means a fork's releases still cannot
         * install over a build signed with a different key, which is the intended
         * behaviour rather than an obstacle.
         */
        const val UPDATE_OWNER = "GPTmadeit"
        const val UPDATE_REPO = "Watchtastic"

        @Volatile
        private var instance: AppGraph? = null

        fun from(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
