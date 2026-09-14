# AGENTS.md — MockRun（校园定位模拟，个人使用/学习）

Android 模拟定位 App，包名 `com.learning.mockrun`，Kotlin。合规边界：自用、学习、研究；不上架不分发（Google Play 与国内商店均禁止以模拟定位为主要用途的应用）。

## 构建环境（本机事实，2026-09-14 核实）

- 机器上**没有**独立 JDK/Gradle。唯一 JDK 是 Android Studio 自带 JBR 25：
  `C:\Program Files\Android\Android Studio\jbr`
  已 `setx JAVA_HOME` 持久化，`gradle.properties` 里 `org.gradle.java.home` 也指向它。两处都有意保留。
- SDK：`C:\Users\micha\AppData\Local\Android\Sdk`，只有 `android-37.0` 平台 + build-tools `36.0.0`，license 已接受。
- **本机直连 dl.google.com 不通**。`settings.gradle.kts` 阿里云镜像优先（google/gradle-plugin/public），官方仓库作回退；wrapper 的 `distributionUrl` 指向腾讯镜像。**别删别"修复"。**

## 版本（全部实地核实过，改前先查镜像元数据，禁止凭记忆猜）

| 项 | 值 | 备注 |
|---|---|---|
| AGP | 9.4.0 | **内置 Kotlin**，禁止再 apply `org.jetbrains.kotlin.android`（会直接报错） |
| Gradle | 9.6.1 | AGP 9.4 官方最低要求 9.6.0 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 | targetSdk 36 是刻意保守（FGS 行为更稳）；36 起 edge-to-edge 默认开启，Phase 3 做 UI 注意 |
| buildToolsVersion | **钉死 36.0.0** | 与已安装版本对齐，避免构建期去被墙的 Google 源下载。看着和 compileSdk 37 不一致，**别去"修正"** |
| androidx | core-ktx 1.19.0 / appcompat 1.8.0 / material 1.14.0 | 阿里云元数据 `<release>` 确认的最新稳定版 |
| osmdroid | 6.1.20 | 仅在 toml 里登记，Phase 2 启用；项目已归档，6.1.20 是最终版 |

## 常用命令

```bash
./gradlew assembleDebug        # 出包 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug       # lint 门禁,保持零 error
```

## lint 已有的刻意取舍

`app/build.gradle.kts` 的 lint 块 disable 了 `ProtectedPermissions` 和 `MockLocation`（模拟定位应用的声明属于正常用法，原因有注释）。剩余警告 `OldTargetApi`、`MissingApplicationIcon`（图标随 Phase 2 做）是有意留着不阻塞的。

## 验收流程（每次改完注入逻辑都要跑）

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. `adb shell appops set com.learning.mockrun android:mock_location allow`
   （或开发者选项 → 选择模拟位置信息应用 → 选中本应用）
3. 打开 App 首页应显示「模拟位置授权：已授权」

## Phase 1 待办（注入逻辑）——已知坑，写实现前先读

1. `addTestProvider` 对**已存在**的 provider 抛 `IllegalArgumentException`：先 `removeTestProvider`（或 try/catch 包住）再 add。
2. **至少同时注入 `GPS_PROVIDER` 和 `NETWORK_PROVIDER`**：消费方（如微信）可能走网络定位或自己的融合逻辑，只注入 GPS 会出现"App 在跑、对方 App 位置不动"。
3. `Location` 字段必须补齐：`accuracy` / `time` / `speed` / `bearing`，且 `elapsedRealtimeNanos` 用 `SystemClock.elapsedRealtimeNanos()` 严格单调递增——只设经纬度或用 `currentTimeMillis` 会被系统当无效点直接丢弃，表现为"注入没反应"。
4. 顺序固定：`addTestProvider` → `setTestProviderEnabled(true)` → `requestLocationUpdates` → 才开始喂点；只 set 不 request 不一定触发回调。
5. 前台服务：API 29+ 用三参 `startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)`；API 33+ 先运行时拿到 `POST_NOTIFICATIONS`，否则常驻通知不显示。
6. Android 12+ 禁止后台启动前台服务：只能由 MainActivity 的按钮（用户可见交互）触发，**不要**放 `Application.onCreate` 或 `BOOT_COMPLETED`。
7. 微信不读 `isFromMockProvider()`/`isMock()`，App 自己不需要判断 mock 标记。

## Phase 2 待办（地图）

- osmdroid 必须先设 `Configuration.getInstance().userAgentValue = packageName`，否则 OSM 瓦片服务器 403。
- 国内访问 OSM 官方源慢：换镜像源或用离线 MBTiles。
- 瓦片缓存路径放 app 私有目录（API 29+ 分区存储）。

## 其他已知取舍（有意识即可，别动）

- `setx JAVA_HOME` 指向 JBR 25 是机器级永久改动：好处是裸终端能跑 gradlew；代价是其他依赖 JAVA_HOME 的老 Java 工具会拿到 JDK 25。
- 权限申请顺序（Phase 1+）：FINE+COARSE 必须一起申请（API 31+ 只申 FINE 会被拒）；`ACCESS_BACKGROUND_LOCATION` API 30+ 必须运行时**单独**申请且要先有前台定位权限。
- 部分 ROM（小米等）会提示"检测到模拟位置"，属正常现象，不影响注入。
