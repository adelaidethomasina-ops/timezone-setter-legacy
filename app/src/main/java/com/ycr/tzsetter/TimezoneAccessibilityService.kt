package com.ycr.tzsetter

import android.os.Build
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 时区自动设置无障碍服务 — v3.7.12 修复版
 *
 * 修复内容:
 *   1. isEnabled() 兼容三种 settings 格式(完整/简短/无包名)+ 兜底匹配
 *   2. 包名过滤改为只要包含"settings"就接受(兼容 OEM 变体)
 *   3. detectStage() 优先级修正:
 *      - 移除 GMT 兜底误判(日期页副标题包含 GMT)
 *      - 必须有目标城市 text 才认为是 LIST_PAGE
 *   4. tryAutomate() 加 root 节点 text 日志,识别失败时打印前 20 个 text
 *   5. handleDatePage() 双重保险 — 找开关失败时也尝试直接点"选择时区"
 *      (如果开关已是关闭状态)
 *   6. 等待 pendingTimezoneId 非空才触发处理
 *   7. 所有诊断日志改为 Log.i(默认 logcat 可见)
 *
 * 支持 ROM:
 *   - 三星 OneUI 7 (Android 15)  - A166P
 *   - 第三方 ROM 8.1               - G5510
 *   - ColorOS / OxygenOS / 氢OS    - 一加5
 */
class TimezoneAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TzA11y"

        @Volatile var pendingTimezoneId: String? = null
        @Volatile var isRunning: Boolean = false
            private set
        @Volatile var lastStatus: String = ""
        @Volatile private var inOperationUntilMs: Long = 0L
        // v3.7.24 时间戳机制 — 不依赖 handler,避免被 removeCallbacksAndMessages 清掉
        fun isInOperation(): Boolean = android.os.SystemClock.uptimeMillis() < inOperationUntilMs
        fun setInOperation(durationMs: Long) {
            inOperationUntilMs = android.os.SystemClock.uptimeMillis() + durationMs
        }
        fun clearInOperation() {
            inOperationUntilMs = 0L
        }

        /** v3.7.12 修复:兼容三种 settings 格式 */
        fun isEnabled(context: Context): Boolean {
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val pkg = context.packageName
                val cls = TimezoneAccessibilityService::class.java.name
                val shortName = cls.substringAfterLast('.')
                // 三种合法格式 + 兜底
                val targets = setOf(
                    "$pkg/$cls",                    // 完整
                    "$pkg/.$shortName",             // 短形式 ADB 常用
                    "$pkg/$shortName"               // 无包名
                )
                val enabled = enabledServices.split(":").any { entry ->
                    val trimmed = entry.trim()
                    targets.any { it.equals(trimmed, ignoreCase = true) } ||
                            trimmed.contains(cls, ignoreCase = true) ||
                            (trimmed.contains(pkg, ignoreCase = true) &&
                                    trimmed.contains(shortName, ignoreCase = true))
                }
                Log.i(TAG, "isEnabled='$enabledServices' result=$enabled")
                enabled
            } catch (e: Exception) {
                Log.w(TAG, "isEnabled exception: ${e.message}")
                false
            }
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        @Volatile private var lastStageLoggedStatic: Stage = Stage.IDLE
        @Volatile private var attemptCountStatic: Int = 0

        fun startAutomation(context: Context, targetTimezoneId: String) {
            pendingTimezoneId = targetTimezoneId
            isRunning = true
            clearInOperation()
            // v3.7.13 修复:重置所有状态,避免上次运行残留 lastStageLogged=LIST_PAGE 导致跳过 stage 检测
            lastStageLoggedStatic = Stage.IDLE
            attemptCountStatic = 0
            lastStatus = "启动中..."
            Log.i(TAG, "startAutomation tzId=$targetTimezoneId (reset state)")
            val intent = Intent(Settings.ACTION_DATE_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        }

        fun cityNamesForTimezone(tzId: String): List<String> {
            return when (tzId) {
                "America/Los_Angeles" -> listOf("洛杉矶", "Los Angeles")
                "America/Phoenix" -> listOf("凤凰城", "Phoenix")
                "America/Denver" -> listOf("丹佛", "Denver")
                "America/Boise" -> listOf("博伊西", "Boise", "丹佛")
                "America/Chicago" -> listOf("芝加哥", "Chicago")
                "America/Indiana/Indianapolis" -> listOf("印第安纳波利斯", "Indianapolis", "纽约")
                "America/Indianapolis" -> listOf("印第安纳波利斯", "Indianapolis", "纽约")
                "America/New_York" -> listOf("纽约", "New York")
                "America/Detroit" -> listOf("底特律", "Detroit", "纽约")
                "America/Anchorage" -> listOf("安克雷奇", "Anchorage")
                "America/Juneau" -> listOf("朱诺", "Juneau", "安克雷奇")
                "America/Sitka" -> listOf("锡特卡", "Sitka", "安克雷奇")
                "America/Adak" -> listOf("埃达克", "Adak")
                "America/Metlakatla" -> listOf("梅特拉卡特拉", "Metlakatla", "安克雷奇")
                "Pacific/Honolulu" -> listOf("檀香山", "Honolulu", "夏威夷")
                "America/Toronto" -> listOf("多伦多", "Toronto", "纽约")
                "America/Vancouver" -> listOf("温哥华", "Vancouver", "洛杉矶")
                "America/Edmonton" -> listOf("埃德蒙顿", "Edmonton", "丹佛")
                "America/Winnipeg" -> listOf("温尼伯", "Winnipeg", "芝加哥")
                "America/Halifax" -> listOf("哈利法克斯", "Halifax")
                "America/St_Johns" -> listOf("圣约翰斯", "St. John's")
                else -> {
                    val city = tzId.substringAfterLast('/').replace('_', ' ')
                    listOf(city)
                }
            }
        }

        fun countryNameForTimezone(tzId: String): String {
            val canadianCities = listOf(
                "America/Toronto", "America/Vancouver", "America/Edmonton",
                "America/Winnipeg", "America/Halifax", "America/St_Johns",
                "America/Regina", "America/Whitehorse", "America/Yellowknife",
                "America/Iqaluit", "America/Moncton", "America/Goose_Bay"
            )
            return when {
                tzId in canadianCities -> "加拿大"
                tzId.startsWith("America/") || tzId == "Pacific/Honolulu" -> "美国"
                tzId.startsWith("Asia/Shanghai") || tzId.startsWith("Asia/Chongqing") -> "中国"
                tzId.startsWith("Asia/Tokyo") -> "日本"
                tzId.startsWith("Asia/Seoul") -> "韩国"
                tzId.startsWith("Europe/London") -> "英国"
                tzId.startsWith("Europe/Paris") -> "法国"
                tzId.startsWith("Europe/Berlin") -> "德国"
                tzId.startsWith("Australia/") -> "澳大利亚"
                else -> ""
            }
        }
    }

    private enum class Stage {
        IDLE, DATE_PAGE, TZ_PAGE, COUNTRY_PAGE, LIST_PAGE, DONE
    }

    @Volatile private var stage: Stage = Stage.IDLE
    @Volatile private var attemptCount: Int = 0
    @Volatile private var lastStageLogged: Stage = Stage.IDLE
    private val handler = Handler(Looper.getMainLooper())
    // v3.7.26: 两个 Runnable 引用作为 token,onAccessibilityEvent 只移除 eventRunnable,不影响 retryRunnable
    private val eventRunnable = Runnable { tryAutomate() }
    private val retryRunnable = Runnable {
        if (isRunning && !isInOperation()) tryAutomate()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // v3.7.16: 加 FLAG_RETRIEVE_INTERACTIVE_WINDOWS,否则 getWindows() 返回空
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.i(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""
        // v3.7.13 加诊断:看每个事件的来源
        if (isRunning && pkg.contains("settings", ignoreCase = true)) {
            Log.i(TAG, "event pkg=$pkg type=${event.eventType} isRunning=$isRunning tz=$pendingTimezoneId inOp=${isInOperation()}")
        }
        if (!isRunning || pendingTimezoneId == null) return
        if (!pkg.contains("settings", ignoreCase = true)) return
        // v3.7.14: 即使 inOperation 也允许 schedule(handler 会去重),
        // 不过会被 tryAutomate 入口的 inOperation 检查再次拦截
        // 但这次 schedule 会等当前 inOperation 释放后再执行

        // v3.7.26: 只移除 eventRunnable(我们的 event-task),保留 retryRunnable
        handler.removeCallbacks(eventRunnable)
        handler.postDelayed(eventRunnable, 350)
    }

    private fun tryAutomate() {
        if (!isRunning || isInOperation()) return
        val tzId = pendingTimezoneId ?: return
        // v3.7.16 关键修复:不用 rootInActiveWindow,因为它可能返回 tzsetter MainActivity 的根
        // 而是从 windows 列表里找 settings APP 的窗口
        val root = findSettingsRoot()
        if (root == null) {
            Log.w(TAG, "settings root is null")
            return
        }

        // v3.7.24: 设置时间戳标记(800ms 后自动失效),避免依赖 handler 调度
        setInOperation(800)
        try {
            val detectedStage = detectStage(root)
            // v3.7.13 修复:每次都打印当前页面前 8 个 text,方便诊断
            val previewTexts = mutableListOf<String>()
            walkAndCollectTexts(root, previewTexts, 0)
            Log.i(TAG, "tryAutomate stage=$detectedStage tz=$tzId rootChildren=${root.childCount} totalTexts=${previewTexts.size} firstTexts=${previewTexts.take(20)}")

            if (detectedStage == Stage.IDLE) {
                attemptCountStatic++
                if (attemptCountStatic > 30) {
                    Log.w(TAG, "IDLE timeout, cleanup")
                    cleanup()
                    return
                }
                // v3.7.14 修复:IDLE 时立刻释放 inOperation,允许后续事件继续触发
                clearInOperation()
                // v3.7.26: 用 eventRunnable
                handler.removeCallbacks(eventRunnable)
                handler.postDelayed(eventRunnable, 500)
                return
            }
            attemptCountStatic = 0
            lastStageLoggedStatic = detectedStage

            when (detectedStage) {
                Stage.DATE_PAGE -> handleDatePage(root)
                Stage.TZ_PAGE -> handleTzPage(root)
                Stage.COUNTRY_PAGE -> handleCountryPage(root, tzId)
                Stage.LIST_PAGE -> handleListPage(root, tzId)
                Stage.DONE, Stage.IDLE -> { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Automation error", e)
            cleanup()
        } finally {
            // v3.7.26: 用 retryRunnable(单独 Runnable),onAccessibilityEvent 不会清掉它
            if (isRunning && stage != Stage.DONE) {
                handler.removeCallbacks(retryRunnable)
                handler.postDelayed(retryRunnable, 1000)
            }
        }
    }

    /**
     * v3.7.12 修复:detectStage 优先级和判断条件
     *
     * 优先级(从特殊到一般):
     *   1. 国家选择页:有 search_src_text(rid 是OneUI 7独有)
     *   2. 选择时区页:有"地区"+"时区"两行,且没有"自动设置时区"
     *   3. 日期和时间页:有"自动设置时区"+"选择时区"
     *   4. 时区列表页:有目标城市 text(不再依赖 GMT 兜底)
     *   5. IDLE:都不是
     */
    private fun detectStage(root: AccessibilityNodeInfo): Stage {
        // v3.7.22: 加详细诊断日志看 OneUI 7 实际匹配情况

        val tz1 = hasNodeWithText(root, "自动设置时区")
        val tz2 = hasNodeWithText(root, "自动设置日期和时间")
        val tz3 = hasNodeWithText(root, "Set time zone automatically")
        val hasAutoSwitch = tz1 || tz2 || tz3 ||
                hasNodeWithText(root, "Automatic date & time") ||
                hasNodeWithText(root, "自动同步")
        val sel1 = hasNodeWithText(root, "选择时区")
        val sel2 = hasNodeWithText(root, "Select time zone")
        val hasSelectTz = sel1 || sel2
        val hasSearchBox = hasNodeWithRid(root, "com.android.settings:id/search_src_text") ||
                hasNodeWithRid(root, "com.samsung.settings:id/search_src_text") ||
                hasNodeWithRid(root, "com.sec.android.settings:id/search_src_text")
        val hasRegionRow = hasNodeWithText(root, "地区") && hasNodeWithText(root, "时区")

        Log.i(TAG, "detectStage: 自动设置时区=$tz1, 自动设置日期和时间=$tz2, 自动同步=${hasNodeWithText(root, "自动同步")}, 选择时区=$sel1")
        Log.i(TAG, "detectStage: hasAutoSwitch=$hasAutoSwitch hasSelectTz=$hasSelectTz hasSearchBox=$hasSearchBox hasRegionRow=$hasRegionRow")
        // v3.7.23: OneUI 7 SwitchPreference 节点 walk 进不去,导致 hasAutoSwitch 始终 false
        // 但"选择时区"是普通 TextView 能找到 — 只要有"选择时区"就一定在日期和时间页
        // 注意: TZ_PAGE 也有"选择时区"导航上的文字,但它有"地区"+"时区"区分

        // v3.7.23 优先级判断:
        // 1) 国家选择页(搜索框) — 必须最先判断,因为 search_src_text 是独有特征
        if (hasSearchBox) {
            return Stage.COUNTRY_PAGE
        }

        // 2) 选择时区页 — 有"地区"+"时区"两行(且没"选择时区"标题在导航栏)
        //    "选择时区"在 TZ_PAGE 是导航栏标题,所以 hasSelectTz 也可能 true
        //    但 TZ_PAGE 同时有 hasRegionRow,DATE_PAGE 没有
        if (hasRegionRow) {
            return Stage.TZ_PAGE
        }

        // 3) 日期和时间页 — 有"选择时区"
        //    OneUI 7 上 hasAutoSwitch 因 SwitchPreference 节点 walk 进不去而始终 false
        //    所以只用 hasSelectTz 判断(排除上面 COUNTRY/TZ 后剩下的就是 DATE)
        //    第三方ROM/ColorOS 上 hasAutoSwitch 也为 true,逻辑一样成立
        if (hasSelectTz) {
            return Stage.DATE_PAGE
        }

        // 4) 时区列表页
        val tzId = pendingTimezoneId
        if (tzId != null) {
            for (city in cityNamesForTimezone(tzId)) {
                if (hasNodeWithText(root, city)) return Stage.LIST_PAGE
            }
            if (hasNodeWithTextContaining(root, "GMT-") ||
                hasNodeWithTextContaining(root, "GMT+")) {
                return Stage.LIST_PAGE
            }
        }

        return Stage.IDLE
    }

    private fun handleDatePage(root: AccessibilityNodeInfo) {
        // 1) 找开关并关闭(如果是开着的)
        val autoTzText = findOneOfTexts(root, listOf(
            "自动设置时区", "自动设置日期和时间",
            "Set time zone automatically", "Automatic date & time",
            "自动同步"
        ))
        if (autoTzText != null) {
            val switch = findSwitchNearText(autoTzText)
            if (switch != null && switch.isChecked) {
                Log.i(TAG, "Toggle off auto-tz switch")
                clickWithFallback(switch)
                lastStatus = "关闭自动时区..."
                return  // 等下次事件
            } else {
                Log.i(TAG, "switch state: ${switch?.isChecked}, no need to toggle")
            }
        }

        // 2) 点"选择时区"
        val selectTzNode = findFirstByText(root, "选择时区")
            ?: findFirstByText(root, "Select time zone")
        if (selectTzNode != null) {
            Log.i(TAG, "Click 选择时区")
            clickWithFallback(selectTzNode)
            lastStatus = "进入时区选择..."
            stage = Stage.TZ_PAGE
            return
        }

        Log.w(TAG, "DATE_PAGE: 找不到'选择时区'按钮")
        attemptCount++
        if (attemptCount > 5) cleanup()
    }

    private fun handleTzPage(root: AccessibilityNodeInfo) {
        val regionNode = findFirstByText(root, "地区") ?: findFirstByText(root, "Region")
        if (regionNode != null) {
            Log.i(TAG, "Click 地区")
            clickWithFallback(regionNode)
            lastStatus = "进入国家选择..."
            stage = Stage.COUNTRY_PAGE
        }
    }

    private fun handleCountryPage(root: AccessibilityNodeInfo, tzId: String) {
        val countryName = countryNameForTimezone(tzId)
        if (countryName.isEmpty()) {
            Log.w(TAG, "no country mapping for $tzId")
            return
        }

        val searchBox = findNodeByRid(root, "com.android.settings:id/search_src_text")
            ?: findNodeByRid(root, "com.samsung.settings:id/search_src_text")
            ?: findNodeByRid(root, "com.sec.android.settings:id/search_src_text")
        if (searchBox == null) {
            Log.w(TAG, "no search box found")
            return
        }

        val currentText = searchBox.text?.toString() ?: ""
        if (currentText != countryName) {
            Log.i(TAG, "input country: $countryName")
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                countryName
            )
            searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            lastStatus = "搜索 $countryName..."
            return
        }

        // v3.7.27 修复:必须找列表项的 TextView (rid=android:id/title),用坐标点击
        val list = root.findAccessibilityNodeInfosByText(countryName)
        Log.i(TAG, "country candidates: ${list?.size ?: 0} items")
        // 打印所有候选 — 调试用
        list?.forEach { node ->
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            Log.i(TAG, "  candidate: text='${node.text}' rid='${node.viewIdResourceName}' clickable=${node.isClickable} bounds=$r")
        }

        // v3.7.28 修复:OneUI 7 viewIdResourceName 全部返回 null,无法用 rid 区分
        // 改用 bounds + clickable 区分:
        //   searchBox: clickable=true, Y在屏幕顶部(< 300)
        //   列表项: clickable=false, Y在列表区域(>= 300)
        val countryItem = list?.firstOrNull { node ->
            val nodeText = node.text?.toString()
            if (nodeText != countryName) return@firstOrNull false
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            // 列表项特征:Y >= 300 (排除顶部 searchBox)
            // 不要求 clickable,因为列表项 TextView 自己 clickable=false
            r.top >= 300
        } ?: list?.firstOrNull { node ->
            // 兜底:精确文字匹配
            node.text?.toString() == countryName
        }

        if (countryItem != null) {
            val nodeRect = android.graphics.Rect()
            countryItem.getBoundsInScreen(nodeRect)
            Log.i(TAG, "click country: $countryName (rid=${countryItem.viewIdResourceName} bounds=$nodeRect)")

            // v3.7.27 关键修复:用坐标点击(更可靠,不依赖找 clickable 祖先)
            if (nodeRect.width() > 10 && nodeRect.height() > 10) {
                clickByCoordinates(nodeRect.centerX().toFloat(), nodeRect.centerY().toFloat())
            } else {
                clickWithFallback(countryItem)
            }
            lastStatus = "进入 $countryName 时区..."
            stage = Stage.LIST_PAGE
        } else {
            Log.w(TAG, "no country list item found for $countryName")
        }
    }

    /**
     * v3.7.18 重写 handleListPage:
     *   1. 检测目标城市是否可见,可见就点
     *   2. 不可见时:用 GestureDescription 滑动半屏(更精确,避免错过)
     *   3. 检测"已经滑过头":如果当前页有之前没出现的更后面的时区,反向回滚
     */
    private var lastVisibleCities: List<String> = emptyList()
    private var scrollDirection: Int = 0  // 0=未确定,1=向下,-1=向上
    private var stuckCount: Int = 0  // 连续多少次列表没变化(到达边界)
    private var directionReversed: Boolean = false  // 是否已反向过

    /**
     * 时区 IANA ID 到 GMT 标准时间偏移(小时,不含 DST)
     * 用于决定列表是该向上滚还是向下滚
     */
    private fun gmtOffsetForTimezone(tzId: String): Int {
        return when (tzId) {
            "America/Los_Angeles", "America/Vancouver" -> -8
            "America/Phoenix", "America/Denver", "America/Boise",
            "America/Edmonton" -> -7
            "America/Chicago", "America/Winnipeg" -> -6
            "America/Indiana/Indianapolis", "America/Indianapolis",
            "America/New_York", "America/Detroit", "America/Toronto" -> -5
            "America/Halifax" -> -4
            "America/St_Johns" -> -3
            "America/Anchorage", "America/Juneau", "America/Sitka",
            "America/Metlakatla" -> -9
            "America/Adak", "Pacific/Honolulu" -> -10
            else -> 0
        }
    }

    /**
     * 从 list visible 列表里推断当前显示的 GMT 偏移
     * 返回 null 表示无法推断
     */
    private fun inferCurrentGmtFromList(visibleTexts: List<String>): Int? {
        for (t in visibleTexts) {
            val m = Regex("GMT([+-])(\\d{1,2})").find(t)
            if (m != null) {
                val sign = if (m.groupValues[1] == "-") -1 else 1
                return sign * m.groupValues[2].toInt()
            }
        }
        return null
    }

    private fun handleListPage(root: AccessibilityNodeInfo, tzId: String) {
        // 1) 找目标城市 — 找到立刻点(回归 v3.7.17 逻辑)
        for (city in cityNamesForTimezone(tzId)) {
            val cityNode = findFirstByText(root, city)
            if (cityNode != null) {
                val nodeRect = android.graphics.Rect()
                cityNode.getBoundsInScreen(nodeRect)
                Log.i(TAG, "click city: $city for $tzId bounds=$nodeRect clickable=${cityNode.isClickable}")
                clickWithFallback(cityNode)
                lastStatus = "已选择 $city ✓"
                stage = Stage.DONE

                handler.postDelayed({
                    cleanup()
                    val backIntent = packageManager.getLaunchIntentForPackage(packageName)
                    backIntent?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        startActivity(it)
                    }
                }, 1500)
                return
            }
        }

        // 2) 收集当前可见城市
        val currentVisible = mutableListOf<String>()
        walkAndCollectTexts(root, currentVisible, 0)
        Log.i(TAG, "list visible: ${currentVisible.take(10)}")

        // 3) 智能方向判断(只在第一次)
        if (scrollDirection == 0) {
            val targetGmt = gmtOffsetForTimezone(tzId)
            val currentGmt = inferCurrentGmtFromList(currentVisible)
            scrollDirection = if (currentGmt == null) {
                // 无法推断,默认向下
                1
            } else if (targetGmt < currentGmt) {
                -1  // 目标 GMT 更小,向上滚
            } else {
                1   // 目标 GMT 更大,向下滚
            }
            Log.i(TAG, "scroll direction decided: dir=$scrollDirection (target GMT$targetGmt, current GMT$currentGmt)")
        }

        // 4) 检测"卡住":连续 visible 不变 = 到达边界
        val visibleStr = currentVisible.take(8).joinToString(",")
        val lastStr = lastVisibleCities.take(8).joinToString(",")
        if (visibleStr == lastStr && currentVisible.isNotEmpty()) {
            stuckCount++
            Log.i(TAG, "list unchanged, stuckCount=$stuckCount")
            if (stuckCount >= 2 && !directionReversed) {
                // 反向滚
                scrollDirection = -scrollDirection
                directionReversed = true
                stuckCount = 0
                Log.i(TAG, "list stuck, REVERSE direction to dir=$scrollDirection")
            } else if (stuckCount >= 3 && directionReversed) {
                Log.w(TAG, "list stuck after reverse, giveup")
                cleanup()
                return
            }
        } else {
            stuckCount = 0
        }
        lastVisibleCities = currentVisible.toList()

        // 5) 滚动
        val scrollable = findScrollable(root)
        if (scrollable == null) {
            Log.w(TAG, "no scrollable")
            attemptCount++
            if (attemptCount > 5) cleanup()
            return
        }

        scrollHalfScreen(scrollable, scrollDirection)
        attemptCount++
        if (attemptCount > 60) {
            Log.w(TAG, "list scroll giveup after 60 attempts")
            cleanup()
        }
    }

    /**
     * 用 GestureDescription 滑动半屏(而非 ACTION_SCROLL_FORWARD 的整屏)
     */
    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.N)
    private fun scrollHalfScreen(scrollable: AccessibilityNodeInfo, direction: Int) {
        // v3.7.28-l: Android < 7.0 (24) 不支持 dispatchGesture,直接回退 ACTION_SCROLL_FORWARD
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            scrollable.performAction(if (direction > 0)
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            return
        }
        try {
            val rect = android.graphics.Rect()
            scrollable.getBoundsInScreen(rect)
            if (rect.height() < 200) {
                // 可滚动区域太小,用旧 API 兜底
                scrollable.performAction(if (direction > 0)
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                return
            }

            val centerX = (rect.left + rect.right) / 2f
            // 滑半屏:从屏幕 70% 滑到 30% (向下找新内容时手势从下向上)
            val startY: Float
            val endY: Float
            if (direction > 0) {
                // 向后翻页(看下面更多内容)— 手势从下往上
                startY = rect.top + rect.height() * 0.75f
                endY = rect.top + rect.height() * 0.25f
            } else {
                // 向前翻页 — 手势从上往下
                startY = rect.top + rect.height() * 0.25f
                endY = rect.top + rect.height() * 0.75f
            }

            val path = android.graphics.Path().apply {
                moveTo(centerX, startY)
                lineTo(centerX, endY)
            }

            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 250))
                .build()

            dispatchGesture(gesture, null, null)
            Log.i(TAG, "scroll half-screen: dir=$direction startY=$startY endY=$endY")
        } catch (e: Exception) {
            Log.w(TAG, "scrollHalfScreen failed: ${e.message}, fallback")
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
    }

    private fun cleanup() {
        Log.i(TAG, "Cleanup")
        isRunning = false
        pendingTimezoneId = null
        clearInOperation()
        stage = Stage.IDLE
        lastStageLogged = Stage.IDLE
        attemptCount = 0
        lastStageLoggedStatic = Stage.IDLE
        attemptCountStatic = 0
        scrollDirection = 0  // v3.7.20: 0 = 未确定
        stuckCount = 0
        directionReversed = false
        lastVisibleCities = emptyList()
        handler.removeCallbacksAndMessages(null)
    }

    // ============ helpers ============

    private fun walkAndCollectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        // v3.7.22: 进一步放宽限制
        if (depth > 50 || out.size > 200) return
        val t = node.text?.toString()
        if (!t.isNullOrEmpty()) out.add(t)
        val cd = node.contentDescription?.toString()
        if (!cd.isNullOrEmpty() && cd != t) out.add("[cd]$cd")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walkAndCollectTexts(child, out, depth + 1)
        }
    }

    private fun findFirstByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // 优先用 findAccessibilityNodeInfosByText(它实际是从 root 开始搜索)
        val list = root.findAccessibilityNodeInfosByText(text)
        if (!list.isNullOrEmpty()) {
            // 优先精确匹配的
            val exact = list.firstOrNull {
                (it.text?.toString() == text) || (it.contentDescription?.toString() == text)
            }
            if (exact != null) return exact
            return list.firstOrNull()
        }
        // 兜底:walk 找
        return walkAndFind(root) {
            (it.text?.toString() == text) || (it.contentDescription?.toString() == text)
        }
    }

    private fun findOneOfTexts(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        for (t in texts) {
            val n = findFirstByText(root, t)
            if (n != null) return n
        }
        return null
    }

    private fun findNodeByRid(root: AccessibilityNodeInfo, rid: String): AccessibilityNodeInfo? {
        return root.findAccessibilityNodeInfosByViewId(rid)?.firstOrNull()
    }

    private fun hasNodeWithText(root: AccessibilityNodeInfo, text: String): Boolean {
        // v3.7.17 修复:不用 findAccessibilityNodeInfosByText (它会跨窗口搜索)
        // 改用 walk 当前 root 树
        return walkAndCheck(root) {
            (it.text?.toString() == text) ||
            (it.contentDescription?.toString() == text)
        }
    }

    private fun hasNodeWithRid(root: AccessibilityNodeInfo, rid: String): Boolean {
        return findNodeByRid(root, rid) != null
    }

    private fun hasNodeWithTextContaining(root: AccessibilityNodeInfo, sub: String): Boolean {
        return walkAndCheck(root) { (it.text?.toString() ?: "").contains(sub) }
    }

    private fun walkAndCheck(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        return walkAndCheckImpl(node, predicate, 0)
    }

    private fun walkAndCheckImpl(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean, depth: Int): Boolean {
        // v3.7.22: 提高深度限制到 50,以防深度计算不准
        if (depth > 50) return false
        if (predicate(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (walkAndCheckImpl(child, predicate, depth + 1)) return true
        }
        return false
    }

    private fun findSwitchNearText(textNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent: AccessibilityNodeInfo? = textNode.parent
        var depth = 0
        while (parent != null && depth < 5) {
            val s = findSwitchIn(parent)
            if (s != null) return s
            parent = parent.parent
            depth++
        }
        return null
    }

    private fun findSwitchIn(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString() ?: ""
        if (cls.contains("Switch")) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = findSwitchIn(child)
            if (r != null) return r
        }
        return null
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return walkAndFind(root) { it.isScrollable }
    }

    private fun walkAndFind(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val r = walkAndFind(child, predicate)
            if (r != null) return r
        }
        return null
    }

    /**
     * v3.7.27: 用 dispatchGesture 在指定坐标点击
     * 比 ACTION_CLICK 更可靠 — 真实模拟手指触摸
     */
    @android.annotation.TargetApi(android.os.Build.VERSION_CODES.N)
    private fun clickByCoordinates(x: Float, y: Float) {
        // v3.7.28-l: Android < 7.0 (24) 不支持 dispatchGesture
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "clickByCoordinates not supported on SDK ${Build.VERSION.SDK_INT}, skip")
            return
        }
        try {
            val path = android.graphics.Path().apply {
                moveTo(x, y)
            }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            dispatchGesture(gesture, null, null)
            Log.i(TAG, "clickByCoordinates: ($x, $y)")
        } catch (e: Exception) {
            Log.w(TAG, "clickByCoordinates failed: ${e.message}")
        }
    }

    private fun clickWithFallback(node: AccessibilityNodeInfo) {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 6) {
            if (parent.isClickable) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            parent = parent.parent
            depth++
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }


    /**
     * v3.7.16 关键方法:从 windows 列表里找 settings APP 的窗口根节点
     * 避免 rootInActiveWindow 返回 tzsetter MainActivity 的根
     */
    private fun findSettingsRoot(): AccessibilityNodeInfo? {
        try {
            val wins = windows ?: return rootInActiveWindow
            // 优先找 settings APP 的窗口
            for (w in wins) {
                val r = w.root ?: continue
                val pkg = r.packageName?.toString() ?: ""
                if (pkg.contains("settings", ignoreCase = true)) {
                    Log.d(TAG, "found settings window: pkg=$pkg")
                    return r
                }
            }
            Log.w(TAG, "no settings window found, fallback to rootInActiveWindow")
            return rootInActiveWindow
        } catch (e: Exception) {
            Log.w(TAG, "findSettingsRoot exception: ${e.message}")
            return rootInActiveWindow
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }
}
