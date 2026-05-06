package com.ycr.tzsetter

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * 设备管理员接收器。
 *
 * 用途：当 app 被注册为 Device Owner 后，它就有权限调用一些
 * 原本受限的系统 API（如 AlarmManager.setTimeZone() 等）。
 *
 * 注册为 Device Owner 的命令（需要先清除所有账户）：
 *   adb shell dpm set-device-owner com.ycr.tzsetter/.TimezoneAdminReceiver
 *
 * 注销（可选，会清除 Device Owner 状态）：
 *   adb shell dpm remove-active-admin com.ycr.tzsetter/.TimezoneAdminReceiver
 */
class TimezoneAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // Device Admin 被启用时的回调，无需操作
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
