# 考研打卡 Android · 1.1.0

Kotlin + Jetpack Compose / Material 3 原生应用。应用 ID：`com.xjb.kaoyancheckin`，最低 Android 8.0（API 26），编译/目标 API 35。当前源码的 `versionName` 为 1.1.0、`versionCode` 为 7。学习数据只保存在本机；检查更新和下载时会访问 GitHub。与旧版证书一致时可覆盖安装。

备考计划的唯一来源是 `app/src/main/assets/plan.json`。网页中的计划常量只是历史遗留，不再与 App 同步维护，也不参与一致性对拍；以后调整计划只改 `plan.json`。

## 构建与测试

界面继续沿用 `WebTheme` / `WebComponents`：启动窗口和 Android 12+ Splash 分别提供浅/深色资源，进入界面后系统栏图标和底色跟随当前皮肤。底部六个 Tab 使用手写描边 VectorDrawable，不依赖 `material-icons-extended`。

进度预测、计划对照、模考排序、今日预测摘要、统计聚合和热力图按 `plan/state/today` 缓存；保存、导入、撤销后发布新的状态对象使缓存失效，跨午夜的 `today` 变化也会重算。仅展开章节、编辑未保存草稿或显示提示不会重新遍历全部记录。计划尚未开始且没有勾选的科目合并为「未开始：科目 日期、…」，提前学习的科目仍保留进度行。

普通提示与撤销共用同一 Snackbar 外观，但使用独立 Host 和队列：普通提示无「撤销」按钮，不打断撤销倒计时/操作。小组件没有 Compose 宿主，保存失败时在桌面摘要中提示，前台或下次打开应用时通过同款普通 Snackbar 显示一次；错误提示单独保存在设备偏好，不写入学习备份。

需要 JDK 17、Android SDK Platform 35 / Build Tools 35，以及可访问 Google Maven、Maven Central 的网络。Gradle 8.9 Wrapper 已随源码提供，不需要系统 Gradle。

```bash
cd android
export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/Android/Sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew testDebugUnitTest assembleDebug --no-daemon
```

首次换机器时，创建未纳入 Git 的 `android/local.properties`：

```properties
sdk.dir=/你的/Android/Sdk
```

如所在网络访问 Google Maven、Maven Central 需要代理，请自行设置 `HTTP_PROXY` / `HTTPS_PROXY`，或在用户级 `~/.gradle/gradle.properties` 配置 JVM 代理；不要把个人代理或凭据提交到仓库。

- APK：`app/build/outputs/apk/debug/app-debug.apk`（开发调试签名，不是发布包）。
- JVM 报告：`app/build/reports/tests/testDebugUnitTest/index.html`。
- 核心测试：`CheckinCoreTest` 覆盖迁移、拒绝规则、深复制与时长；`MergeImportTest` 覆盖合并、替换、恢复和失败不改输入；`StudyPlanTest` 覆盖阶段、最低线、补签、任务与连胜；`DraftSaverTest`、`DraftPatchTest`、`DraftLifecycleTest` 覆盖防抖自动保存和字段补丁；`ScoreEntryTest`、`ScoreChartMathTest` 覆盖模考分科与趋势图坐标。
- 计划文件校验：在仓库根目录运行 `node android/tools/verify-plan.cjs`，无需 npm 安装。脚本只校验 `plan.json` 可解析，并抽查 `START`、`HISTORY_START`、`LEGACY_PHASE`、`PHASES`、`PH4`、`PH5`、`SUBJ`、`MILES` 的基本结构，不再读取网页或要求两边逐字一致。
- Android CI：`.github/workflows/android.yml` 在 push 和 pull_request 时使用 JDK 17、Android SDK 35 运行 `./gradlew --no-daemon testDebugUnitTest assembleDebug`，并先执行上面的计划校验。默认单测不包含截图类。新增的 `WidgetSnapshotTest`、`UpdateProtocolTest` 和 `UpdateClientTest` 覆盖小组件快照、共享写入、跨日、更新协议和校验，测试不访问真实网络。
- 外观与小组件：`ThemeRepositoryTest` 覆盖主题名称解析和本机持久化；`WidgetPresentationTest`、`WidgetRemoteViewsTest` 覆盖小尺寸布局、状态和真实 RemoteViews；`DraftLifecycleTest` 也检查切换主题后 ViewModel、页面与草稿保留。
- 新增的 `UndoTaskTest`、`UndoLifecycleTest` 覆盖撤销有效期、覆盖、共享写入及小组件前后台操作；`WordProgressTest`、`ChapterEstimateTest` 覆盖单词备份和计算、章节日期及估算边界。

技术版本固定为 AGP 8.7.3、Kotlin 1.9.24、KSP 1.9.24-1.0.20、Compose BOM 2024.09.03、Compose Compiler 1.5.14、Room 2.6.1。没有通知推送、相机、网页套壳或云同步；网络权限用于匿名检查和下载应用更新。

### Robolectric 数据库隔离

使用 ActivityScenario + Room 的测试统一通过 `TestDatabase` 启动 Activity，并在恢复 Clock、更新后端等全局配置之前关闭它。它会关闭所有 Scenario，反复推进主 Looper、等待 ViewModel 写入子任务完成，再 reset/delete 数据库；不能只等待 `state` 更新就开始下一测试，也不能在主线程阻塞等待仍需主 Looper 执行的协程。

Robolectric 同一 sandbox 会复用 AndroidX 2.8.6 的 `AndroidViewModelFactory._instance`，其中保存的 Application 可能来自上一测试。只删除当前 Application 的数据库不能隔离旧 Application 目录。辅助会在测试边界清除此缓存，并检查每个新 ViewModel 使用当前测试的 Application；升级 AndroidX 后需核对该字段。

Repository 的每一代连接在 IO 线程同步执行 DAO 操作，读写与关闭共用连接锁。`reset()` 退役旧连接后，旧 store 的读取和后续写入都会失败，不能懒打开已删除的数据库。正常 Activity 暂停/销毁不会 reset 或取消写入；生命周期测试用闩锁阻塞存储，确认销毁后已发起的写入仍落盘。

`RepositoryIsolationTest` 覆盖未打开旧连接、读写之间 reset；`CrossMidnightTest` 覆盖今日/历史日期、跨周目标复制、章节日期与 ON_RESUME。前台日期轮询按距上海午夜时间等待，最短 1 秒、最长 60 秒，避免固定时钟过密轮询，并在设备改时后及时重新检查。

### JVM 界面截图

截图测试使用 Robolectric 的 API 34 原生图形模式与 Roborazzi，不需要模拟器。它会将 `app/src/test/resources/screenshot-seed.json` 按完整恢复流程写入测试数据库，再启动真实 Activity，录制六个页面的浅色和深色图。测试时将日期固定为上海时间 2026-11-09；应用实际运行时仍使用系统日期。

在上面配置好 JDK、SDK 和网络之后，在 `android/` 目录运行：

```bash
./gradlew :app:testDebugUnitTest --tests com.xjb.kaoyancheckin.ScreenshotTest \
  -PrecordScreenshots=true --no-daemon --max-workers=2
```

若 Gradle 或 Robolectric 需要显式使用本机代理，在命令末尾加上 `-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7891 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7891`。依赖和 Robolectric 的 API 34 文件均已缓存时可加 `--offline`。

图片默认写入 `app/build/screenshots/`，再次录制会覆盖同名文件；可用 `-PscreenshotDir=/绝对路径` 或 `-DscreenshotDir=/绝对路径` 指定其他目录以保留基线。普通 `testDebugUnitTest` 不运行截图类，也不校验基线。

`ScreenshotTest` 还录制 `app-tabbar.png`、`app-prog-unstarted.png`、`app-notice-snackbar.png` 及对应 `-dark` 图，分别覆盖矢量底栏、合并后的未开始科目与无撤销按钮的普通提示。`StartupThemeTest` 覆盖 API 28/34 浅深色启动资源；`SystemBarsAndTabsTest` 覆盖所有皮肤的系统栏和 Tab 语义；`ScreenCacheTest` 验证无关重组复用结果、保存和跨午夜失效；`UndoLifecycleTest` 保留原有撤销流程并覆盖普通提示共存。

截图种子是合成数据，今日新词 40、复习 90，生理有两章及首次勾选日期。录制时会额外滚动到单词输入和章节估算，生成 `app-today-words.png`、`app-prog-estimate.png` 及对应的 `-dark` 深色图。测试还触发单词任务勾选切换并等待撤销按钮出现，生成 `app-today-undo.png` / `app-today-undo-dark.png`；截图后会点击撤销。运行截图测试的机器仍应人工检查图片，尤其是小屏、字体放大和其他主题。

### 小组件截图录制

`WidgetScreenshotTest` 用 Robolectric/Roborazzi 渲染真实 AppWidget RemoteViews，包含默认主题的浅色与深色，默认单测排除。测试直接将测量为 300×130dp 的组件绘制到独立 bitmap（xhdpi 下为 600×260 像素），再交给 Roborazzi；不把组件放进 Activity 窗口截整屏。本次实现不包含已录制图片。需要 JDK、SDK 和测试依赖后，在 `android/` 目录执行：

```bash
./gradlew :app:testDebugUnitTest --tests com.xjb.kaoyancheckin.WidgetScreenshotTest \
  -PrecordScreenshots=true -PscreenshotDir=/workspace/kaoyan-widget-preview \
  --no-daemon --max-workers=2
```

输出为 `widget-light.png` 和 `widget-dark.png`，重复录制会覆盖这两个文件，不会写入真机截图。它们是 JVM 预览，不替代桌面启动器和真机验收。

## 外观主题

「设置 → 外观主题」可预览并选择晨读蓝、砚青、暖杏、夜读紫或竹青。未选择过主题时使用晨读蓝，沿用原有网页蓝 `#2563EB` 和原有浅、深色基础配色。每套都有浅色和深色，随系统切换；成功、警告和错误等语义色不随主题改变。

主题以名称保存在本机 SharedPreferences 中，与 Room 中的打卡文档和 JSON 备份分开；遇到未知名称时回退晨读蓝。更换主题立即更新界面并刷新已添加的小组件，小组件也使用所选主题并跟随系统深浅色。

## 桌面小组件与更新

将「今日最低线」添加到桌面；推荐 4×2，可缩放。小组件显示今天最低线、连胜和任务状态，点击任务即可勾选或取消，点击其他区域打开 App 今日页。复试准备阶段按现有规则任意一项达标；任务较多时小组件显示能放下的任务，全部任务可在 App 内操作。缩得很小时显示最低线计数和连胜。字体放大、启动器网格与实际可用区域因设备而异，需要在手机上检查。

App 和小组件共享 `checkin.db`、同一个进程内互斥仓库，以及 `StudyPlan` 的打卡和达标规则。保存时在锁内重读后修改并规范化，避免互相覆盖；小组件保存后，前台 App 通过仓库变更通知重读并显示可撤销提示。App 在后台时，最近一次勾选操作保留 45 秒，窗口内打开 App 可撤销；小组件也可再点一次改回。只保留一步，新勾选操作覆盖前一步。App 写入后主动更新所有小组件，上海时间零点由 AlarmManager 唤醒刷新；进程运行时监听日期变化，时间或时区变化及开机后重新注册。Android 对后台静态日期广播有限制，不能单靠该广播跨日。Android 13 及以上使用 `USE_EXACT_ALARM`，Android 12 使用 `SCHEDULE_EXACT_ALARM`；系统若拒绝精确闹钟则退回近似闹钟，系统省电或应用被强行停止可能延迟刷新。`USE_EXACT_ALARM` 的应用商店分发有额外政策限制。

在「设置 → 检查更新」可手动检查，启动或返回前台时每个上海自然日最多静默检查一次。它匿名请求 GitHub latest release，查找并下载名为 `update.json` 的资产；如果旧 Release 没有这个文件，就不自动安装。仅当 `versionCode` 大于当前版本才提示新版。确认后下载到应用私有缓存、显示进度并校验 SHA-256，随后用系统安装器安装；若系统尚未允许本应用安装未知来源应用，会先引导到系统设置，允许后返回即可继续。网络错误、超时、内容无效、文件或校验失败时可重试，也可通过提示中的 Release 页面手动下载。安装包会在下次下载前清理，清除应用缓存也会删除它。

`update.json` 使用 UTF-8 JSON 对象，以下为已发布的 0.4.0 清单格式示例：

```json
{
  "versionCode": 4,
  "versionName": "0.4.0",
  "apkUrl": "https://github.com/xjb1337/kaoyan-checkin/releases/download/v0.4.0/kaoyan-checkin-0.4.0.apk",
  "sha256": "64 位十六进制 SHA-256 摘要",
  "notes": "更新说明"
}
```

摘要接受大小写和冒号、连字符或空格分隔；地址必须为 HTTPS。结构化清单明确指定版本号、安装包和摘要，无需从 tag 或文件名猜测 `versionCode`。清单缺失、字段不全、格式不合法、APK 不可用或摘要不一致都不会启动安装器。

## 签名与发版

Gradle 从 `SIGNING_STORE_FILE`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD` 四个环境变量读取 release 签名；只设置部分变量会报错。四项均未设置时，release 回退到 Android 默认 debug signing（通常为当前用户的 `~/.android/debug.keystore`），`assembleDebug` 继续使用默认 debug signing。回退钥匙必须与已安装版本证书一致才可覆盖安装；不同机器自动生成的 debug keystore 并不一定相同。不要把 keystore 或密码写进仓库。

`.github/workflows/release.yml` 在推送匹配 `v*` 的 tag 后执行，要求 tag 与 Gradle 的 `versionName` 一致。它需要提前在仓库中配置这四项 CI secret：`SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。工作流将 base64 解码到 runner 临时目录，通过 `SIGNING_STORE_FILE` 交给 Gradle，完成后删除；构建和单测通过后，用 `apksigner` 校验证书 SHA-256 必须为 `04bdfa4e44d57c1e7ec9406cc84e5d6d5e26f77d180000658fef922523f7e51d`，不一致即失败。随后生成 `kaoyan-checkin-<versionName>.apk` 和 `update.json`，创建或更新 tag 对应的 GitHub Release。工作流不存储 keystore，也不打印密码。普通 push/PR 的 Android CI 无需签名 secret。

## 从网页迁移

1. 在原来使用的网页浏览器打开「设置 → 备份与恢复 → 导出备份（下载 JSON）」，确认文件保存成功。网页存储键是 `kaoyan2028_v1`，但导入的是导出的 JSON 对象，不是包含该键的 localStorage 外层对象。
2. 将 JSON 文件传到 Android 手机。首次打开应用会出现「从网页导出 JSON 导入」入口，也可以直接点底部「设置」。
3. 点击「选择网页导出的 JSON 文件」，或把 JSON 文本粘贴到输入框，点击「检查备份内容」。查看天数、周目标、模考次数和日期范围。
4. 选择「合并这份备份」或「完整恢复这份备份」，阅读确认框后确认。保存成功会提示“原记录已留本机副本”。不合法的数据不会替换当前存档。
5. 导入后检查「今日」的历史日期、「进度」章节和「周目标」。网页与原生应用各自独立保存，不会自动同步；迁移不会删除浏览器原数据。

### 导出回网页 / 换机

「设置 → 导出备份 JSON」使用 Android 系统文件选择器（SAF），可选本地文件夹或系统提供的网盘。文件名为 `kaoyan-checkin-YYYY-MM-DD.json`，日期按 Asia/Shanghai 计算。文件写入完成后，确认已另存到手机外，再点「已确认另存到手机外」记录备份时间。取消选择或写入失败不记录备份时间。

导出的 JSON 可以直接在网页的同一备份入口导入，也可导入另一台手机。只在手机内部保存一份文件不等于异地备份。卸载、清除应用数据会删除本机 Room 数据库及恢复副本；系统自动备份已关闭。

## 数据契约与安全性

Room `document` 表只存一行（`id = 1`）JSON 文档，不拆分天、周、成绩。App 和小组件通过同一仓库互斥地读取最新存档后写入，一次写入同时包含主数据与 `_recovery`；成功后才更新界面状态，保存失败会提示，不把未落盘的候选状态展示为已保存。

规范化后的备份保留网页使用的旧字段，并增加可选的单词和章节日期字段：

```text
schemaVersion: 2
days: { YYYY-MM-DD: { t:{}, c:[{n,d}], h, sh:{}, rw:{g,s,n}, u, w?:{n,r} } }
weeks: { YYYY-MM-DD: { must:[3], md:[3], ex:[{n,d}], review, u } }
recur: [], scores: [{id,d,n,tot,p:{}}], ch: {}, cf: {}, ce: {}, mk: []
settings: { exam, backupDays, wordBase, wordGoal }, upd, bk, tip
_recovery?: string
```

- 接受无 `schemaVersion` 的旧版及版本 1 / 2；规范化输出版本 2。旧 `note` 迁移到 `rw.g`，旧未分类总时长补入「其他」，既有分科学习时间不丢弃。
- `w.n` 为当天新词、`w.r` 为当天复习，各为 0–2000 的整数，缺省按 0；只有单词数的日期也算有数据。`wordBase` 默认为 1300（范围 0–20000），`wordGoal` 默认为 5500（范围 1–50000），均为整数；超范围或类型不合法会以中文错误拒绝导入。累计已背 = 基数 + 所有日期新词，复习不计入累计。
- `ch` 保持每科布尔数组；`cf` 保存从零章变为有勾选时的上海日期，全部取消后清除；`ce` 保存最后一章勾选完成的上海日期，再取消任一章后清除。旧数据若缺少首次或完成日期，不推造历史日期。短期 `_undo` 只存本机文档，不进入导出备份和导入前恢复副本；主题偏好也不进入备份。Room schema 保持 1。
- **向旧版回退有数据损失**：0.4.0 能读取 1.0.0 备份中的旧字段，但其规范化会丢弃 `w`、`cf`、`ce`、`wordBase` 和 `wordGoal`。将 1.0.0 备份导入 0.4.0 后再导出或写回，新字段无法恢复；导回网页等旧读取器也可能丢失它们。回退前请保留原始 1.0.0 JSON。
- 日期、数值、周目标结构和中文错误信息按 `core.js` 校验。递归拒绝 `__proto__` / `constructor` / `prototype`，层级上限 15；每个文本上限及导入原文上限均沿用网页 5 × 1024 × 1024 个 UTF-16 单元（网页 UI 称 5 MB）。拒绝非标准 JSON。
- **合并**：每日/每周按记录 `u` 选择，时间相同保留本地；`w` 随整天记录选择。只有导入 `upd` 更大时才更新固定任务、成绩、章节及其 `cf`/`ce`、补签与设置。`tip` 只会置为 1，`bk` 只取更大值，不因旧备份回退。完全无内容变化时与网页一样不改变合并结果；真正导入操作总会将 `upd` 设为当前时间。
- **完整恢复**：不按时间合并，直接采用备份内容。导入前当前主数据（去掉 `_recovery`）成为新的 `_recovery`；导入文件自带的恢复副本不覆盖这一规则。
- **恢复上一份**：将 `_recovery` 作为完整恢复来源，当前主数据再成为恢复副本。只保留一份，可往返切换，不递归嵌套。正常导出始终剥离 `_recovery`。
- **损坏保护**：启动无法读取/验证原存档时停止日常写入，允许导出原始文本和从备份恢复。恢复时强制替换并把原始文本留为 `_recovery`，不悄悄覆盖坏档。损坏状态的“导出原始存档”可能不是合法 JSON，需另存后人工修复。
- 无真实学习数据随代码提交，测试仅使用合成记录。

## 六页使用

- **今日**：前后日期与日期选择器不能跳到今天之后；显示阶段、最低线、固定/自定义任务、各科时长、收获/难点/明天三问、连续打卡与初试倒计时。勾选或取消任务后可在约 6 秒内点「撤销」，连胜和最低线随数据重新计算。单词任务下可选填当天新词、复习数量，勾选任务不会自动填数。单词数、三问复盘、周目标和手输时长采用 600ms 防抖自动保存，切页、退到后台或组件销毁时立即 flush；勾选和加减时长即时保存。自定义任务可删除当天记录，确认后只影响当天，不改固定任务。空白浏览日期不会创建占位记录。
- **进度**：查看单词累计、目标、今日新词/复习及剩余数量；展开各科章节勾选。章节按已完成章数除以首次勾选至今天的自然日数（含首日）估算学完日期，再与计划结束日比较；日期只是按当前速度推算，不是承诺。已经完成的科目按实际记录的完成日期比较，旧数据缺少日期时明确说明。还可记录模考总分及可选的生理、生化、病理、内科、外科分科得分，趋势图带坐标刻度和 200 分参考线；删除模考记录需确认。
- **周目标**：周一为起点，三件必做与完成标记、额外任务增删、周复盘；支持复制上周目标和复制自动周报文字。未来周只读。
- **统计**：累计小时、分科小时、连胜、本周及前两周最低线完成率、近 8 周学习时长柱状图和计划全程热力图。数据健康按网页语义显示备份相对时间：「今天」或「X 天前」，从未备份时醒目显示「从未备份」，达到提醒周期时提示「该备份了」。
- **节点**：网页原文的阶段路线与 18 个关键提醒；所有“预计”日期仍只是规划占位。
- **设置**：五套主题预览与切换、初试日期、单词基数与第一轮目标、固定任务（停用需确认）、补签卡（显示最近 6 条已用日期）、7/14/30 天备份提醒、文件/粘贴导入预览、合并/完整恢复、恢复上一份和确认清空。清空与网页一致保留设置和固定任务，删除其余学习数据及恢复副本；主题偏好单独保存在本机。

最低线为 `words` + `drill`，或使用补签；`fs` 阶段仅任意任务完成即达标，不接受补签代替。每个自然月最多两次补签，只能用于计划开始后且早于今天的未达标日期。`fs` 在网页中实际指初试后的复试准备阶段；考前 PH4 仍要求两项最低线。

日期和文件名统一按上海时区。返回前台或前台跨零点时，只有原本正在看“今天”的页面才跟随新日期，历史选择不跳转；周导航同理。未来日期/未来周允许浏览，但不能编辑。

## 验收建议

手动验收：首次启动 → 导入网页 JSON → 检查历史任务与科目小时 → 修改复盘后等待自动保存 → 强制停止并重开 → 导出并在网页检查 → 导入另一份做合并/替换 → 恢复上一份 → 用畸形 JSON 验证原数据未变。另检查未来日期只读、补签月限与已用列表、删除模考/停用固定任务的取消与确认、备份状态提醒、取消文件选择、横屏/大字体/深色模式。

## 1.0.0 能力边界

- 已实现：任务一步撤销、单词数量及第一轮进度、章节完成时间估算；桌面小组件、匿名检查应用更新，以及原有的复制上周目标、周报文字、复盘与时长自动保存、模考趋势图和备份恢复功能。
- 仍未包含：通知或提醒推送、日历 ICS 导入导出、云同步/多设备自动同步，以及自动化模拟器或真机设备测试。发版沿用既有调试签名身份，不是另建的正式密钥。
- 恢复副本只保留上一份，不是多版本备份历史；本机 Room 保存也不是云备份。

Android CI 会在 GitHub 托管 runner 上执行 JVM 单元测试、`assembleDebug` 和计划校验。JVM 截图测试默认排除，需要按上文命令手动录制；本轮未把真机安装、文件选择器或视觉检查宣称为已完成的设备验收。
