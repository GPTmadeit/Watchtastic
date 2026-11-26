package com.watchtastic.mesh

import com.watchtastic.mesh.model.ChatMessage
import com.watchtastic.mesh.model.MeshNode
import com.watchtastic.mesh.model.MsgStatus
import com.watchtastic.mesh.model.NodeMetrics
import com.watchtastic.mesh.model.Waypoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MeshStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.store(file: File) = MeshStore(
        file = file,
        scope = backgroundScope,
        mutedChannels = MutableStateFlow(emptySet()),
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun msg(id: Int, conversation: String, outgoing: Boolean = false) = ChatMessage(
        id = id,
        conversation = conversation,
        fromNum = 7,
        toNum = 9,
        channel = 0,
        text = "m$id",
        timeMs = 1_000L + id,
        outgoing = outgoing,
        status = if (outgoing) MsgStatus.Sent else MsgStatus.Received,
    )

    // ------------------------------------------------------------- history cap

    @Test
    fun `a busy channel cannot evict a quiet direct conversation`() {
        // The old single cap of 500 dropped the oldest message anywhere, so a public
        // channel talking all day pushed every direct message out within a day or two.
        val dm = (1..5).map { msg(it, "dm:7") }
        val channel = (100 until 100 + HISTORY_PER_CONVERSATION * 3).map { msg(it, "ch:0") }
        val kept = trimHistory(dm + channel)
        assertEquals(dm, kept.filter { it.conversation == "dm:7" })
        assertEquals(HISTORY_PER_CONVERSATION, kept.count { it.conversation == "ch:0" })
    }

    @Test
    fun `each conversation keeps its newest messages, in order`() {
        val all = (1..10).map { msg(it, "ch:0") }
        val kept = trimHistory(all, perConversation = 4, total = 100)
        assertEquals(listOf(7, 8, 9, 10), kept.map { it.id })
    }

    @Test
    fun `the overall cap still bounds the snapshot`() {
        val all = (1..30).map { msg(it, "dm:${it % 10}") }
        val kept = trimHistory(all, perConversation = 50, total = 12)
        assertEquals(12, kept.size)
        assertEquals((19..30).toList(), kept.map { it.id })
    }

    @Test
    fun `small histories pass through untouched`() {
        val all = (1..3).map { msg(it, "ch:0") }
        assertTrue(trimHistory(all) === all)
    }

    // ------------------------------------------------------------- persistence

    @Test
    fun `state survives a restart`() = runTest {
        val file = File(tmp.root, "store.json")
        val first = store(file)
        first.load()
        first.setMyNodeNum(9)
        first.addMessage(msg(1, "dm:7").copy(receivedAtMs = 5_000L))
        first.upsertNode(
            MeshNode(
                num = 7, userId = "!00000007", longName = "Seven", shortName = "SVN",
                metrics = NodeMetrics(lightningStrikes1h = 3),
            ),
        )
        first.markRead("ch:0")
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("snapshot was written", file.exists())

        val second = store(file)
        second.load()
        assertEquals(first.messages.value, second.messages.value)
        assertEquals(first.nodes.value, second.nodes.value)
        assertEquals(first.lastRead.value, second.lastRead.value)
        assertEquals(9, second.myNodeNum.value)
    }

    @Test
    fun `a snapshot written by 1_5_0 still loads`() = runTest {
        // Every field added since must have a default, or upgrading would fail to
        // decode the snapshot — which is handled by starting fresh, i.e. by silently
        // deleting the wearer's history. This is a verbatim 1.5.0-shaped snapshot.
        val file = File(tmp.root, "store.json")
        file.writeText(
            """
            {"myNodeNum":9,"nodes":[{"num":7,"userId":"!00000007","longName":"Seven",
            "shortName":"SVN","hwModel":"UNSET","role":"CLIENT","lastHeardSeconds":0,
            "snr":0.0,"rssi":0,"hopsAway":null,"channelIndex":0,"position":null,
            "metrics":{"batteryLevel":80,"voltage":null,"channelUtilization":null,
            "airUtilTx":null,"uptimeSeconds":null,"temperature":null,"relativeHumidity":null,
            "barometricPressure":null,"lightningStrikes1h":null,"lightningDistanceKm":null},
            "isFavorite":false,"isIgnored":false,"isMuted":false,"viaMqtt":false,
            "hasPublicKey":false,"isUnmessagable":false,"isSelf":false}],
            "channels":[{"index":0,"name":"","role":"PRIMARY","hasKey":true,
            "uplinkEnabled":false,"downlinkEnabled":false}],
            "messages":[{"id":1,"conversation":"dm:7","fromNum":7,"toNum":9,"channel":0,
            "text":"old","timeMs":1000,"outgoing":false,"status":"Received","replyId":0,
            "isReaction":false,"snr":0.0,"rssi":0,"hopsAway":null,"pkiEncrypted":false,
            "failureReason":null}],
            "radioInfo":{"firmwareVersion":"2.7.0","hwModel":"","role":"","region":"",
            "modemPreset":"LONG_FAST","hopLimit":3,"numChannels":1,"hasBluetooth":true,
            "hasWifi":false,"hasPKC":false,"canShutdown":false,"rebootCount":0,
            "nodeDbCount":0,"txEnabled":true},
            "waypoints":[],"lastRead":{"dm:7":500}}
            """.trimIndent(),
        )
        val store = store(file)
        store.load()

        val message = store.messages.value.single()
        assertEquals("old", message.text)
        assertEquals(0L, message.receivedAtMs)
        // With no arrival time recorded, unread falls back to the message's own time.
        assertTrue(message.isUnread(store.lastRead.value))
        assertEquals(80, store.nodes.value.getValue(7).metrics.batteryLevel)
        assertTrue("file kept", file.exists())
    }

    @Test
    fun `an unreadable snapshot starts fresh but is kept, not destroyed`() = runTest {
        val file = File(tmp.root, "store.json")
        file.writeText("{ this is not json")
        val store = store(file)
        store.load()
        assertTrue(store.messages.value.isEmpty())
        assertFalse(file.exists())
        // Set aside for recovery, in case the fault is ours rather than the file's.
        assertEquals("{ this is not json", File(tmp.root, "store.json.unreadable").readText())
    }

    @Test
    fun `deleting a message only removes our own copy`() = runTest {
        val store = store(File(tmp.root, "store.json"))
        store.addMessage(msg(1, "dm:7", outgoing = true))
        store.addMessage(msg(2, "dm:7"))
        store.deleteMessage(1)
        store.deleteMessage(2) // inbound: not ours to delete this way
        assertEquals(listOf(2), store.messages.value.map { it.id })
    }

    // --------------------------------------------------------------- waypoints

    private fun waypoint(id: Int, by: Int, lat: Double = 1.0, lockedTo: Int = 0) = Waypoint(
        id = id,
        name = "camp",
        description = "",
        latitude = lat,
        longitude = 2.0,
        lockedTo = lockedTo,
        createdByNum = by,
    )

    @Test
    fun `the creator can move its own waypoint`() = runTest {
        val store = store(File(tmp.root, "s.json"))
        assertTrue(store.upsertWaypoint(waypoint(1, by = 7)))
        assertTrue(store.upsertWaypoint(waypoint(1, by = 7, lat = 5.0)))
        assertEquals(5.0, store.waypoints.value.single().latitude, 0.0)
    }

    @Test
    fun `another node cannot move someone else's waypoint`() = runTest {
        val store = store(File(tmp.root, "s.json"))
        store.upsertWaypoint(waypoint(1, by = 7))
        assertFalse(store.upsertWaypoint(waypoint(1, by = 8, lat = 99.0)))
        assertEquals(1.0, store.waypoints.value.single().latitude, 0.0)
        assertEquals(7, store.waypoints.value.single().createdByNum)
    }

    @Test
    fun `a waypoint cannot be locked to a different node`() = runTest {
        val store = store(File(tmp.root, "s.json"))
        assertFalse(store.upsertWaypoint(waypoint(1, by = 8, lockedTo = 7)))
        assertTrue(store.waypoints.value.isEmpty())
    }
}
