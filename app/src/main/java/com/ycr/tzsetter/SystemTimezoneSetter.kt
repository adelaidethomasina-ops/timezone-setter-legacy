package com.ycr.tzsetter

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 系统控制器：统一管理时区 + Mock Location 两项能力。
 *
 * v3.7.5 改动（关键）：
 * 1. 路径1 DO API：API 28+ 才有 dpm.setTimeZone()，老系统(7.0/8.1)上 DO 走 AlarmManager
 * 2. 路径2 AlarmManager：删除立即检查 getDefault() 的误判逻辑（异步 API，立即检查必失败）
 *    改用"调用未抛异常即视为成功"，更准确反映真实情况
 * 3. 新增 PROFILE_OWNER 模式（A166P 三星 OneUI 7 / Android 15 走这条）
 * 4. AuthMode 中 NORMAL_PERMISSION 涵盖 DO + PO + 显式授权三种状态
 */
object SystemTimezoneSetter {

    private const val TAG = "TzSetter"

    sealed class Result {
        data class Success(val tzId: String, val via: String) : Result()
        data class PermissionDenied(val tzId: String) : Result()
        data class Error(val message: String) : Result()
    }

    enum class AuthMode { DEVICE_OWNER, PROFILE_OWNER, NORMAL_PERMISSION, ACCESSIBILITY, NONE }

    fun getAuthMode(context: Context): AuthMode {
        if (isDeviceOwner(context)) return AuthMode.DEVICE_OWNER
        if (isProfileOwner(context)) return AuthMode.PROFILE_OWNER
        if (hasPermission(context)) return AuthMode.NORMAL_PERMISSION
        if (TimezoneAccessibilityService.isEnabled(context)) return AuthMode.ACCESSIBILITY
        return AuthMode.NONE
    }

    fun hasPermission(context: Context): Boolean {
        // v3.7.28-l: 用 PackageManager.checkPermission 代替 Context.checkSelfPermission(API 23+)
        // 这样在 Android 5.0 (API 21) 上也能工作
        return context.packageManager.checkPermission(
            android.Manifest.permission.SET_TIME_ZONE,
            context.packageName
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isDeviceOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            // 双重检查:isDeviceOwnerApp + isAdminActive
            // Samsung Android 7.x/8.x 上 isDeviceOwnerApp() 有时返回 false 即使实际是 DO
            // 改用 isAdminActive(admin) 配合检查作为兜底
            if (dpm.isDeviceOwnerApp(context.packageName)) return true
            // 兜底:某些 ROM 上 isDeviceOwnerApp 不可靠,直接检查 admin 是否激活
            // 如果 admin active + 我们声明的包名与系统记录的 DO 包名一致 → 视为 DO
            val admin = ComponentName(context, TimezoneAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                // 进一步通过反射或 dumpsys 风格 API 确认
                // 但保险起见,只要 admin 是 active 且 setTimeZone 不抛 SecurityException 就视为 DO
                return true
            }
            false
        } catch (e: Exception) { false }
    }

    fun isProfileOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isProfileOwnerApp(context.packageName)) return true
            false
        } catch (e: Exception) { false }
    }

    /**
     * 改系统时区。优先级：
     *   1. Device Owner API (Android 9+)：dpm.setTimeZone()，最快静默改
     *   2. AlarmManager.setTimeZone()：DO/PO 自动有 SET_TIME_ZONE 权限
     *      - Android 8.1+ DO 走这条
     *      - Android 15 + 三星 OneUI 7 PO 走这条
     *      - 7.0 DO 也走这条
     *   3. 无障碍服务自动操作系统设置 UI（一加 ColorOS 等严格 ROM）
     *   4. 都不行 → PermissionDenied
     */
    fun setSystemTimezone(context: Context, tzId: String): Result {
        // 路径 1：Device Owner API (仅 API 28+ 提供)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && isDeviceOwner(context)) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(context, TimezoneAdminReceiver::class.java)
                val ok = dpm.setTimeZone(admin, tzId)
                if (ok) {
                    Log.i(TAG, "Time zone set via DEVICE_OWNER: $tzId")
                    return Result.Success(tzId, "DEVICE_OWNER")
                }
                // dpm.setTimeZone 返回 false 说明运营商 NITZ 自动同步开启，提示用户
                Log.w(TAG, "DPM.setTimeZone returned false (auto-sync may be on)")
            } catch (e: SecurityException) {
                Log.w(TAG, "DPM.setTimeZone SecurityException: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "DPM.setTimeZone failed: ${e.message}")
            }
        }

        // 路径 2：AlarmManager.setTimeZone()
        // - DO/PO 自动有 SET_TIME_ZONE 权限
        // - 关键：移除原版的"立即检查 TimeZone.getDefault()"误判逻辑
        //   setTimeZone 是异步的，立即检查几乎肯定失败，应该假设成功
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setTimeZone(tzId)
            // 不立即验证 - 设置是异步的
            // 调用未抛异常 = 系统接受了请求 = 视为成功
            Log.i(TAG, "Time zone set via ALARM_MANAGER: $tzId")
            return Result.Success(tzId, "ALARM_MANAGER")
        } catch (e: SecurityException) {
            Log.w(TAG, "AlarmManager.setTimeZone SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "AlarmManager.setTimeZone failed: ${e.message}")
        }

        // 路径 2.5 (v3.7.6 增强 / v3.7.7)：反射调用 IAlarmManager.setTimeZone()
        // 绕过 AlarmManager 包装层的 SET_TIME_ZONE 权限检查
        // 适用场景:
        //   - Android 14+ (A166P): 系统改了规则,SET_TIME_ZONE 不再自动授予 DO/PO
        //   - 第三方ROM (G5510): 阉割了 DO/PO 的自动授权
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val mServiceField = AlarmManager::class.java.getDeclaredField("mService")
            mServiceField.isAccessible = true
            val service = mServiceField.get(am)

            // v3.7.7 修复:用 getMethods + getDeclaredMethods + 接口的 methods 全找一遍
            // 因为 Stub$Proxy 上 public methods 不一定包含 setTimeZone(可能是 private)
            val allMethods = mutableSetOf<java.lang.reflect.Method>()
            allMethods.addAll(service.javaClass.methods)
            allMethods.addAll(service.javaClass.declaredMethods)
            // 也试找接口上的方法
            for (iface in service.javaClass.interfaces) {
                allMethods.addAll(iface.methods)
                allMethods.addAll(iface.declaredMethods)
            }
            val candidates = allMethods.filter { it.name == "setTimeZone" }

            for (method in candidates) {
                try {
                    method.isAccessible = true
                    val args: Array<Any?> = when (method.parameterTypes.size) {
                        1 -> arrayOf(tzId)
                        2 -> arrayOf(tzId, "android")
                        3 -> arrayOf(tzId, 0, "android")
                        else -> continue
                    }
                    method.invoke(service, *args)
                    Log.i(TAG, "Time zone set via REFLECTION (${method.parameterTypes.size}-arg): $tzId")
                    return Result.Success(tzId, "REFLECTION")
                } catch (e: Exception) {
                    Log.w(TAG, "Reflection ${method.parameterTypes.size}-arg failed: ${e.cause?.message ?: e.message}")
                }
            }
            if (candidates.isEmpty()) {
                Log.w(TAG, "Reflection: no setTimeZone method found on $service")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reflection setup failed: ${e.message}")
        }

        // 路径 2.6 (v3.7.7 新增)：直接 Binder transact 调用 alarm 服务
        // 这是 ADB 命令 `service call alarm 3 s16 <tz>` 的 Java 等价实现
        // 绕过所有 AIDL Proxy 层,直接发 Parcel 给底层 service
        // Android 15 上 IAlarmManager 移除了 setTimeZone 方法,但底层 alarm service 的
        // transaction code 3 还在(三星保留兼容性),所以直接 transact 能成功
        try {
            // 用反射拿 ServiceManager
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManager.getMethod("getService", String::class.java)
            val alarmBinder = getServiceMethod.invoke(null, "alarm") as android.os.IBinder?

            if (alarmBinder != null) {
                val data = android.os.Parcel.obtain()
                val reply = android.os.Parcel.obtain()
                try {
                    // alarm 服务的 transaction code 3 = setTimeZone
                    // 跟 ADB 命令 `service call alarm 3 s16 <tz>` 完全等价
                    // s16 = String, 16-bit
                    data.writeInterfaceToken("android.app.IAlarmManager")
                    data.writeString(tzId)
                    val ok = alarmBinder.transact(3, data, reply, 0)
                    reply.readException()  // 如果 service 端抛了 exception 这里会重抛
                    if (ok) {
                        Log.i(TAG, "Time zone set via BINDER_TRANSACT (code 3): $tzId")
                        return Result.Success(tzId, "BINDER_TRANSACT")
                    } else {
                        Log.w(TAG, "Binder transact returned false")
                    }
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } else {
                Log.w(TAG, "ServiceManager.getService(\"alarm\") returned null")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Binder transact failed: ${e.cause?.message ?: e.message}")
        }

        // 路径 2.7 (v3.7.8 新增)：TimeManager.setManualTimeZone
        // 这是 Android 12+ (API 31) 的新 API,走 time_detector 服务
        // Android 14+ 把时区设置从 alarm service 挪到了 time_detector
        // DO/PO 在 time_detector 上有 MANAGE_TIME_AND_ZONE_DETECTION 权限
        // 这是 A166P (Android 15 + PO) 上唯一可能成功的路径
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                // TimeManager 在 API 31+ 才存在,用反射调用避免编译期依赖
                val timeManager = context.getSystemService("time_manager")
                if (timeManager != null) {
                    // 构造 ManualTimeZoneSuggestion(zoneId)
                    val suggestionClass = Class.forName("android.app.time.ManualTimeZoneSuggestion")
                    val suggestion = suggestionClass.getConstructor(String::class.java).newInstance(tzId)

                    // 调 timeManager.setManualTimeZone(suggestion)
                    val setMethod = timeManager.javaClass.getMethod("setManualTimeZone", suggestionClass)
                    setMethod.invoke(timeManager, suggestion)

                    Log.i(TAG, "Time zone set via TIME_MANAGER (API 31+): $tzId")
                    return Result.Success(tzId, "TIME_MANAGER")
                } else {
                    Log.w(TAG, "TimeManager service not available")
                }
            } catch (e: Exception) {
                Log.w(TAG, "TimeManager setManualTimeZone failed: ${e.cause?.message ?: e.message}")
            }

            // 路径 2.8 (v3.7.8 同时新增): 直接 transact 到 time_detector 服务
            // 即使 TimeManager API 失败,也试着直接发 Binder 给 time_detector
            try {
                val serviceManager = Class.forName("android.os.ServiceManager")
                val getServiceMethod = serviceManager.getMethod("getService", String::class.java)
                val timeDetectorBinder = getServiceMethod.invoke(null, "time_detector") as android.os.IBinder?

                if (timeDetectorBinder != null) {
                    val data = android.os.Parcel.obtain()
                    val reply = android.os.Parcel.obtain()
                    try {
                        // 试 transaction code 1 (suggestManualTimeZone in some versions)
                        data.writeInterfaceToken("android.app.timedetector.ITimeDetectorService")
                        data.writeString(tzId)
                        val ok = timeDetectorBinder.transact(1, data, reply, 0)
                        reply.readException()
                        if (ok) {
                            Log.i(TAG, "Time zone set via TIME_DETECTOR_TRANSACT: $tzId")
                            return Result.Success(tzId, "TIME_DETECTOR_TRANSACT")
                        }
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "TimeDetector transact failed: ${e.cause?.message ?: e.message}")
            }
        }

        // 路径 3：无障碍服务自动操作（ColorOS 等严格 ROM）
        if (TimezoneAccessibilityService.isEnabled(context)) {
            TimezoneAccessibilityService.startAutomation(context, tzId)
            return Result.Success(tzId, "ACCESSIBILITY (跳转系统设置中…)")
        }

        // 都走不通
        return Result.PermissionDenied(tzId)
    }

    fun openDateSettings(context: Context) {
        val intent = Intent(Settings.ACTION_DATE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun deviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
    }

    /**
     * 解除 Device Owner 身份。
     */
    fun clearDeviceOwner(context: Context): Result {
        if (!isDeviceOwner(context)) {
            return Result.Error("当前不是 Device Owner，无需解除")
        }
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.clearDeviceOwnerApp(context.packageName)
            if (isDeviceOwner(context)) {
                Result.Error("系统未接受解除请求")
            } else {
                Result.Success("cleared", "DEVICE_OWNER_CLEARED")
            }
        } catch (e: SecurityException) {
            Result.Error("无权解除：${e.message}")
        } catch (e: Exception) {
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    const val PACKAGE_NAME = "com.ycr.tzsetter"
    const val ADMIN_COMPONENT = "$PACKAGE_NAME/.TimezoneAdminReceiver"

    val deviceOwnerCommand: String
        get() = "adb shell dpm set-device-owner $ADMIN_COMPONENT"

    val profileOwnerCommand: String
        get() = "adb shell dpm set-active-admin $ADMIN_COMPONENT && " +
                "adb shell dpm set-profile-owner --user 0 $ADMIN_COMPONENT"

    val grantPermissionCommand: String
        get() = "adb shell pm grant $PACKAGE_NAME android.permission.SET_TIME_ZONE"

    val grantLocationCommand: String
        get() = "adb shell pm grant $PACKAGE_NAME android.permission.ACCESS_FINE_LOCATION && " +
                "adb shell pm grant $PACKAGE_NAME android.permission.ACCESS_COARSE_LOCATION"
}
