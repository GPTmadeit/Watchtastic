package com.watchtastic.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.util.Log
import com.watchtastic.mesh.MeshConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

class GattException(message: String) : Exception(message)

/**
 * A thin, coroutine-shaped wrapper over one [BluetoothGatt] connection.
 *
 * Android's GATT stack tolerates exactly one outstanding operation per connection and
 * reports completion on a callback thread, so every request here is funnelled through
 * [opLock] and parked on a [CompletableDeferred] that the matching callback resolves.
 * Issuing a second read before the first completes is the classic source of silent
 * BLE stalls, and the lock is what makes that structurally impossible.
 *
 * Notifications are the one exception: they arrive unsolicited, so they land on
 * [notifications] instead of a deferred.
 */
@SuppressLint("MissingPermission")
class GattLink(
    private val context: Context,
    val device: BluetoothDevice,
) {
    private companion object {
        const val TAG = "GattLink"
        const val CONNECT_TIMEOUT_MS = 25_000L
        const val OP_TIMEOUT_MS = 12_000L
    }

    private val opLock = Mutex()

    @Volatile
    private var gatt: BluetoothGatt? = null

    /** Emits the characteristic UUID whose value the radio just pushed to us. */
    val notifications = MutableSharedFlow<UUID>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits the GATT status once the link drops for any reason. */
    val disconnected = MutableSharedFlow<Int>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var connectOp: CompletableDeferred<Unit>? = null

    @Volatile
    private var servicesOp: CompletableDeferred<Unit>? = null

    @Volatile
    private var mtuOp: CompletableDeferred<Int>? = null

    @Volatile
    private var readOp: CompletableDeferred<ByteArray>? = null

    @Volatile
    private var writeOp: CompletableDeferred<Unit>? = null

    @Volatile
    private var descriptorOp: CompletableDeferred<Unit>? = null

    @Volatile
    var negotiatedMtu: Int = 23
        private set

    @Volatile
    private var closed = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        connectOp?.complete(Unit)
                    } else {
                        connectOp?.completeExceptionally(
                            GattException("connect failed, status=$status"),
                        )
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Fail anything in flight so callers unblock instead of timing out.
                    val err = GattException("link dropped, status=$status")
                    connectOp?.completeExceptionally(err)
                    servicesOp?.completeExceptionally(err)
                    mtuOp?.completeExceptionally(err)
                    readOp?.completeExceptionally(err)
                    writeOp?.completeExceptionally(err)
                    descriptorOp?.completeExceptionally(err)
                    if (!closed) disconnected.tryEmit(status)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesOp?.complete(Unit)
            } else {
                servicesOp?.completeExceptionally(
                    GattException("service discovery failed, status=$status"),
                )
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            // A refused MTU bump is survivable, just slow, so never fail the op here.
            mtuOp?.complete(mtu)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                readOp?.complete(value)
            } else {
                readOp?.completeExceptionally(
                    GattException("read ${characteristic.uuid} failed, status=$status"),
                )
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeOp?.complete(Unit)
            } else {
                writeOp?.completeExceptionally(
                    GattException("write ${characteristic.uuid} failed, status=$status"),
                )
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            notifications.tryEmit(characteristic.uuid)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                descriptorOp?.complete(Unit)
            } else {
                descriptorOp?.completeExceptionally(
                    GattException("descriptor write failed, status=$status"),
                )
            }
        }
    }

    /** Opens the connection and discovers services. Throws on failure. */
    suspend fun connect() {
        val op = CompletableDeferred<Unit>()
        connectOp = op
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw GattException("connectGatt returned null")
        gatt = g
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { op.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw GattException("timed out connecting to ${device.address}")
        } finally {
            connectOp = null
        }

        val discover = CompletableDeferred<Unit>()
        servicesOp = discover
        if (!g.discoverServices()) {
            servicesOp = null
            throw GattException("discoverServices rejected")
        }
        try {
            withTimeout(OP_TIMEOUT_MS) { discover.await() }
        } catch (e: TimeoutCancellationException) {
            throw GattException("service discovery timed out")
        } finally {
            servicesOp = null
        }
    }

    /**
     * Asks for a bigger MTU. Best-effort: the radio may hold us at 23 bytes and
     * everything still works, just slower.
     */
    suspend fun requestMtu(mtu: Int): Int = opLock.withLock {
        val g = requireGatt()
        val op = CompletableDeferred<Int>()
        mtuOp = op
        if (!g.requestMtu(mtu)) {
            mtuOp = null
            return@withLock negotiatedMtu
        }
        try {
            withTimeout(OP_TIMEOUT_MS) { op.await() }
        } catch (e: Exception) {
            Log.w(TAG, "MTU negotiation did not complete: ${e.message}")
            negotiatedMtu
        } finally {
            mtuOp = null
        }
    }

    fun hasMeshService(): Boolean =
        gatt?.getService(MeshConstants.SERVICE_UUID) != null

    suspend fun read(uuid: UUID): ByteArray = opLock.withLock {
        val g = requireGatt()
        val ch = characteristic(g, uuid)
        val op = CompletableDeferred<ByteArray>()
        readOp = op
        if (!g.readCharacteristic(ch)) {
            readOp = null
            throw GattException("readCharacteristic($uuid) rejected")
        }
        try {
            withTimeout(OP_TIMEOUT_MS) { op.await() }
        } catch (e: TimeoutCancellationException) {
            throw GattException("read $uuid timed out")
        } finally {
            readOp = null
        }
    }

    suspend fun write(uuid: UUID, value: ByteArray) = opLock.withLock {
        val g = requireGatt()
        val ch = characteristic(g, uuid)
        val op = CompletableDeferred<Unit>()
        writeOp = op
        val rc = g.writeCharacteristic(
            ch,
            value,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
        if (rc != BluetoothStatusCodes.SUCCESS) {
            writeOp = null
            throw GattException("writeCharacteristic($uuid) rejected, rc=$rc")
        }
        try {
            withTimeout(OP_TIMEOUT_MS) { op.await() }
        } catch (e: TimeoutCancellationException) {
            throw GattException("write $uuid timed out")
        } finally {
            writeOp = null
        }
    }

    /** Subscribes to notifications, including the CCCD write the radio needs. */
    suspend fun enableNotifications(uuid: UUID) = opLock.withLock {
        val g = requireGatt()
        val ch = characteristic(g, uuid)
        if (!g.setCharacteristicNotification(ch, true)) {
            throw GattException("setCharacteristicNotification($uuid) rejected")
        }
        val cccd = ch.getDescriptor(MeshConstants.CCCD_UUID)
            ?: throw GattException("no CCCD on $uuid")
        val op = CompletableDeferred<Unit>()
        descriptorOp = op
        val rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (rc != BluetoothStatusCodes.SUCCESS) {
            descriptorOp = null
            throw GattException("CCCD write rejected, rc=$rc")
        }
        try {
            withTimeout(OP_TIMEOUT_MS) { op.await() }
        } catch (e: TimeoutCancellationException) {
            throw GattException("CCCD write timed out")
        } finally {
            descriptorOp = null
        }
    }

    fun close() {
        closed = true
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    private fun requireGatt(): BluetoothGatt =
        gatt ?: throw GattException("not connected")

    private fun characteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic {
        val service = g.getService(MeshConstants.SERVICE_UUID)
            ?: throw GattException("radio is missing the Meshtastic service")
        return service.getCharacteristic(uuid)
            ?: throw GattException("radio is missing characteristic $uuid")
    }
}
