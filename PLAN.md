# MockRun 总计划（主脑-工人模式）v3

> 分工：ZCode（主脑）拆任务、派活、验收；DeepSeek（WorkBuddy 内置 CLI）写代码。
> 每完成一个任务打勾并记录验收结论；git commit 一律先问用户。

**版本记录**：
- v2 = DeepSeek 复查（2026-09-14，session `bc754c07`，15 条意见）吸收版。
- v3 = 用户指示修订（2026-09-14）：**核心先行，细枝末节等真机用户反馈**——
  保活类 fallback（T1c 心跳自检、长跑验收、电池引导）移入暂缓清单；
  「文件日志 + 导出发回」升入核心（用户拿日志回来诊断，ROM 通用适配的抓手）；
  适配口径改为**原生 Android 通用行为**，不做厂商特定 hack。
- 已达标无需任务的复查项：manifest 已声明 `FOREGROUND_SERVICE_LOCATION` +
  `foregroundServiceType="location"`（Android 14+ 要求）。

## 当前基线（2026-09-14，R0.0.1 时点更新）

- 已提交：Phase 0～2b + Phase 3a（预置路线/回放引擎/全屏地图）+ 自定义路线毛坯
  （077c63c 及此前）。
- **待 commit（门禁全绿，等用户点头）**：Release 0.0.1 路线收集版
  ——「分享给作者」(FileProvider+系统分享面板，分享对象=路线文件)、
  versionName 0.0.1、release 复用 debug 签名、RouteStore 原子写、
  docs/RELEASE-0.0.1-上手.md。真机已装 release 包验证（零崩溃）。
- T5 毛坯已随 077c63c 落地（打点/列表/存取/容错降级），v2 验收项中
  「原子写」已在 R0.0.1 补齐；剩余「命名/重命名 UI」仍属 T5 正式任务。
- 日志现状：只有 android.util.Log，**无文件日志**；分享闭环只挂了路线文件，
  尚未挂日志。
- T8 前置条件已变：百度 SDK 在 maven（com.baidu.lbsyun:BaiduMapSDK_Map 8.2.0.2），
  **不需要手动 aar**，只差 `baidu_ak=`（local.properties）。
- 三道门禁：`testDebugUnitTest` / `assembleDebug` / `:app:lintDebug`。
  Bash 子进程需先 `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`，
  否则 gradlew 起不来（setx 不影响已存在的 shell）。

## 全局硬约束（每个任务提示词都带上，违反即打回）

1. 内部坐标一律 WGS-84；进高德地图前才转 GCJ-02。
2. 注入链路语义（remove→add→setEnabled→喂点、单调 elapsedRealtimeNanos、
   Location 字段补齐）不许动；**暂停/调速的时间语义必须落在 RoutePlayer，
   服务只做命令转发**，不许在注入侧"暂停"（会破坏单调时间戳契约）。
3. AGP 9.4 内置 Kotlin，禁止 apply kotlin 插件；buildTools 钉死 36.0.0；
   阿里云镜像优先——不许"升级/修正"任何版本或仓库配置。
4. ABI 只留 arm64-v8a；targetSdk 36，edge-to-edge 默认开启。
5. 新逻辑尽量纯 Kotlin（JVM 可单测），Android 依赖留在外壳。
6. 工人只改代码不 commit；每任务交付时自己跑通三道门禁。
7. 只用标准 Android API，适配目标=各 ROM 通用的原生 Android 行为，
   不做厂商特定 hack（MIUI 专属逻辑等用户反馈再说，见暂缓清单）。

## 任务列表

### T0 R0.0.1 收尾提交（等用户点头）
工作区里的 Release 0.0.1 改动整理提交。
验收：工作区 clean、门禁绿、commit message 符合既有风格。

### T1a ✅ 回放引擎：暂停/继续/调速（纯 Kotlin）
RoutePlayer 增加受控语义：pause（位置冻结，漂移可继续，**上报速度归零**）、
resume（时间线平移，**位置不许跳变**）、setSpeed（只影响后续推进速率）。
实现：里程累加器（accumulatedM + anchorNanos + currentSpeedMps），三个控制
方法只移动基准，已走里程永不重算。DeepSeek 完成（session `10c585fa`，
共 3 轮：初版 + 打回"暂停时速度须归零"×2，第 2 轮它谎报已修被门禁实测抓住）。
验收：新增 6 个单测（确定性断言/时间平移/边界序列），主脑跑三道门禁全绿
（2026-09-14）。T1b 接入时注意 API：`resume(nanos)` / `setSpeed(speed, nanos)`
需传 `SystemClock.elapsedRealtimeNanos()`。

### T1b 控制通道 + UI + 通知栏停止
- 服务加命令入口（startService action 或静态方法）：pause/resume/setSpeed/stop；
  UI 控制卡加暂停/继续（运行中调速直接接现有 SeekBar）。
- 通知栏带"停止"action（PendingIntent → 服务 stop 自身 + 清 provider）。
- 进程被杀语义：维持 START_NOT_STICKY（被杀=停止态，不自动复活、不兜底）。
验收：UI 全流程可操作；通知栏停止可用；门禁绿。
（锁屏长跑/被杀自愈等保活项见暂缓清单，等用户反馈。）

### T2 首页授权状态卡
「模拟位置授权：已授权/未授权」+ 通知权限状态（AppOps 为权威口径）。
未授权态：文案引导"开发者选项 → 选择模拟位置信息应用 → 本应用"（标准
Android 只能引导），点击跳开发者选项；授权态点击无动作。
验收：状态判定抽纯函数可单测（AppOps 结果→UI 态映射）；两种状态真机可走通；
门禁绿。（电池优化白名单引导移暂缓清单。）

### T3 edge-to-edge 适配（先于 T6，为输入框预留 ime）
底部控制卡避让 systemBars + ime insets（T6 命名输入框需要）；地图延伸到
状态栏后。不用 deprecated API（fitsSystemWindows/setSystemUiVisibility）。
验收：insets 应用逻辑可断言（padding ≥ 对应 inset）；真机手势/三键两态
看一眼（用户执行）；门禁绿。

### T4 启动图标（零风险，随时可插）
ic_beagle 做自适应图标（前景/背景层，mipmap-anydpi-v26 + 兼容回退），
manifest 补 `android:icon` + `android:roundIcon`。
验收：lint 不再报 MissingApplicationIcon；门禁绿。

### T5 自定义路线收尾（毛坯已落地）
剩余：命名/重命名 UI、列表管理完善（选用/删除）。
已有（不重做）：打点绘制、JSON 存取（原子写已补）、容错降级、路线文件分享。
验收：命名/重命名/删除全流程真机可用；存取层单测保持绿；坐标系边界
不破坏；门禁绿。

### T6 GPX 导入
解析 `<trkpt>`/`<rtept>` → GeoPoint 列表，进自定义路线流程
（注意：R0.0.1 分享的是路线 JSON，GPX 仍属新活）。
- **决策（写死）**：忽略 `<ele>` 与时间戳，海拔一律由回放引擎漂移模型生成。
- 容错：带/无命名空间、带前缀、空点、损坏输入都不崩。
验收：单测含 Garmin/Strava 风格样例（自造）+ 非法输入；导入→预览→回放
全流程；门禁绿。

### T7 百度引擎接入（BLOCKED：只差 `baidu_ak=`）
SDK 走 maven（com.baidu.lbsyun:BaiduMapSDK_Map 8.2.0.2），无需 aar。
剩余前置：`local.properties` 配 `baidu_ak=`；`SDKInitializer` 隐私合规调用
在用户同意后；BD-09 页内转 WGS-84；与高德对齐的坐标系回归测试。
验收：切百度引擎后 选点/显示/回放 与高德对等；门禁绿。

### T8 收尾
README（功能/构建/真机验收步骤）；验收流程文档更新到当前 UI；
lint 维持零 error。
验收：照 README 从零能跑；门禁绿。

### T9 文件日志 + 导出发回（核心，用户诊断抓手）
- 轻量文件日志：app 私有目录滚动文件（单文件 ≤1MB×3），记录注入生命周期
  关键事件——服务启停、provider 安装/清理结果、喂点异常、授权检查结果、
  pause/resume/调速命令；日志头部带 设备型号/Android 版本/ROM 指纹/
  app 版本（不同机型反馈回来才好对症）。
- 复用 R0.0.1 分享闭环（FileProvider + ACTION_SEND）：主界面加「导出日志」，
  与"分享给作者"同一管道。
- 滚动裁剪/格式化纯 Kotlin + 单测。
验收：单测覆盖格式化与滚动；真机 导出→分享→发回 链路用户可走通；门禁绿。

## 执行顺序与依赖

T0 → T1a → T1b → T9 → T2 → T3 → T4 → T5 → T6 → T8；T7 独立，ak 到位随时插入。
T1b 依赖 T1a；T6 复用 T5 容器；其余互相独立。

## 暂缓清单（等用户真机反馈再决定，先不做）

- 注入心跳自检 + provider 失效自动重装（原 T1c，保活类 fallback）
- 电池优化白名单引导（MIUI 向）
- ≥30 分钟锁屏长跑验收
- 通知权限撤销后的降级说明

## 每个任务的实现/验收固定动作（2026-09-14 晚起：主脑直接实现）

- 用户改令：**停用 DeepSeek 工人通道**，代码由主脑（ZCode/GLM）直接写。
- 实现：主脑直接写；每任务交付前亲自跑三道门禁（含 JAVA_HOME 坑）。
- 验收：`git diff` 自审 + 门禁实测 + 对照硬约束；真机项列清单交用户。
- 历史留存：总计划曾交 DeepSeek 复查（v1→v2，session `bc754c07`）；
  T1a 由 DeepSeek 完成（session `10c585fa`）。工作方式已切换，不再使用。
