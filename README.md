# 时区助手 Lollipop 版 (v3.7.28-l)

**针对 Android 5.0+ 设备**(SM-N9008 / Galaxy Note 3 国行版等)。

基于 v3.7.28 主版本,minSdk 从 24 降到 21,适配老设备。

工作原理:
- Android 5.0 上 `AlarmManager.setTimeZone()` 直接可用,**不需要 Device Owner / 无障碍**
- 路径优先级:DO API → AlarmManager → 反射 → Binder → 无障碍兜底
- 在 Android 5.0 上,**几乎肯定第二条路径(AlarmManager)就成功**

如果在 Android 5.0 上路径 2 失败,会回退到无障碍 UI 自动化,跟 G5510 (Android 8.1) 行为类似。

主版本(minSdk=24,适配 Android 7.0+ 的现代设备)在: timezone-setter


---

# 时区助手 (TimezoneSetter)

输入美国/加拿大邮编，自动把系统时区设置成对应的时区。**完全离线**，数据内置。

---

## 工作方式

```
输入邮编 (97058)
   ↓
离线查表: ZIP3 → 时区 ID  (美国 944 条)
         ZIP5 → 时区 ID  (跨时区州精确覆盖 3100 条)
         FSA  → 时区 ID  (加拿大 3600 条)
   ↓
调用 AlarmManager.setTimeZone("America/Los_Angeles")
   ↓
系统时区改完
```

---

## 构建步骤

1. 用 **Android Studio Iguana (2023.2.1)** 或更新版本打开本项目根目录
2. 首次打开时 Studio 会自动：
   - 下载 `gradle-wrapper.jar`（因为我没法把 jar 打进 zip）
   - 下载 Gradle 8.9
   - 下载依赖（AGP 8.5 / Kotlin 1.9.24 / Compose BOM 2024.08）
3. 等同步完成，点 Run 即可安装到设备

**最低 Android 版本：** Android 7.0 (API 24)  
**目标版本：** Android 14 (API 34)

---

## 首次授权 (一次性，永久有效)

app 装好之后只需做一次：

### 方式 A：用电脑 ADB

```bash
adb shell pm grant com.ycr.tzsetter android.permission.SET_TIME_ZONE
```

### 方式 B：纯手机无线调试 (Android 11+)

1. 手机 → 开发者选项 → 开启「无线调试」
2. 手机上装 **LADB** (Play Store) 或 **Shizuku** → 连接本机 ADB
3. 在其中执行同样的命令

授权完成后，app 顶部状态会变绿「已授权自动模式」。之后输入邮编点「自动设置为系统时区」即可，**每次都秒改，不再需要任何手动操作**。

> ⚠️ **卸载 app 会丢失授权**，重装后需再运行一次上述命令。系统升级、重启不会丢失。

---

## 数据来源与精度

- **美国 ZIP3 映射**：基于 USPS 官方 ZIP 前缀分配表，944 条，覆盖全部已分配 ZIP3
- **美国 ZIP5 精确覆盖**：手工整理跨时区州的例外邮编，共 3102 条
  - 佛罗里达 Panhandle（32401–32469, 32501–32599）
  - 印第安纳西北（46301–46499）和西南 Evansville 区
  - 肯塔基西部（42000–42799）
  - 田纳西西部/东部分区
  - 密歇根 UP 四县
  - 北/南达科他西部
  - 内布拉斯加 Panhandle
  - 堪萨斯西端 4 县、德州 El Paso/Hudspeth、爱达荷北部、俄勒冈 Malheur
  - 阿留申群岛（99546–99591）
  - 亚利桑那 Navajo Nation
- **加拿大 FSA 映射**：3600 条，覆盖所有省份；含 Kenora/Thunder Bay 分区、Nunavut 东/中/西三时区、萨省不 DST 特殊规则
- **时区 ID**：IANA tzdata 标准（`America/Los_Angeles` 等），自动处理 DST

数据生成脚本：`build_data.py`（仓库根目录）。如需更新，修改脚本后重新运行即可。

---

## 项目结构

```
TimezoneSetter/
├── build.gradle.kts                        # 根构建文件
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── build_data.py                           # 数据生成脚本
├── README.md
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/                         # 邮编数据（162KB）
        │   ├── us_zip3.csv
        │   ├── us_overrides.csv
        │   └── ca_fsa.csv
        ├── java/com/ycr/tzsetter/
        │   ├── MainActivity.kt             # Compose UI
        │   ├── ZipTimezoneLookup.kt        # 查询核心
        │   └── SystemTimezoneSetter.kt     # 改系统时区
        └── res/
            ├── drawable/                   # 图标 vectors
            ├── mipmap-*/                   # 各密度启动图标
            └── values/
                ├── strings.xml
                └── themes.xml
```

---

## 常见问题

**Q: 为什么不能做到装上就自动改？**  
A: `SET_TIME_ZONE` 是 Android 系统的 `signature|privileged` 权限，Google 从 4.2 起就禁止普通 app 直接获得。ADB 授权是合法的「一次性破冰」，之后就完全自动了。

**Q: 某些 ROM 上 ADB 授权后仍然失败？**  
A: 部分国行 ROM（EMUI/MIUI 某些版本）对该权限有额外限制。此时 app 会提示错误并自动降级——你可以用「打开系统设置」按钮手动调整，或尝试 Shizuku 方案。

**Q: 数据需要多久更新一次？**  
A: 美国时区规则自 2007 年稳定至今，加拿大萨省/育空近年变动过（都是「不再使用 DST」），本数据已包含。IANA tzdata 的修正由系统侧更新，app 无需改动。

**Q: 想新增其他国家？**  
A: 在 `build_data.py` 里按 FSA 模式加一张表，再在 `ZipTimezoneLookup.kt` 的 `lookup()` 里加一个分支即可。

---

## 许可

- 代码：自用，无限制
- 数据：基于公开的 USPS ZIP 分配和 IANA tzdata，均为公共领域信息
