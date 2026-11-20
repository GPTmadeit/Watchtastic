package com.watchtastic.mesh

import android.util.Log
import com.watchtastic.mesh.model.ChatMessage
import com.watchtastic.mesh.model.Conversation
import com.watchtastic.mesh.model.ConversationKey
import com.watchtastic.mesh.model.MeshChannel
import com.watchtastic.mesh.model.MeshNode
import com.watchtastic.mesh.model.MsgStatus
import com.watchtastic.mesh.model.NodeMetrics
import com.watchtastic.mesh.model.NodePosition
import com.watchtastic.mesh.model.RadioInfo
import com.watchtastic.mesh.model.Waypoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.meshtastic.proto.ConfigProtos
import java.io.File

/**
 * Everything the UI reads, held in memory and snapshotted to disk.
 *
 * A watch mesh is small — a few hundred nodes and a bounded message history — so a
 * relational store would cost more in build complexity and query indirection than it
 * would ever pay back. Instead state lives in [StateFlow]s (which Compose observes
 * directly) and is written out as one debounced JSON snapshot, so the app opens with a
 * populated node list even before the radio reconnects.
 */
class MeshStore(
    /** Where the snapshot lives. A plain file rather than a Context, so tests can run it. */
    private val file: File,
    private val scope: CoroutineScope,
    /** Channels the wearer has silenced on this watch; see [com.watchtastic.platform.Prefs]. */
    private val mutedChannels: StateFlow<Set<Int>>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    companion object {
        private const val TAG = "MeshStore"
        private const val SNAPSHOT_DEBOUNCE_MS = 1_500L

        /** The snapshot's file name inside the app's private files directory. */
        const val FILE_NAME = "mesh-store.json"
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _myNodeNum = MutableStateFlow(0)
    val myNodeNum: StateFlow<Int> = _myNodeNum.asStateFlow()

    private val _nodes = MutableStateFlow<Map<Int, MeshNode>>(emptyMap())
    val nodes: StateFlow<Map<Int, MeshNode>> = _nodes.asStateFlow()

    private val _channels = MutableStateFlow<List<MeshChannel>>(emptyList())
    val channels: StateFlow<List<MeshChannel>> = _channels.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _radioInfo = MutableStateFlow(RadioInfo())
    val radioInfo: StateFlow<RadioInfo> = _radioInfo.asStateFlow()

    private val _waypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()

    /**
     * The last full config protos the radio sent us, kept verbatim and in memory only.
     *
     * Config writes are replace-not-merge: `set_config` with a freshly built message
     * resets every field it doesn't mention. A `LoRaConfig` rebuilt from the handful of
     * values this app displays would quietly zero `tx_power`, `channel_num`,
     * `override_frequency` and the rest. Keeping the original lets every edit go out as
     * `existing.toBuilder()`, so only the field the wearer touched actually changes.
     */
    private val _loraConfig = MutableStateFlow<ConfigProtos.Config.LoRaConfig?>(null)
    val loraConfig: StateFlow<ConfigProtos.Config.LoRaConfig?> = _loraConfig.asStateFlow()

    private val _deviceConfig = MutableStateFlow<ConfigProtos.Config.DeviceConfig?>(null)
    val deviceConfig: StateFlow<ConfigProtos.Config.DeviceConfig?> = _deviceConfig.asStateFlow()

    fun setLoraConfig(config: ConfigProtos.Config.LoRaConfig) {
        _loraConfig.value = config
    }

    fun setDeviceConfig(config: ConfigProtos.Config.DeviceConfig) {
        _deviceConfig.value = config
    }

    /** Conversation key -> timestamp the user last opened it, for unread counts. */
    private val _lastRead = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastRead: StateFlow<Map<String, Long>> = _lastRead.asStateFlow()

    /** Fires for each freshly received message so the service can notify + buzz. */
    val inboundMessages = MutableSharedFlow<ChatMessage>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Fires the first time a node number is ever seen.
     *
     * Emitted for every new node including the whole node DB during config download —
     * filtering that burst is the consumer's job, because only it knows whether the link
     * is mid-sync or settled.
     */
    val discoveredNodes = MutableSharedFlow<MeshNode>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val saveRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @OptIn(FlowPreview::class)
    private fun startSaver() {
        scope.launch {
            saveRequests.debounce(SNAPSHOT_DEBOUNCE_MS).collect { persist() }
        }
    }

    init {
        startSaver()
    }

    private fun requestSave() {
        saveRequests.tryEmit(Unit)
    }

    // ---------------------------------------------------------------- node db

    fun setMyNodeNum(num: Int) {
        _myNodeNum.value = num
        requestSave()
    }

    fun upsertNode(node: MeshNode) {
        if (!_nodes.value.containsKey(node.num) && node.num != _myNodeNum.value) {
            discoveredNodes.tryEmit(node)
        }
        _nodes.update { current ->
            val existing = current[node.num]
            // Radios re-broadcast NodeInfo without position/metrics, so merge rather
            // than replace or we'd flap the detail screen to empty every few minutes.
            val merged = if (existing == null) {
                node
            } else {
                node.copy(
                    position = node.position ?: existing.position,
                    // NodeInfo carries device metrics only; merging keeps the
                    // environment readings and lightning that arrived separately.
                    metrics = node.metrics.mergedOver(existing.metrics),
                    lastHeardSeconds = maxOf(node.lastHeardSeconds, existing.lastHeardSeconds),
                    snr = if (node.snr != 0f) node.snr else existing.snr,
                    rssi = if (node.rssi != 0) node.rssi else existing.rssi,
                    hopsAway = node.hopsAway ?: existing.hopsAway,
                    channelIndex = if (node.channelIndex != 0) {
                        node.channelIndex
                    } else {
                        existing.channelIndex
                    },
                )
            }
            current + (node.num to merged)
        }
        requestSave()
    }

    /** Applies a partial update, creating a placeholder node if we've never seen it. */
    fun mutateNode(num: Int, block: (MeshNode) -> MeshNode) {
        var created: MeshNode? = null
        _nodes.update { current ->
            val existing = current[num]
            val base = existing ?: MeshNode(
                num = num,
                userId = MeshConstants.nodeIdOf(num),
                longName = MeshConstants.nodeIdOf(num),
                shortName = MeshConstants.nodeIdOf(num).takeLast(4),
            )
            val updated = block(base)
            if (existing == null) created = updated
            current + (num to updated)
        }
        // A node can announce itself by any packet, not just NodeInfo, so discovery is
        // reported from here too.
        created?.takeIf { it.num != _myNodeNum.value }?.let { discoveredNodes.tryEmit(it) }
        requestSave()
    }

    fun applyPosition(num: Int, position: NodePosition, heardSeconds: Int) {
        mutateNode(num) {
            it.copy(
                position = position,
                lastHeardSeconds = maxOf(it.lastHeardSeconds, heardSeconds),
            )
        }
    }

    fun applyMetrics(num: Int, metrics: NodeMetrics, heardSeconds: Int) {
        mutateNode(num) { existing ->
            // Telemetry packets carry one variant at a time; keep the fields this
            // packet didn't mention.
            existing.copy(
                metrics = metrics.mergedOver(existing.metrics),
                lastHeardSeconds = maxOf(existing.lastHeardSeconds, heardSeconds),
            )
        }
    }

    fun removeNode(num: Int) {
        _nodes.update { it - num }
        requestSave()
    }

    fun clearNodes() {
        val self = _myNodeNum.value
        _nodes.update { current -> current.filterKeys { it == self } }
        requestSave()
    }

    // ---------------------------------------------------------------- channels

    fun setChannel(channel: MeshChannel) {
        _channels.update { current ->
            (current.filterNot { it.index == channel.index } + channel).sortedBy { it.index }
        }
        requestSave()
    }

    fun setRadioInfo(block: (RadioInfo) -> RadioInfo) {
        _radioInfo.update(block)
        requestSave()
    }

    // ---------------------------------------------------------------- messages

    fun addMessage(message: ChatMessage) {
        _messages.update { current ->
            // Radios can re-deliver a packet after a reconnect; dedupe on packet id.
            if (message.id != 0 && current.any { it.id == message.id && !it.outgoing }) {
                current
            } else {
                trimHistory(current + message)
            }
        }
        if (!message.outgoing) inboundMessages.tryEmit(message)
        requestSave()
    }

    fun updateMessageStatus(id: Int, status: MsgStatus, failureReason: String? = null) {
        _messages.update { current ->
            current.map {
                if (it.id == id && it.outgoing) {
                    it.copy(status = status, failureReason = failureReason ?: it.failureReason)
                } else {
                    it
                }
            }
        }
        requestSave()
    }

    fun deleteMessage(id: Int) {
        _messages.update { current -> current.filterNot { it.id == id && it.outgoing } }
        requestSave()
    }

    fun deleteConversation(key: String) {
        _messages.update { current -> current.filterNot { it.conversation == key } }
        requestSave()
    }

    fun markRead(key: String) {
        _lastRead.update { it + (key to System.currentTimeMillis()) }
        requestSave()
    }

    fun upsertWaypoint(waypoint: Waypoint) {
        _waypoints.update { current ->
            current.filterNot { it.id == waypoint.id } + waypoint
        }
        requestSave()
    }

    fun deleteWaypoint(id: Int) {
        _waypoints.update { current -> current.filterNot { it.id == id } }
        requestSave()
    }

    // ---------------------------------------------------------------- derived

    /**
     * Channel conversations always appear (they're addressable even when silent);
     * direct conversations only appear once there is traffic.
     */
    /**
     * Channels paired with the name they should actually display.
     *
     * Folded in before the main combine because the primary channel's name depends on the
     * modem preset, and `combine` only has typed overloads up to five flows.
     */
    private val namedChannels = combine(_channels, _radioInfo) { channels, info ->
        channels.map { it to it.resolveName(info.modemPreset) }
    }

    val conversations: kotlinx.coroutines.flow.Flow<List<Conversation>> =
        combine(
            _messages,
            _nodes,
            namedChannels,
            _lastRead,
            mutedChannels,
        ) { messages, nodes, channels, lastRead, muted ->
            val byConversation = messages.filterNot { it.isReaction }.groupBy { it.conversation }

            val channelRows = channels.filter { it.first.isEnabled }.map { (channel, title) ->
                val key = ConversationKey.channel(channel.index)
                val msgs = byConversation[key].orEmpty()
                Conversation(
                    key = key,
                    title = title,
                    subtitle = msgs.lastOrNull()?.let { preview(it, nodes) } ?: "No messages yet",
                    lastMessage = msgs.lastOrNull(),
                    unreadCount = unreadIn(msgs, lastRead[key]),
                    isChannel = true,
                    channelIndex = channel.index,
                    isMuted = channel.index in muted,
                )
            }

            val directRows = byConversation.keys
                .filterNot { ConversationKey.isChannel(it) }
                .mapNotNull { key ->
                    val nodeNum = ConversationKey.nodeNum(key) ?: return@mapNotNull null
                    val msgs = byConversation[key].orEmpty()
                    val node = nodes[nodeNum]
                    Conversation(
                        key = key,
                        title = node?.displayLong ?: MeshConstants.nodeIdOf(nodeNum),
                        subtitle = msgs.lastOrNull()?.let { preview(it, nodes) } ?: "",
                        lastMessage = msgs.lastOrNull(),
                        unreadCount = unreadIn(msgs, lastRead[key]),
                        isChannel = false,
                        nodeNum = nodeNum,
                        isMuted = node?.isMuted == true,
                    )
                }

            (directRows + channelRows).sortedWith(
                compareByDescending<Conversation> { it.unreadCount > 0 }
                    .thenByDescending { it.lastMessage?.timeMs ?: 0L },
            )
        }

    private fun preview(message: ChatMessage, nodes: Map<Int, MeshNode>): String {
        val who = when {
            message.outgoing -> "You"
            else -> nodes[message.fromNum]?.displayShort ?: MeshConstants.nodeIdOf(message.fromNum)
        }
        return "$who: ${message.text}"
    }

    private fun unreadIn(messages: List<ChatMessage>, lastReadMs: Long?): Int {
        val since = lastReadMs ?: 0L
        return messages.count { !it.outgoing && it.timeMs > since }
    }

    fun totalUnread(): Int = countUnread(_messages.value, _lastRead.value)

    /**
     * Unread total as a flow, for surfaces that must follow it both ways.
     *
     * Reading a conversation has to *lower* the count as promptly as a new message
     * raises it. The ongoing notification used to refresh only on arrival, so its badge
     * kept claiming unread messages long after they had been read — and the tile and
     * complication would inherit the same staleness without this.
     */
    val unreadTotal: StateFlow<Int> = combine(_messages, _lastRead, ::countUnread)
        .stateIn(scope, SharingStarted.Eagerly, 0)

    // ---------------------------------------------------------------- snapshot

    @Serializable
    private data class Snapshot(
        val myNodeNum: Int = 0,
        val nodes: List<MeshNode> = emptyList(),
        val channels: List<MeshChannel> = emptyList(),
        val messages: List<ChatMessage> = emptyList(),
        val radioInfo: RadioInfo = RadioInfo(),
        val waypoints: List<Waypoint> = emptyList(),
        val lastRead: Map<String, Long> = emptyMap(),
    )

    /**
     * Completes once the snapshot has been read (or found absent).
     *
     * A tile or complication can be the thing that starts the process, and it asks for
     * data immediately. Answering before [load] finishes would render "0 unread" and an
     * empty mesh for a moment even when neither is true.
     */
    private val loadedGate = CompletableDeferred<Unit>()

    suspend fun awaitLoaded() = loadedGate.await()

    suspend fun load() = withContext(ioDispatcher) {
        try {
            loadSnapshot()
        } finally {
            loadedGate.complete(Unit)
        }
    }

    private fun loadSnapshot() {
        if (!file.exists()) return
        runCatching {
            val snapshot = json.decodeFromString<Snapshot>(file.readText())
            _myNodeNum.value = snapshot.myNodeNum
            _nodes.value = snapshot.nodes.associateBy { it.num }
            _channels.value = snapshot.channels
            _messages.value = snapshot.messages
            _radioInfo.value = snapshot.radioInfo
            _waypoints.value = snapshot.waypoints
            _lastRead.value = snapshot.lastRead
        }.onFailure {
            // Set aside rather than deleted. Starting fresh is the right move for the
            // app, but if the cause is a decoding bug in a future version rather than a
            // damaged file, deleting it would have destroyed the wearer's history for
            // good. One copy is kept; a later failure replaces it.
            Log.w(TAG, "snapshot unreadable, starting fresh", it)
            runCatching {
                val aside = File(file.parentFile, "${file.name}.unreadable")
                aside.delete()
                if (!file.renameTo(aside)) file.delete()
            }
        }
    }

    private suspend fun persist() = withContext(ioDispatcher) {
        runCatching {
            val snapshot = Snapshot(
                myNodeNum = _myNodeNum.value,
                nodes = _nodes.value.values.toList(),
                channels = _channels.value,
                messages = _messages.value,
                radioInfo = _radioInfo.value,
                waypoints = _waypoints.value,
                lastRead = _lastRead.value,
            )
            // Write-then-rename: a watch can be yanked off the charger mid-write and we
            // would rather keep the previous good snapshot than a truncated one.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "failed to persist snapshot", it) }
    }

    suspend fun wipe() = withContext(ioDispatcher) {
        _nodes.value = emptyMap()
        _messages.value = emptyList()
        _channels.value = emptyList()
        _waypoints.value = emptyList()
        _lastRead.value = emptyMap()
        _radioInfo.value = RadioInfo()
        _myNodeNum.value = 0
        runCatching { file.delete() }
        Unit
    }
}

private fun countUnread(messages: List<ChatMessage>, lastRead: Map<String, Long>): Int =
    messages.count { it.isUnread(lastRead) }

/** Newest messages kept per conversation. */
internal const val HISTORY_PER_CONVERSATION = 200

/** Newest messages kept overall, across every conversation. */
internal const val HISTORY_TOTAL = 1_000

/**
 * Bounds message history without letting one conversation crowd out the rest.
 *
 * This used to be a single cap of 500 across everything, oldest first — so on a busy
 * public channel, a quiet direct conversation was evicted within a day or two simply
 * because the channel kept talking. Now each conversation keeps its own newest
 * [perConversation] messages, and only beyond [total] does the oldest overall go.
 * Order is preserved; [messages] is in arrival order, oldest first.
 */
internal fun trimHistory(
    messages: List<ChatMessage>,
    perConversation: Int = HISTORY_PER_CONVERSATION,
    total: Int = HISTORY_TOTAL,
): List<ChatMessage> {
    if (messages.size <= minOf(perConversation, total)) return messages

    val excess = messages.groupingBy { it.conversation }.eachCount()
        .mapValues { (_, count) -> count - perConversation }
        .filterValues { it > 0 }
        .toMutableMap()

    val bounded = if (excess.isEmpty()) {
        messages
    } else {
        // Walking oldest-first, skip each over-full conversation's oldest messages.
        messages.filter { message ->
            val skip = excess[message.conversation] ?: 0
            if (skip > 0) {
                excess[message.conversation] = skip - 1
                false
            } else {
                true
            }
        }
    }
    return if (bounded.size > total) bounded.takeLast(total) else bounded
}
