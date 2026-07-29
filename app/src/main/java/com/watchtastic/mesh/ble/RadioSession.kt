package com.watchtastic.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.watchtastic.mesh.MeshConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.meshtastic.proto.MeshProtos

/**
 * One live conversation with one radio, speaking the Meshtastic BLE protocol.
 *
 * The protocol is deceptively simple but order-sensitive:
 *
 *  1. connect and discover services,
 *  2. raise the MTU (512 turns a ~30 s config download into a couple of seconds),
 *  3. subscribe to `FromNum` *before* asking for anything, so no reply can be missed,
 *  4. drain whatever is already sitting in the `FromRadio` FIFO,
 *  5. then write `want_config_id` and drain again.
 *
 * `FromRadio` is a FIFO exposed through a single characteristic: you read it in a loop
 * until a read comes back empty. `FromNum` only tells you *that* there is something
 * waiting, never how much, so every notification means "drain again".
 */
// Reaching this class at all requires a bonded device, which requires BLUETOOTH_CONNECT;
// the permission is requested on the connect screen before any session is constructed.
@SuppressLint("MissingPermission")
class RadioSession(
    context: Context,
    device: BluetoothDevice,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val TAG = "RadioSession"
    }

    private val link = GattLink(context, device)
    private val drainLock = Mutex()
    private var pump: Job? = null

    val deviceAddress: String = device.address
    val deviceName: String = runCatching { device.name }.getOrNull() ?: device.address

    private val _incoming = MutableSharedFlow<MeshProtos.FromRadio>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    val incoming: SharedFlow<MeshProtos.FromRadio> = _incoming

    val disconnected: SharedFlow<Int> = link.disconnected

    val mtu: Int get() = link.negotiatedMtu

    /**
     * Brings the link up through step 4 above. The caller then sends `want_config_id`
     * via [requestConfig] so it can pick and remember the nonce.
     */
    suspend fun open() {
        link.connect()
        if (!link.hasMeshService()) {
            link.close()
            throw GattException("That device isn't running Meshtastic")
        }
        link.requestMtu(MeshConstants.DESIRED_MTU)

        pump = scope.launch {
            link.notifications.collect { uuid ->
                if (uuid == MeshConstants.FROMNUM_UUID) {
                    runCatching { drain() }
                        .onFailure { Log.w(TAG, "drain after notify failed: ${it.message}") }
                }
            }
        }

        link.enableNotifications(MeshConstants.FROMNUM_UUID)
        // Clear anything the radio queued while we were away, so the config download
        // below isn't interleaved with stale packets.
        drain()
    }

    /** Writes `want_config_id` and drains the response burst. Returns the nonce used. */
    suspend fun requestConfig(nonce: Int): Int {
        send(MeshProtos.ToRadio.newBuilder().setWantConfigId(nonce).build())
        drain()
        return nonce
    }

    suspend fun send(toRadio: MeshProtos.ToRadio) {
        link.write(MeshConstants.TORADIO_UUID, toRadio.toByteArray())
    }

    suspend fun sendHeartbeat() {
        send(
            MeshProtos.ToRadio.newBuilder()
                .setHeartbeat(MeshProtos.Heartbeat.newBuilder().setNonce(0))
                .build(),
        )
    }

    /**
     * Reads `FromRadio` until it comes back empty.
     *
     * Guarded by [drainLock] because a `FromNum` notification can land while a drain is
     * already running; without the lock the two loops would interleave reads and each
     * would see the other's "empty" as its own terminator.
     */
    suspend fun drain() = drainLock.withLock {
        while (true) {
            val bytes = link.read(MeshConstants.FROMRADIO_UUID)
            if (bytes.isEmpty()) return@withLock
            val packet = try {
                MeshProtos.FromRadio.parseFrom(bytes)
            } catch (e: Exception) {
                // A malformed frame is not fatal; skip it and keep draining.
                Log.w(TAG, "dropping unparseable FromRadio frame (${bytes.size} B)", e)
                continue
            }
            _incoming.emit(packet)
        }
    }

    fun close() {
        pump?.cancel()
        pump = null
        // Politeness: tells the firmware to tear down its side rather than wait for a
        // supervision timeout.
        runCatching {
            scope.launch {
                runCatching {
                    send(MeshProtos.ToRadio.newBuilder().setDisconnect(true).build())
                }
                link.close()
            }
        }.onFailure { link.close() }
    }

    /** Drops the link immediately without the courtesy disconnect frame. */
    fun abort() {
        pump?.cancel()
        pump = null
        link.close()
    }
}
