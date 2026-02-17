package com.example.antiloss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.resume

data class LocationPayload(
    val collectedAt: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val speedMps: Float
)

object LocationCollector {

    @SuppressLint("MissingPermission")
    suspend fun collect(context: Context): LocationPayload? {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null

        // 优先使用 Fused Location (Google Play Services)
        var location = tryGetFusedLocation(context)

        // 如果 Fused 失败，尝试原生 LocationManager GPS
        if (location == null) {
            location = tryGetNativeLocation(context, LocationManager.GPS_PROVIDER)
        }

        // 如果 GPS 失败，尝试原生 LocationManager 网络定位
        if (location == null) {
            location = tryGetNativeLocation(context, LocationManager.NETWORK_PROVIDER)
        }

        return location?.let {
            LocationPayload(
                collectedAt = Instant.now().toString(),
                lat = it.latitude,
                lon = it.longitude,
                accuracyM = it.accuracy,
                speedMps = it.speed
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryGetFusedLocation(context: Context): Location? = withTimeoutOrNull(5000) {
        try {
            val fused = LocationServices.getFusedLocationProviderClient(context)
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(0) // 强制实时，不要缓存
                .build()

            suspendCancellableCoroutine { cont ->
                fused.getCurrentLocation(request, null)
                    .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                    .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
            }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun tryGetNativeLocation(context: Context, provider: String): Location? = withTimeoutOrNull(4000) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!lm.isProviderEnabled(provider)) return@withTimeoutOrNull null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+ 提供的实时获取方法
                suspendCancellableCoroutine { cont ->
                    lm.getCurrentLocation(provider, null, context.mainExecutor) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                }
            } else {
                // 旧版本尝试获取一个较为新鲜的缓存，因为旧版本 requestSingleUpdate 较难协程化且耗时
                lm.getLastKnownLocation(provider)
            }
        } catch (e: Exception) {
            null
        }
    }
}
