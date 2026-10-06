package com.igng.opencode.lagoon.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The 会话 title's scope picker: 全部会话 (default) or one project. Projects are registered on this
 * device by browsing a remote server directory; the server's own project list is never auto-imported.
 */
@Composable
internal fun ProjectScopeSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  if (state.browsePath != null) { DirectoryBrowserSheet(state, controller); return }
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
        Text("尚未添加项目。点下面的「添加服务器目录」，浏览服务器并选一个目录加入。",
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
      item { Spacer(Modifier.height(12.dp)) }
      item {
        Card(Modifier.fillMaxWidth()) {
          ChoiceRow("添加服务器目录…", "浏览服务器目录并添加为项目", false) { controller.openDirectoryBrowser() }
        }
      }
    }
  }
}

/** Lightweight project chooser for a draft: picks where the new session starts, without changing the home scope. */
@Composable
internal fun DraftTargetSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit) {
  if (state.browsePath != null) { DirectoryBrowserSheet(state, controller); return }
  SuperBottomSheet(title = "在哪个项目里新建", show = true, onDismissRequest = onDismiss) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      item {
        if (state.projects.isEmpty()) {
          Text("尚未添加项目。点下面的「添加服务器目录」，浏览服务器并选一个目录加入。",
            Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        } else Card(Modifier.fillMaxWidth()) {
          HomeScope.byActivity(state.sessions, state.projects).forEach { project ->
            ChoiceRow(project.name, HomeScope.displayPath(project.directory, null), state.projectId == project.id) {
              if (project.id != state.projectId) controller.selectProject(project.id); onDismiss()
            }
          }
        }
      }
      item { Spacer(Modifier.height(12.dp)) }
      item {
        Card(Modifier.fillMaxWidth()) {
          ChoiceRow("添加服务器目录…", "浏览服务器目录并添加为项目", false) { controller.openDirectoryBrowser() }
        }
      }
    }
  }
}

/** Joins a remote directory with a listed entry; the server may return either a name or a full path. */
private fun childPath(parent: String, entry: String): String =
  if (entry.startsWith("/")) entry else parent.trimEnd('/') + "/" + entry.trimStart('/')

/**
 * Remote directory browser used to add a project. Directories come first, the current path is a
 * tappable breadcrumb, and search is scoped to the current directory. Only directories can be added.
 */
@Composable
internal fun DirectoryBrowserSheet(state: LagoonState, controller: LagoonController) {
  var query by rememberSaveable { mutableStateOf("") }
  val path = state.browsePath.orEmpty()
  val crumbs = remember(path) { if (path.isBlank()) emptyList() else HomeScope.crumbs(path) }
  val searching = query.trim().length >= 2
  val entries = remember(state.browseEntries) { state.browseEntries }
  SuperBottomSheet(title = "选择服务器目录", show = true, onDismissRequest = { controller.closeDirectoryBrowser() }) {
    Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { index, (label, target) ->
          if (index > 0) Text("/", Modifier.padding(horizontal = 4.dp), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          Text(label.ifBlank { "/" }, Modifier.clickable { controller.browseDirectory(target) },
            style = MiuixTheme.textStyles.footnote2.copy(color = if (index == crumbs.lastIndex) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
        if (crumbs.isEmpty()) Text("正在定位服务器目录…", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
      TextField(query, { query = it; controller.searchDirectories(it) }, label = "搜索目录或文件", useLabelAsPlaceholder = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
      ResourceHint(state.resource("browse"), "这个目录是空的", retry = { if (path.isNotBlank()) controller.browseDirectory(path) })
      if (searching) {
        if (state.browseSearch.isEmpty()) Text("没有匹配项", Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        else LazyColumn(Modifier.fillMaxWidth()) {
          items(state.browseSearch, key = { it }) { result ->
            val name = result.trimEnd('/').substringAfterLast('/')
            BasicComponent(title = name.ifBlank { result }, summary = result, onClick = { controller.browseDirectory(childPath(path, result)) })
          }
        }
      } else {
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
          if (path.isNotBlank() && path.trimEnd('/') != "") item(key = "..") {
            BasicComponent(title = "上级目录", summary = HomeScope.parent(path), onClick = { controller.browseDirectory(HomeScope.parent(path)) })
          }
          items(entries, key = { it.path }) { node ->
            val directory = node.type == "directory"
            if (directory) BasicComponent(title = node.path.trimEnd('/').substringAfterLast('/').ifBlank { node.path }, summary = "文件夹",
              onClick = { controller.browseDirectory(childPath(path, node.path)) })
            else BasicComponent(title = node.path.substringAfterLast('/').ifBlank { node.path }, summary = node.path)
          }
        }
      }
      if (path.isNotBlank() && !searching) {
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
          ChoiceRow("添加「${path.trimEnd('/').substringAfterLast('/').ifBlank { path }}」为项目", HomeScope.displayPath(path, null), false) {
            controller.addProject(path)
          }
        }
      }
    }
  }
}
