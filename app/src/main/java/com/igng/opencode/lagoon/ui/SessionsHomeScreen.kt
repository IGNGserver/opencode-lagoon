package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.lagoon.core.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** “已完成” = results this device saw finish and the user has not opened yet. */
private enum class HomeFilter(val label: String) { ALL("全部"), ATTENTION("待处理"), RUNNING("运行中"), UNSEEN("已完成") }

/** Current server's projects. Chat/project selection never narrows or resets the home catalog. */
@Composable
fun SessionsHomeScreen(state: LagoonState, controller: LagoonController, onOpen: (String) -> Unit,
  onServers: () -> Unit, onNewSession: () -> Unit, onProjects: () -> Unit) {
  var query by rememberSaveable(state.serverId) { mutableStateOf("") }
  var filter by rememberSaveable(state.serverId) { mutableStateOf(HomeFilter.ALL) }
  var searching by rememberSaveable(state.serverId) { mutableStateOf(false) }
  var menu by remember { mutableStateOf(false) }
  val list = rememberLazyListState()
  val now by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(60_000); value = System.currentTimeMillis() } }
  val active = remember(state.tasks, state.parents) { state.activeRootTasks }
  val unseen = remember(state.notices) { state.notices.filterNot { it.viewed }.map { it.sessionId }.toSet() }
  val statuses = remember(state.sessions, state.notices, active) { state.sessions.associate { it.id to state.sessionStatus(it, active) } }
  val roots = state.sessions.filter { it.visibleOnHome }
  val groups = groupSessions(roots.filter { session ->
    val status = statuses[session.id]
    val matches = when (filter) {
      HomeFilter.ALL -> true
      HomeFilter.ATTENTION -> status in setOf(SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION)
      HomeFilter.UNSEEN -> session.id in unseen
      HomeFilter.RUNNING -> status == SessionStatus.RUNNING
    }
    matches && (query.isBlank() || session.displayTitle().contains(query, true) ||
      resolveSessionProject(session, state.projects)?.name?.contains(query, true) == true || session.directory.contains(query, true))
  }, state.projects)
  val searchingOrFiltered = query.isNotBlank() || filter != HomeFilter.ALL
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
      Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "筛选会话") { menu = true }, verticalAlignment = Alignment.CenterVertically) {
          Icon(MiuixIcons.Tasks, null, Modifier.size(26.dp))
          Spacer(Modifier.width(10.dp))
          Text(if (filter == HomeFilter.ALL) "全部会话" else filter.label, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
          Text("⌄", modifier = Modifier.padding(start = 8.dp), fontSize = 22.sp)
        }
        IconButton(onClick = { menu = true }) { Icon(MiuixIcons.More, "首页操作") }
      }
      if (state.profiles.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.profiles.forEach { profile -> HomeChip(profile.name, profile.id == state.serverId) { if (profile.id != state.serverId) controller.connect(profile.id) } }
      }
      if (searching) TextField(query, { query = it }, label = "搜索标题或项目", useLabelAsPlaceholder = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
      if (state.cached || state.degraded || !state.connected && !state.loading) {
        Text(when { state.cached -> "离线缓存"; state.degraded -> "部分数据待同步"; else -> "尚未连接" },
          style = MiuixTheme.textStyles.footnote2, color = MiuixColorTokens.Warning,
          modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { controller.reload() }.padding(horizontal = 20.dp, vertical = 8.dp))
      }
      LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(top = 6.dp, bottom = 92.dp)) {
        groups.forEach { group ->
          val expanded = group.key !in state.collapsedProjects || searchingOrFiltered
          item(key = "project:${group.key}", contentType = "project") {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
              .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
              .clickable(role = Role.Button, enabled = !searchingOrFiltered, onClickLabel = if (expanded) "折叠项目" else "展开项目") { controller.toggleProjectGroup(group.key) }
              .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
              Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(MiuixIcons.Folder, null, Modifier.size(20.dp), tint = muted)
                Spacer(Modifier.width(8.dp))
                Text(group.name, color = muted, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(if (expanded) "⌄" else "›", modifier = Modifier.padding(start = 10.dp), color = muted, fontSize = 19.sp)
              }
              Spacer(Modifier.width(12.dp))
              Text(group.sessions.size.toString(), style = MiuixTheme.textStyles.footnote2, color = muted.copy(alpha = 0.65f))
            }
          }
          if (expanded) items(group.sessions, key = { "session:${it.id}" }, contentType = { "session" }) { session ->
            ProjectSessionRow(session, statuses[session.id] ?: SessionStatus.NONE,
              session.id in unseen, state.notices.any { it.sessionId == session.id && !it.viewed && it.error }, now) { onOpen(session.id) }
          }
        }
        if (groups.isEmpty()) item(key = "empty") {
          Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(when { state.loading -> "正在读取会话…"; searchingOrFiltered -> "没有符合条件的会话"; state.connected -> "暂无会话"; else -> "连接 OpenCode" }, color = muted)
            if (!state.loading) TextButton(text = if (searchingOrFiltered) "显示全部" else if (state.connected) "新建会话" else "添加服务器",
              onClick = { if (searchingOrFiltered) { query = ""; filter = HomeFilter.ALL } else if (state.connected) onNewSession() else onServers() })
          }
        }
        if (state.sessionCursors.isNotEmpty()) item(key = "more") {
          TextButton(text = if (state.pending("sessions-more")) "正在加载…" else "加载更多会话", enabled = !state.pending("sessions-more"),
            onClick = { controller.loadMoreSessions() }, modifier = Modifier.fillMaxWidth())
        }
        else if (!state.catalogComplete) item(key = "cached-limit") {
          Text("缓存仅保留最近会话", modifier = Modifier.padding(horizontal = 20.dp), color = muted, style = MiuixTheme.textStyles.footnote2)
        }
      }
    }
    if (state.connected) Box(Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 16.dp).size(56.dp)
      .shadow(8.dp, CircleShape).clip(CircleShape).background(MiuixTheme.colorScheme.onSurface)
      .clickable(role = Role.Button, onClickLabel = "新建会话", onClick = onNewSession), contentAlignment = Alignment.Center) {
      Icon(MiuixIcons.Add, "新建会话", Modifier.size(28.dp), tint = MiuixTheme.colorScheme.surface)
    }
    // This screen is composed inside the root Scaffold, which owns the MIUIX popup registry.
    if (menu) SuperBottomSheet(title = "会话", show = true, onDismissRequest = { menu = false }) {
      Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          HomeFilter.entries.forEach { option -> HomeChip(option.label, filter == option) { filter = option; menu = false } }
        }
        TextButton(text = if (searching) "收起搜索" else "搜索会话", onClick = { searching = !searching; if (!searching) query = ""; menu = false }, modifier = Modifier.fillMaxWidth())
        TextButton(text = "刷新", onClick = { controller.reload(); menu = false }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth()) {
          TextButton(text = "展开全部", onClick = { controller.collapseProjectGroups(false); menu = false }, modifier = Modifier.weight(1f))
          TextButton(text = "折叠全部", onClick = { controller.collapseProjectGroups(true); menu = false }, modifier = Modifier.weight(1f))
        }
        TextButton(text = "管理项目 / 目录", onClick = { menu = false; onProjects() }, modifier = Modifier.fillMaxWidth())
        TextButton(text = "管理服务器", onClick = { menu = false; onServers() }, modifier = Modifier.fillMaxWidth())
      }
    }
  }
}

@Composable
private fun HomeChip(label: String, selected: Boolean, onClick: () -> Unit) {
  Text(label, modifier = Modifier.clip(CircleShape).background(if (selected) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.surfaceContainer)
    .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp),
    color = if (selected) MiuixTheme.colorScheme.surface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
    fontSize = 14.sp, maxLines = 1)
}

@Composable
private fun ProjectSessionRow(session: Session, status: SessionStatus, unseen: Boolean, unseenError: Boolean, now: Long, onClick: () -> Unit) {
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  val color = when {
    status in setOf(SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION) -> MiuixColorTokens.Warning
    status == SessionStatus.RUNNING -> MiuixTheme.colorScheme.primary
    unseenError || status == SessionStatus.FAILED -> MiuixColorTokens.Error
    unseen -> MiuixTheme.colorScheme.primary
    else -> muted.copy(alpha = 0.25f)
  }
  // “已完成 / 失败” only exist while unread, so every non-empty status is worth a label.
  val label = status.label
  Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick)
    .padding(start = 28.dp, end = 20.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(6.dp).background(color, CircleShape))
    Spacer(Modifier.width(16.dp))
    Text(session.displayTitle(), modifier = Modifier.weight(1f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.width(10.dp))
    Column(horizontalAlignment = Alignment.End) {
      if (label.isNotEmpty()) Text(label, color = color, fontSize = 11.sp, maxLines = 1)
      Text(formatRelative(session.activityAt, now), color = muted.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1)
    }
  }
}
