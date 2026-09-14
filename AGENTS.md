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

## Phase 2 地图选点(进行中)

- **引擎可切换**:MapEngine 枚举(AMAP/BAIDU)持久化在 SharedPreferences;主界面 RadioGroup 切换;
  「地图选点」按钮按引擎分发;选点页统一通过 PickerContract 返回 **WGS-84** 坐标(引擎各自页内完成转换)。
- 高德已接入:`com.amap.api:3dmap:10.0.600`(maven central)。选点页 AMapPickerActivity:
  隐私合规调用(updatePrivacyShow/Agree)必须在 super.onCreate 之前;数据是 GCJ-02,标记放 GCJ-02、返回前转 WGS-84。
- 百度待接入:等用户下载「基础地图」aar → 放 app/libs → BaiduPickerActivity;数据是 BD-09,同样转 WGS-84。
  未接入前选百度引擎点「地图选点」会 toast 提示,不崩。
- **key 管理**:高德 key 放 local.properties 的 `amap_key=`(gitignore 内),经 manifestPlaceholder 注入;
  key 留空时地图空白网格但点击回调照常工作。百度 key 同理 `baidu_ak=`。
- 版本目录条目不能用会让 Kotlin 访问器以数字开头的名字(如 amap-3dmap → libs.amap.3dmap 编译错),
  已命名 amap-map。
- ABI 只保留 arm64-v8a(目标机 K30 Pro);以后要上模拟器记得加 x86_64。
- Android 12 上 shell 不能 `am start` 未导出的 Activity(SecurityException),测试选点页只能用户手点。

## Phase 3 轨迹回放(已完成核心)

- **RoutePlayer**: 沿折线匀速推进 + OU 高斯随机游走漂移(参考公开 GPS 噪声建模,推模型重写)。
  闭合环线取模循环,开线往返;步频横向抖动/Accuracy/Altitude/速度各自独立漂移。
  喂点契约:服务每秒调 at(SystemClock.elapsedRealtimeNanos()),测试注入固定种子。
- **PresetRoutes**: 锚点+周长生成环线(标准操场 400m 双直道双半圆 / 1k / 2k / 3.5k 圆环),锚点=地图长按。
- **坐标系边界规则(关键)**: 内部状态(锚点/路线/注入)一律 WGS-84;高德显示是 GCJ-02,
  进出地图各转换一次(wgs84ToGcj02 显示 / gcj02ToWgs84 输入)。别混。
- **比格主题**: 地图标记 = ic_beagle(俯视小比格,flat marker 按 bearing 转身),
  服务小图标 = ic_paw;控制卡 = 全屏地图底部半透明圆角卡。UI 继续往这个方向做。
- **百度 SDK 在 maven**:`com.baidu.lbsyun:BaiduMapSDK_Map` 8.2.0.2(阿里云)/8.2.0(central),
  不需要手动 aar。下一步:主界面双引擎地图视图切换(TextureMapView ↔ BaiduMap 的 MapView)。
- 高德 TextureMapView(不是 MapView,性能更好);地图显示无需 key 也能跑(空白网格,点击/长按回调正常)。

## Phase 3 待办(UI/轨迹)

- osmdroid 必须先设 `Configuration.getInstance().userAgentValue = packageName`，否则 OSM 瓦片服务器 403。
- 国内访问 OSM 官方源慢：换镜像源或用离线 MBTiles。
- 瓦片缓存路径放 app 私有目录（API 29+ 分区存储）。
- （用户已定方案:高德+百度双引擎切换,osmdroid 暂时不用;坐标转换层 CoordinateConverter 已带单测）

## 其他已知取舍（有意识即可，别动）

- `setx JAVA_HOME` 指向 JBR 25 是机器级永久改动：好处是裸终端能跑 gradlew；代价是其他依赖 JAVA_HOME 的老 Java 工具会拿到 JDK 25。
- 权限申请顺序（Phase 1+）：FINE+COARSE 必须一起申请（API 31+ 只申 FINE 会被拒）；`ACCESS_BACKGROUND_LOCATION` API 30+ 必须运行时**单独**申请且要先有前台定位权限。
- 部分 ROM（小米等）会提示"检测到模拟位置"，属正常现象，不影响注入。
