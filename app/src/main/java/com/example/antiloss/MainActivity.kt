package com.example.antiloss

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var store: PrefStore
    private lateinit var apiClient: ApiClient
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private val logDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TrackerService.ACTION_LOG) {
                val msg = intent.getStringExtra(TrackerService.EXTRA_LOG_MSG)
                if (msg != null) addLog(msg)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        addLog(if (allGranted) "所有必要权限已授予" else "部分权限被拒绝，请在设置中手动开启")
        
        // 针对 Android 10+ 的后台位置权限特殊引导
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                addLog("提示: 请在设置中将位置权限设为“始终允许”以保证后台稳定")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = PrefStore(this)
        apiClient = ApiClient(store, this)

        val etServer = findViewById<EditText>(R.id.etServerBaseUrl)
        val etAdminKey = findViewById<EditText>(R.id.etSecret) // 复用 Secret 输入框作为 AdminKey
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)

        // 设置 Hint 提示用户
        etAdminKey.hint = "管理员密钥 (仅注册时需要)"
        findViewById<EditText>(R.id.etDeviceId).apply {
            isEnabled = false
            setText("自动生成: ${store.deviceId}")
        }

        etServer.setText(store.serverBaseUrl)
        etAdminKey.setText(store.adminKey)

        addLog("欢迎使用守护系统")
        requestPermissionsIfNeeded()
        checkBatteryOptimization()

        val filter = IntentFilter(TrackerService.ACTION_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }

        btnLogin.setOnClickListener {
            val baseUrl = etServer.text.toString().trim().trimEnd('/')
            val adminKey = etAdminKey.text.toString().trim()

            if (baseUrl.isEmpty()) {
                addLog("错误: 服务器地址不能为空")
                return@setOnClickListener
            }

            store.serverBaseUrl = baseUrl
            store.adminKey = adminKey

            CoroutineScope(Dispatchers.Main).launch {
                addLog("尝试自动注册并登录...")
                // 尝试登录，如果失败则尝试带 adminKey 注册
                val loginRes = apiClient.login()
                if (loginRes.isSuccess) {
                    addLog("登录成功!")
                } else {
                    addLog("登录失败，尝试使用管理员密钥注册...")
                    val regRes = apiClient.registerDevice(adminKey)
                    if (regRes.isSuccess) {
                        addLog("注册成功! 正在登录...")
                        if (apiClient.login().isSuccess) addLog("登录成功!")
                        else addLog("登录失败，请检查配置")
                    } else {
                        addLog("注册失败: ${regRes.exceptionOrNull()?.message}")
                    }
                }
            }
        }

        btnStart.setOnClickListener {
            val intent = Intent(this, TrackerService::class.java).apply {
                action = TrackerService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            addLog("守护服务启动中...")
        }

        btnStop.setOnClickListener {
            val intent = Intent(this, TrackerService::class.java).apply {
                action = TrackerService.ACTION_STOP
            }
            startService(intent)
            addLog("守护服务已停止")
        }
    }

    private fun addLog(message: String) {
        val time = logDateFormat.format(Date())
        val newLog = "${tvLog.text}\n[$time] $message"
        tvLog.text = newLog
        svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        // 如果是 Android 10+，后台位置权限通常需要单独申请或引导
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(logReceiver)
    }
}
