# 实现结构与验收

- `core/OpenCodeApi.kt`: OpenCode 2.x（`anomalyco/opencode` `packages/protocol`）REST、Basic Auth、`location[directory]` 位置上下文、`/api/event` 全局 SSE。各 2.0.x 版本间变化的路由由 `core/ApiCapabilities.kt` 从实例 `/openapi.json` 解析。所有请求绑定固定 origin，禁止跨源重定向转发凭据；SSE 溢出会转成流错误以触发重连对账。
- `core/HttpOrigin.kt`: scheme+host+port 的凭据目标标识与回环明文白名单。
- `core/LagoonController.kt`: 所有页面共享的服务端状态、任务归一化和操作入口；异步写操作通过不可变 `OperationContext`（serverId + generation + client + selection revision + snapshot）绑定起点，切换后不再回写。
- `core/Models.kt`: 数据模型、`Session.Message.Info` 解析（user / assistant / shell / 通知类记录 / 隐藏记录）与 `TaskReducer` 任务阶段归约。
- `core/TranscriptProjection.kt`: 打开的会话的实时事件投影，对应官方 `packages/client/src/solid/data.ts` 的事件归约（`session.step.*`、`session.text.*`、`session.tool.*`、inbox、revert、compaction 等）。
- `core/TranscriptRows.kt`: 时间线行投影，对应官方 `packages/session-ui/src/timeline/projection.ts`（轮次分组、shell 独立成轮、通知行、中断分隔、只显示最后一条错误、撤销边界隐藏）。
- `core/TaskSummary.kt`: 全服务器范围的任务计数（运行中 / 未读已完成 / 待回复 / 失败）与统一显示文案，供 App 内灵动岛、Android Live Update 与小米超级岛共用。
- `core/ServerStore.kt`: 服务器资料与 Keystore AES-GCM 凭据。
- `core/OfflineCache.kt`: 加密离线缓存（catalog 与消息）。
- `core/KeystoreCipher.kt`: `ServerStore`/`OfflineCache` 共用的 AndroidKeyStore AES-GCM 加解密。
- `core/PairLink.kt`: 官方 `opencode pair` 链接解析（强制 HTTPS）。
- `core/Http.kt` / `core/Diagnostics.kt`: 进程级 OkHttp 连接池/调度器；轻量日志。
- `ui/`: 小米 HyperOS / MIUIX 风格页面与不同消息 Part 的渲染；信息架构主线为 Server → Project/Directory → Session → Conversation，一级导航「会话 / 活动 / 设置」，会话首页置顶「需要处理 / 正在运行」并按项目分组，Chat 是会话主界面（工具调用为可展开的紧凑轨迹，待办 / 改动 / 子任务 / 文件是会话上下文弹层入口）；`MainActivity` 负责根导航、预见式返回与深链。
- `system/`: 通知 Channel、Android Live Updates 请求、厂商灵动岛参数、任务前台服务与通知操作。全服务器任务总览沿用统一口径（`core/TaskSummary.kt`）：`TaskNotifications.buildSummary` 生成单条 ongoing 的 Live Update 通知（`setRequestPromotedOngoing` + `setShortCriticalText`），并由 `LagoonController` 在状态变化时统一发布；`system/IslandAdapters.kt` 按「能力探测 + 品牌兜底」把同一通知分发给小米超级岛、vivo 原子岛与标准实时更新通道（品牌矩阵见 `docs/ISLAND_ADAPTATION.md`）。
- `docs/task-event-contract.json`: Android `TaskReducer` 遵守的任务阶段归约契约。

## 必须在真实环境检查

1. 设置 OpenCode Server Basic Auth，使用 HTTPS URL 添加服务器；确认认证失败不会保存资料。
2. 从手机发送任务，检查消息、工具折叠、停止、SSE 重连后状态一致；确认灵动岛显示「运行中 / 未读已完成 / 待回复 / 失败」计数，且打开会话后已完成计数下降。
3. 触发 permission 和 question；分别从 App 与系统通知操作，检查服务器继续执行。
4. 检查子会话、Diff、文件浏览；测试新建、运行中继续发送、斜杠命令、重命名、删除等会话操作。
5. 在 Android 16 设备检查 Live Updates；在已获焦点通知权限的小米 HyperOS 3 设备检查超级岛。
6. 分品牌核对灵动岛适配状态（可直接查看设置页「灵动岛适配」分区），逐项回填 `docs/ISLAND_ADAPTATION.md`。

契约来源：`anomalyco/opencode` 2.x 源码（`packages/protocol`、`packages/schema`）与官方客户端（`packages/client`、`packages/session-ui`）。接入目标实例时可核对该实例 `/openapi.json`。
