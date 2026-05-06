package com.ycr.tzsetter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * 持续推送假位置的前台服务。
 *
 * 设计要点：
 * 1. 同时注入 GPS、NETWORK、FUSED 三个 provider，最大化兼容性
 * 2. 每秒刷新一次，在目标坐标 ±500 米范围内随机抖动（模拟真实手机 GPS 噪声）
 * 3. 作为前台服务运行，避免系统杀掉
 * 4. 通知栏提供一键停止按钮
 *
 * Service 启动：
 *   val intent = Intent(context, MockLocationService::class.java).apply {
 *     action = MockLocationService.ACTION_START
 *     putExtra(MockLocationService.EXTRA_LAT, lat)
 *     putExtra(MockLocationService.EXTRA_LNG, lng)
 *     putExtra(MockLocationService.EXTRA_LABEL, "The Dalles, OR (97058)")
 *   }
 *   ContextCompat.startForegroundService(context, intent)
 */
class MockLocationService : Service() {

    companion object {
        const val ACTION_START = "com.ycr.tzsetter.action.MOCK_START"
        const val ACTION_STOP = "com.ycr.tzsetter.action.MOCK_STOP"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_LABEL = "label"

        private const val CHANNEL_ID = "mock_location"
        private const val NOTI_ID = 1001

        /** 随机偏移半径（米），满足用户的"中心点±500m随机"需求 */
        private const val RADIUS_METERS = 500.0

        /** 推送频率（ms） */
        private const val PUSH_INTERVAL_MS = 1000L

        /** 全局状态：当前是否在运行 */
        @Volatile var isRunning: Boolean = false
            private set

        @Volatile var currentLabel: String = ""
            private set

        @Volatile var currentLat: Double = 0.0
            private set

        @Volatile var currentLng: Double = 0.0
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        "fused"                                // Android 12+ 上的 FUSED provider
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Mock Location"
                startMocking(lat, lng, label)
            }
            ACTION_STOP -> {
                stopMocking()
            }
        }
        return START_STICKY
    }

    private fun startMocking(lat: Double, lng: Double, label: String) {
        // 取消旧任务
        job?.cancel()

        currentLat = lat
        currentLng = lng
        currentLabel = label
        isRunning = true

        startForeground(NOTI_ID, buildNotification(label, lat, lng))

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 为每个 provider 启用 test mode
        for (p in providers) {
            try {
                // 移除旧的（如果存在）
                try { lm.removeTestProvider(p) } catch (_: Exception) {}

                lm.addTestProvider(
                    p,
                    false,  // requiresNetwork
                    false,  // requiresSatellite
                    false,  // requiresCell
                    false,  // hasMonetaryCost
                    true,   // supportsAltitude
                    true,   // supportsSpeed
                    true,   // supportsBearing
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
                lm.setTestProviderEnabled(p, true)
            } catch (e: Exception) {
                Log.w("MockLoc", "addTestProvider $p failed: ${e.message}")
            }
        }

        job = scope.launch {
            while (isActive) {
                pushLocation(lm, lat, lng)
                delay(PUSH_INTERVAL_MS)
            }
        }
    }

    private fun pushLocation(lm: LocationManager, baseLat: Double, baseLng: Double) {
        // 中心点 ±500 米随机偏移
        val (dLat, dLng) = randomOffset(RADIUS_METERS, baseLat)
        val lat = baseLat + dLat
        val lng = baseLng + dLng

        for (p in providers) {
            try {
                val loc = Location(p).apply {
                    latitude = lat
                    longitude = lng
                    accuracy = 3.0f + Random.nextFloat() * 2f   // 3~5m 模拟真实 GPS 精度
                    altitude = 0.0
                    speed = 0f
                    bearing = 0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        verticalAccuracyMeters = 3f
                        speedAccuracyMetersPerSecond = 0f
                        bearingAccuracyDegrees = 0f
                    }
                }
                lm.setTestProviderLocation(p, loc)
            } catch (e: Exception) {
                // 单个 provider 失败不影响其他
            }
        }
    }

    /**
     * 给定半径（米），在地球表面随机选一个点。
     * 以当前纬度为参考做等距近似。
     * 返回 (Δlat, Δlng) 度数。
     */
    private fun randomOffset(radiusMeters: Double, baseLat: Double): Pair<Double, Double> {
        // 地球半径 6371km，1度纬度 ≈ 111km，1度经度 ≈ 111km * cos(lat)
        val angle = Random.nextDouble() * 2 * Math.PI
        val distance = Math.sqrt(Random.nextDouble()) * radiusMeters   // 均匀分布在圆盘内
        val dxMeters = distance * Math.cos(angle)
        val dyMeters = distance * Math.sin(angle)
        val dLat = dyMeters / 111_000.0
        val dLng = dxMeters / (111_000.0 * Math.cos(Math.toRadians(baseLat)))
        return dLat to dLng
    }

    private fun stopMocking() {
        job?.cancel()
        job = null
        isRunning = false

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        for (p in providers) {
            try { lm.removeTestProvider(p) } catch (_: Exception) {}
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        isRunning = false
    }

    // ============================================================
    // Notification
    // ============================================================
    private fun buildNotification(label: String, lat: Double, lng: Double): Notification {
        // v3.7.28-l: getSystemService(Class<T>) 是 API 23+ 才有,用字符串版本兼容 5.0
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "模拟位置服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "模拟位置正在运行时显示"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        // 停止意图
        val stopIntent = Intent(this, MockLocationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 打开 app
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val coords = "%.4f°, %.4f°".format(lat, lng)

        // ★ v3.7.5 关键修复:Notification.Builder(Context, String) 是 API 26+
        // 在 Android 7.0/7.1 (API 24/25) 上必须用单参 Builder(Context),否则 NoSuchMethodError 闪退
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setContentTitle("📍 模拟位置运行中")
            .setContentText("$label  $coords")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openPending)

        // ★ v3.7.5 修复:Notification.Action.Builder 是 API 23+,
        // 老系统(虽然我们的 minSdk=24 不会触发)用兜底单参 addAction
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_delete,
                    "停止",
                    stopPending
                ).build()
            )
        } else {
            @Suppress("DEPRECATION")
            builder.addAction(
                android.R.drawable.ic_delete,
                "停止",
                stopPending
            )
        }

        return builder.build()
    }
}
