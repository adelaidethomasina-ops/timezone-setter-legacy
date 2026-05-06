package com.ycr.tzsetter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
// v3.7.5 新增 import
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    // v3.7.5 新增:运行时权限请求 launcher
    // 必须定义为 class field(在 onCreate 之前),否则会抛 LifecycleOwner state 异常
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.filter { it.value }.map { it.key }
        val denied = permissions.entries.filter { !it.value }.map { it.key }
        if (denied.isNotEmpty()) {
            android.util.Log.w("MainActivity", "Permissions denied: $denied")
        }
        if (granted.isNotEmpty()) {
            android.util.Log.i("MainActivity", "Permissions granted: $granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ZipTimezoneLookup.ensureLoaded(applicationContext)
        OfflineGeoLookup.ensureLoaded(applicationContext)

        // v3.7.5 新增:启动时请求运行时定位权限 + 通知权限
        // - 定位权限:API 34+ 启动 location 类型前台服务必需,否则 SecurityException 闪退
        // - 通知权限:API 33+ 必需,否则前台服务通知不显示
        requestRequiredPermissions()

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                primary = Color(0xFF1E5EFF),
                onPrimary = Color.White,
                surface = Color(0xFFF7F8FA),
                background = Color(0xFFF7F8FA),
            )) { AppScreen() }
        }
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()

        // 定位权限(API 34+ 启动 FGS location 必需)
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // 通知权限(API 33+ 必需)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

/** UI 层合并结果 */
data class CombinedResult(
    val tz: ZipTimezoneLookup.LookupResult,
    val geo: NetworkDataSource.PlaceInfo?,
    val geoError: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var zipInput by remember { mutableStateOf("") }
    var combined by remember { mutableStateOf<CombinedResult?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var tzApplyResult by remember { mutableStateOf<SystemTimezoneSetter.Result?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var authMode by remember { mutableStateOf(SystemTimezoneSetter.getAuthMode(context)) }
    var mockStatus by remember { mutableStateOf(MockLocationController.getStatus(context)) }
    var mockRunning by remember { mutableStateOf(MockLocationService.isRunning) }
    var showAuthGuide by remember { mutableStateOf(false) }
    var showMockGuide by remember { mutableStateOf(false) }
    var showSuccessDialog by remember { mutableStateOf<String?>(null) }
    var dialogDismissed by remember { mutableStateOf(false) }

    // 定时刷新状态
    LaunchedEffect(Unit) {
        while (true) {
            authMode = SystemTimezoneSetter.getAuthMode(context)
            mockStatus = MockLocationController.getStatus(context)
            mockRunning = MockLocationService.isRunning
            // 无障碍模式：检测 lastStatus，若已完成则替换 tzApplyResult（仅一次）
            val r = tzApplyResult
            if (r is SystemTimezoneSetter.Result.Success && r.via.startsWith("ACCESSIBILITY") && !r.via.contains("✓")) {
                val a11yStatus = TimezoneAccessibilityService.lastStatus
                if (a11yStatus.startsWith("✓") || a11yStatus.startsWith("\u2713")) {
                    tzApplyResult = SystemTimezoneSetter.Result.Success(r.tzId, "ACCESSIBILITY ($a11yStatus)")
                    // 清掉 lastStatus 防止下次循环再次触发
                    TimezoneAccessibilityService.lastStatus = ""
                    if (!dialogDismissed) {
                        val locInfo = combined?.geo?.let { g -> "\n📍 ${g.placeName}, ${g.stateAbbrev}" } ?: ""
                        showSuccessDialog = "✓ 时区 + 定位 已设置\n🕐 ${r.tzId}$locInfo\n(通过无障碍辅助)"
                    }
                }
            }
            kotlinx.coroutines.delay(1500)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("时区 + 定位助手", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 两个状态卡片并排或叠放
            AuthStatusCard(authMode) { showAuthGuide = true }
            MockStatusCard(mockStatus, mockRunning) { showMockGuide = true }

            ZipInputCard(
                zipInput = zipInput,
                isLoading = isLoading,
                onZipChange = {
                    zipInput = it
                    errorMsg = null
                    tzApplyResult = null
                    dialogDismissed = false
                },
                onQuery = {
                    // 收起键盘
                    focusManager.clearFocus()
                    // 1. 本地查时区
                    val tz = ZipTimezoneLookup.lookup(context, zipInput)
                    if (tz == null) {
                        combined = null
                        errorMsg = "无法识别邮编。US 用 5 位数字（如 97058），CA 用 6 位 FSA（如 M5V3L9）"
                        return@ZipInputCard
                    }
                    errorMsg = null
                    combined = CombinedResult(tz, null)
                    tzApplyResult = null

                    // 2. 异步查经纬度（缓存优先）
                    isLoading = true
                    scope.launch {
                        val countryCode =
                            if (tz.country == ZipTimezoneLookup.Country.US) "us" else "ca"
                        // US 邮编补齐 5 位（前导零）
                        val queryZip = if (tz.country == ZipTimezoneLookup.Country.US)
                            tz.normalized.take(5).padStart(5, '0')
                        else
                            tz.normalized.take(3)

                        // 1. 离线数据（优先，瞬间返回）
                        val offline = OfflineGeoLookup.lookup(context, countryCode, queryZip)
                        if (offline != null) {
                            val info = NetworkDataSource.PlaceInfo(
                                zip = offline.zip,
                                country = countryCode.uppercase(),
                                placeName = offline.city,
                                state = offline.stateAbbrev,
                                stateAbbrev = offline.stateAbbrev,
                                latitude = offline.latitude,
                                longitude = offline.longitude,
                            )
                            combined = combined?.copy(geo = info)
                            isLoading = false
                            return@launch
                        }

                        // 2. 本地缓存（上次通过网络查过）
                        val cached = LocationCache.get(context, countryCode, queryZip)
                        if (cached != null) {
                            combined = combined?.copy(geo = cached)
                            isLoading = false
                            return@launch
                        }

                        // 3. 网络兜底
                        combined = combined?.copy(
                            geoError = "离线库无此邮编，正在联网查询..."
                        )
                        val (fetched, netErr) = try {
                            withContext(Dispatchers.IO) {
                                NetworkDataSource.fetch(countryCode, queryZip) to null
                            }
                        } catch (e: java.net.UnknownHostException) {
                            null to "无网络，请打开网络连接后重试"
                        } catch (e: java.net.SocketTimeoutException) {
                            null to "网络超时，请重试"
                        } catch (e: Exception) {
                            null to "网络错误：${e.message ?: e.javaClass.simpleName}"
                        }
                        if (fetched != null) {
                            LocationCache.put(context, countryCode, queryZip, fetched)
                            combined = combined?.copy(geo = fetched, geoError = null)
                        } else {
                            combined = combined?.copy(
                                geoError = netErr ?: "API 无此邮编（可能是 PO Box 或军邮）。时区仍可使用。"
                            )
                        }
                        isLoading = false
                    }
                }
            )

            errorMsg?.let { ErrorCard(it) }

            combined?.let { r ->
                ResultCard(
                    combined = r,
                    isLoading = isLoading,
                    mockRunning = mockRunning,
                    onApplyTz = {
                        val result = SystemTimezoneSetter.setSystemTimezone(context, r.tz.timezoneId)
                        tzApplyResult = result
                        if (result is SystemTimezoneSetter.Result.Success && !result.via.startsWith("ACCESSIBILITY")) {
                            showSuccessDialog = "✓ 时区已设置\n${r.tz.timezoneId}"
                        }
                    },
                    onApplyMock = {
                        r.geo?.let { g ->
                            MockLocationController.startMock(
                                context, g.latitude, g.longitude,
                                "${g.placeName}, ${g.stateAbbrev} ${g.zip}"
                            )
                            val lat = "%.4f".format(g.latitude)
                            val lng = "%.4f".format(g.longitude)
                            showSuccessDialog = "✓ 定位已设置\n${g.placeName}, ${g.stateAbbrev}\n${lat}°, ${lng}°"
                        }
                    },
                    onApplyBoth = {
                        val result = SystemTimezoneSetter.setSystemTimezone(context, r.tz.timezoneId)
                        tzApplyResult = result
                        r.geo?.let { g ->
                            MockLocationController.startMock(
                                context, g.latitude, g.longitude,
                                "${g.placeName}, ${g.stateAbbrev} ${g.zip}"
                            )
                        }
                        if (result is SystemTimezoneSetter.Result.Success && !result.via.startsWith("ACCESSIBILITY")) {
                            val locInfo = r.geo?.let { g -> "\n📍 ${g.placeName}, ${g.stateAbbrev}" } ?: ""
                            showSuccessDialog = "✓ 时区 + 定位 已设置\n🕐 ${r.tz.timezoneId}$locInfo"
                        }
                    },
                    onStopMock = { MockLocationController.stopMock(context) },
                    onOpenDateSettings = { SystemTimezoneSetter.openDateSettings(context) },
                )
                tzApplyResult?.let { TzApplyResultCard(it) { showAuthGuide = true } }
            }

            if (authMode == SystemTimezoneSetter.AuthMode.DEVICE_OWNER) {
                DangerZoneCard()
            }
            Spacer(Modifier.height(24.dp))
            FooterText(context)
        }
    }

    if (showAuthGuide) AuthGuideDialog { showAuthGuide = false }
    if (showMockGuide) MockGuideDialog(context) { showMockGuide = false }

    // 成功弹窗
    showSuccessDialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { showSuccessDialog = null; dialogDismissed = true },
            icon = { Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(48.dp)) },
            title = { Text("操作成功", fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20)) },
            text = { Text(msg, fontSize = 15.sp, lineHeight = 22.sp) },
            confirmButton = {
                TextButton(onClick = { showSuccessDialog = null; dialogDismissed = true }) {
                    Text("好的", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = Color(0xFFE8F5E9),
        )
    }
}

// ====================================================================
// 状态卡片
// ====================================================================

@Composable
fun AuthStatusCard(mode: SystemTimezoneSetter.AuthMode, onClick: () -> Unit) {
    val (bg, fg, title, sub) = when (mode) {
        SystemTimezoneSetter.AuthMode.DEVICE_OWNER -> Quad(
            Color(0xFFE8F5E9), Color(0xFF1B5E20),
            "✓ 时区：设备管理员模式",
            "可直接修改系统时区"
        )
        SystemTimezoneSetter.AuthMode.NORMAL_PERMISSION -> Quad(
            Color(0xFFE3F2FD), Color(0xFF0D47A1),
            "时区：普通权限模式",
            "可尝试修改系统时区"
        )
        SystemTimezoneSetter.AuthMode.PROFILE_OWNER -> Quad(
            Color(0xFFE8F5E9), Color(0xFF1B5E20),
            "✓ 时区：用户管理员模式",
            "可直接修改系统时区"
        )
        SystemTimezoneSetter.AuthMode.ACCESSIBILITY -> Quad(
            Color(0xFFE3F2FD), Color(0xFF0D47A1),
            "时区：无障碍辅助模式",
            "通过自动操作系统设置改时区（3-5 秒）"
        )
        SystemTimezoneSetter.AuthMode.NONE -> Quad(
            Color(0xFFFFF3E0), Color(0xFFE65100),
            "时区：未授权",
            "需要 ADB 配置一次"
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Schedule, null, tint = fg, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = fg, fontSize = 14.sp)
                Text(sub, fontSize = 12.sp, color = fg)
            }
            if (mode != SystemTimezoneSetter.AuthMode.DEVICE_OWNER) {
                TextButton(onClick = onClick) { Text("设置", color = fg) }
            }
        }
    }
}

@Composable
fun MockStatusCard(
    status: MockLocationController.Status,
    running: Boolean,
    onClick: () -> Unit
) {
    val (bg, fg, title, sub) = when {
        running -> Quad(
            Color(0xFFE8F5E9), Color(0xFF1B5E20),
            "📍 定位：正在模拟 (${MockLocationService.currentLabel})",
            "已注入到 GPS / NETWORK / FUSED"
        )
        status == MockLocationController.Status.READY -> Quad(
            Color(0xFFE3F2FD), Color(0xFF0D47A1),
            "定位：已就绪",
            "查询邮编后可一键开启模拟定位"
        )
        status == MockLocationController.Status.NOT_SELECTED -> Quad(
            Color(0xFFFFF3E0), Color(0xFFE65100),
            "定位：需要设置",
            "开发者选项里选本 app 为模拟位置应用"
        )
        else -> Quad(
            Color(0xFFFFF3E0), Color(0xFFE65100),
            "定位：开发者选项未开启",
            "需要先启用开发者选项"
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (running) Icons.Default.MyLocation else Icons.Default.LocationOn,
                null, tint = fg, modifier = Modifier.size(28.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = fg, fontSize = 14.sp)
                Text(sub, fontSize = 12.sp, color = fg)
            }
            if (status != MockLocationController.Status.READY) {
                TextButton(onClick = onClick) { Text("设置", color = fg) }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

// ====================================================================
// 输入 & 结果
// ====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZipInputCard(
    zipInput: String,
    isLoading: Boolean,
    onZipChange: (String) -> Unit,
    onQuery: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("输入邮编", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedTextField(
                value = zipInput,
                onValueChange = onZipChange,
                placeholder = { Text("例：97058 / M5V 3L9") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters
                ),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = onQuery,
                enabled = zipInput.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.White, strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("查询时区 & 定位", modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
fun ErrorCard(msg: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Error, null, tint = Color(0xFFC62828))
            Text(msg, color = Color(0xFFC62828), fontSize = 14.sp)
        }
    }
}

@Composable
fun ResultCard(
    combined: CombinedResult,
    isLoading: Boolean,
    mockRunning: Boolean,
    onApplyTz: () -> Unit,
    onApplyMock: () -> Unit,
    onApplyBoth: () -> Unit,
    onStopMock: () -> Unit,
    onOpenDateSettings: () -> Unit,
) {
    val tz = combined.tz
    val geo = combined.geo
    var nowStr by remember { mutableStateOf("") }
    LaunchedEffect(tz.timezoneId) {
        while (true) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone(tz.timezoneId)
            }
            nowStr = sdf.format(Date())
            kotlinx.coroutines.delay(1000)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Public, null, tint = Color(0xFF1E5EFF))
                Text("查询结果", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (tz.country == ZipTimezoneLookup.Country.US) "🇺🇸 US" else "🇨🇦 CA",
                    fontSize = 13.sp, color = Color.Gray
                )
            }

            InfoRow("邮编", tz.zipInput)
            InfoRow("时区", tz.timezoneId, mono = true)
            InfoRow("偏移", tz.offsetDisplay, mono = true)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Schedule, null, tint = Color(0xFF666666),
                    modifier = Modifier.size(16.dp))
                Text("当地时间 $nowStr", fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace, color = Color(0xFF444444))
            }

            // 地理信息
            when {
                geo != null -> {
                    Divider(Modifier.padding(vertical = 4.dp))
                    InfoRow("城市", "${geo.placeName}, ${geo.stateAbbrev}")
                    InfoRow("州/省", geo.state)
                    InfoRow("坐标",
                        "%.4f°, %.4f°".format(geo.latitude, geo.longitude),
                        mono = true)
                }
                isLoading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp), strokeWidth = 2.dp
                        )
                        Text("正在查询经纬度...", fontSize = 12.sp, color = Color.Gray)
                    }
                }
                combined.geoError != null -> {
                    Text("⚠️ ${combined.geoError}", fontSize = 12.sp, color = Color(0xFFE65100))
                }
            }

            Divider(Modifier.padding(vertical = 4.dp))

            // 操作按钮
            if (geo != null) {
                // 一键两个一起
                Button(
                    onClick = onApplyBoth,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
                ) {
                    Icon(Icons.Default.FlashOn, null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("一键：时区 + 定位",
                        modifier = Modifier.padding(vertical = 4.dp),
                        fontWeight = FontWeight.Bold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApplyTz,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("仅设时区", fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 2.dp)) }

                if (mockRunning) {
                    OutlinedButton(
                        onClick = onStopMock,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFFD32F2F)
                        )
                    ) { Text("停止定位", fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp)) }
                } else {
                    Button(
                        onClick = onApplyMock,
                        enabled = geo != null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) { Text("仅设定位", fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp)) }
                }
            }

            TextButton(
                onClick = onOpenDateSettings,
                modifier = Modifier.fillMaxWidth()
            ) { Text("打开系统日期设置（手动）", fontSize = 12.sp) }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color.Gray, fontSize = 13.sp, modifier = Modifier.width(60.dp))
        Text(
            value, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
fun TzApplyResultCard(result: SystemTimezoneSetter.Result, onShowGuide: () -> Unit) {
    when (result) {
        is SystemTimezoneSetter.Result.Success -> {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                Row(Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2E7D32))
                    Column {
                        Text("✓ 系统时区已更新",
                            fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                        Text("${result.tzId}  (via ${result.via})",
                            fontSize = 11.sp, color = Color(0xFF558B2F),
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        is SystemTimezoneSetter.Result.PermissionDenied -> {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("未授权，无法改时区",
                        fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                    Button(onClick = onShowGuide,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("查看授权方法") }
                }
            }
        }
        is SystemTimezoneSetter.Result.Error -> {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                Row(Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Error, null, tint = Color(0xFFC62828))
                    Text("失败：${result.message}", color = Color(0xFFC62828))
                }
            }
        }
    }
}

// ====================================================================
// 指南弹窗
// ====================================================================

@Composable
fun AuthGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("时区授权（ADB 一次性）", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("方案一：Device Owner（推荐）",
                    fontWeight = FontWeight.Bold, color = Color(0xFF1E5EFF))
                StepText("1", "清除所有账户", "设置 → 账户 → 移除全部")
                StepText("2", "开启 USB 调试", "开发者选项 → USB 调试")
                StepText("3", "电脑 ADB 执行命令", "")
                CopyableCommand(SystemTimezoneSetter.deviceOwnerCommand, context)

                Spacer(Modifier.height(8.dp))
                Text("方案二：普通权限（部分 ROM）",
                    fontWeight = FontWeight.Bold, color = Color(0xFF546E7A))
                CopyableCommand(SystemTimezoneSetter.grantPermissionCommand, context)

                Spacer(Modifier.height(8.dp))
                Text("方案三：无障碍服务（不能 Root 也不能设 DO 时用）",
                    fontWeight = FontWeight.Bold, color = Color(0xFF00897B))
                Text("适用于一加 ColorOS / 小米 MIUI 等限制严的国产 ROM。" +
                    "授权后 app 会自动跳转系统设置，模拟点击改时区，约 3-5 秒完成。",
                    fontSize = 12.sp, color = Color(0xFF555555))
                Button(
                    onClick = {
                        TimezoneAccessibilityService.openAccessibilitySettings(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00897B))
                ) { Text("打开无障碍设置 → 启用「时区助手」") }
                Text("路径：系统设置 → 无障碍 → 已下载的服务 → 时区助手 → 打开",
                    fontSize = 11.sp, color = Color.Gray)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("我知道了") } }
    )
}

@Composable
fun MockGuideDialog(context: Context, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模拟定位设置", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("模拟定位需要把本 app 设为「模拟位置应用」：",
                    fontSize = 13.sp, color = Color(0xFF555555))
                StepText("1", "开启开发者选项",
                    "设置 → 关于手机 → 连点版本号 7 次")
                StepText("2", "选择模拟位置应用",
                    "开发者选项 → 找到「选择模拟位置应用」 → 选择「时区助手」")
                StepText("3", "返回本 app", "定位状态会变成「已就绪」")

                Spacer(Modifier.height(8.dp))
                Text("或用 ADB 命令（已 Device Owner 的设备）：",
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                CopyableCommand(MockLocationController.adbSetMockCommand, context)

                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { MockLocationController.openDevSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("打开开发者选项") }

                Spacer(Modifier.height(6.dp))
                Text("💡 成功率说明",
                    fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF1E5EFF))
                Text("· 普通 app 基本都能骗过\n" +
                    "· 检测「开发者选项开关」的 app 会识破（如某些政务、打车、反作弊 app）\n" +
                    "· 成功率和 Lexa Fake GPS、狐影等同类 app 相同",
                    fontSize = 12.sp, color = Color.Gray)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("好的") } }
    )
}

@Composable
fun CopyableCommand(cmd: String, context: Context) {
    Surface(
        color = Color(0xFF0D1117),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(cmd, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                color = Color(0xFF9ECE6A), modifier = Modifier.weight(1f))
            IconButton(onClick = { copyToClipboard(context, cmd) }) {
                Icon(Icons.Default.ContentCopy, "复制",
                    tint = Color(0xFFB0BEC5), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun StepText(num: String, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = Color(0xFF1E5EFF), shape = CircleShape,
            modifier = Modifier.size(22.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(num, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (detail.isNotEmpty()) {
                Text(detail, fontSize = 11.sp, color = Color(0xFF555555))
            }
        }
    }
}

@Composable
fun FooterText(context: Context) {
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()) {
        Text("📍 ${SystemTimezoneSetter.deviceInfo()}",
            fontSize = 11.sp, color = Color.Gray)
        Text("v$versionName · 时区离线 · 定位离线 (${OfflineGeoLookup.totalEntries(context)} 条)",
            fontSize = 11.sp, color = Color.Gray)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("adb command", text))
    android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
fun DangerZoneCard() {
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf<String?>(null) }

    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚠️ 危险操作",
                fontWeight = FontWeight.Bold, color = Color(0xFF5D4037), fontSize = 13.sp)
            Text("下面的按钮用于升级 app 前解除 Device Owner 身份。\n" +
                "解除后可用新签名的 APK 覆盖安装，但需要重新 ADB 配置才能恢复自动能力。",
                fontSize = 11.sp, color = Color(0xFF795548))
            OutlinedButton(
                onClick = { showConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD84315))
            ) {
                Text("解除 Device Owner（升级前用）", fontSize = 12.sp)
            }
            showResult?.let { msg ->
                Text(msg, fontSize = 11.sp, color = Color(0xFF4E342E))
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("确认解除 Device Owner？") },
            text = {
                Text("解除后：\n" +
                    "· 本 app 将失去自动改时区 / 自动配置定位的权限\n" +
                    "· 但可以用 adb uninstall 正常卸载\n" +
                    "· 以及用新签名的 APK 覆盖安装\n\n" +
                    "通常只在升级到新版本时才需要这样做。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = SystemTimezoneSetter.clearDeviceOwner(context)
                    showResult = when (r) {
                        is SystemTimezoneSetter.Result.Success ->
                            "✓ 已解除。现在可以 adb uninstall 本 app 了。"
                        is SystemTimezoneSetter.Result.Error -> "✗ 失败：${r.message}"
                        is SystemTimezoneSetter.Result.PermissionDenied -> "✗ 无权限"
                    }
                    showConfirm = false
                }) { Text("确认解除", color = Color(0xFFD32F2F)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            }
        )
    }
}
