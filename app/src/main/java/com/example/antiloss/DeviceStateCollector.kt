package com.example.antiloss

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

data class HeartbeatPayload(
    val collectedAt: String,
    val batteryPct: Int,
    val charging: Boolean,
    val networkType: String,
    val appVersion: String
)

object DeviceStateCollector {
    fun collect(context: Context): HeartbeatPayload {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetworkInfo
        val networkType = if (network?.isConnected == true) network.typeName ?: "UNKNOWN" else "OFFLINE"

        return HeartbeatPayload(
            collectedAt = java.time.Instant.now().toString(),
            batteryPct = battery,
            charging = charging,
            networkType = networkType,
            appVersion = BuildConfig.VERSION_NAME
        )
    }

    fun getDeviceInfo(): JSONObject {
        return JSONObject()
            .put("model", Build.MODEL)
            .put("brand", Build.BRAND)
            .put("osVersion", Build.VERSION.RELEASE)
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("manufacturer", Build.MANUFACTURER)
    }
}
