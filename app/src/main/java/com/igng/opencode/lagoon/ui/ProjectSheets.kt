package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The 会话 title's scope picker: 全部会话 (default) or one project. Projects come only from the
 * OpenCode server — this client never adds a folder of its own; choosing one only filters the home list.
 */
@Composable
internal fun ProjectScopeSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  var query by rememberSaveable { mutableStateOf("") }
  val counts = HomeScope.counts(state.sessions, state.projects)
  val projects = HomeScope.byActivity(state.sessions, state.projects).filter {
    query.isBlank() || it.name.contains(query.trim(), true) || it.directory.contains(query.trim(), true)
  }
  SuperBottomSheet(title = "选择项目", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      if (state.projects.size > 8) item {
        TextField(query, { query = it }, label = "搜索项目", useLabelAsPlaceholder = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
      }
      item {
        Card(Modifier.fillMaxWidth()) {
          ChoiceRow("全部会话", "${state.sessions.count { it.parentId == null }} 个会话", state.scopeProjectId == null) { controller.setScope(null); onDismiss() }
        }
      }
      item { SmallTitle("项目", insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) }
      if (projects.isEmpty()) item {
        Text(if (state.projects.isEmpty()) "服务器还没有已创建的项目。请先在电脑端用 OpenCode 打开一个目录，再下拉刷新。" else "没有匹配的项目",
          Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else item {
        Card(Modifier.fillMaxWidth()) {
          projects.forEach { project ->
            ChoiceRow(project.name, "${HomeScope.displayPath(project.directory, null)} · ${counts[project.id] ?: 0} 个会话", state.scopeProjectId == project.id) {
              controller.setScope(project.id); onDismiss()
            }
          }
        }
      }
    }
  }
}

/** Lightweight project chooser for a draft: picks where the new session starts, without changing the home scope. */
@Composable
internal fun DraftTargetSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  SuperBottomSheet(title = "在哪个项目里新建", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      item {
        if (state.projects.isEmpty()) {
          Text("服务器还没有已创建的项目。请先在电脑端用 OpenCode 打开一个目录，再下拉刷新。",
            Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        } else Card(Modifier.fillMaxWidth()) {
          HomeScope.byActivity(state.sessions, state.projects).forEach { project ->
            ChoiceRow(project.name, HomeScope.displayPath(project.directory, null), state.projectId == project.id) {
              if (project.id != state.projectId) controller.selectProject(project.id); onDismiss()
            }
          }
        }
      }
    }
  }
}
