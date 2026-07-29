package com.watchtastic

import android.app.Application
import com.watchtastic.di.AppGraph

class WatchtasticApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph.from(this)
        graph.notifier.ensureChannels()
    }
}
