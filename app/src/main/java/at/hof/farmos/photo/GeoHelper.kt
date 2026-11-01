package at.hof.farmos.photo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Ermittelt die aktuelle Position ohne Google-Dienste (reiner Android-LocationManager). */
object GeoHelper {

    val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun hasPermission(context: Context): Boolean = permissions.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 20_000): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return null

        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                // Alle Methoden explizit, sonst Absturz auf älteren Android-Versionen.
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                providers.forEach { p ->
                    runCatching { lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper()) }
                }
            }
        }
        if (fresh != null) return fresh

        // Notlösung: letzte bekannte Position (max. 10 Minuten alt)
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 10 * 60 * 1000 }
            .maxByOrNull { it.time }
    }
}
