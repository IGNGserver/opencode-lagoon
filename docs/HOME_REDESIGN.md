# 首页分组与会话展示规则

本次修复将首页改为当前服务器的项目列表：项目可折叠，内部主会话按最后修改时间倒序。搜索和状态筛选放在菜单中；新建按钮位于列表右下方；保留现有 Dock。首页只应用一次状态栏顶部 inset。

## 原因与修复

| 原因 | 修复规则 |
| --- | --- |
| 用首条用户消息代替服务端标题，导致与官方显示不同 | 只使用 `session.title`；精确匹配官方时间占位标题后显示“新会话 / 子会话” |
| 首页被当前选中项目限制，并通过正文/草稿判断会话是否显示 | 当前服务器所有项目分组；只过滤 `parentID` 非空与数字类型 `time.archived`，保留无正文、草稿和脚本创建的主会话 |
| 解析器忽略 V2 `canonical`，列表只查询项目根目录 | 兼容 `canonical` / `worktree` / `directory`，保留 `sandboxes`；V2 全局查询并传 `parentID=null`，V1 查询根目录及已知 worktree |
| 子会话终态被当作主会话结果、历史结果被普遍标为未读 | 等待授权/回答和运行状态沿父链汇总；结果状态归属主会话；“待查看”来自完成/失败事件通知记录 |
| 查看旧正文或离线缓存可能错误清除新结果 | 成功取得在线正文且详情可见后，只清除发起请求时已存在的未读结果 |

组内使用 `time.updated`，缺失时回退 `time.created`，同时间按会话 ID 排序。等待或运行状态不改变列表次序。同名项目以项目 ID 区分；已登记的 worktree 仍属于原项目。打开会话、切换新建会话的项目，不会改变首页查询范围。

## 官方依据与兼容边界

对照了 2026-10-03 本机正在提供的官方 V2 客户端资源，其项目模型、根会话查询及通知账本已与旧版源码不同：

- `oc-index.js` SHA-256：`677e2fa5821961c0daff63d661a278117e7d26d7beaa5cd110f069b8c9bb408f`。
- `runtime-DaHoVrip.js` SHA-256：`036e2dfd60fecebefe3e365b0d59dc84db7511f6fec151a35299019bbdd0b0be`。
- 标题与通知模式也参考官方源码：[session-title.ts](https://github.com/anomalyco/opencode/blob/907b3bc518fa48e90e8ec24dd327d13eee71c36c/packages/app/src/utils/session-title.ts)、[notification.tsx](https://github.com/anomalyco/opencode/blob/907b3bc518fa48e90e8ec24dd327d13eee71c36c/packages/app/src/context/notification.tsx)。

通知账本按服务器保存，最多 500 条、保留 30 天，事件 ID 防重复。V2 完成/失败事件产生未读结果，中断不产生完成未读；V1 沿用 idle/error 事件。历史 `outcome` 或缺失的 `time.viewed` 本身不会产生“待查看”。通知账本属于各客户端本地数据，本应用不会导入官方桌面客户端的既有未读历史。

当实例文档确认支持 `POST /api/session/{id}/view` 时，同步的 body 为该次结果的 `{idle: time.idle}`；不会用当前时钟替代执行轮次。服务端同步失败保留本地已读状态。目录或控制接口失败时保留上次可信数据，并提示部分数据待同步。

## 验证

仓库验证命令为 `ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug`，本机额外指定共享 `GRADLE_USER_HOME` 与本地 project cache。测试覆盖标题、主/子会话和归档过滤、项目/worktree 身份、稳定排序、原生分页游标、全项目查询、现代执行事件、未读持久化及读取并发、离线缓存和精确 idle 请求。

Android 模拟器使用独立 AVD 与模拟 V2 服务数据检查实际界面；它不能替代真实手机、OEM 状态栏或用户实际服务器的验收。未发布或安装到用户设备。
