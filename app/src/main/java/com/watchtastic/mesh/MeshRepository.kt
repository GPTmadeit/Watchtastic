package com.watchtastic.mesh

import android.content.Context
import android.util.Log
import com.google.protobuf.ByteString
import com.watchtastic.mesh.ble.BleScanner
import com.watchtastic.mesh.ble.BondManager
import com.watchtastic.mesh.ble.GattException
import com.watchtastic.mesh.ble.RadioSession
import com.watchtastic.mesh.model.ChatMessage
import com.watchtastic.mesh.model.ConversationKey
import com.watchtastic.mesh.model.LinkState
import com.watchtastic.mesh.model.MsgStatus
import com.watchtastic.mesh.model.NodePosition
import com.watchtastic.mesh.model.TraceRoute
import com.watchtastic.mesh.model.Waypoint
import com.watchtastic.platform.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The app's single door to the radio.
 *
 * Owns the connect/retry lifecycle, the config-download handshake, and every outbound
 * operation. The UI never touches [RadioSession] directly — it reads flows from here and
 * from [MeshStore], which keeps all the ordering rules in one place.
 */
class MeshRepository(
    private val context: Context,
    val store: MeshStore,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) : PacketEvents {

    private companion object {
        const val TAG = "MeshRepository"

        /** Config download is chatty; a big node DB legitimately takes a while. */
        const val CONFIG_TIMEOUT_MS = 90_000L

        /** How long to wait for a Routing ACK before calling a message failed. */
        const val ACK_TIMEOUT_MS = 60_000L

        const val BACKOFF_START_MS = 1_000L
        const val BACKOFF_MAX_MS = 30_000L

        /**
         * Rough denominator for sync progress. The radio never says how many frames the
         * config download will contain, so we show progress against a typical burst and
         * clamp below 1.0 — an honest approximation beats a fake spinner.
         */
        const val EXPECTED_CONFIG_ITEMS = 40f

        /** Coordinates travel as integers scaled by 1e7. */
        const val COORD_SCALE = 1e7

        /** `Data.bitfield` bit 0: the sender approves this packet being relayed to MQTT. */
        const val BITFIELD_OK_TO_MQTT = 1

        /**
         * How long a message may wait in the outbox for the radio. Long enough to ride
         * out a reconnect cycle or a radio reboot; short enough that "on my way" is
         * never delivered an afternoon late.
         */
        const val OUTBOX_MAX_AGE_MS = 30 * 60 * 1000L

        const val OUTBOX_EXPIRED = "Radio out of reach"
    }

    val scanner = BleScanner(context)
    private val bonds = BondManager(context)
    private val router = PacketRouter(store, this)

    private val _link = MutableStateFlow<LinkState>(LinkState.Idle)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    /** One-shot messages for the UI (errors, admin confirmations). */
    val notices = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val _traceRoutes = MutableStateFlow<List<TraceRoute>>(emptyList())
    val traceRoutes: StateFlow<List<TraceRoute>> = _traceRoutes.asStateFlow()

    val inboundMessages: SharedFlow<ChatMessage> = store.inboundMessages

    @Volatile
    private var session: RadioSession? = null

    private var lifecycleJob: Job? = null
    private var collectorJob: Job? = null
    private var heartbeatJob: Job? = null

    /** Serialises radio switches so one attempt is fully torn down before the next starts. */
    private val switchMutex = Mutex()

    @Volatile
    private var configNonce = 0

    @Volatile
    private var configItems = 0

    @Volatile
    private var configGate: CompletableDeferred<Unit>? = null

    /**
     * Ids of outgoing messages written to the radio by this process. "Queued" alone
     * can't tell the outbox apart from a write still awaiting its QueueStatus, and only
     * the former may be flushed.
     */
    private val inFlight: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Serialises outbox flushes, since a reconnect and a resync can both trigger one. */
    private val outboxMutex = Mutex()

    /** Set when the user explicitly disconnects, so the retry loop doesn't fight them. */
    @Volatile
    private var userStopped = false

    private fun notify(text: String) {
        notices.tryEmit(text)
    }

    // ------------------------------------------------------------- lifecycle

    /** Reconnects to the remembered radio, if there is one and auto-connect is on. */
    fun resumeSavedRadio() {
        val address = prefs.radioAddress.value ?: return
        if (!prefs.autoConnect.value) return
        if (_link.value.isUsable) return
        connect(address, prefs.radioName.value ?: address)
    }

    /**
     * Switches to [address], tearing the previous link down first.
     *
     * The wait matters. Cancelling a job only *requests* it stop; its `finally` still has
     * to run. Firing a new attempt immediately let the old attempt's cleanup land after
     * the new session was already in the field and abort it — so tapping a second radio
     * appeared to connect and then silently reverted to the first. Serialising through
     * [switchMutex] and joining makes that ordering impossible.
     */
    fun connect(address: String, name: String) {
        userStopped = false
        scope.launch {
            switchMutex.withLock {
                lifecycleJob?.cancelAndJoin()
                lifecycleJob = scope.launch { runLink(address, name) }
            }
        }
    }

    fun disconnect() {
        userStopped = true
        scope.launch {
            lifecycleJob?.cancelAndJoin()
            lifecycleJob = null
            teardownSession(target = null, graceful = true)
            _link.value = LinkState.Idle
        }
    }

    fun forgetRadio() {
        disconnect()
        prefs.forgetRadio()
        scope.launch { store.wipe() }
    }

    val isConnected: Boolean get() = session != null && _link.value.isUsable

    /**
     * Connect, sync, hold, and on loss retry with capped exponential backoff.
     *
     * A watch drifts in and out of range of a radio sitting in a pack all day, so a
     * dropped link is the normal case rather than an error — the loop treats it as such
     * and keeps the last-known node DB on screen throughout.
     */
    private suspend fun runLink(address: String, name: String) {
        var backoff = BACKOFF_START_MS
        var attempt = 0

        // The radio's config download writes into the store. Starting it before the
        // snapshot has finished loading would let the load land on top and replace live
        // state with the previous session's.
        store.awaitLoaded()

        while (coroutineContext.isActive && !userStopped) {
            // Scoped to this attempt, so the cleanup below can only ever tear down the
            // session this iteration opened — never one a newer attempt has since put in
            // place.
            var mine: RadioSession? = null
            try {
                _link.value = if (attempt == 0) {
                    LinkState.Connecting(name)
                } else {
                    LinkState.Reconnecting(name, attempt)
                }

                if (!scanner.isBluetoothOn) throw GattException("Bluetooth is off")
                val device = scanner.deviceFor(address)
                    ?: throw GattException("Can't find that radio")

                if (!bonds.isBonded(device)) {
                    val paired = bonds.ensureBonded(device) {
                        _link.value = LinkState.Pairing(name)
                    }
                    if (!paired) throw GattException("Pairing was not completed")
                }

                val s = RadioSession(context, device, scope)
                mine = s
                session = s
                // Start consuming before open(), because open() drains the FIFO and a
                // collector attached afterwards would miss that first burst.
                collectorJob = scope.launch {
                    s.incoming.collect { frame ->
                        runCatching { router.handle(frame) }
                            .onFailure { Log.w(TAG, "packet handling failed", it) }
                    }
                }

                s.open()
                syncConfig(s, name)

                prefs.rememberRadio(address, s.deviceName.ifBlank { name })
                _link.value = LinkState.Connected(name)
                attempt = 0
                backoff = BACKOFF_START_MS
                startHeartbeat(s)
                syncRadioClock()
                flushOutbox()

                // Park here for the life of the connection. A deferred, not a flow, so a
                // drop that happened while we were busy above is still seen.
                val status = s.dropped.await()
                Log.i(TAG, "link dropped (status=$status)")
                if (userStopped) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "link attempt failed: ${e.message}")
                _link.value = LinkState.Failed(e.message ?: "Connection failed")
            } finally {
                teardownSession(mine, graceful = false)
            }

            if (userStopped || !coroutineContext.isActive) break
            attempt++
            // Leave the failure on screen while we wait; the loop head will flip to
            // "Reconnecting" the moment it actually tries again.
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
        }
    }

    /** Sends `want_config_id` and waits for the matching `config_complete_id`. */
    private suspend fun syncConfig(s: RadioSession, name: String) {
        val nonce = Random.nextInt(1, Int.MAX_VALUE)
        configNonce = nonce
        configItems = 0
        val gate = CompletableDeferred<Unit>()
        configGate = gate
        _link.value = LinkState.Syncing(name, 0f)
        s.requestConfig(nonce)
        try {
            withTimeout(CONFIG_TIMEOUT_MS) {
                // Fail fast if the link goes mid-download. Waiting on the gate alone sat
                // out the whole timeout on a dead link — and the edge of range, where
                // links drop, is exactly where reconnects (and so syncs) happen.
                select<Unit> {
                    gate.onAwait { }
                    s.dropped.onAwait { throw GattException("Radio disconnected during sync") }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw GattException("Radio stopped responding during sync")
        } finally {
            configGate = null
        }
    }

    private fun startHeartbeat(s: RadioSession) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (coroutineContext.isActive) {
                delay(MeshConstants.HEARTBEAT_INTERVAL_MS)
                runCatching { s.sendHeartbeat() }
                    .onFailure { Log.w(TAG, "heartbeat failed: ${it.message}") }
            }
        }
    }

    /**
     * Tears down [target] only.
     *
     * The identity check is the point: a losing attempt must never null out the field
     * when a newer session already owns it, or switching radios kills the link that just
     * came up. Passing null means "whatever is current" and is only used by [disconnect].
     */
    private fun teardownSession(target: RadioSession?, graceful: Boolean) {
        val victim = target ?: session
        if (victim == null) return
        if (session === victim) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            collectorJob?.cancel()
            collectorJob = null
            session = null
        }
        if (graceful) victim.close() else victim.abort()
    }

    // ------------------------------------------------------------ PacketEvents

    override fun onConfigProgress() {
        configItems++
        val current = _link.value
        if (current is LinkState.Syncing) {
            _link.value = current.copy(
                progress = (configItems / EXPECTED_CONFIG_ITEMS).coerceIn(0f, 0.95f),
            )
        }
    }

    override fun onConfigComplete(nonce: Int) {
        // A stale nonce means this burst belongs to a previous attempt; ignoring it stops
        // us declaring ourselves synced on a half-finished download.
        if (nonce != configNonce) {
            Log.i(TAG, "ignoring config_complete for stale nonce $nonce")
            return
        }
        val enabled = store.channels.value.count { it.isEnabled }
        store.setRadioInfo { it.copy(numChannels = enabled) }
        configGate?.complete(Unit)
    }

    override fun onRadioRebooted() {
        notify("Radio rebooted — resyncing")
        requestResync()
    }

    override fun onDeliveryReceipt(requestId: Int, error: MeshProtos.Routing.Error) {
        if (error == MeshProtos.Routing.Error.NONE) {
            store.updateMessageStatus(requestId, MsgStatus.Delivered)
        } else {
            store.updateMessageStatus(requestId, MsgStatus.Failed, error.humanReason())
        }
    }

    override fun onTraceRoute(result: TraceRoute) {
        _traceRoutes.update { (listOf(result) + it).take(10) }
    }

    override fun onAdminReply(message: AdminProtos.AdminMessage) {
        // Admin replies carry the same payloads as unsolicited config frames, so we
        // re-enter the router rather than duplicating the decode rules here.
        when (message.payloadVariantCase) {
            AdminProtos.AdminMessage.PayloadVariantCase.GET_CHANNEL_RESPONSE ->
                router.handle(
                    MeshProtos.FromRadio.newBuilder()
                        .setChannel(message.getChannelResponse)
                        .build(),
                )

            AdminProtos.AdminMessage.PayloadVariantCase.GET_CONFIG_RESPONSE ->
                router.handle(
                    MeshProtos.FromRadio.newBuilder()
                        .setConfig(message.getConfigResponse)
                        .build(),
                )

            AdminProtos.AdminMessage.PayloadVariantCase.GET_DEVICE_METADATA_RESPONSE ->
                router.handle(
                    MeshProtos.FromRadio.newBuilder()
                        .setMetadata(message.getDeviceMetadataResponse)
                        .build(),
                )

            else -> Unit
        }
    }

    override fun onClientNotification(text: String) {
        if (text.isNotBlank()) notify(text)
    }

    // --------------------------------------------------------------- sending

    private fun requireSession(): RadioSession =
        session ?: throw GattException("Radio is not connected")

    /**
     * Starts a `Data` payload with the MQTT consent flag applied.
     *
     * `Data.bitfield` bit 0 is documented as "user approves the packet being uploaded to
     * MQTT". Leaving the field unset meant an MQTT gateway would relay everyone else's
     * traffic but silently drop anything sent from the watch — messages appeared on the
     * mesh but never reached the logger. It is genuinely a consent flag rather than a
     * transport detail, so it follows a preference; that preference defaults to on
     * because that is how every other client behaves and a mesh with a gateway expects it.
     *
     * Deliberately not applied to admin traffic, which is addressed to our own radio and
     * has no business leaving the mesh.
     */
    private fun airData(portnum: Portnums.PortNum): MeshProtos.Data.Builder =
        MeshProtos.Data.newBuilder()
            .setPortnum(portnum)
            .setBitfield(if (prefs.okToMqtt.value) BITFIELD_OK_TO_MQTT else 0)

    /** Non-zero, so the radio never mistakes it for "assign one yourself". */
    private fun nextPacketId(): Int {
        var id = Random.nextInt()
        while (id == 0) id = Random.nextInt()
        return id
    }

    private suspend fun sendPacket(packet: MeshProtos.MeshPacket) {
        requireSession().send(MeshProtos.ToRadio.newBuilder().setPacket(packet).build())
    }

    private fun packetTo(
        dest: Int,
        channel: Int,
        data: MeshProtos.Data,
        wantAck: Boolean,
        id: Int = nextPacketId(),
    ): MeshProtos.MeshPacket = MeshProtos.MeshPacket.newBuilder()
        .setId(id)
        .setTo(dest)
        .setChannel(channel)
        .setDecoded(data)
        .setWantAck(wantAck)
        .setHopLimit(store.radioInfo.value.hopLimit.coerceIn(1, 7))
        .build()

    /** Resolves a conversation key into the (destination, channel) the radio expects. */
    private fun routeFor(conversation: String): Pair<Int, Int> =
        if (ConversationKey.isChannel(conversation)) {
            val index = ConversationKey.channelIndex(conversation) ?: 0
            MeshConstants.BROADCAST_ADDR to index
        } else {
            val num = ConversationKey.nodeNum(conversation)
                ?: throw GattException("Unknown conversation")
            // Direct messages must go out on the channel that node was heard on.
            num to (store.nodes.value[num]?.channelIndex ?: 0)
        }

    /**
     * Sends a text message — or, when the radio is out of reach, keeps it in the outbox
     * and sends it the moment the link comes back.
     *
     * A watch drifts in and out of range of the radio in a pack all day, so "not
     * connected" is a normal moment to want to reply, not an error. Refusing outright
     * made the wearer wait and retype; now the message is accepted, shown as waiting, and
     * flushed on reconnect (see [flushOutbox]) — or marked failed after
     * [OUTBOX_MAX_AGE_MS], because a reply delivered hours late can do more harm than one
     * that visibly didn't go.
     */
    suspend fun sendText(conversation: String, text: String, replyTo: Int = 0): Result<SendReceipt> =
        runCatching {
            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "Nothing to send" }
            val bytes = trimmed.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MeshConstants.MAX_TEXT_BYTES) {
                "Too long (${bytes.size}/${MeshConstants.MAX_TEXT_BYTES} bytes)"
            }
            val (dest, channel) = routeFor(conversation)
            submit(
                ChatMessage(
                    id = nextPacketId(),
                    conversation = conversation,
                    fromNum = store.myNodeNum.value,
                    toNum = dest,
                    channel = channel,
                    text = trimmed,
                    timeMs = System.currentTimeMillis(),
                    outgoing = true,
                    status = MsgStatus.Queued,
                    replyId = replyTo,
                ),
            )
        }.onFailure { notify(it.message ?: "Send failed") }

    /** Tapback. The `emoji` field is a flag; the payload carries the glyph. */
    suspend fun sendReaction(conversation: String, targetId: Int, emoji: String): Result<SendReceipt> =
        runCatching {
            val (dest, channel) = routeFor(conversation)
            submit(
                ChatMessage(
                    id = nextPacketId(),
                    conversation = conversation,
                    fromNum = store.myNodeNum.value,
                    toNum = dest,
                    channel = channel,
                    text = emoji,
                    timeMs = System.currentTimeMillis(),
                    outgoing = true,
                    status = MsgStatus.Queued,
                    replyId = targetId,
                    isReaction = true,
                ),
            )
        }.onFailure { notify(it.message ?: "Reaction failed") }

    /**
     * Sends a failed message again, as a fresh message at the bottom of the thread.
     *
     * A new packet id rather than a retransmission: the old one may have partly crossed
     * the mesh, and nodes that saw it would silently drop a repeat of the same id.
     */
    suspend fun resend(messageId: Int): Result<SendReceipt> = runCatching {
        val old = store.messages.value.firstOrNull { it.id == messageId && it.outgoing }
            ?: throw GattException("That message is gone")
        require(old.status == MsgStatus.Failed) { "Already on its way" }
        store.deleteMessage(old.id)
        submit(
            old.copy(
                id = nextPacketId(),
                timeMs = System.currentTimeMillis(),
                status = MsgStatus.Queued,
                failureReason = null,
            ),
        )
    }.onFailure { notify(it.message ?: "Resend failed") }

    /** Records [message] in its thread straight away, then sends it or queues it. */
    private suspend fun submit(message: ChatMessage): SendReceipt {
        // Optimistic insert: the bubble appears immediately and its status ticks up as
        // QueueStatus and Routing come back.
        store.addMessage(message)
        val sent = isConnected && transmit(message)
        if (!sent) {
            notify("Queued — sends on reconnect")
            expireIfUnsent(message.id, message.timeMs)
        }
        return SendReceipt(message.id, queued = !sent)
    }

    /**
     * Writes one outgoing message to the radio. Returns false — leaving it queued for the
     * next [flushOutbox] — if the link isn't there to take it.
     *
     * Previously a failed write threw after the message had already been shown as
     * Queued, and since the acknowledgement watchdog was only armed after a successful
     * write, nothing ever moved it on: it sat on "Queued" for good.
     */
    private suspend fun transmit(message: ChatMessage): Boolean {
        // Claimed before writing, so a flush racing a direct send can't double-send it.
        if (!inFlight.add(message.id)) return true
        return try {
            val (dest, channel) = routeFor(message.conversation)
            sendPacket(packetFor(message, dest, channel))
            // Reactions don't ask for an ACK, so there is nothing to wait for.
            if (!message.isReaction) watchForAck(message.id)
            true
        } catch (e: CancellationException) {
            inFlight.remove(message.id)
            throw e
        } catch (e: Exception) {
            inFlight.remove(message.id)
            Log.w(TAG, "send deferred to outbox: ${e.message}")
            false
        }
    }

    private fun packetFor(message: ChatMessage, dest: Int, channel: Int): MeshProtos.MeshPacket {
        val data = airData(Portnums.PortNum.TEXT_MESSAGE_APP)
            .setPayload(ByteString.copyFromUtf8(message.text))
        if (message.replyId != 0) data.setReplyId(message.replyId)
        if (message.isReaction) data.setEmoji(1)
        return packetTo(dest, channel, data.build(), wantAck = !message.isReaction, id = message.id)
    }

    /**
     * Sends everything that queued up while the radio was out of reach, oldest first.
     * Runs once the link is fully synced, so routes reflect the radio's current node DB.
     */
    private suspend fun flushOutbox() = outboxMutex.withLock {
        val now = System.currentTimeMillis()
        val pending = store.messages.value.filter {
            it.outgoing && it.status == MsgStatus.Queued && it.id !in inFlight
        }
        for (message in pending) {
            if (now - message.timeMs > OUTBOX_MAX_AGE_MS) {
                store.updateMessageStatus(message.id, MsgStatus.Failed, OUTBOX_EXPIRED)
            } else if (!transmit(message)) {
                break // The link went again; the rest wait for the next connection.
            }
        }
    }

    /** Fails a message that never left the outbox, once it is too old to be worth sending. */
    private fun expireIfUnsent(id: Int, queuedAtMs: Long) {
        scope.launch {
            delay((queuedAtMs + OUTBOX_MAX_AGE_MS - System.currentTimeMillis()).coerceAtLeast(0L))
            val message = store.messages.value.firstOrNull { it.id == id && it.outgoing }
                ?: return@launch
            if (message.status == MsgStatus.Queued && id !in inFlight) {
                store.updateMessageStatus(id, MsgStatus.Failed, OUTBOX_EXPIRED)
            }
        }
    }

    /**
     * Arms expiry for anything still queued from a previous run — otherwise a message
     * left in the outbox when the process died would show as waiting indefinitely if
     * the radio never came back.
     */
    private fun resumeOutbox() {
        scope.launch {
            store.awaitLoaded()
            store.messages.value
                .filter { it.outgoing && it.status == MsgStatus.Queued }
                .forEach { expireIfUnsent(it.id, it.timeMs) }
        }
    }

    init {
        resumeOutbox()
    }

    /**
     * Marks a message failed if nothing acknowledges it. Without this, a message the
     * mesh silently dropped would sit on "Sent" forever, which reads as success.
     */
    private fun watchForAck(id: Int) {
        scope.launch {
            delay(ACK_TIMEOUT_MS)
            val message = store.messages.value.firstOrNull { it.id == id } ?: return@launch
            if (message.status == MsgStatus.Queued || message.status == MsgStatus.Sent) {
                store.updateMessageStatus(id, MsgStatus.Failed, "No acknowledgement")
            }
        }
    }

    /**
     * Gives the radio the watch's wall-clock time.
     *
     * A radio without GPS has no clock until a client provides one — upstream's own
     * schema says so — and Watchtastic exists for exactly the situation where no phone
     * is around to do it. Without a clock the radio stamps traffic with uptime instead
     * of dates, and its node database's "last heard" times are boot-relative. The
     * firmware applies this at NTP quality, which ranks below GPS, so a radio with a fix
     * keeps its own time; this only ever fills a gap or corrects drift.
     */
    private suspend fun syncRadioClock() {
        runCatching {
            sendAdmin { it.setSetTimeOnly((System.currentTimeMillis() / 1000L).toInt()) }
        }.onFailure { Log.w(TAG, "clock sync failed: ${it.message}") }
    }

    suspend fun requestPosition(nodeNum: Int): Result<Unit> = runCatching {
        val channel = store.nodes.value[nodeNum]?.channelIndex ?: 0
        val data = airData(Portnums.PortNum.POSITION_APP)
            .setPayload(MeshProtos.Position.getDefaultInstance().toByteString())
            .setWantResponse(true)
            .build()
        sendPacket(packetTo(nodeNum, channel, data, wantAck = true))
        notify("Position requested")
    }.onFailure { notify(it.message ?: "Request failed") }

    suspend fun traceRoute(nodeNum: Int): Result<Unit> = runCatching {
        val channel = store.nodes.value[nodeNum]?.channelIndex ?: 0
        val data = airData(Portnums.PortNum.TRACEROUTE_APP)
            .setPayload(MeshProtos.RouteDiscovery.getDefaultInstance().toByteString())
            .setWantResponse(true)
            .build()
        sendPacket(packetTo(nodeNum, channel, data, wantAck = true))
        notify("Tracing route…")
    }.onFailure { notify(it.message ?: "Traceroute failed") }

    /** Broadcasts an attention-grabbing alert (rings buzzers on receiving nodes). */
    suspend fun sendAlert(channelIndex: Int, text: String): Result<Unit> = runCatching {
        val data = airData(Portnums.PortNum.ALERT_APP)
            .setPayload(ByteString.copyFromUtf8(text.take(100)))
            .build()
        sendPacket(packetTo(MeshConstants.BROADCAST_ADDR, channelIndex, data, wantAck = false))
        notify("Alert sent")
    }.onFailure { notify(it.message ?: "Alert failed") }

    /**
     * Hands the watch's own GPS fix to the local node, which then broadcasts it on its
     * own schedule. Addressed to ourselves, not the mesh: this *sets* our position
     * rather than transmitting a position report.
     */
    suspend fun provideOwnPosition(position: NodePosition): Result<Unit> = runCatching {
        val self = store.myNodeNum.value
        require(self != 0) { "Not synced with a radio yet" }
        val builder = MeshProtos.Position.newBuilder()
            .setLatitudeI((position.latitude * COORD_SCALE).toInt())
            .setLongitudeI((position.longitude * COORD_SCALE).toInt())
            .setTime((System.currentTimeMillis() / 1000L).toInt())
            .setLocationSource(MeshProtos.Position.LocSource.LOC_EXTERNAL)
        position.altitudeMeters?.let { builder.setAltitude(it) }
        val data = airData(Portnums.PortNum.POSITION_APP)
            .setPayload(builder.build().toByteString())
            .build()
        sendPacket(packetTo(self, 0, data, wantAck = false))
    }

    /** Pushes our position out to the mesh now, rather than waiting for the radio's timer. */
    suspend fun broadcastPosition(channelIndex: Int): Result<Unit> = runCatching {
        val self = store.myNodeNum.value
        val position = store.nodes.value[self]?.position
            ?: throw GattException("No position to share yet")
        val builder = MeshProtos.Position.newBuilder()
            .setLatitudeI((position.latitude * COORD_SCALE).toInt())
            .setLongitudeI((position.longitude * COORD_SCALE).toInt())
            .setTime((System.currentTimeMillis() / 1000L).toInt())
        position.altitudeMeters?.let { builder.setAltitude(it) }
        val data = airData(Portnums.PortNum.POSITION_APP)
            .setPayload(builder.build().toByteString())
            .build()
        sendPacket(packetTo(MeshConstants.BROADCAST_ADDR, channelIndex, data, wantAck = false))
        notify("Position shared")
    }.onFailure { notify(it.message ?: "Share failed") }

    suspend fun sendWaypoint(
        channelIndex: Int,
        name: String,
        description: String,
        position: NodePosition,
    ): Result<Unit> = runCatching {
        val id = Random.nextInt(1, Int.MAX_VALUE)
        val waypoint = MeshProtos.Waypoint.newBuilder()
            .setId(id)
            .setLatitudeI((position.latitude * COORD_SCALE).toInt())
            .setLongitudeI((position.longitude * COORD_SCALE).toInt())
            .setName(name.take(30))
            .setDescription(description.take(100))
            .build()
        val data = airData(Portnums.PortNum.WAYPOINT_APP)
            .setPayload(waypoint.toByteString())
            .build()

        // Record it locally as well as broadcasting it. The radio does not loop our own
        // broadcasts back to us, so without this a waypoint you drop is transmitted to
        // everyone else and then vanishes from your own list.
        store.upsertWaypoint(
            Waypoint(
                id = id,
                name = name.take(30),
                description = description.take(100),
                latitude = position.latitude,
                longitude = position.longitude,
                createdByNum = store.myNodeNum.value,
            ),
        )

        sendPacket(packetTo(MeshConstants.BROADCAST_ADDR, channelIndex, data, wantAck = false))
        notify("Waypoint shared")
    }.onFailure { notify(it.message ?: "Waypoint failed") }

    // ----------------------------------------------------------------- admin

    /**
     * Admin messages are ordinary packets on the ADMIN_APP port addressed to our own
     * node. Because the link is a bonded BLE connection to the radio in our own pocket,
     * the firmware treats us as locally trusted and no session passkey is needed — that
     * is only required for administering *remote* nodes across the mesh.
     */
    private suspend fun sendAdmin(
        wantResponse: Boolean = false,
        build: (AdminProtos.AdminMessage.Builder) -> Unit,
    ) {
        val self = store.myNodeNum.value
        require(self != 0) { "Not synced with a radio yet" }
        val admin = AdminProtos.AdminMessage.newBuilder().also(build).build()
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.ADMIN_APP)
            .setPayload(admin.toByteString())
            .setWantResponse(wantResponse)
            .build()
        sendPacket(packetTo(self, 0, data, wantAck = false))
    }

    suspend fun setOwner(longName: String, shortName: String): Result<Unit> = runCatching {
        val long = longName.trim().take(39)
        val short = shortName.trim().take(4)
        require(long.isNotEmpty() && short.isNotEmpty()) { "Both names are required" }
        val user = MeshProtos.User.newBuilder()
            .setId(MeshConstants.nodeIdOf(store.myNodeNum.value))
            .setLongName(long)
            .setShortName(short)
            .build()
        sendAdmin { it.setSetOwner(user) }
        // Reflect it locally straight away; the radio echoes it back shortly.
        store.mutateNode(store.myNodeNum.value) {
            it.copy(longName = long, shortName = short)
        }
        notify("Name updated")
    }.onFailure { notify(it.message ?: "Update failed") }

    suspend fun setFavorite(nodeNum: Int, favorite: Boolean): Result<Unit> = runCatching {
        sendAdmin {
            if (favorite) it.setSetFavoriteNode(nodeNum) else it.setRemoveFavoriteNode(nodeNum)
        }
        store.mutateNode(nodeNum) { it.copy(isFavorite = favorite) }
    }.onFailure { notify(it.message ?: "Update failed") }

    suspend fun setIgnored(nodeNum: Int, ignored: Boolean): Result<Unit> = runCatching {
        sendAdmin {
            if (ignored) it.setSetIgnoredNode(nodeNum) else it.setRemoveIgnoredNode(nodeNum)
        }
        store.mutateNode(nodeNum) { it.copy(isIgnored = ignored) }
    }.onFailure { notify(it.message ?: "Update failed") }

    suspend fun toggleMuted(nodeNum: Int): Result<Unit> = runCatching {
        sendAdmin { it.setToggleMutedNode(nodeNum) }
        store.mutateNode(nodeNum) { it.copy(isMuted = !it.isMuted) }
    }.onFailure { notify(it.message ?: "Update failed") }

    suspend fun removeNode(nodeNum: Int): Result<Unit> = runCatching {
        sendAdmin { it.setRemoveByNodenum(nodeNum) }
        store.removeNode(nodeNum)
        notify("Node removed")
    }.onFailure { notify(it.message ?: "Remove failed") }

    /**
     * Changing LoRa config reboots the radio, which is why this is wrapped in
     * begin/commit: the firmware batches the edits and applies them in one pass.
     *
     * The edit starts from the radio's own last-sent `LoRaConfig`. `set_config` replaces
     * the whole message, so building a fresh one here would reset `tx_power`,
     * `channel_num`, `override_frequency` and every other field the watch never shows.
     */
    suspend fun updateLoRaConfig(
        region: ConfigProtos.Config.LoRaConfig.RegionCode? = null,
        preset: ConfigProtos.Config.LoRaConfig.ModemPreset? = null,
        hopLimit: Int? = null,
        txEnabled: Boolean? = null,
    ): Result<Unit> = runCatching {
        val current = store.loraConfig.value
            ?: throw GattException("Radio config not loaded yet")
        val builder = current.toBuilder()
        region?.let { builder.setRegion(it) }
        preset?.let { builder.setModemPreset(it).setUsePreset(true) }
        hopLimit?.let { builder.setHopLimit(it.coerceIn(1, 7)) }
        txEnabled?.let { builder.setTxEnabled(it) }
        val lora = builder.build()

        sendAdmin { it.setBeginEditSettings(true) }
        sendAdmin { it.setSetConfig(ConfigProtos.Config.newBuilder().setLora(lora).build()) }
        sendAdmin { it.setCommitEditSettings(true) }
        store.setLoraConfig(lora)
        notify("Radio settings saved — rebooting")
    }.onFailure { notify(it.message ?: "Save failed") }

    suspend fun setDeviceRole(role: ConfigProtos.Config.DeviceConfig.Role): Result<Unit> =
        runCatching {
            val current = store.deviceConfig.value
                ?: throw GattException("Radio config not loaded yet")
            val device = current.toBuilder().setRole(role).build()
            sendAdmin { it.setBeginEditSettings(true) }
            sendAdmin {
                it.setSetConfig(ConfigProtos.Config.newBuilder().setDevice(device).build())
            }
            sendAdmin { it.setCommitEditSettings(true) }
            store.setDeviceConfig(device)
            notify("Role set to ${role.name}")
        }.onFailure { notify(it.message ?: "Save failed") }

    suspend fun reboot(seconds: Int = 5): Result<Unit> = runCatching {
        sendAdmin { it.setRebootSeconds(seconds) }
        notify("Rebooting radio")
    }.onFailure { notify(it.message ?: "Reboot failed") }

    suspend fun shutdown(seconds: Int = 5): Result<Unit> = runCatching {
        sendAdmin { it.setShutdownSeconds(seconds) }
        notify("Shutting radio down")
    }.onFailure { notify(it.message ?: "Shutdown failed") }

    suspend fun resetNodeDb(): Result<Unit> = runCatching {
        sendAdmin { it.setNodedbReset(true) }
        store.clearNodes()
        notify("Node database cleared")
    }.onFailure { notify(it.message ?: "Reset failed") }

    /** Asks the radio to resend everything; useful after a config change or reboot. */
    fun requestResync() {
        val s = session ?: return
        val name = _link.value.deviceLabel ?: s.deviceName
        scope.launch {
            val synced = runCatching { syncConfig(s, name) }
                .onFailure { notify("Resync failed: ${it.message}") }
                .isSuccess
            if (synced) {
                _link.value = LinkState.Connected(name)
                // Anything sent while the resync had the link marked unusable went to
                // the outbox; without this it waited for the next reconnect, or expired.
                flushOutbox()
            }
        }
    }
}

/** What became of a message handed to [MeshRepository.sendText] and friends. */
data class SendReceipt(
    val id: Int,
    /** True when it is waiting in the outbox for the radio rather than already sent. */
    val queued: Boolean,
)

/** Turns firmware routing codes into something worth showing on a 1.2" screen. */
private fun MeshProtos.Routing.Error.humanReason(): String = when (this) {
    MeshProtos.Routing.Error.NONE -> "Delivered"
    MeshProtos.Routing.Error.NO_ROUTE -> "No route to node"
    MeshProtos.Routing.Error.GOT_NAK -> "Rejected by node"
    MeshProtos.Routing.Error.TIMEOUT -> "Timed out"
    MeshProtos.Routing.Error.NO_INTERFACE -> "No radio interface"
    MeshProtos.Routing.Error.MAX_RETRANSMIT -> "Too many retries"
    MeshProtos.Routing.Error.NO_CHANNEL -> "Channel not available"
    MeshProtos.Routing.Error.TOO_LARGE -> "Message too large"
    MeshProtos.Routing.Error.NO_RESPONSE -> "No response"
    MeshProtos.Routing.Error.DUTY_CYCLE_LIMIT -> "Duty cycle limit reached"
    MeshProtos.Routing.Error.BAD_REQUEST -> "Bad request"
    MeshProtos.Routing.Error.NOT_AUTHORIZED -> "Not authorised"
    MeshProtos.Routing.Error.PKI_FAILED -> "Encryption failed"
    MeshProtos.Routing.Error.PKI_UNKNOWN_PUBKEY -> "Unknown public key"
    MeshProtos.Routing.Error.RATE_LIMIT_EXCEEDED -> "Rate limited"
    else -> "Failed (${name.lowercase().replace('_', ' ')})"
}
