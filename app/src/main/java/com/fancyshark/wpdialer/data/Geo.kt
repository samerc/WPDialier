package com.fancyshark.wpdialer.data

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object Geo {

    /** Resolves coordinates to a human-readable address line, or null. */
    suspend fun reverseGeocode(context: Context, lat: Double, lon: Double): String? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            withTimeoutOrNull(8_000) {
                runCatching {
                    val geocoder = Geocoder(context, Locale.getDefault())
                    if (android.os.Build.VERSION.SDK_INT < 33) {
                        // Pre-13 only has the blocking call; we're on IO already.
                        @Suppress("DEPRECATION")
                        return@runCatching geocoder.getFromLocation(lat, lon, 1)
                            ?.firstOrNull()?.let { a ->
                                (0..a.maxAddressLineIndex)
                                    .joinToString(", ") { a.getAddressLine(it) }
                            }
                    }
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(
                            lat, lon, 1,
                            object : Geocoder.GeocodeListener {
                                override fun onGeocode(results: MutableList<android.location.Address>) {
                                    val address = results.firstOrNull()?.let { a ->
                                        (0..a.maxAddressLineIndex)
                                            .joinToString(", ") { a.getAddressLine(it) }
                                    }
                                    if (cont.isActive) cont.resume(address)
                                }

                                // The lambda SAM only covers onGeocode; without
                                // this, failures hang until the outer timeout.
                                override fun onError(errorMessage: String?) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            },
                        )
                    }
                }.getOrNull()
            }
        }

    /** One-shot current location; null on missing permission or timeout. */
    suspend fun currentLocation(context: Context): Location? =
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            null
        } else withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val manager = context.getSystemService(LocationManager::class.java)
                if (manager == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                // Cancel the provider request when the timeout aborts us, so
                // the fused provider doesn't keep running (battery).
                // The fused provider is Android 12+; older phones use the
                // network provider (cell/Wi-Fi), else GPS.
                val provider = when {
                    android.os.Build.VERSION.SDK_INT >= 31 -> LocationManager.FUSED_PROVIDER
                    runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }
                        .getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
                    else -> LocationManager.GPS_PROVIDER
                }
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val signal = android.os.CancellationSignal()
                    cont.invokeOnCancellation { runCatching { signal.cancel() } }
                    runCatching {
                        manager.getCurrentLocation(
                            provider,
                            signal,
                            context.mainExecutor,
                        ) { location -> if (cont.isActive) cont.resume(location) }
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                } else {
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) cont.resume(location)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                    cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                    runCatching {
                        @Suppress("DEPRECATION")
                        manager.requestSingleUpdate(
                            provider, listener, android.os.Looper.getMainLooper(),
                        )
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        }
}
