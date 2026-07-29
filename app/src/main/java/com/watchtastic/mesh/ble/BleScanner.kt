package com.watchtastic.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.watchtastic.mesh.MeshConstants
import com.watchtastic.mesh.model.DiscoveredRadio
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class ScanUnavailable(message: String) : Exception(message)

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {

    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun deviceFor(address: String): BluetoothDevice? =
        runCatching { adapter?.getRemoteDevice(address) }.getOrNull()

    /**
     * Streams radios in range, sorted strongest-first.
     *
     * The scan is filtered on the Meshtastic service UUID, so we never surface — or
     * even receive — advertisements from unrelated peripherals. That keeps the list
     * short enough to be usable on a watch and is why the manifest can declare
     * `neverForLocation`.
     */
    fun scan(): Flow<List<DiscoveredRadio>> = callbackFlow {
        val bt = adapter ?: throw ScanUnavailable("This watch has no Bluetooth adapter")
        if (!bt.isEnabled) throw ScanUnavailable("Bluetooth is off")
        val scanner = bt.bluetoothLeScanner
            ?: throw ScanUnavailable("BLE scanning is unavailable")

        val found = linkedMapOf<String, DiscoveredRadio>()

        fun publish() {
            trySend(found.values.sortedByDescending { it.rssi })
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                // Advertised name first: radios put their short name there. Fall back to
                // the GATT name, then the MAC so the row is never blank.
                val name = result.scanRecord?.deviceName
                    ?: runCatching { device.name }.getOrNull()
                    ?: device.address
                found[device.address] = DiscoveredRadio(
                    address = device.address,
                    name = name,
                    rssi = result.rssi,
                    bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                )
                publish()
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanUnavailable("Scan failed (code $errorCode)"))
            }
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MeshConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            // A watch is scanning for a device the wearer is holding; latency matters
            // more than the extra milliamps for the few seconds this is open.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        scanner.startScan(listOf(filter), settings, callback)
        publish()

        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
