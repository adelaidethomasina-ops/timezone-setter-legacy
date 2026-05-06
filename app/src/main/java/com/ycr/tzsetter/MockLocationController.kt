package com.ycr.tzsetter

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Mock Location 控制器：
 * - 检测 app 是否已被设为系统的"模拟位置应用"
 * - Device Owner 身份下可尝试自动设置
 * - 启动/停止 MockLocationService
 */
object MockLocationController {

    enum class Status {
        /** 已经被选为 Mock Location app，可用 */
        READY,
        /** 还没被选为 Mock Location app，需要去开发者选项设置 */
        NOT_SELECTED,
        /** 开发者选项未开启 */
        DEV_OPTIONS_OFF
    }

    /**
     * 检测 app 是否已被指定为 Mock Location 提供者。
     * 方法：尝试读取 AppOps 的 MOCK_LOCATION 权限状态。
     */
    fun getStatus(context: Context): Status {
        // 检查开发者选项是否开启
        val devOn = try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
            ) == 1
        } catch (e: Exception) { false }

        if (!devOn) return Status.DEV_OPTIONS_OFF

        // 检查是不是 Mock Location app
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
        } catch (e: Exception) { AppOpsManager.MODE_ERRORED }

        return if (mode == AppOpsManager.MODE_ALLOWED) Status.READY
        else Status.NOT_SELECTED
    }

    /**
     * 尝试自动配置 Mock Location（Device Owner 身份才能生效）：
     * 1. 开启开发者选项
     * 2. 把本 app 设为 Mock Location app
     *
     * 返回 true 表示至少尝试了某种自动配置。
     * 最终还是要靠 getStatus() 确认。
     */
    fun tryAutoConfigure(context: Context): Boolean {
        if (!SystemTimezoneSetter.isDeviceOwner(context)) return false
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, TimezoneAdminReceiver::class.java)
            // Device Owner 可以通过 setGlobalSetting 改全局设置
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    dpm.setGlobalSetting(admin,
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "1")
                } catch (e: Exception) {}
            }
            // 注意：ALLOW_MOCK_LOCATION 在 Android 6.0+ 已废弃，
            // 真正的开关是 AppOps 中的 OPSTR_MOCK_LOCATION，
            // 必须用 pm grant 或通过开发者选项 UI 设置，无法通过 DPM 直接设
            true
        } catch (e: Exception) { false }
    }

    /** 打开开发者选项设置页 */
    fun openDevSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // 某些 ROM 没有这个 action，fallback
            context.startActivity(Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** 启动模拟位置服务 */
    fun startMock(context: Context, lat: Double, lng: Double, label: String) {
        val intent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_START
            putExtra(MockLocationService.EXTRA_LAT, lat)
            putExtra(MockLocationService.EXTRA_LNG, lng)
            putExtra(MockLocationService.EXTRA_LABEL, label)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /** 停止模拟位置服务 */
    fun stopMock(context: Context) {
        val intent = Intent(context, MockLocationService::class.java).apply {
            action = MockLocationService.ACTION_STOP
        }
        context.startService(intent)
    }

    /** ADB 命令：把本 app 设为 Mock Location app */
    val adbSetMockCommand: String
        get() = "adb shell appops set ${SystemTimezoneSetter.PACKAGE_NAME} " +
                "android:mock_location allow"
}
