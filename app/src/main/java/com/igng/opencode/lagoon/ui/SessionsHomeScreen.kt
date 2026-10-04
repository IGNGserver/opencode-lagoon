package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** “已完成” = results this device saw finish and the user has not opened yet. */
private enum class HomeFilter(val label: String) { ALL("全部"), ATTENTION("待处理"), RUNNING("运行中"), UNSEEN("已完成") }

/**
 * 会话首页。顶栏大标题（MainActivity）就是范围选择：「全部会话」默认按最近活动平铺所有项目的主会话、
 * 每行带项目名；选中某个项目只看它。状态与未读语义沿用 docs/HOME_REDESIGN.md（未读账本、父链汇总）。
 */
@Composable
fun SessionsHomeScreen(state: LagoonState, controller: LagoonController, onOpen: (String) -> Unit,
  onServers: () -> Unit, onNewSession: () -> Unit, scrollBehavior: ScrollBehavior) {
  var query by rememberSaveable(state.serverId) { mutableStateOf("") }
  var filter by rememberSaveable(state.serverId) { mutableStateOf(HomeFilter.ALL) }
  var refreshing by remember { mutableStateOf(false) }
  val refreshScope = rememberCoroutineScope()
  val list = rememberLazyListState()
  val now by produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(60_000); value = System.currentTimeMillis() } }
  val active = remember(state.tasks, state.parents) { state.activeRootTasks }
  val unseen = remember(state.notices) { state.notices.filterNot { it.viewed }.map { it.sessionId }.toSet() }
  val statuses = remember(state.sessions, state.notices, active) { state.sessions.associate { it.id to state.sessionStatus(it, active) } }
  val allScope = state.scopeProjectId == null
  val shown = HomeScope.roots(state.sessions, state.projects, state.scopeProjectId).filter { session ->
    val status = statuses[session.id]
    val matches = when (filter) {
      HomeFilter.ALL -> true
      HomeFilter.ATTENTION -> status in setOf(SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION)
      HomeFilter.UNSEEN -> session.id in unseen
      HomeFilter.RUNNING -> status == SessionStatus.RUNNING
    }
    matches && (query.isBlank() || session.displayTitle().contains(query, true) ||
      resolveSessionProject(session, state.projects)?.name?.contains(query, true) == true || session.directory.contains(query, true))
  }
  val searchingOrFiltered = query.isNotBlank() || filter != HomeFilter.ALL
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary

  PullToRefresh(isRefreshing = refreshing, onRefresh = { refreshing = true; refreshScope.launch { controller.reload().join(); refreshing = false } },
    topAppBarScrollBehavior = scrollBehavior, refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "已刷新")) {
    LazyColumn(Modifier.fillMaxSize().overScrollVertical(), state = list, contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp)) {
      item(key = "search") {
        TextField(query, { query = it }, label = if (allScope) "搜索标题或项目" else "搜索此项目的会话", useLabelAsPlaceholder = true,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
      }
      item(key = "filters") {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          HomeFilter.entries.forEach { option -> HomeChip(option.label, filter == option) { filter = option } }
        }
      }
      if (state.cached || state.degraded || !state.connected && !state.loading) item(key = "sync") {
        Text(when { state.cached -> "离线缓存 · 下拉重试"; state.degraded -> "部分数据待同步 · 下拉重试"; else -> "尚未连接" },
          style = MiuixTheme.textStyles.footnote2, color = MiuixColorTokens.Warning,
          modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { controller.reload() }.padding(horizontal = 20.dp, vertical = 8.dp))
      }
      items(shown, key = { "session:${it.id}" }, contentType = { "session" }) { session ->
        HomeSessionRow(session, resolveSessionProject(session, state.projects)?.name?.takeIf { allScope }, statuses[session.id] ?: SessionStatus.NONE,
          session.id in unseen, state.notices.any { it.sessionId == session.id && !it.viewed && it.error }, now) { onOpen(session.id) }
      }
      if (shown.isEmpty()) item(key = "empty") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(when { state.loading -> "正在读取会话…"; searchingOrFiltered -> "没有符合条件的会话"; state.connected -> "暂无会话"; else -> "连接 OpenCode" }, color = muted)
          if (!state.loading) TextButton(text = if (searchingOrFiltered) "显示全部" else if (state.connected) "新建会话" else "添加服务器",
            onClick = { if (searchingOrFiltered) { query = ""; filter = HomeFilter.ALL } else if (state.connected) onNewSession() else onServers() })
        }
      }
      if (state.sessionCursors.isNotEmpty()) item(key = "more") {
        TextButton(text = if (state.pending("sessions-more")) "正在加载…" else "加载更多会话", enabled = !state.pending("sessions-more"),
          onClick = { controller.loadMoreSessions() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
      }
      else if (!state.catalogComplete) item(key = "cached-limit") {
        Text("缓存仅保留最近会话", modifier = Modifier.padding(horizontal = 20.dp), color = muted, style = MiuixTheme.textStyles.footnote2)
      }
    }
  }
}

@Composable
private fun HomeChip(label: String, selected: Boolean, onClick: () -> Unit) {
  Text(label, modifier = Modifier.clip(CircleShape).background(if (selected) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.surfaceContainer)
    .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    color = if (selected) MiuixTheme.colorScheme.surface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
    fontSize = 14.sp, maxLines = 1)
}

/** One session: status dot, title, project (in 全部), and the status label / relative time. */
@Composable
private fun HomeSessionRow(session: Session, project: String?, status: SessionStatus, unseen: Boolean, unseenError: Boolean, now: Long, onClick: () -> Unit) {
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  val color = when {
    status in setOf(SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION) -> MiuixColorTokens.Warning
    status == SessionStatus.RUNNING -> MiuixTheme.colorScheme.primary
    unseenError || status == SessionStatus.FAILED -> MiuixColorTokens.Error
    unseen -> MiuixTheme.colorScheme.primary
    else -> muted.copy(alpha = 0.25f)
  }
  Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
    .padding(start = 24.dp, end = 20.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(6.dp).background(color, CircleShape))
    Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f)) {
      Text(session.displayTitle(), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (project != null) Text(project, color = muted, style = MiuixTheme.textStyles.footnote2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.width(10.dp))
    Column(horizontalAlignment = Alignment.End) {
      if (status.label.isNotEmpty()) Text(status.label, color = color, fontSize = 11.sp, maxLines = 1)
      Text(formatRelative(session.activityAt, now), color = muted.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1)
    }
  }
}
