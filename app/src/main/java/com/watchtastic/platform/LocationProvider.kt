package com.watchtastic.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.watchtastic.mesh.model.NodePosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The watch's own GNSS receiver, offered to the mesh as our node position.
 *
 * This is the one capability a watch has that a typical Meshtastic handheld client does
 * not take for granted: the Pixel Watch has a dual-band GNSS receiver on the wrist, so a
 * radio with no GPS module of its own can still report an accurate position.
 *
 * Uses the platform [LocationManager] rather than the fused provider so the app stays
 * free of Play Services and works on any Wear OS device.
 */
@SuppressLint("MissingPermission")
class LocationProvider(private val context: Context) {

    private companion object {
        const val TAG = "LocationProvider"

        /**
         * LoRa duty cycles are measured in minutes, not seconds. Sampling faster than
         * this would only burn battery producing fixes we'd never transmit.
         */
        const val MIN_INTERVAL_MS = 60_000L
        const val MIN_DISTANCE_M = 25f

        /** A cached fix younger than this is used instead of waking the receiver. */
        const val FRESH_ENOUGH_MS = 90_000L
    }

    private val manager: LocationManager? =
        context.getSystemService(LocationManager::class.java)

    private val _lastFix = MutableStateFlow<NodePosition?>(null)
    val lastFix: StateFlow<NodePosition?> = _lastFix.asStateFlow()

    private var listener: LocationListener? = null

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    val isGpsEnabled: Boolean
        get() = runCatching {
            manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        }.getOrDefault(false)

    /** Starts updates. [onFix] fires on every accepted fix. Safe to call twice. */
    fun start(onFix: (NodePosition) -> Unit) {
        if (!hasPermission || listener != null) return
        val lm = manager ?: return

        val l = LocationListener { location -> handle(location, onFix) }
        listener = l
        runCatching {
            // Ask both providers: GPS is authoritative outdoors, network fills in fast
            // indoors, and whichever arrives first is better than nothing.
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_INTERVAL_MS,
                    MIN_DISTANCE_M,
                    l,
                    Looper.getMainLooper(),
                )
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_INTERVAL_MS,
                    MIN_DISTANCE_M,
                    l,
                    Looper.getMainLooper(),
                )
            }
        }.onFailure {
            Log.w(TAG, "could not start location updates", it)
            listener = null
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { manager?.removeUpdates(l) }
    }

    private fun handle(location: Location, onFix: (NodePosition) -> Unit) {
        // A fix with no usable accuracy is worse than silence: it would pollute the mesh
        // with a position other nodes might navigate towards.
        if (location.hasAccuracy() && location.accuracy > 200f) return
        val position = location.toPosition()
        _lastFix.value = position
        onFix(position)
    }

    /**
     * Gets one fix on demand, without turning on continuous position sharing.
     *
     * Dropping a waypoint or opening the map needs to know where you are *now*, but that
     * shouldn't require opting into broadcasting your position to the mesh — they are
     * separate decisions. Returns null if permission is missing, location is off, or no
     * fix arrives in time.
     */
    suspend fun requestSingleFix(timeoutMs: Long = 25_000L): NodePosition? {
        if (!hasPermission) return null
        val lm = manager ?: return null

        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER

            else -> return null
        }

        // A recent cached fix beats making the wearer wait for the GNSS receiver.
        runCatching { lm.getLastKnownLocation(provider) }.getOrNull()?.let { last ->
            if (System.currentTimeMillis() - last.time < FRESH_ENOUGH_MS) {
                return last.toPosition().also { _lastFix.value = it }
            }
        }

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                runCatching {
                    lm.getCurrentLocation(
                        provider,
                        signal,
                        context.mainExecutor,
                    ) { location ->
                        val position = location?.toPosition()
                        if (position != null) _lastFix.value = position
                        if (continuation.isActive) continuation.resume(position)
                    }
                }.onFailure {
                    Log.w(TAG, "single fix request failed", it)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private fun Location.toPosition() = NodePosition(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude()) altitude.toInt() else null,
        timeSeconds = (time / 1000L).toInt(),
        groundSpeed = if (hasSpeed()) speed.toInt() else null,
    )
}
