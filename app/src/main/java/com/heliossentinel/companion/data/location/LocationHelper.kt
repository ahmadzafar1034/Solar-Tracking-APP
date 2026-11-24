package com.heliossentinel.companion.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.heliossentinel.companion.domain.GeoLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot phone location for the sun-position maths. Uses the framework
 * LocationManager only (no Google Play Services dependency), and coarse
 * accuracy is plenty: 1 km of error moves the sun by ~0.01°.
 */
class LocationHelper(context: Context) {

    private val appContext = context.applicationContext

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission() first
    suspend fun currentLocation(timeoutMs: Long = 10_000L): GeoLocation? {
        if (!hasPermission()) return null
        val manager = appContext.getSystemService(LocationManager::class.java) ?: return null

        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        for (provider in providers) {
            val fresh = withTimeoutOrNull(timeoutMs) { requestOnce(manager, provider) }
            if (fresh != null) return GeoLocation(fresh.latitude, fresh.longitude)
        }

        val lastKnown = (providers + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        return lastKnown?.let { GeoLocation(it.latitude, it.longitude) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(appContext)) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            } else {
                // Android 8–10: every LocationListener method is abstract there, so all must be overridden
                // or the system's callbacks would crash with AbstractMethodError.
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Required on API < 29")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation { manager.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }
}
