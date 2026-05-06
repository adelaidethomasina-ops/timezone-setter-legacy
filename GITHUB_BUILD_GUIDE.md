# GitHub 在线编译 APK - 新手完整指南

**零技术基础，全程点鼠标，5 步拿到 APK**

---

## 第 1 步：新建仓库

注册好 GitHub 后，登录状态下打开：

👉 **https://github.com/new**

填写：
- **Repository name**（仓库名）：随便起，比如 `timezone-setter`
- **Public / Private**：选 **Public**（公开，免费额度不限）
- 其他**不要勾选**（不要勾 README、不要勾 .gitignore、不要勾 license，保持仓库完全是空的）
- 点最下面的绿色 **"Create repository"** 按钮

---

## 第 2 步：上传项目文件

创建后会跳到一个空仓库页面，你会看到一行提示：

> Get started by creating a new file or **uploading an existing file**

点那个 **"uploading an existing file"** 蓝色链接。

或者直接访问：
```
https://github.com/你的用户名/timezone-setter/upload/main
```

### 上传操作

1. **先解压** 你下载的 `TimezoneSetter.zip`
2. 解压后得到 `TimezoneSetter` 文件夹，**进入这个文件夹**
3. 把文件夹里**所有东西**（包括 `app/`、`gradle/`、`.github/`、`build.gradle.kts`、`README.md` 等等）全部选中
4. **拖拽到 GitHub 页面的上传区**

⚠️ **重要：要上传的是 `TimezoneSetter` 文件夹**里**所有内容**，不是这个文件夹本身。仓库根目录里应该直接看到 `app/`、`build.gradle.kts` 这些文件，而不是套一层 `TimezoneSetter/`。

上传过程中看到列表里包括：
- ✅ `.github/workflows/build.yml`  ← 这个最关键
- ✅ `app/` 目录
- ✅ `build.gradle.kts`
- ✅ `settings.gradle.kts`
- ✅ `gradle.properties`
- ✅ `gradle/wrapper/gradle-wrapper.properties`

### 提交

在页面下方的 **"Commit changes"** 区：
- 第一行填：`初始上传` （或留默认的也行）
- 点绿色 **"Commit changes"** 按钮

---

## 第 3 步：等待自动编译

提交后，点顶部的 **"Actions"** 标签。你会看到一条正在运行的工作流：**"Build APK"**，旁边有个黄色小圆点（表示正在跑）。

点进去看细节，**大约 5-8 分钟**会完成，变成绿色对钩 ✅。

如果**变红色叉 ❌**，说明编译失败——点进去看日志，把错误截图发给我，我帮你看。

---

## 第 4 步：下载 APK

编译成功后，进入那次运行的详情页面，**滚到最底下**，有个 **"Artifacts"** 区域：

📦 **TimezoneSetter-debug-apk**

点它下载，得到一个 zip 文件。**解压** zip，里面有 `app-debug.apk`。

---

## 第 5 步：装到手机

1. 把 APK 传到手机（微信/邮件/数据线都行）
2. 手机上点击 APK 安装
3. 首次安装可能提示"未知来源应用"，去设置里允许一下
4. 装好后**在电脑上**执行 ADB 授权命令：
   ```
   adb shell pm grant com.ycr.tzsetter android.permission.SET_TIME_ZONE
   ```
5. 打开 app，输邮编，点按钮，搞定 🎉

---

## 常见问题

**Q: 上传文件时看不到 `.github` 文件夹？**  
A: Windows 和 Mac 默认隐藏以 `.` 开头的文件。
- Windows：文件资源管理器 → 查看 → 勾选"隐藏的项目"
- Mac：按 `Cmd + Shift + .` 显示隐藏文件

**Q: 点了 Commit 之后 Actions 里什么都没有？**  
A: 刷新一下页面。如果还是没有，说明 `.github/workflows/build.yml` 没上传成功，检查仓库根目录有没有 `.github` 文件夹。

**Q: 编译失败 ❌ 怎么办？**  
A: 点进失败的 Build APK → 展开"编译 Debug APK"那一步看错误信息 → 截图发给我。

**Q: 以后改代码要怎么重新编译？**  
A: 在仓库网页上直接编辑文件 → Commit → Actions 会自动重新跑。或者重新"uploading an existing file"覆盖上传。

---

准备好了告诉我，有任何一步卡住截图过来。
