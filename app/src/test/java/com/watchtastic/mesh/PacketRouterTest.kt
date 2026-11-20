package com.watchtastic.mesh

import com.google.protobuf.ByteString
import com.watchtastic.mesh.model.ChatMessage
import com.watchtastic.mesh.model.MsgStatus
import com.watchtastic.mesh.model.TraceRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.meshtastic.proto.AdminProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.MeshProtos.FromRadio
import org.meshtastic.proto.MeshProtos.MeshPacket
import org.meshtastic.proto.Portnums.PortNum
import org.meshtastic.proto.TelemetryProtos
import java.io.File

/**
 * Real `FromRadio` frames through the real router into a real store.
 *
 * This is where the protocol's sharp edges live, and until now none of it ran under
 * test — which is how lightning telemetry could be decoded correctly and then dropped one
 * step later, unnoticed. Everything here is a frame a radio actually sends.
 */
class PacketRouterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val nowSeconds = (now / 1000).toInt()
    private val self = 0x0a0b0c0d
    private val alice = 0x11111111
    private val bob = 0x22222222

    private class RecordingEvents : PacketEvents {
        val receipts = mutableListOf<Pair<Int, MeshProtos.Routing.Error>>()
        val configComplete = mutableListOf<Int>()
        override fun onDeliveryReceipt(requestId: Int, error: MeshProtos.Routing.Error) {
            receipts += requestId to error
        }
        override fun onTraceRoute(result: TraceRoute) = Unit
        override fun onAdminReply(message: AdminProtos.AdminMessage) = Unit
        override fun onClientNotification(text: String) = Unit
        override fun onRadioRebooted() = Unit
        override fun onConfigComplete(nonce: Int) {
            configComplete += nonce
        }
        override fun onConfigProgress() = Unit
    }

    private val events = RecordingEvents()

    private fun TestScope.newStore(file: File = File(tmp.root, "store.json")) = MeshStore(
        file = file,
        scope = backgroundScope,
        mutedChannels = MutableStateFlow(emptySet()),
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    /** A store and router that have already been told which node is ours. */
    private fun TestScope.connected(): Pair<MeshStore, PacketRouter> {
        val store = newStore()
        val router = PacketRouter(store, events) { now }
        router.handle(
            FromRadio.newBuilder()
                .setMyInfo(MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(self))
                .build(),
        )
        return store to router
    }

    private var nextId = 1000

    private fun packet(
        from: Int,
        to: Int,
        port: PortNum,
        payload: ByteString,
        channel: Int = 0,
        id: Int = nextId++,
        rxTime: Int? = nowSeconds,
        data: MeshProtos.Data.Builder.() -> Unit = {},
    ): FromRadio {
        val packet = MeshPacket.newBuilder()
            .setFrom(from)
            .setTo(to)
            .setId(id)
            .setChannel(channel)
            .setDecoded(
                MeshProtos.Data.newBuilder().setPortnum(port).setPayload(payload).apply(data),
            )
        rxTime?.let { packet.setRxTime(it) }
        return FromRadio.newBuilder().setPacket(packet).build()
    }

    private fun text(from: Int, to: Int, body: String, channel: Int = 0, id: Int = nextId++,
                     rxTime: Int? = nowSeconds, data: MeshProtos.Data.Builder.() -> Unit = {}) =
        packet(from, to, PortNum.TEXT_MESSAGE_APP, ByteString.copyFromUtf8(body), channel, id, rxTime, data)

    private fun telemetry(from: Int, build: TelemetryProtos.Telemetry.Builder.() -> Unit) =
        packet(
            from, MeshConstants.BROADCAST_ADDR, PortNum.TELEMETRY_APP,
            TelemetryProtos.Telemetry.newBuilder().apply(build).build().toByteString(),
        )

    // ------------------------------------------------------------------ messages

    @Test
    fun `a direct message lands in its sender's conversation, unread`() = runTest {
        val (store, router) = connected()
        router.handle(text(alice, self, "meet at the saddle"))

        val message = store.messages.value.single()
        assertEquals("dm:$alice", message.conversation)
        assertEquals("meet at the saddle", message.text)
        assertEquals(now, message.receivedAtMs)
        assertEquals(1, store.totalUnread())
    }

    @Test
    fun `a broadcast lands in the channel it arrived on`() = runTest {
        val (store, router) = connected()
        router.handle(text(alice, MeshConstants.BROADCAST_ADDR, "relay is up", channel = 2))
        assertEquals("ch:2", store.messages.value.single().conversation)
    }

    @Test
    fun `traffic between two other nodes is not ours to show, but they were heard`() = runTest {
        val (store, router) = connected()
        router.handle(text(alice, bob, "private"))
        assertTrue(store.messages.value.isEmpty())
        assertEquals(nowSeconds, store.nodes.value.getValue(alice).lastHeardSeconds)
    }

    @Test
    fun `a tapback is a reaction, never an unread message`() = runTest {
        val (store, router) = connected()
        router.handle(text(alice, self, "👍") { setEmoji(1).setReplyId(77) })
        val reaction = store.messages.value.single()
        assertTrue(reaction.isReaction)
        assertEquals(77, reaction.replyId)
        assertEquals(0, store.totalUnread())
    }

    @Test
    fun `a packet re-delivered after a reconnect is stored once`() = runTest {
        val (store, router) = connected()
        router.handle(text(alice, self, "once", id = 42))
        router.handle(text(alice, self, "once", id = 42))
        assertEquals(1, store.messages.value.size)
    }

    // ---------------------------------------------------------------- timestamps

    @Test
    fun `no rx_time means the radio had no clock, so the watch's is used`() = runTest {
        val (store, router) = connected()
        router.handle(text(alice, self, "hi", rxTime = null))
        assertEquals(now, store.messages.value.single().timeMs)
    }

    @Test
    fun `an uptime-sized rx_time is not read as 1970`() = runTest {
        val (store, router) = connected()
        router.handle(text(alice, self, "hi", rxTime = 3_600))
        assertEquals(now, store.messages.value.single().timeMs)
    }

    @Test
    fun `a radio clock far ahead of the watch is not trusted`() = runTest {
        val (store, router) = connected()
        router.handle(text(alice, self, "hi", rxTime = nowSeconds + 3_600))
        assertEquals(now, store.messages.value.single().timeMs)
    }

    @Test
    fun `a message the radio held while we were away is unread despite being old`() = runTest {
        val (store, router) = connected()
        // Heard by the radio an hour ago while the watch was out of range, and only
        // delivered now. The wearer last opened the thread half an hour ago, so has
        // never seen it — but judged by the radio's timestamp it was "already read".
        router.handle(text(alice, self, "you there?", rxTime = nowSeconds - 3_600))
        val lastOpened = mapOf("dm:$alice" to now - 1_800_000)

        val message = store.messages.value.single()
        assertEquals(now - 3_600_000, message.timeMs) // keeps the true time for display
        assertTrue(message.isUnread(lastOpened))
    }

    @Test
    fun `node info with a boot-relative last-heard counts as never heard`() = runTest {
        val (store, router) = connected()
        router.handle(nodeInfo(alice, lastHeard = 5_000))
        assertEquals(0, store.nodes.value.getValue(alice).lastHeardSeconds)
    }

    @Test
    fun `boot-relative node info does not erase when we actually heard a node`() = runTest {
        val (store, router) = connected()
        router.handle(text(alice, MeshConstants.BROADCAST_ADDR, "hello"))
        router.handle(nodeInfo(alice, lastHeard = 5_000))
        assertEquals(nowSeconds, store.nodes.value.getValue(alice).lastHeardSeconds)
    }

    private fun nodeInfo(
        num: Int,
        lastHeard: Int,
        battery: Int? = null,
    ): FromRadio {
        val info = MeshProtos.NodeInfo.newBuilder()
            .setNum(num)
            .setUser(
                MeshProtos.User.newBuilder()
                    .setId(MeshConstants.nodeIdOf(num))
                    .setLongName("Alice")
                    .setShortName("ALI"),
            )
            .setLastHeard(lastHeard)
        battery?.let {
            info.setDeviceMetrics(TelemetryProtos.DeviceMetrics.newBuilder().setBatteryLevel(it))
        }
        return FromRadio.newBuilder().setNodeInfo(info).build()
    }

    // ----------------------------------------------------------------- telemetry

    @Test
    fun `lightning telemetry is kept all the way into the store`() = runTest {
        // The 1.5.0 regression, end to end: decoded correctly, then dropped by the merge.
        val (store, router) = connected()
        router.handle(
            telemetry(alice) {
                setEnvironmentMetrics(
                    TelemetryProtos.EnvironmentMetrics.newBuilder()
                        .setLightningStrikeCount1H(4)
                        .setLightningDistanceKm(7.5f),
                )
            },
        )
        val metrics = store.nodes.value.getValue(alice).metrics
        assertEquals(4, metrics.lightningStrikes1h)
        assertEquals(7.5f, metrics.lightningDistanceKm)
        assertTrue(metrics.hasLightning)
    }

    @Test
    fun `device telemetry after environment telemetry keeps both`() = runTest {
        val (store, router) = connected()
        router.handle(
            telemetry(alice) {
                setEnvironmentMetrics(
                    TelemetryProtos.EnvironmentMetrics.newBuilder().setTemperature(11.5f),
                )
            },
        )
        router.handle(
            telemetry(alice) {
                setDeviceMetrics(TelemetryProtos.DeviceMetrics.newBuilder().setBatteryLevel(78))
            },
        )
        val metrics = store.nodes.value.getValue(alice).metrics
        assertEquals(11.5f, metrics.temperature)
        assertEquals(78, metrics.batteryLevel)
    }

    @Test
    fun `a node info re-broadcast does not wipe environment readings`() = runTest {
        // NodeInfo carries device metrics only, and arrives for every node on every
        // reconnect; it used to replace the whole telemetry block.
        val (store, router) = connected()
        router.handle(
            telemetry(alice) {
                setEnvironmentMetrics(
                    TelemetryProtos.EnvironmentMetrics.newBuilder()
                        .setTemperature(11.5f)
                        .setLightningStrikeCount1H(2),
                )
            },
        )
        router.handle(nodeInfo(alice, lastHeard = nowSeconds, battery = 64))
        val metrics = store.nodes.value.getValue(alice).metrics
        assertEquals(11.5f, metrics.temperature)
        assertEquals(2, metrics.lightningStrikes1h)
        assertEquals(64, metrics.batteryLevel)
    }

    // ------------------------------------------------------------ delivery state

    private fun outgoing(id: Int) = ChatMessage(
        id = id,
        conversation = "dm:$alice",
        fromNum = self,
        toNum = alice,
        channel = 0,
        text = "on my way",
        timeMs = now,
        outgoing = true,
        status = MsgStatus.Queued,
    )

    private fun queueStatus(id: Int, res: Int) = FromRadio.newBuilder()
        .setQueueStatus(MeshProtos.QueueStatus.newBuilder().setMeshPacketId(id).setRes(res))
        .build()

    @Test
    fun `the radio accepting a message marks it sent`() = runTest {
        val (store, router) = connected()
        store.addMessage(outgoing(42))
        router.handle(queueStatus(42, res = 0))
        assertEquals(MsgStatus.Sent, store.messages.value.single().status)
    }

    @Test
    fun `the radio refusing a message marks it failed, with a reason`() = runTest {
        val (store, router) = connected()
        store.addMessage(outgoing(42))
        router.handle(queueStatus(42, res = 3))
        val message = store.messages.value.single()
        assertEquals(MsgStatus.Failed, message.status)
        assertTrue(message.failureReason!!.isNotBlank())
    }

    @Test
    fun `a routing reply is reported as a delivery receipt`() = runTest {
        val (_, router) = connected()
        router.handle(
            packet(
                alice, self, PortNum.ROUTING_APP,
                MeshProtos.Routing.newBuilder()
                    .setErrorReason(MeshProtos.Routing.Error.NONE).build().toByteString(),
            ) { setRequestId(42) },
        )
        assertEquals(listOf(42 to MeshProtos.Routing.Error.NONE), events.receipts)
    }

    @Test
    fun `config complete is handed on with its nonce`() = runTest {
        val (_, router) = connected()
        router.handle(FromRadio.newBuilder().setConfigCompleteId(9_876).build())
        assertEquals(listOf(9_876), events.configComplete)
    }

    // ------------------------------------------------------------------- misc

    @Test
    fun `a waypoint without coordinates is ignored`() = runTest {
        val (store, router) = connected()
        router.handle(
            packet(
                alice, MeshConstants.BROADCAST_ADDR, PortNum.WAYPOINT_APP,
                MeshProtos.Waypoint.newBuilder().setId(5).setName("Camp").build().toByteString(),
            ),
        )
        assertTrue(store.waypoints.value.isEmpty())
    }

    @Test
    fun `a garbage payload is survived, not thrown`() = runTest {
        val (store, router) = connected()
        router.handle(
            packet(alice, self, PortNum.TELEMETRY_APP, ByteString.copyFrom(byteArrayOf(-1, -1, -1))),
        )
        // The node was still heard; nothing else changed.
        assertFalse(store.nodes.value.getValue(alice).metrics.hasTelemetry)
        assertNull(store.messages.value.firstOrNull())
    }
}
