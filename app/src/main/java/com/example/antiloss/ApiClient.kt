package com.example.antiloss

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ApiClient(private val store: PrefStore, private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun registerDevice(adminKey: String): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        val baseUrl = normalizeBaseUrl(store.serverBaseUrl)
        val deviceId = store.deviceId
        val secret = store.deviceSecret
        
        if (adminKey.isBlank()) error("Admin Key 不能为空")

        // 自动获取设备硬件信息
        val deviceInfo = DeviceStateCollector.getDeviceInfo()
        
        val bodyJson = JSONObject()
            .put("deviceId", deviceId)
            .put("secret", secret)
            .put("info", deviceInfo) // 携带设备硬件信息
            .toString()

        val req = Request.Builder()
            .url("$baseUrl/api/v1/auth/register")
            .header("x-admin-key", adminKey)
            .post(bodyJson.toRequestBody(jsonType))
            .build()

        http.newCall(req).execute().use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("注册失败(${resp.code}): $responseBody")
            }
        }
    } }

    suspend fun login(): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        val baseUrl = normalizeBaseUrl(store.serverBaseUrl)
        val deviceId = store.deviceId
        val secret = store.deviceSecret
        val targetUrl = "$baseUrl/api/v1/auth/device-login"

        val bodyJson = JSONObject()
            .put("deviceId", deviceId)
            .put("secret", secret)
            .toString()

        val req = Request.Builder()
            .url(targetUrl)
            .post(bodyJson.toRequestBody(jsonType))
            .build()

        http.newCall(req).execute().use { resp ->
            val responseBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                error("登录失败(${resp.code}): $responseBody")
            }
            val token = JSONObject(responseBody).optString("token")
            if (token.isBlank()) {
                error("登录失败：服务器未返回 Token")
            }
            store.token = token
        }
    }.recoverCatching { e ->
        val baseUrl = store.serverBaseUrl
        when (e) {
            is ConnectException -> error("连接失败: $baseUrl")
            is UnknownHostException -> error("地址解析失败: $baseUrl")
            is SocketTimeoutException -> error("连接超时: $baseUrl")
            else -> throw e
        }
    } }

    suspend fun sendHeartbeat(payload: HeartbeatPayload): Result<Unit> = signedPost(
        "/api/v1/heartbeat",
        JSONObject()
            .put("collectedAt", payload.collectedAt)
            .put("batteryPct", payload.batteryPct)
            .put("charging", payload.charging)
            .put("networkType", payload.networkType)
            .put("appVersion", payload.appVersion)
            .toString()
    )

    suspend fun sendLocation(payload: LocationPayload): Result<Unit> = signedPost(
        "/api/v1/location",
        JSONObject()
            .put("collectedAt", payload.collectedAt)
            .put("lat", payload.lat)
            .put("lon", payload.lon)
            .put("accuracyM", payload.accuracyM)
            .put("speedMps", payload.speedMps)
            .toString()
    )

    private suspend fun signedPost(path: String, bodyJson: String, retryOn401: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        val baseUrl = normalizeBaseUrl(store.serverBaseUrl)
        var token = store.token
        val secret = store.deviceSecret

        if (baseUrl.isBlank()) error("未配置服务器地址")
        if (token.isBlank()) {
            login().getOrThrow()
            token = store.token
        }

        val ts = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val bodyHash = sha256Hex(bodyJson)
        val toSign = "POST\n$path\n$ts\n$nonce\n$bodyHash"
        val signature = hmacHex(sha256Hex(secret).toByteArray(Charsets.UTF_8), toSign)

        val req = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $token")
            .header("x-timestamp", ts)
            .header("x-nonce", nonce)
            .header("x-signature", signature)
            .post(bodyJson.toRequestBody(jsonType))
            .build()

        http.newCall(req).execute().use { resp ->
            if (resp.code == 401 && retryOn401) {
                login().getOrThrow()
                return@withContext signedPost(path, bodyJson, false)
            }
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                error("数据提交失败(${resp.code}): $err")
            }
        }
    } }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacHex(key: ByteArray, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val out = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return out.joinToString("") { "%02x".format(it) }
    }

    private fun normalizeBaseUrl(raw: String): String {
        val baseUrl = raw.trim().trimEnd('/')
        if (!(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            error("地址必须以 http:// 或 https:// 开头")
        }
        return baseUrl
    }
}
