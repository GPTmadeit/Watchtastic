package com.watchtastic.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.watchtastic.di.AppGraph
import com.watchtastic.mesh.MeshConstants
import com.watchtastic.mesh.model.ConversationKey
import com.watchtastic.mesh.model.LinkState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the radio link alive while the app isn't in the foreground.
 *
 * A mesh client is only useful if it hears traffic when the screen is off, so the BLE
 * connection is anchored to a `connectedDevice` foreground service. The service does not
 * own the connection — [com.watchtastic.mesh.MeshRepository] does, at application scope —
 * it owns the *promise* to keep the process alive, plus the user-visible surface
 * (ongoing chip, notifications, haptics) that justifies that promise.
 */
class MeshService : LifecycleService() {

    companion object {
        private const val TAG = "MeshService"
        private const val ACTION_START = "com.watchtastic.action.START"
        private const val ACTION_STOP = "com.watchtastic.action.STOP"

        /**
         * Android 14 refuses to start a `connectedDevice` foreground service unless the
         * app *currently holds* one of the underlying transport permissions — declaring
         * `FOREGROUND_SERVICE_CONNECTED_DEVICE` in the manifest is necessary but not
         * sufficient. Before the wearer has been through the permission prompt on the
         * connect screen, starting this service is a guaranteed SecurityException, so
         * every entry point checks first.
         */
        fun isPermitted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

        /** Starts the link service, or does nothing if it isn't currently allowed. */
        fun start(context: Context) {
            if (!isPermitted(context)) {
                Log.i(TAG, "not starting: Bluetooth permission not granted yet")
                return
            }
            val intent = Intent(context, MeshService::class.java).setAction(ACTION_START)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "could not start foreground service", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }

    private val graph: AppGraph by lazy { AppGraph.from(this) }

    private var lastState: LinkState = LinkState.Idle

    /** Node numbers already announced, so a node is only ever new once per run. */
    private val announcedNodes = mutableSetOf<Int>()

    override fun onCreate() {
        super.onCreate()
        graph.notifier.ensureChannels()
        observeLink()
        observeMessages()
        observeNewNodes()
        observeLocationSharing()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = graph.notifier.buildLinkNotification(
            graph.repository.link.value,
            graph.store.totalUnread(),
        )

        // Belt and braces: the permission can be revoked between the caller's check and
        // this call, and a SecurityException here would take the whole process down.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Notifier.ONGOING_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(Notifier.ONGOING_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "foreground start rejected; running without a link service", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Restarted by the system after a kill: pick the radio back up.
        graph.repository.resumeSavedRadio()
        return Service.START_STICKY
    }

    private fun observeLink() {
        lifecycleScope.launch {
            graph.repository.link
                // Syncing emits once per config frame. Coarsening the progress to 10%
                // buckets turns ~40 notification rebuilds per sync into at most ten.
                .distinctUntilChanged { old, new -> notificationKey(old) == notificationKey(new) }
                .collectLatest { state ->
                    // Only buzz on genuine transitions, not on every progress tick.
                    when {
                        state is LinkState.Connected && lastState !is LinkState.Connected ->
                            graph.haptics.connected()

                        lastState is LinkState.Connected && state !is LinkState.Connected ->
                            graph.haptics.disconnected()
                    }
                    lastState = state
                    graph.notifier.updateLink(state, graph.store.totalUnread())
                }
        }
    }

    private fun notificationKey(state: LinkState): String = when (state) {
        is LinkState.Syncing -> "sync:${(state.progress * 10).toInt()}"
        is LinkState.Reconnecting -> "reconnect:${state.attempt}"
        is LinkState.Failed -> "failed:${state.reason}"
        else -> state::class.java.name
    }

    private fun observeMessages() {
        lifecycleScope.launch {
            graph.repository.inboundMessages.collect { message ->
                if (message.isReaction) return@collect

                val isDirect = !ConversationKey.isChannel(message.conversation)
                if (!isDirect && !graph.prefs.notifyChannels.value) return@collect

                val nodes = graph.store.nodes.value
                val sender = nodes[message.fromNum]?.displayLong
                    ?: MeshConstants.nodeIdOf(message.fromNum)

                if (nodes[message.fromNum]?.isMuted == true) return@collect

                val conversationTitle = if (isDirect) {
                    "Direct"
                } else {
                    val index = ConversationKey.channelIndex(message.conversation) ?: 0
                    if (index in graph.prefs.mutedChannels.value) return@collect
                    val channel = graph.store.channels.value.firstOrNull { it.index == index }
                    channel?.displayName ?: "Channel $index"
                }

                graph.haptics.incoming()
                graph.notifier.postMessage(message, sender, conversationTitle)
                graph.notifier.updateLink(graph.repository.link.value, graph.store.totalUnread())
            }
        }
    }

    /**
     * Announces nodes the mesh has never shown us before.
     *
     * Two filters do the real work. The link must be [LinkState.Connected] — during
     * config download the radio dumps its entire node database, and notifying once per
     * entry would mean a wrist full of buzzes every time the watch reconnects. And each
     * node number is announced at most once per process, because a node can introduce
     * itself twice (a bare packet, then its NodeInfo) within seconds.
     */
    private fun observeNewNodes() {
        lifecycleScope.launch {
            graph.store.discoveredNodes.collect { node ->
                if (!graph.prefs.notifyNewNodes.value) return@collect
                if (graph.repository.link.value !is LinkState.Connected) return@collect
                if (!announcedNodes.add(node.num)) return@collect
                if (node.isIgnored) return@collect

                val detail = buildList {
                    node.hopsAway?.let {
                        add(if (it == 0) "direct" else "$it hop${if (it == 1) "" else "s"}")
                    }
                    if (node.snr != 0f) add("SNR %.1f dB".format(node.snr))
                    add(node.role.lowercase().replace('_', ' '))
                }.joinToString(" · ")

                graph.haptics.tick()
                graph.notifier.postNewNode(node.num, node.displayLong, detail)
            }
        }
    }

    /** Feeds the watch's GPS to the radio, but only while the wearer has opted in. */
    private fun observeLocationSharing() {
        lifecycleScope.launch {
            graph.prefs.shareLocation.collectLatest { enabled ->
                if (enabled) {
                    graph.location.start { fix ->
                        lifecycleScope.launch { graph.repository.provideOwnPosition(fix) }
                    }
                } else {
                    graph.location.stop()
                }
            }
        }
    }

    override fun onDestroy() {
        graph.location.stop()
        super.onDestroy()
    }
}
