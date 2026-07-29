package com.watchtastic.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Drives Android's pairing flow for radios configured with a PIN.
 *
 * Meshtastic's default `RANDOM_PIN` mode makes the radio show a six-digit code on its
 * own display, which the wearer types into the system pairing dialog. We deliberately
 * never try to supply that PIN ourselves — we just start the bond and wait for the
 * platform to tell us how it went, so the user stays in control of the confirmation.
 */
@SuppressLint("MissingPermission")
class BondManager(private val context: Context) {

    private companion object {
        const val TAG = "BondManager"

        /** Generous: the wearer has to read a code off the radio and type it in. */
        const val BOND_TIMEOUT_MS = 90_000L
    }

    fun isBonded(device: BluetoothDevice): Boolean =
        device.bondState == BluetoothDevice.BOND_BONDED

    /**
     * Returns true once [device] is bonded. Returns false if the user (or the radio)
     * refused. Safe to call when already bonded — it short-circuits.
     */
    suspend fun ensureBonded(device: BluetoothDevice, onPairingStarted: () -> Unit): Boolean {
        if (isBonded(device)) return true

        val result = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changed = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                )
                if (changed?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED -> result.complete(true)
                    BluetoothDevice.BOND_NONE -> result.complete(false)
                    else -> Unit // BOND_BONDING: keep waiting.
                }
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
        )
        return try {
            if (!device.createBond()) {
                Log.w(TAG, "createBond() was rejected outright for ${device.address}")
                return false
            }
            onPairingStarted()
            withTimeout(BOND_TIMEOUT_MS) { result.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "bonding timed out for ${device.address}")
            false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
