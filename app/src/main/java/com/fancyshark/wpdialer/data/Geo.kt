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
        withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { cont ->
                val manager = context.getSystemService(LocationManager::class.java)
                if (manager == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                // Cancel the provider request when the timeout aborts us, so
                // the fused provider doesn't keep running (battery).
                val signal = android.os.CancellationSignal()
                cont.invokeOnCancellation { runCatching { signal.cancel() } }
                runCatching {
                    manager.getCurrentLocation(
                        LocationManager.FUSED_PROVIDER,
                        signal,
                        context.mainExecutor,
                    ) { location -> if (cont.isActive) cont.resume(location) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
}
