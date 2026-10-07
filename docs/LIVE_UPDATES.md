# 实时更新（Android Live Updates）

本文件记录 OpenCode Lagoon 的任务总览如何以系统「实时更新」显示在状态栏胶囊、锁屏 / 息屏卡片等系统表面。

## 设计原则

- **系统表面交给系统画**：胶囊、下拉卡片、锁屏 / 息屏的外观与配色全部由系统决定，App 只按标准格式提供内容，不写颜色、不用自定义布局（`RemoteViews`）、不在 App 内模仿灵动岛。
- **只走标准通道**：只使用 Android 16 的 Live Updates 标准 API。厂商系统把标准实时更新映射到自家岛 / 胶囊由系统自行完成，App 不写入任何厂商私有 extras，也不申请厂商通道（2026-10-07 决定移除全部品牌适配；此前的 vivo 原子岛、OPPO 流体云、荣耀灵动胶囊与小米焦点通知模板均已移除）。
- **只有一个岛**：只有“服务器总览”通知申请提升为实时更新；单会话通知（运行中、待授权、完成 / 失败结果）都是普通通知，不申请提升。
- **只在进行中上岛**：Google 的规范要求实时更新只覆盖“有明确开始和结束、正在进行”的活动，因此总览只在有任务运行或等待处理时常驻上岛，结束后降级为普通通知。

所有文案由 `core/LiveUpdateContent.kt` 从 `core/TaskSummary.kt` 纯函数派生，计数口径不变：`运行中 / 未读已完成 / 待回复 / 失败`。

## 生命周期

| 阶段 | 条件 | 通知形态 |
|---|---|---|
| `ACTIVE` | 有运行中或待处理任务 | ongoing + `setRequestPromotedOngoing(true)` + `setShortCriticalText("2跑·1完")`，即系统实时更新（上岛） |
| `SETTLED` | 只剩未读的完成 / 失败 | 非 ongoing、不提升、可划掉的普通通知 |
| `EMPTY` | 无内容 | 移除通知 |

- 用户划掉总览后（`deleteIntent` → `NotificationActionReceiver`），在阶段不变期间不再重发；阶段一变（例如全部结束、或有新任务开始）就重新展示。
- 点击落点：优先正在等待回复的会话，否则为头条任务（待处理 > 运行中 > 失败 > 已完成）。
- 展开内容：标题为主计数（如“2 个任务运行中”），副标题为服务器名，正文最多 3 行“状态 · 会话名”，超出追加“另有 N 个任务”。

## 标准通道要求

系统提升一条通知为实时更新需要同时满足（见 Android 官方 Live Updates 文档）：

- Manifest 声明 `android.permission.POST_PROMOTED_NOTIFICATIONS`（非运行时权限）；
- `NotificationCompat.Builder#setRequestPromotedOngoing(true)`；
- 通知为 `ongoing`、有 `contentTitle`、样式属于标准 / `BigTextStyle` / `CallStyle` / `ProgressStyle` / `MetricStyle`；
- 不使用自定义 `RemoteViews`、不是分组摘要、不 `setColorized(true)`，通道重要性不是 `IMPORTANCE_MIN`。

用户可在系统设置中关闭实时更新；`NotificationManager.canPostPromotedNotifications()` 可查询当前是否允许，设置页的「任务通知」分区会展示这一状态并提供跳转授权的入口（`promotedNotificationSettingsIntent`）。

## 代码结构

- `core/LiveUpdateContent.kt`：阶段判定、标题 / 正文 / 展开文案、胶囊短文案、点击落点、划掉后的重发规则。
- `system/TaskNotifications.kt`：`buildSummary` / `showSummary` 按阶段构建总览；单会话通知不申请提升。
- `system/SummaryDismissals.kt`：按服务器记录用户划掉总览时的阶段。
- `system/LiveUpdateSupport.kt`：探测标准实时更新在本机的可用状态，供设置页展示。

## 真机验收清单

1. 运行多个任务：状态栏胶囊 / 锁屏实时卡片显示 `x跑·y完`，点开后为系统样式卡片，正文列出最多 3 个会话。
2. 触发权限确认：标题变为“N 个任务待你处理”，点击进入对应会话。
3. 全部结束：胶囊 / 岛消失，总览变为可划掉的普通通知；划掉后不再弹出，直到有新任务开始。
4. 打开已完成会话：计数下降，全部已读后总览移除。
5. 分别在浅色 / 深色模式截图，确认外观完全由系统决定；在 Android 15 及以下确认退化为普通通知。
