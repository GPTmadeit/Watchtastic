package com.watchtastic.mesh.model

import java.util.Locale
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Where the BLE link is, from the UI's point of view. */
sealed interface LinkState {
    data object Idle : LinkState

    data object Scanning : LinkState

    data class Connecting(val deviceName: String) : LinkState

    /** Bonding needs the user to confirm a PIN shown on the radio's own screen. */
    data class Pairing(val deviceName: String) : LinkState

    /** Streaming the node DB / config down. [progress] is 0f..1f, best-effort. */
    data class Syncing(val deviceName: String, val progress: Float) : LinkState

    data class Connected(val deviceName: String) : LinkState

    data class Reconnecting(val deviceName: String, val attempt: Int) : LinkState

    data class Failed(val reason: String) : LinkState

    val isUsable: Boolean get() = this is Connected

    val deviceLabel: String?
        get() = when (this) {
            is Connecting -> deviceName
            is Pairing -> deviceName
            is Syncing -> deviceName
            is Connected -> deviceName
            is Reconnecting -> deviceName
            else -> null
        }
}

/** A radio we found while scanning. */
data class DiscoveredRadio(
    val address: String,
    val name: String,
    val rssi: Int,
    val bonded: Boolean,
)

@Serializable
data class NodePosition(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Int? = null,
    val timeSeconds: Int = 0,
    val satsInView: Int = 0,
    val groundSpeed: Int? = null,
) {
    /** Great-circle distance in metres. */
    fun distanceTo(other: NodePosition): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadius * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing in degrees true, 0..360. */
    fun bearingTo(other: NodePosition): Float {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }
}

@Serializable
data class NodeMetrics(
    val batteryLevel: Int? = null,
    val voltage: Float? = null,
    val channelUtilization: Float? = null,
    val airUtilTx: Float? = null,
    val uptimeSeconds: Int? = null,
    val temperature: Float? = null,
    val relativeHumidity: Float? = null,
    val barometricPressure: Float? = null,
    /**
     * Lightning, from an AS3935 sensor on the reporting node.
     *
     * Worth surfacing on a watch above most other environment readings: a storm closing
     * in is the one weather fact that changes what you do next, and it is exactly the
     * situation where nobody is looking at a phone.
     */
    val lightningStrikes1h: Int? = null,
    val lightningDistanceKm: Float? = null,
) {
    /** Radios report 101% to mean "running on external power". */
    val isPluggedIn: Boolean get() = (batteryLevel ?: 0) > 100

    /** True when the reporting node has heard lightning within the last hour. */
    val hasLightning: Boolean get() = (lightningStrikes1h ?: 0) > 0

    /**
     * Whether there is anything at all worth drawing a telemetry card for.
     *
     * Checking a couple of fields by hand meant a weather station reporting only
     * lightning — no battery, no temperature — had its whole card hidden.
     */
    val hasTelemetry: Boolean
        get() = batteryLevel != null || voltage != null || temperature != null ||
            relativeHumidity != null || barometricPressure != null ||
            uptimeSeconds != null || channelUtilization != null || airUtilTx != null ||
            lightningStrikes1h != null || lightningDistanceKm != null
}

@Serializable
data class MeshNode(
    val num: Int,
    val userId: String,
    val longName: String,
    val shortName: String,
    val hwModel: String = "UNSET",
    val role: String = "CLIENT",
    val lastHeardSeconds: Int = 0,
    val snr: Float = 0f,
    val rssi: Int = 0,
    val hopsAway: Int? = null,
    /** Channel slot this node was heard on; direct messages must be sent on it. */
    val channelIndex: Int = 0,
    val position: NodePosition? = null,
    val metrics: NodeMetrics = NodeMetrics(),
    val isFavorite: Boolean = false,
    val isIgnored: Boolean = false,
    val isMuted: Boolean = false,
    val viaMqtt: Boolean = false,
    val hasPublicKey: Boolean = false,
    val isUnmessagable: Boolean = false,
    val isSelf: Boolean = false,
) {
    val lastHeardMs: Long get() = lastHeardSeconds * 1000L

    fun isOnline(nowMs: Long, staleMs: Long): Boolean =
        lastHeardSeconds != 0 && nowMs - lastHeardMs < staleMs

    /** Short name is what fits on a watch; fall back to the id when the radio hasn't told us. */
    val displayShort: String
        get() = shortName.ifBlank { userId.takeLast(4) }

    val displayLong: String
        get() = longName.ifBlank { userId }
}

@Serializable
data class MeshChannel(
    val index: Int,
    val name: String,
    val role: String,
    val hasKey: Boolean,
    val uplinkEnabled: Boolean = false,
    val downlinkEnabled: Boolean = false,
) {
    val isEnabled: Boolean get() = role != "DISABLED"

    /**
     * What to call this channel, given the radio's current modem preset.
     *
     * The primary channel travels with an empty name: by convention its name *is* the
     * modem preset, which is why a mesh on MEDIUM_FAST calls its default channel
     * "MediumFast". Hardcoding "LongFast" here — as this used to — mislabelled every mesh
     * that wasn't on the default preset, and no amount of clearing the thread would fix
     * it because the name never came from the messages.
     *
     * Takes the preset as a parameter rather than reading it, so there is no way to render
     * a channel name without having decided what preset it belongs to.
     */
    fun resolveName(modemPreset: String): String = name.ifBlank {
        if (index == 0) presetChannelName(modemPreset) else "Channel $index"
    }
}

/** `MEDIUM_FAST` becomes `MediumFast`, matching how the rest of the ecosystem writes it. */
fun presetChannelName(modemPreset: String): String {
    if (modemPreset.isBlank() || modemPreset == "UNRECOGNIZED") return "Primary"
    return modemPreset.split('_').joinToString("") { part ->
        part.lowercase().replaceFirstChar { it.uppercase() }
    }
}

enum class MsgStatus {
    /** Handed to our own outbound queue but not yet written to the radio. */
    Queued,

    /** The radio accepted it into its TX queue. */
    Sent,

    /** A Routing ACK came back. */
    Delivered,

    /** Routing returned an error, or we gave up waiting. */
    Failed,

    /** Anything we received. */
    Received,
}

@Serializable
data class ChatMessage(
    val id: Int,
    val conversation: String,
    val fromNum: Int,
    val toNum: Int,
    val channel: Int,
    val text: String,
    val timeMs: Long,
    val outgoing: Boolean,
    val status: MsgStatus,
    /** Non-zero when this message is a reply to, or a reaction on, another packet. */
    val replyId: Int = 0,
    /** True when [text] is a tapback emoji rather than prose. */
    val isReaction: Boolean = false,
    val snr: Float = 0f,
    val rssi: Int = 0,
    val hopsAway: Int? = null,
    val pkiEncrypted: Boolean = false,
    val failureReason: String? = null,
)

/**
 * Conversation keys are stable strings so they survive persistence and navigation
 * arguments without needing a lookup table.
 */
object ConversationKey {
    fun channel(index: Int) = "ch:$index"

    fun direct(nodeNum: Int) = "dm:$nodeNum"

    fun isChannel(key: String) = key.startsWith("ch:")

    fun channelIndex(key: String): Int? = key.removePrefix("ch:").toIntOrNull()

    fun nodeNum(key: String): Int? = key.removePrefix("dm:").toIntOrNull()
}

data class Conversation(
    val key: String,
    val title: String,
    val subtitle: String,
    val lastMessage: ChatMessage?,
    val unreadCount: Int,
    val isChannel: Boolean,
    val nodeNum: Int? = null,
    val channelIndex: Int? = null,
    val isMuted: Boolean = false,
)

@Serializable
data class RadioInfo(
    val firmwareVersion: String = "",
    val hwModel: String = "",
    val role: String = "",
    val region: String = "",
    val modemPreset: String = "",
    val hopLimit: Int = 3,
    val numChannels: Int = 0,
    val hasBluetooth: Boolean = true,
    val hasWifi: Boolean = false,
    val hasPKC: Boolean = false,
    val canShutdown: Boolean = false,
    val rebootCount: Int = 0,
    val nodeDbCount: Int = 0,
    val txEnabled: Boolean = true,
)

/** One hop-by-hop traceroute result, newest first in the UI. */
data class TraceRoute(
    val targetNum: Int,
    val towards: List<TraceHop>,
    val back: List<TraceHop>,
    val completedAtMs: Long,
)

data class TraceHop(val nodeNum: Int, val snr: Float?)

@Serializable
data class Waypoint(
    val id: Int,
    val name: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val expireSeconds: Int = 0,
    val lockedTo: Int = 0,
    /**
     * Who put it there. Local bookkeeping only — the waypoint proto has no author field,
     * so this is filled from the sending node on receive, and from our own node number
     * when we drop one.
     */
    val createdByNum: Int = 0,
)

/** Signal quality buckets. Meshtastic SNR is roughly -20..+10 dB. */
enum class SignalQuality { None, Poor, Fair, Good, Excellent }

fun signalQualityOf(snr: Float, rssi: Int): SignalQuality = when {
    snr == 0f && rssi == 0 -> SignalQuality.None
    snr < -15f || (rssi != 0 && rssi < -120) -> SignalQuality.Poor
    snr < -7f -> SignalQuality.Fair
    snr < 2f -> SignalQuality.Good
    else -> SignalQuality.Excellent
}

/** Compass-style 16-point label, handy when the screen is 40 mm wide. */
fun bearingLabel(bearing: Float): String {
    val points = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )
    val idx = (((bearing % 360f) + 360f) % 360f / 22.5f).toInt() % 16
    return points[idx]
}

fun formatDistance(meters: Double, imperial: Boolean): String = if (imperial) {
    val feet = meters * 3.28084
    if (feet < 1000) "${feet.toInt()} ft" else "%.1f mi".format(Locale.US, feet / 5280.0)
} else {
    if (abs(meters) < 1000) "${meters.toInt()} m" else "%.1f km".format(Locale.US, meters / 1000.0)
}
