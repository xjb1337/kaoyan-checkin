# 给 AI 编程助手的项目规定

## 网页版已冻结，不要修改
本仓库只继续开发 Android App（`android/` 目录）。根目录下的网页版保留作参考和历史备份，**不删除、不修改、不重构、不格式化**：

- `index.html`、`core.js`、`views.js`、`sw.js`、`server.py`、`manifest.webmanifest`
- `icon-180.png`、`icon-512.png`
- `tests/`（网页版测试）
- `使用说明.md`

需求只改 App，除非仓库所有者明确要求改网页版。Android 仍需兼容网页版导出的备份格式。

## 界面规定：不放无端解释文字
不写说明计算方法、图表读法、功能用途的句子；用标签和单位、图标、颜色、状态徽章、图例、目标线、布局分组、空状态操作按钮来表达含义。只保留数据本身、操作按钮，以及错误和风险提示（会丢数据、不可撤销等）。

## 数据口径与历史存档兼容
- **小组件与首页一致**：小组件（`WidgetSnapshot` / `WidgetPresentation`）展示的任务列表与完成计数须与「今日」页保持一致（同时体现全部当日任务与最低线达标状态），避免 App 显示 `3/4` 而小组件只显示 `2/2`。
- **历史存档兜底与可补录**：新增依赖新字段（如 `cf` 首次日期、`cd` 章节日期）的预测或统计功能时，不能假设旧版本或网页版导入的存档已包含这些字段；缺失时优先用已有日期字段回退，并在界面上提供直接补录入口。
- **UI 与导航改动同步更新测试**：调整底部 Tab、小组件文案或核心交互后，同步检查并更新 `SystemBarsAndTabsTest`、`DraftLifecycleTest`、`WidgetSnapshotTest` 与 `WidgetPresentationTest`。

## Android 实现与持久化约束
- **小组件布局仅使用 `@RemoteView` 原生支持组件**：`res/layout/checkin_widget*.xml` 中禁止使用 `<View>`（例如占位符请使用 `<FrameLayout>`），否则 `RemoteViews` 会抛出 `InflateException`。
- **`JSONObject.obj(key)` 缺失时返回游离对象**：修改 `day.obj("w")` 等子对象时，若父对象可能尚未包含该键，必须显式调用 `day.put("w", w)` 写回，避免静默丢数据；同时保留 `w.n`/`w.r` 与 `settings.wordBase`/`wordGoal` 对显式 JSON `null` 的拒绝校验（见 `WordProgressTest`）。
- **控制单行存档体积，避免触发 SQLite 2MB `CursorWindow` 上限**：主文档 `document.json` 不得内嵌完整恢复副本（`_recovery` 须独立存储），防止 `SELECT` 抛出 `SQLiteBlobTooBigException`。
