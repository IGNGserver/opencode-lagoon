# OpenCode Lagoon

原生 Android OpenCode 控制端。Kotlin + Jetpack Compose 界面采用小米 HyperOS / MIUIX 设计规范，引入连续曲率 Squircle、科技蓝语义调色板。App 直接访问用户自己的 OpenCode Server，无需第三方中转服务。

## 功能

- 仅支持 OpenCode 2.x 服务端（`/api/*` 接口，以 `/api/info` 识别版本；1.x 已不再支持）；多服务器连接、Basic Auth/官方 pair 链接、Android Keystore 保护的密码与会话 Cookie、离线缓存、SSE 自动重连
- 项目与会话、异步任务（运行中可继续发送以引导当前轮次）、Agent/Model、斜杠命令（走服务端 `/command`）、文本/Reasoning/Tool 消息、停止与权限/表单处理；时间线按官方客户端规则投影，系统指令、合成输入、技能等给模型看的记录只显示一行通知
- 子会话、改动、文件浏览与搜索、重命名、删除；随版本变化的接口（重命名、恢复撤销、改动、已读同步）按实例 `/openapi.json` 选择
- 全服务器任务总览：实时显示「xx 个运行中、xx 个未读已完成」，有待处理时追加「xx 个待回复」、有失败时追加「xx 个失败」；在 Android 16+ 以系统实时更新（Live Updates）显示在状态栏胶囊 / 锁屏卡片，外观由系统决定，详见 `docs/LIVE_UPDATES.md`
- 本地前台监控与运行中、待处理、完成通知；任务总览仅统计未读结果，用户打开会话后即视为已读

## 本地构建

需要 JDK 17+、Android SDK Platform 36。执行：

```sh
ANDROID_HOME=/path/to/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

本仓库把 Gradle build 输出放在 `/tmp/opencode-lagoon-gradle/`，每个 checkout 使用独立路径 `/tmp/opencode-lagoon-gradle/<checkout-hash>/app/outputs/apk/debug/app-debug.apk`。

在 App 中添加可访问的 OpenCode Server URL。服务端需启用 Basic Auth；也可以直接粘贴 `opencode pair` 输出的官方链接，App 会访问一次链接、保存返回的会话 Cookie，再用该 Cookie 连接 API。建议通过 HTTPS 或可信 VPN 访问。使用局域网 HTTP 时，必须在该服务器资料中明确开启明文连接，授权会随资料保存。

### 当前连接与授权

本地前台服务只监控当前服务器。切换服务器会明确停止旧服务器的本地监控，远端任务继续。离线缓存与局部失败数据均标记为过期，不能用于发送操作。

消息按官方客户端方式发送：客户端生成消息 id，仅在连接中断时用同一 id 重试一次。待处理权限按会话读取（`/api/session/{id}/permission`），表单按会话所在目录读取，不会因服务器工作目录不同而漏掉。“始终允许”只在服务端给出保存范围时提供，设置页可查看和撤销已保存权限。

## 更名与迁移

项目由 “OpenCode Mobile” 更名为 “OpenCode Lagoon”（与 GitHub 仓库名 `IGNGserver/opencode-lagoon` 对齐）。以下标识随之改变，跨版本部署需要按此迁移：

- 显示名与工程名：应用显示名、`rootProject.name`、发布工作流名与安装包文件名（`OpenCode-Lagoon-<version>.apk`）、Gradle 输出目录 `/tmp/opencode-lagoon-gradle/`。
- 应用标识：`applicationId` 与 Kotlin 包改为 `com.igng.opencode.lagoon`，与旧的 `com.igng.opencode.mobile` 在 Android 上是两个不同应用。存量安装不会覆盖升级，需要卸载旧包再装新包；`KeystoreCipher` 别名改为 `opencode-lagoon-*`，旧包保存的密码、Cookie 与离线缓存本来就不跨 UID，新包一律重新录入。
- 深链接：scheme 改为 `opencode-lagoon://`。旧版通知与旧 scheme 链接不会被新包接住，升级后由新包重新发出通知。
- CI：仓库 Secrets 由 `OPENCODE_MOBILE_*` 改名为 `OPENCODE_LAGOON_*`，keystore 别名与主文件名改名但证书不变（见 [`docs/ANDROID_SIGNING.md`](docs/ANDROID_SIGNING.md)）。旧的 `OPENCODE_MOBILE_*` Secrets 已随更名进入 `main` 删除；若需要回到更名前的发布工作流，只能按 `docs/ANDROID_SIGNING.md` 从本机 keystore 与口令文件重新写入。
- `docs/releases/` 下的历史发布说明保持原样，它们记录的是当时实际发布的名称。

## 验收边界

Android 构建与单元测试只能证明代码可编译和有限的 API/状态逻辑。真实 OpenCode 版本、SSE 断线恢复、Android 16 Live Updates 提升都需要在目标服务器和设备上验收。Android 15+ 的 dataSync 前台服务有运行时长限制。
