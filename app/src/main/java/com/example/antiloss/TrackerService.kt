package com.example.antiloss

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TrackerService : Service() {

    companion object {
        const val ACTION_START = "com.example.antiloss.START"
        const val ACTION_STOP = "com.example.antiloss.STOP"
        const val ACTION_LOG = "com.example.antiloss.LOG"
        const val EXTRA_LOG_MSG = "log_msg"
        
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001
        private const val INTERVAL_MS = 60 * 1000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                sendLog("正在停止守护服务...")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startTracking()
            else -> startTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        createNotificationChannel()
        
        val notification = buildNotification("守护服务已启动")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (loopJob?.isActive == true) {
            sendLog("服务已在运行中")
            return
        }

        acquireWakeLock()

        val store = PrefStore(this)
        val api = ApiClient(store, this)

        loopJob = scope.launch {
            sendLog("开始定时追踪 (间隔: 60秒)")
            while (true) {
                try {
                    val now = java.time.LocalTime.now().toString().substring(0, 8)
                    sendLog("[$now] 开始执行上传任务...")
                    
                    val heartbeat = DeviceStateCollector.collect(this@TrackerService)
                    val hbResult = api.sendHeartbeat(heartbeat)
                    if (hbResult.isSuccess) {
                        sendLog("心跳上传成功")
                    } else {
                        sendLog("心跳上传失败: ${hbResult.exceptionOrNull()?.message}")
                    }

                    val loc = LocationCollector.collect(this@TrackerService)
                    if (loc != null) {
                        val locResult = api.sendLocation(loc)
                        if (locResult.isSuccess) {
                            sendLog("位置上传成功: ${loc.lat}, ${loc.lon}")
                        } else {
                            sendLog("位置上传失败: ${locResult.exceptionOrNull()?.message}")
                        }
                    } else {
                        sendLog("警告: 无法获取当前位置，请确认GPS已开启或权限已授予")
                    }

                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, buildNotification("上次上传: $now"))
                } catch (t: Throwable) {
                    sendLog("循环内发生严重错误: ${t.message}")
                    t.printStackTrace()
                }
                
                delay(INTERVAL_MS)
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntiLoss:TrackingWakeLock").apply {
            setReferenceCounted(false)
            acquire() // no timeout
        }
        sendLog("已获取CPU唤醒锁")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
            sendLog("已释放CPU唤醒锁")
        }
    }

    private fun sendLog(message: String) {
        val intent = Intent(ACTION_LOG).apply {
            putExtra(EXTRA_LOG_MSG, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.tracking_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.tracking_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        sendLog("服务正在销毁...")
        loopJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
