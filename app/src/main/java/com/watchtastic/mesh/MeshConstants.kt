package com.watchtastic.mesh

import java.util.UUID

/**
 * Wire-level constants for the Meshtastic BLE client API.
 *
 * Source of truth: https://meshtastic.org/docs/development/device/client-api/
 */
object MeshConstants {
    /** The radio's GATT service. Also the scan filter, so we never enumerate unrelated peripherals. */
    val SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")

    /** Write `ToRadio` protobufs here. */
    val TORADIO_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")

    /** Read `FromRadio` protobufs here; a zero-length read means the FIFO is drained. */
    val FROMRADIO_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")

    /** Notifies whenever the radio has queued something for us. */
    val FROMNUM_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * The docs strongly recommend 512; the radio negotiates down if it can't manage it.
     * Anything smaller makes config download painfully slow.
     */
    const val DESIRED_MTU = 512

    /** `to`/`from` value meaning "everyone on this channel". */
    const val BROADCAST_ADDR: Int = -1 // 0xFFFFFFFF

    /**
     * Firmware refuses `Data.payload` longer than 233 bytes (`Constants.DATA_PAYLOAD_LEN`).
     * Clients conventionally cap user text at 200 to leave room for the envelope.
     */
    const val MAX_TEXT_BYTES = 200

    /** Channel slots 0..7; slot 0 is always PRIMARY. */
    const val NUM_CHANNELS = 8

    /** Keeps the BLE session from being reaped while we're idle. */
    const val HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L

    /** A node with no traffic for this long is treated as offline. */
    const val NODE_STALE_MS = 2 * 60 * 60 * 1000L

    /** Meshtastic renders node numbers as `!` plus 8 lowercase hex digits. */
    fun nodeIdOf(num: Int): String = "!%08x".format(num)

    fun nodeNumOf(id: String): Int? =
        id.removePrefix("!").takeIf { it.length == 8 }?.toLongOrNull(16)?.toInt()
}
