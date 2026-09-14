# MockRun 总计划（主脑-工人模式）

> 分工：ZCode（主脑）拆任务、派活、验收；DeepSeek（WorkBuddy 内置 CLI）写代码。
> 总计划本身先交 DeepSeek 复查，修订后才开工。
> 每完成一个任务打勾并记录验收结论；提交（git commit）一律先问用户。

## 当前基线（2026-09-14）

- 已提交：Phase 0～2b（构建骨架 / FGS 注入 / 坐标转换层 / 高德选点+引擎切换）。
- **未提交**（上个会话成果，门禁已验证全绿）：预置路线 PresetRoutes、
  回放引擎 RoutePlayer（OU 噪声漂移模型）+ 单测、主界面全屏地图+控制卡改版、
  比格标记动画、ic_beagle/ic_paw/panel_bg 资源。
- 三道门禁：`testDebugUnitTest` / `assembleDebug` / `:app:lintDebug` 全部通过。
  （Bash 子进程需先 `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`）

## 全局硬约束（每个任务都要带上，违反即打回）

1. 内部坐标一律 WGS-84；进高德地图前才转 GCJ-02（现有代码就是这么分的）。
2. 注入链路（MockLocationService）：provider 安装顺序、单调 elapsedRealtimeNanos、
   Location 字段补齐，这些是踩过坑的契约，**不许动语义**。
3. AGP 9.4 内置 Kotlin，禁止 apply kotlin 插件；buildTools 钉死 36.0.0；
   阿里云镜像优先——**不许"升级/修正"任何版本或仓库配置**。
4. ABI 只留 arm64-v8a；targetSdk 36，edge-to-edge 默认开启。
5. 新逻辑尽量写成纯 Kotlin（可 JVM 单测），Android 依赖留在外壳。
6. 工人只改代码不 commit；每任务交付时跑通三道门禁。

## 任务列表

### T0 整理提交 Phase 3a（等用户点头）
未提交的路线/回放引擎改动按逻辑分 1~2 个 commit 收进版本库。
验收：`git log` 干净、工作区 clean、门禁绿。

### T1 运行中控制：暂停/继续 + 运行中调速 + 通知栏停止
- 服务加受控命令通道（startService action 或静态入口）：pause / resume /
  setSpeed / stop；通知栏带"停止"action。
- RoutePlayer 支持暂停语义：暂停期间位置冻结（漂移可继续），resume 后时间线
  平移，**位置不许跳变**；setSpeed 只改后续推进速率。
- 验收：单测覆盖 暂停→继续→调速 序列，断言时间线连续；UI 有暂停/继续按钮；
  通知栏停止按钮可用。

### T2 首页授权状态卡
按 AGENTS.md 验收流程要求，首页显示「模拟位置授权：已授权/未授权」+
通知权限状态；未授权点击直达授权路径（现有 statusText 点击行为并入）。
验收：授权与未授权两种状态的 UI 断言（逻辑抽纯函数可单测）。

### T3 edge-to-edge 适配
targetSdk 36 强制 e2e：底部控制卡避让手势条/导航栏，地图全屏延伸到状态栏后。
验收：控制卡在手势导航与三键导航下都不被遮挡（WindowInsets 方案，
不依赖 deprecated API）。

### T4 自定义路线（打点成线）
新增"自定义"路线模式：地图上依次打点连成线（撤销上一点/清空），
可命名保存到 app 私有目录（JSON），列表可选、可删，选中即可作为回放路线。
验收：存取层纯 Kotlin + 单测；UI 打点/保存/删除/选用全流程可用；
坐标系边界不破坏（存储 WGS-84）。

### T5 GPX 导入
解析 GPX `<trkpt>` → GeoPoint 列表（纯 Kotlin 解析器 + 单测，含非法输入容错），
导入后进入与 T4 相同的"自定义路线"流程。
验收：单测覆盖正常/空点/损坏输入；导入的路线能在地图预览并回放。

### T6 启动图标（消 MissingApplicationIcon）
ic_beagle 升级为自适应图标（前景/背景两层，mipmap anydpi-v26 + 兼容回退），
manifest 补 `android:icon`。
验收：lint 不再报 MissingApplicationIcon；门禁绿。

### T7 百度引擎接入（BLOCKED：等用户下载「基础地图」aar 放 app/libs）
BaiduPickerActivity（BD-09 页内转 WGS-84 返回）+ 主地图百度渲染。
验收：切百度引擎后选点/显示/回放全流程与高德对等。

### T8 收尾
README（功能、构建、真机验收步骤）、验收流程文档更新到当前 UI、lint 维持零 error。
验收：照着 README 从零能跑起来。

## 执行顺序与依赖

T0 → T1 → T2 → T3 → T4 → T5 → T6 → T8；T7 独立，aar 到位后随时插入。
T4/T5 有依赖（T5 复用 T4 的自定义路线容器），其余互相独立。

## 每个任务的派发/验收固定动作

- 派发：DeepSeek 代理模式，提示词带=任务目标+涉及文件+全局硬约束+验收标准+门禁命令+JAVA_HOME 坑+"别 commit"。
- 验收（主脑亲自）：`git diff` 逐行审 → 跑三道门禁 → 对照硬约束 → 真机项列成清单交用户。
- 打回：带具体意见 `-r <session_id>` 继续同一会话改。
