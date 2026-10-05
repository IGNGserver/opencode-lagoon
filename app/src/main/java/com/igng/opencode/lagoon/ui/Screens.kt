package com.igng.opencode.lagoon.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.text.KeyboardOptions
import com.igng.opencode.lagoon.core.*
import com.igng.opencode.lagoon.system.IslandRegistry
import com.igng.opencode.lagoon.system.IslandSupport
import com.igng.opencode.lagoon.system.TaskNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.*
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/* ------------------------------------------------------------------ *
 * 信息架构主线：Server → Project/Directory → Session → Conversation
 * 「会话」是默认首页：按项目折叠分组，组内严格按服务端修改时间排序。
 * 状态附着在 Session 上，不再保留工作台 Dashboard、焦点看板与指标大数字。
 * ------------------------------------------------------------------ */

private fun projectName(state: LagoonState, session: Session): String =
  resolveSessionProject(session, state.projects)?.name
    ?: session.directory.substringAfterLast('/').ifBlank { session.directory }

private class DateFormatterCache {
  private var locale: java.util.Locale? = null
  private var formatter: DateFormat? = null
  fun get(current: java.util.Locale): DateFormat {
    val cached = formatter
    if (cached != null && locale == current) return cached
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, current).also {
      locale = current
      formatter = it
    }
  }
}
private val sessionDateFormatters = DateFormatterCache()

/** 相对时间：会话列表需要“刚刚 / 12 分钟前”这类高信息密度时间。 */
internal fun formatRelative(timestamp: Long, now: Long = System.currentTimeMillis()): String {
  if (timestamp <= 0) return ""
  val diff = now - timestamp
  val minute = 60_000L
  val hour = 60 * minute
  val day = 24 * hour
  return when {
    diff < minute -> "刚刚"
    diff < hour -> "${diff / minute} 分钟前"
    diff < day -> "${diff / hour} 小时前"
    diff < 7 * day -> "${diff / day} 天前"
    else -> sessionDateFormatters.get(java.util.Locale.getDefault()).format(Date(timestamp))
  }
}

/** 已运行时长：活动页与运行中会话。 */
private fun formatElapsed(since: Long, now: Long = System.currentTimeMillis()): String {
  if (since <= 0) return ""
  val diff = (now - since).coerceAtLeast(0)
  val minute = 60_000L
  val hour = 60 * minute
  return when {
    diff < minute -> "已运行 ${diff / 1000} 秒"
    diff < hour -> "已运行 ${diff / minute} 分钟"
    else -> "已运行 ${diff / hour} 小时"
  }
}


/** The state itself is the pill (“运行中 / 等待授权 …”); the meta line only locates the session. */
private fun buildMeta(state: LagoonState, session: Session, includeServer: Boolean = false): String =
  buildList {
    add(projectName(state, session))
    if (includeServer && state.profiles.size > 1) state.server?.name?.let(::add)
  }.joinToString(" · ")

/**
 * 高密度会话条目：标题 / 所属项目 / 状态 / 时间（多服务器时含 Server）。
 * 需要处理的会话以警示底色置顶强调，状态始终附着在 Session 上。
 */
@Composable
private fun MiuixSessionRow(
  session: Session,
  task: TaskState?,
  meta: String,
  onClick: () -> Unit,
  emphasized: Boolean = false,
  trailing: String? = null
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    pressFeedbackType = PressFeedbackType.Sink,
    showIndication = true,
    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    colors = CardDefaults.defaultColors(
      color = if (emphasized) MiuixColorTokens.WarningSubtle else MiuixTheme.colorScheme.surfaceContainer
    ),
    onClick = onClick
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          text = session.title,
          style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.Medium),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
          text = meta,
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      Spacer(Modifier.width(10.dp))
      Column(horizontalAlignment = Alignment.End) {
        if (task != null && task.phase != TaskPhase.IDLE) {
          MiuixStatePill(task.phase)
          Spacer(Modifier.height(3.dp))
        }
        if (!trailing.isNullOrBlank()) {
          Text(
            text = trailing,
            style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantActions)
          )
        }
      }
    }
  }
}

@Composable
fun MiuixEmptyStateCard(title: String, subtitle: String) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    cornerRadius = 16.dp,
    insideMargin = PaddingValues(20.dp),
    colors = CardDefaults.defaultColors(
      color = MiuixTheme.colorScheme.surfaceContainer
    )
  ) {
    Text(
      text = title,
      style = MiuixTheme.textStyles.headline2.copy(
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onSurface
      )
    )
    Spacer(Modifier.height(4.dp))
    Text(
      text = subtitle,
      style = MiuixTheme.textStyles.footnote1.copy(
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
      )
    )
  }
}

@Composable
private fun MiuixEmptyActionCard(title: String, subtitle: String, action: String, onAction: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    cornerRadius = 16.dp,
    insideMargin = PaddingValues(20.dp),
    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
  ) {
    Text(
      text = title,
      style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
    )
    Spacer(Modifier.height(4.dp))
    Text(
      text = subtitle,
      style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    )
    Spacer(Modifier.height(14.dp))
    Button(
      onClick = onAction,
      cornerRadius = 18.dp,
      colors = ButtonDefaults.buttonColorsPrimary()
    ) {
      Text(action)
    }
  }
}

/* ---------------------------- 活动中心 ---------------------------- */

/**
 * Coding agent 活动中心：需要处理（授权 / 提问就地回复）、正在运行、最近完成。
 * 用于快速处理 Permission / Question 与查看跨项目任务状态，不是统计 Dashboard。
 */
@Composable
fun ActivityScreen(
  state: LagoonState,
  controller: LagoonController,
  scrollBehavior: ScrollBehavior,
  onOpen: (String) -> Unit
) {
  var refreshing by remember { mutableStateOf(false) }
  val refreshScope = rememberCoroutineScope()
  val permissions = state.permissions
  val questions = state.questions
  val now by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(1000); value = System.currentTimeMillis() } }
  val active = state.activeRootTasks
  val running = state.sessions.filter { session -> session.parentId == null && active[session.id]?.phase in TaskState.RUNNING_PHASES }
  // Results come from the unread ledger: unread ones carry “已完成 / 失败”, opened ones are history.
  val finished = state.notices.groupBy { it.sessionId }.mapNotNull { (id, notices) ->
    val session = state.sessions.firstOrNull { it.id == id && it.parentId == null } ?: return@mapNotNull null
    val latest = notices.maxBy { it.time }
    val unseen = notices.unseenFor(id)
    val phase = when { id in active -> null; unseen.any { it.error } -> TaskPhase.FAILED; unseen.isNotEmpty() -> TaskPhase.COMPLETED; else -> null }
    Triple(session, latest.time, phase?.let { TaskState(id, it, since = latest.time, finishedAt = latest.time) })
  }.sortedByDescending { it.second }.take(12)

  PullToRefresh(isRefreshing = refreshing, onRefresh = { refreshing = true; refreshScope.launch { controller.reload().join(); refreshing = false } },
    topAppBarScrollBehavior = scrollBehavior, refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "已刷新")) {
  LazyColumn(
    modifier = Modifier.fillMaxSize().overScrollVertical(),
    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 120.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    if (permissions.isEmpty() && questions.isEmpty() && running.isEmpty() && finished.isEmpty()) {
      item { MiuixEmptyStateCard("暂无活动", "需要处理的授权 / 提问、运行中的任务和最近结果会显示在这里。") }
    }

    if (permissions.isNotEmpty() || questions.isNotEmpty()) {
      item { MiuixSectionHeader("需要处理", permissions.size + questions.size) }
      items(permissions, key = { "activity-permission-${it.id}" }) { request ->
        Column {
          MiuixSessionAnchor(state, request.sessionId, onOpen)
          MiuixPermissionCard(request, controller, state.supportsSavedPermissions)
        }
      }
      items(questions, key = { "activity-question-${it.id}" }) { request ->
        Column {
          MiuixSessionAnchor(state, request.sessionId, onOpen)
          MiuixQuestionCard(request, controller)
        }
      }
    }

    if (running.isNotEmpty()) {
      item { MiuixSectionHeader("正在运行", running.size) }
      items(running, key = { "activity-running-${it.id}" }) { session ->
        MiuixSessionRow(
          session = session.copy(title = state.title(session)),
          task = active[session.id],
          meta = buildMeta(state, session, includeServer = true),
          onClick = { onOpen(session.id) },
          trailing = active[session.id]?.let { formatElapsed(it.since, now) } ?: formatRelative(session.updated)
        )
      }
    }

    if (finished.isNotEmpty()) {
      item { MiuixSectionHeader("最近完成", finished.size) }
      items(finished, key = { "activity-done-${it.first.id}" }) { (session, time, task) ->
        MiuixSessionRow(
          session = session.copy(title = state.title(session)),
          task = task,
          meta = buildMeta(state, session, includeServer = true),
          onClick = { onOpen(session.id) },
          trailing = formatRelative(time)
        )
      }
    }
  }
  }
}

/** 待处理请求所属会话的轻量锚点：点按进入会话上下文。 */
@Composable
private fun MiuixSessionAnchor(state: LagoonState, sessionId: String, onOpen: (String) -> Unit) {
  val session = state.sessions.firstOrNull { it.id == sessionId } ?: return
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = androidx.compose.foundation.LocalIndication.current,
        onClick = { onOpen(sessionId) }
      )
      .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = MiuixIcons.VerticalSplit,
      contentDescription = null,
      tint = MiuixTheme.colorScheme.primary,
      modifier = Modifier.size(14.dp)
    )
    Spacer(Modifier.width(6.dp))
    Text(
      text = state.title(session),
      style = MiuixTheme.textStyles.footnote1.copy(
        color = MiuixTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium
      ),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f)
    )
    Text(
      text = "进入会话 ›",
      style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.primary)
    )
  }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
  Card(
    modifier = Modifier.padding(end = 6.dp),
    pressFeedbackType = PressFeedbackType.Sink,
    showIndication = true,
    cornerRadius = 18.dp,
    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
    colors = CardDefaults.defaultColors(
      color = if (selected) MiuixColorTokens.PrimarySubtle else MiuixTheme.colorScheme.secondaryContainer
    ),
    onClick = onClick
  ) {
    Text(
      text = label,
      style = MiuixTheme.textStyles.footnote1.copy(
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
      )
    )
  }
}

@Composable
fun ServersModal(
  state: LagoonState,
  controller: LagoonController,
  onDismiss: () -> Unit,
  onConnected: () -> Unit
) {
  var editing by remember { mutableStateOf<ServerProfile?>(null) }
  var showForm by remember { mutableStateOf(state.profiles.isEmpty()) }
  var deleting by remember { mutableStateOf<ServerProfile?>(null) }
  val scope = rememberCoroutineScope()

  SuperBottomSheet(
    title = if (showForm) (if (editing == null) "添加新服务器" else "编辑服务器") else "服务器连接管理",
    show = true,
    onDismissRequest = onDismiss
  ) {
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
      if (showForm) {
        MiuixServerForm(
          existing = editing,
          onCancel = {
            if (state.profiles.isNotEmpty()) {
              showForm = false
              editing = null
            } else {
              onDismiss()
            }
          },
          onSave = { profile, password, done ->
            scope.launch {
              try {
                val pair = PairLinkResolver.isPairLink(profile.url)
                val resolved = if (pair) PairLinkResolver.resolve(profile.url) else null
                val actualProfile = if (resolved == null) profile else profile.copy(url = resolved.serverUrl, username = resolved.credentials.username)
                val savedUrl = state.profiles.firstOrNull { it.id == profile.id }?.url
                val credentials = resolved?.credentials ?: profileCredentials(
                  savedUrl, actualProfile.url, controller.credentials(profile.id), actualProfile.username, password
                )
                val version = controller.testServer(actualProfile, credentials)
                controller.saveServer(actualProfile, credentials.password, credentials.cookie, credentials.username)
                done("已连接 OpenCode $version")
                showForm = false
                editing = null
                onConnected()
              } catch (error: Exception) {
                done(error.message ?: "连接失败")
              }
            }
          }
        )
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().overScrollVertical(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          item {
            TextButton(text = "添加新服务器", onClick = { editing = null; showForm = true }, modifier = Modifier.fillMaxWidth(),
              colors = ButtonDefaults.textButtonColorsPrimary())
          }
          items(state.profiles, key = { it.id }) { profile ->
            Card(insideMargin = PaddingValues(16.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                  modifier = Modifier
                    .size(40.dp)
                    .background(MiuixTheme.colorScheme.primaryVariant.copy(alpha = 0.15f), miuixSquircleShape(10.dp)),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(
                    imageVector = MiuixIcons.Sort,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                  )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                  Text(profile.name, style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold))
                  Text(
                    profile.url,
                    style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                if (profile.id == state.serverId) {
                  MiuixStatePill(if (state.connected) TaskPhase.COMPLETED else TaskPhase.DISCONNECTED, if (state.connected) "活跃" else "离线")
                }
              }
              Spacer(Modifier.height(12.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "连接", onClick = { controller.connect(profile.id); onConnected() },
                  enabled = profile.id != state.serverId || !state.connected, modifier = Modifier.weight(1f),
                  colors = ButtonDefaults.textButtonColorsPrimary())
                TextButton(text = "编辑", onClick = { editing = profile; showForm = true }, modifier = Modifier.weight(1f))
                TextButton(text = "删除", onClick = { deleting = profile }, modifier = Modifier.weight(1f),
                  colors = ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error))
              }
            }
          }
        }
      }
    }
  }

  deleting?.let { profile ->
    SuperDialog(
      title = "移除 ${profile.name}？",
      show = deleting != null,
      onDismissRequest = { deleting = null }
    ) {
      Column(Modifier.padding(top = 8.dp)) {
        Text(
          "仅从此设备删除连接记录和存储凭据，不会对远程服务器数据产生任何影响。",
          style = MiuixTheme.textStyles.body1
        )
        DialogActions("取消", { deleting = null }, "确认删除", danger = true) {
          controller.deleteServer(profile.id)
          deleting = null
        }
      }
    }
  }
}

@Composable
private fun MiuixServerForm(
  existing: ServerProfile?,
  onCancel: () -> Unit,
  onSave: (ServerProfile, String?, (String) -> Unit) -> Unit
) {
  val clipboard = LocalClipboardManager.current
  val id = remember(existing?.id) { existing?.id ?: UUID.randomUUID().toString() }
  var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
  var url by remember(existing?.id) { mutableStateOf(existing?.url ?: "") }
  var username by rememberSaveable(existing?.id) { mutableStateOf(existing?.username ?: "opencode") }
  var password by remember(existing?.id) { mutableStateOf("") }
  var autoConnect by rememberSaveable(existing?.id) { mutableStateOf(existing?.autoConnect ?: true) }
  var notifications by rememberSaveable(existing?.id) { mutableStateOf(existing?.notifications ?: true) }
  var allowHttp by rememberSaveable(existing?.id) { mutableStateOf(existing?.allowCleartext == true) }
  var showAdvanced by remember { mutableStateOf(existing?.allowCleartext == true) }
  var result by remember { mutableStateOf("") }
  var working by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    if (existing == null && url.isBlank()) {
      val clipText = clipboard.getText()?.text?.trim().orEmpty()
      if (clipText.isNotBlank() && (PairLinkResolver.isPairLink(clipText) || clipText.startsWith("http://") || clipText.startsWith("https://"))) {
        url = clipText
        if (name.isBlank()) name = "我的服务器"
      }
    }
  }

  val normalizedInput = url.trim()
  val isPair = PairLinkResolver.isPairLink(normalizedInput)

  LazyColumn(
    modifier = Modifier.fillMaxSize().overScrollVertical(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Card(
        colors = CardDefaults.defaultColors(color = MiuixColorTokens.PrimarySubtle),
        insideMargin = PaddingValues(12.dp)
      ) {
        Text(
          "💡 推荐在电脑端运行 opencode pair 并将配对链接直接粘贴到下方，即可免密码一键配置认证。",
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.primary)
        )
      }
    }

    item {
      TextField(
        value = name,
        onValueChange = { text: String -> name = text },
        label = "服务器名称标识",
        modifier = Modifier.fillMaxWidth()
      )
    }

    item {
      Column(Modifier.fillMaxWidth()) {
        TextField(
          value = url,
          onValueChange = { text: String ->
            url = text
            if (name.isBlank() && text.isNotBlank()) name = "我的 OpenCode"
          },
          label = if (url.isBlank()) "http://192.168.1.x:4096 或配对链接" else "服务器地址 或 配对链接",
          useLabelAsPlaceholder = url.isBlank(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          modifier = Modifier.fillMaxWidth()
        )
        Text(
          text = "示例：http://192.168.1.100:4096 或 https://oc.example.com。手机无法通过 localhost 访问电脑，请填电脑的局域网 IP 或直接粘贴 opencode pair 配对链接。",
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
          modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp)
        )
      }
    }

    if (normalizedInput.startsWith("http://", true)) item {
      SuperSwitch(title = "允许此地址使用 HTTP", summary = "请仅在你信任的网络中开启", checked = allowHttp, onCheckedChange = { allowHttp = it })
    }
    if (!isPair) {
      item {
        TextField(
          value = username,
          onValueChange = { text: String -> username = text },
          label = "用户名 (Basic Auth)",
          modifier = Modifier.fillMaxWidth()
        )
      }
      item {
        TextField(
          value = password,
          onValueChange = { text: String -> password = text },
          label = if (existing == null) "访问密码（无认证服务器可留空）" else "新密码（留空保持不变）",
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
      }
    }

    item {
      SuperArrow(
        title = "高级与网络设置",
        summary = if (showAdvanced) "收起额外配置" else "连接与通知行为",
        onClick = { showAdvanced = !showAdvanced }
      )
    }

    if (showAdvanced) {
      item {
        SuperSwitch(
          title = "自动连接此服务器",
          checked = autoConnect,
          onCheckedChange = { autoConnect = it }
        )
      }
      item {
        SuperSwitch(
          title = "接收任务完成与等待通知",
          checked = notifications,
          onCheckedChange = { notifications = it }
        )
      }
    }

    if (result.isNotBlank()) {
      item {
        Text(
          text = result,
          color = if (result.startsWith("已连接")) MiuixColorTokens.Success else MiuixColorTokens.Error,
          style = MiuixTheme.textStyles.body2
        )
      }
    }

    item {
      DialogActions(
        "取消", onCancel, if (working) "正在测试…" else "保存并连接",
        enabled = !working && name.isNotBlank() &&
          (normalizedInput.startsWith("https://", true) || (normalizedInput.startsWith("http://", true) && allowHttp))
      ) {
        working = true
        val normalizedUrl = normalizedInput.trimEnd('/')
        val profile = (existing ?: ServerProfile(id, "", "")).copy(
          name = name.trim().ifBlank { "OpenCode" }, url = normalizedUrl, username = username.trim().ifBlank { "opencode" }, autoConnect = autoConnect, notifications = notifications,
          allowCleartext = normalizedUrl.startsWith("http://", true) && allowHttp
        )
        onSave(profile, password.takeIf { it.isNotBlank() || existing == null }) { message ->
          result = message
          working = false
        }
      }
    }
  }
}

@Composable
fun SettingsScreen(
  state: LagoonState,
  controller: LagoonController,
  themeMode: ThemeMode,
  onTheme: (ThemeMode) -> Unit,
  previewBack: Boolean,
  onPreviewBack: (Boolean) -> Unit,
  onNotifications: () -> Unit,
  onManageServers: () -> Unit,
  scrollBehavior: ScrollBehavior
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  // 各厂商灵动岛 / 标准通道的可用状态。检测会读取系统设置与通知服务，放到 IO 线程执行。
  var islandSupport by remember { mutableStateOf<List<IslandSupport>?>(null) }
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  fun refreshSupport() { scope.launch { islandSupport = withContext(Dispatchers.IO) { IslandRegistry.diagnostics(context, state.server) } } }
  DisposableEffect(lifecycleOwner, state.serverId) {
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshSupport() }
    lifecycleOwner.lifecycle.addObserver(observer); refreshSupport()
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
  val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
  val clipboard = LocalClipboardManager.current
  var crashReport by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) { crashReport = withContext(Dispatchers.IO) { CrashLog.read(context) } }


  var showModels by remember { mutableStateOf(false) }
  var showSaved by remember { mutableStateOf(false) }
  val permissionProject = state.session?.let { resolveSessionProject(it, state.projects) } ?: state.project

  // MIUIX settings layout: 12dp card margin + SmallTitle's 28dp inset aligns titles with row content.
  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .overScrollVertical()
      .nestedScroll(scrollBehavior.nestedScrollConnection),
    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 120.dp)
  ) {
    item {
      SmallTitle("网络与连接")
      Card(Modifier.fillMaxWidth()) {
        SuperArrow(
          title = state.server?.name ?: "未连接服务器",
          summary = if (state.connected) "OpenCode ${state.version.ifBlank { "V2" }} · 正常通信中" else "离线或尚未连接 · 点按管理服务器",
          startAction = {
            StatusDot(if (state.connected) MiuixColorTokens.Success else MiuixTheme.colorScheme.onSurfaceVariantSummary, Modifier.padding(end = 12.dp))
          },
          onClick = onManageServers
        )
      }
    }

    item {
      SmallTitle("显示与交互")
      Card(Modifier.fillMaxWidth()) {
        SuperDropdown(
          title = "深浅色外观",
          items = ThemeMode.entries.map { it.label },
          selectedIndex = ThemeMode.entries.indexOf(themeMode),
          onSelectedIndexChange = { onTheme(ThemeMode.entries[it]) }
        )
        SuperSwitch(
          title = "返回手势预览",
          summary = "拖动返回手势时轻微预览上一级页面",
          checked = previewBack,
          onCheckedChange = onPreviewBack
        )
      }
    }

    item {
      SmallTitle("模型与权限")
      Card(Modifier.fillMaxWidth()) {
        SuperArrow(
          title = "管理模型",
          summary = if (state.modelCatalog.isEmpty()) "连接服务器后可选择显示哪些模型" else "显示 ${state.visibleModels.size} / ${state.modelCatalog.size} 个模型",
          enabled = state.modelCatalog.isNotEmpty(),
          onClick = { showModels = true }
        )
        if (state.supportsSavedPermissions) SuperArrow(
          title = "已保存的项目权限",
          summary = permissionProject?.let { "当前项目：${it.name}" } ?: "先在会话页选择项目",
          enabled = permissionProject != null && state.connected,
          onClick = { showSaved = true; controller.loadSavedPermissions() }
        )
      }
    }

    item {
      SmallTitle("超级岛与通知")
      Card(Modifier.fillMaxWidth()) {
        SuperArrow(
          title = "通知权限",
          summary = "有任务运行或等你处理时，以系统「实时更新」显示运行中 / 已完成 / 待回复 / 失败计数（状态栏胶囊、澎湃 OS 超级岛、ColorOS 流体云），外观与配色由系统决定",
          onClick = onNotifications
        )
        val list = islandSupport
        if (list == null) {
          BasicComponent(title = "实时更新通道", summary = "正在检测本机实时更新能力…")
        } else {
          list.forEach { item ->
            BasicComponent(
              title = item.label,
              summary = item.note,
              endActions = {
                Text(
                  when {
                    !item.supported -> "不支持"
                    item.granted -> "已就绪"
                    else -> "待授权"
                  },
                  style = MiuixTheme.textStyles.footnote1.copy(
                    color = when {
                      !item.supported -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                      item.granted -> MiuixColorTokens.Success
                      else -> MiuixColorTokens.Warning
                    }
                  )
                )
              }
            )
          }
          if (list.any { it.vendor == "android" && it.supported && !it.granted }) SuperArrow(
            title = "开启实时更新权限",
            summary = "Android 16 实时更新需要单独授权",
            onClick = { TaskNotifications(context).promotedNotificationSettingsIntent()?.let { runCatching { context.startActivity(it) } } }
          )
          BasicComponent(summary = "荣耀灵动胶囊、ColorOS 15 流体云暂未完成接入，当前不可用。")
        }
      }
    }

    item {
      SmallTitle("关于与诊断")
      Card(Modifier.fillMaxWidth()) {
        BasicComponent(
          title = "OpenCode Lagoon ${packageInfo.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()})",
          summary = "OpenCode ${state.version.ifBlank { "未知版本" }} · ${if (state.connected) "可连接" else "未连接"}\n实时同步：${if (state.streamConnected) "正常" else "恢复中"} · ${if (state.degraded || state.cached) "数据待更新" else "数据已同步"}"
        )
        SuperArrow(
          title = "复制诊断信息",
          summary = "版本、系统与连接状态，不含凭据",
          onClick = {
            clipboard.setText(AnnotatedString("OpenCode Lagoon ${packageInfo.versionName}\nAndroid ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}\n${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nserver=${state.version}, api=${state.connected}, stream=${state.streamConnected}, stale=${state.degraded}, cached=${state.cached}\ncapabilitiesDocumented=${state.capabilities.documented}, sessions=${state.sessions.size}, messages=${state.messages.size}"))
          }
        )
        crashReport?.let { report ->
          BasicComponent(title = "上次崩溃", summary = "${report.lineSequence().firstOrNull().orEmpty()}\n${CrashLog.headline(report)}")
          SuperArrow(title = "复制崩溃日志", onClick = { clipboard.setText(AnnotatedString(report)) })
          SuperArrow(title = "清除崩溃记录", onClick = { CrashLog.clear(context); crashReport = null })
        }
      }
    }
  }

  if (showModels) ManageModelsSheet(state, controller) { showModels = false }
  if (showSaved) SuperBottomSheet(title = "已保存的项目权限", show = true, onDismissRequest = { showSaved = false; controller.closeSavedPermissions() }) {
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(bottom = 16.dp)) {
      permissionProject?.let { Text("项目：${it.name}", Modifier.padding(bottom = 8.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)) }
      ResourceHint(state.resource("saved"), "没有已保存的权限规则", retry = { controller.loadSavedPermissions() })
      LazyColumn {
        items(state.savedPermissions.orEmpty().distinctBy { it.id }, key = { it.id }) { rule ->
          BasicComponent(title = rule.action, summary = rule.resource, endActions = {
            TextButton(text = "撤销", enabled = !state.pending("revoke:${rule.id}"), onClick = { controller.revokeSavedPermission(rule) },
              colors = ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error))
          })
        }
      }
    }
  }
}
