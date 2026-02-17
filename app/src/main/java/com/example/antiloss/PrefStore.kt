package com.example.antiloss

import android.content.Context
import android.provider.Settings
import java.util.UUID

class PrefStore(context: Context) {
    private val prefs = context.getSharedPreferences("anti_loss", Context.MODE_PRIVATE)
    
    // 获取 Android ID 作为默认设备 ID 的一部分
    private val androidId: String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: UUID.randomUUID().toString().take(8)

    var serverBaseUrl: String
        get() = prefs.getString("serverBaseUrl", "https://guard.yixidc.cc/") ?: "https://guard.yixidc.cc/"
        set(value) = prefs.edit().putString("serverBaseUrl", value).apply()

    var deviceId: String
        get() {
            val saved = prefs.getString("deviceId", "")
            if (!saved.isNullOrBlank()) return saved
            val newId = "dev_$androidId"
            deviceId = newId
            return newId
        }
        set(value) = prefs.edit().putString("deviceId", value).apply()

    var deviceSecret: String
        get() {
            val saved = prefs.getString("deviceSecret", "")
            if (!saved.isNullOrBlank()) return saved
            val newSecret = UUID.randomUUID().toString().replace("-", "").take(16)
            deviceSecret = newSecret
            return newSecret
        }
        set(value) = prefs.edit().putString("deviceSecret", value).apply()

    var adminKey: String
        get() = prefs.getString("adminKey", "") ?: ""
        set(value) = prefs.edit().putString("adminKey", value).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(value) = prefs.edit().putString("token", value).apply()
}
