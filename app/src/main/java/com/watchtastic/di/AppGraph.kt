package com.watchtastic.di

import android.content.Context
import com.watchtastic.mesh.MeshRepository
import com.watchtastic.mesh.MeshStore
import com.watchtastic.platform.Haptics
import com.watchtastic.platform.LocationProvider
import com.watchtastic.platform.Prefs
import com.watchtastic.service.Notifier
import com.watchtastic.update.DriveFolderClient
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
     * Self-update channel. The folder is public and listable without credentials; an
     * API key is optional and only makes the listing call more durable.
     */
    val updates = UpdateManager(
        appContext,
        DriveFolderClient(folderId = UPDATE_FOLDER_ID, apiKey = UPDATE_API_KEY),
    )

    init {
        scope.launch { store.load() }
    }

    companion object {
        /** The shared "Watchtastic" Drive folder that release APKs are dropped into. */
        const val UPDATE_FOLDER_ID = "1V9CEw9HNeu7KEQpR9mKappWXpnZLYDZ_"

        /**
         * Optional Google Drive API key. Leave blank to use the keyless public-folder
         * listing; set it to harden the listing call against HTML changes.
         */
        const val UPDATE_API_KEY = ""

        @Volatile
        private var instance: AppGraph? = null

        fun from(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context).also { instance = it }
            }
    }
}
