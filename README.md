# 跳广告助手 (SkipAdApp)

一个自动跳过应用开屏广告的Android应用，使用无障碍服务实现自动检测和点击跳过按钮。

## 功能特点

- **自动跳过广告**：自动检测应用开屏广告的跳过按钮并模拟点击
- **后台保活**：使用多种机制确保服务不被系统杀死
- **禁止联网**：不添加INTERNET权限，保护用户隐私
- **开机自启**：支持开机自动启动服务
- **统计功能**：记录已跳过的广告次数

## 技术实现

### 1. 无障碍服务 (AccessibilityService)

核心功能通过 `SkipAdAccessibilityService` 实现：
- 监听窗口状态变化和内容变化事件
- 通过多种方式查找跳过按钮：
  - 文本关键词匹配（跳过、关闭、skip、close等）
  - ID关键词匹配
  - 内容描述匹配
  - 位置检测（右上角/右下角的小按钮）
- 支持直接点击和手势点击两种方式

### 2. 后台保活机制

通过 `KeepAliveService` 实现多重保活：
- **前台服务**：显示常驻通知，提高进程优先级
- **WakeLock**：防止CPU休眠
- **心跳机制**：定期检测服务状态
- **守护广播**：监听屏幕亮起/关闭、用户解锁等事件
- **开机自启**：通过 `BootReceiver` 实现开机启动

### 3. 禁止联网

- AndroidManifest.xml 中不添加 `INTERNET` 权限
- 从系统层面禁止应用联网

## 项目结构

```
SkipAdApp/
├── app/
│   ├── src/main/
│   │   ├── java/com/skipad/
│   │   │   ├── MainActivity.java          # 主界面
│   │   │   ├── service/
│   │   │   │   ├── SkipAdAccessibilityService.java  # 无障碍服务
│   │   │   │   └── KeepAliveService.java  # 保活服务
│   │   │   ├── receiver/
│   │   │   │   ├── BootReceiver.java      # 开机广播接收器
│   │   │   │   └── DaemonReceiver.java    # 守护广播接收器
│   │   │   └── util/
│   │   │       └── SkipAdPreferences.java # 偏好设置工具
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml      # 主界面布局
│   │   │   ├── values/
│   │   │   │   ├── strings.xml
│   │   │   │   ├── colors.xml
│   │   │   │   └── styles.xml
│   │   │   └── xml/
│   │   │       └── accessibility_service_config.xml
│   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── proguard-rules.pro
├── build.gradle
├── settings.gradle
└── README.md
```

## 编译方法

### 使用 Android Studio

1. 打开 Android Studio
2. 选择 "Open an existing project"
3. 选择 `SkipAdApp` 目录
4. 等待 Gradle 同步完成
5. 点击 Build -> Build APK

### 使用命令行

```bash
# Windows
cd SkipAdApp
gradlew assembleDebug

# Linux/Mac
cd SkipAdApp
./gradlew assembleDebug
```

生成的APK位于：`app/build/outputs/apk/debug/app-debug.apk`

## 使用说明

1. **安装应用**：将APK安装到手机

2. **开启无障碍服务**：
   - 打开应用，点击"打开无障碍服务"
   - 在系统设置中找到"跳广告助手"
   - 开启无障碍服务权限

3. **启动服务**：
   - 返回应用，点击"启动服务"
   - 服务将在后台运行

4. **电池优化设置**（推荐）：
   - 点击"电池优化"按钮
   - 将应用加入电池优化白名单
   - 防止被系统杀死

5. **开机自启**：
   - 在设置中开启"开机自启动"
   - 重启手机后服务会自动启动

## 支持的应用

默认支持所有应用，已针对以下常见应用优化：
- 微信、QQ
- 淘宝、天猫、京东
- 微博、今日头条
- 抖音、快手
- 网易云音乐、腾讯视频、优酷
- 知乎、豆瓣
- 拼多多、支付宝
- 百度、爱奇艺
- 各类银行App

## 权限说明

| 权限 | 用途 |
|------|------|
| SYSTEM_ALERT_WINDOW | 悬浮窗权限，用于保活 |
| FOREGROUND_SERVICE | 前台服务权限 |
| RECEIVE_BOOT_COMPLETED | 开机自启动 |
| WAKE_LOCK | 防止CPU休眠 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 请求忽略电池优化 |

**注意**：本应用不申请 `INTERNET` 权限，无法联网。

## 注意事项

1. 本应用需要无障碍服务权限才能正常工作
2. 部分手机可能需要手动关闭电池优化
3. 某些定制ROM可能需要额外设置（如自启动权限）
4. 本应用仅供学习交流使用

## 开发环境

- Android SDK 34
- Gradle 8.2
- Android Gradle Plugin 8.2.0
- Java 8+

## 许可证

MIT License
