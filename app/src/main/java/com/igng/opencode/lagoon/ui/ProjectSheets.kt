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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AddFolder
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The 会话 title's scope picker: 全部会话 (default) or one project, plus a small ＋ that opens the
 * server folder browser. Choosing only filters the home list.
 */
@Composable
internal fun ProjectScopeSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit, onAdd: () -> Unit) {
  var query by rememberSaveable { mutableStateOf("") }
  val counts = HomeScope.counts(state.sessions, state.projects)
  val home = state.directoryListing?.home
  val projects = HomeScope.byActivity(state.sessions, state.projects).filter {
    query.isBlank() || it.name.contains(query.trim(), true) || it.directory.contains(query.trim(), true)
  }
  SuperBottomSheet(
    title = "选择项目",
    show = true,
    onDismissRequest = onDismiss,
    endAction = { IconButton(onClick = onAdd, enabled = state.connected) { Icon(MiuixIcons.AddFolder, "添加项目", tint = MiuixTheme.colorScheme.onSurface) } }
  ) {
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
        Text(if (state.projects.isEmpty()) "还没有项目，点右上角的文件夹按钮，从服务器上选一个目录添加。" else "没有匹配的项目",
          Modifier.padding(16.dp), style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else item {
        Card(Modifier.fillMaxWidth()) {
          projects.forEach { project ->
            ChoiceRow(project.name, "${HomeScope.displayPath(project.directory, home)} · ${counts[project.id] ?: 0} 个会话", state.scopeProjectId == project.id) {
              controller.setScope(project.id); onDismiss()
            }
          }
        }
      }
    }
  }
}

/**
 * Pick a folder on the OpenCode server instead of typing an absolute path: start at the server's
 * default location, tap folders to go deeper, tap a breadcrumb to go back, then add the current folder.
 */
@Composable
internal fun DirectoryBrowserSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit, onAdded: () -> Unit) {
  LaunchedEffect(Unit) { controller.browseDirectories(null) }
  var showHidden by rememberSaveable { mutableStateOf(false) }
  val listing = state.directoryListing
  val path = listing?.path.orEmpty()
  val known = state.projects.map { normalizedDirectory(it.directory) }.toSet()
  val folders = listing?.directories.orEmpty().filter { showHidden || !it.startsWith('.') }
  val ready = listing?.status?.state == ResourceState.READY
  val added = path.isNotBlank() && normalizedDirectory(path) in known
  fun close() { controller.closeDirectoryBrowser(); onDismiss() }
  SuperBottomSheet(title = "添加项目", show = true, onDismissRequest = ::close) {
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.82f)) {
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val crumbs = if (path.isBlank()) emptyList() else HomeScope.crumbs(path)
        crumbs.forEachIndexed { index, (label, target) ->
          val last = index == crumbs.lastIndex
          Text(label, Modifier.clickable(enabled = !last) { controller.browseDirectories(target) }.padding(horizontal = 4.dp, vertical = 8.dp),
            style = MiuixTheme.textStyles.body2.copy(
              color = if (last) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.primary,
              fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal))
          if (!last && index > 0) Text("›", style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
      }
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(text = "上一级", enabled = path.isNotBlank() && path != "/", onClick = { controller.browseDirectories(HomeScope.parent(path)) })
        Spacer(Modifier.weight(1f))
        Text("显示隐藏文件夹", style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        Spacer(Modifier.width(8.dp))
        Switch(checked = showHidden, onCheckedChange = { showHidden = it })
      }
      Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
          listing == null || !ready -> ResourceHint(listing?.status ?: ResourceStatus(ResourceState.LOADING), "", retry = { controller.browseDirectories(path.ifBlank { null }) })
          folders.isEmpty() -> Text("这里没有子文件夹，可以直接添加当前文件夹。", Modifier.padding(16.dp),
            style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
          else -> LazyColumn(Modifier.fillMaxSize()) {
            items(folders, key = { it }) { name ->
              val child = HomeScope.child(path, name)
              BasicComponent(
                title = name,
                summary = "已添加".takeIf { normalizedDirectory(child) in known },
                startAction = { Icon(MiuixIcons.Folder, null, Modifier.padding(end = 12.dp).size(22.dp), tint = MiuixTheme.colorScheme.primary) },
                endActions = { Icon(MiuixIcons.ChevronForward, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) },
                onClick = { controller.browseDirectories(child) }
              )
            }
          }
        }
      }
      val name = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
      TextButton(
        text = when { added -> "「$name」已在项目列表中"; path.isBlank() -> "添加"; else -> "添加「$name」" },
        enabled = ready && !added && state.connected,
        onClick = { controller.addProjectDirectory(path); controller.closeDirectoryBrowser(); onAdded() },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        colors = ButtonDefaults.textButtonColorsPrimary()
      )
    }
  }
}

/** Project chip under the draft composer: where the new session will be created. */
@Composable
internal fun DraftTargetChip(state: LagoonState, onPick: () -> Unit) {
  CapsuleSelector(state.project?.name ?: "选择项目", onPick, enabled = state.connected,
    leading = { Icon(MiuixIcons.Folder, null, Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary) })
}

/** Lightweight project chooser for a draft: picks where the new session starts, without changing the home scope. */
@Composable
internal fun DraftTargetSheet(state: LagoonState, controller: LagoonController, onDismiss: () -> Unit, onAdd: () -> Unit) {
  val home = state.directoryListing?.home
  SuperBottomSheet(title = "在哪个项目里新建", show = true, onDismissRequest = onDismiss,
    endAction = { IconButton(onClick = onAdd, enabled = state.connected) { Icon(MiuixIcons.AddFolder, "添加项目", tint = MiuixTheme.colorScheme.onSurface) } }) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
      item {
        Card(Modifier.fillMaxWidth()) {
          HomeScope.byActivity(state.sessions, state.projects).forEach { project ->
            ChoiceRow(project.name, HomeScope.displayPath(project.directory, home), state.projectId == project.id) {
              if (project.id != state.projectId) controller.selectProject(project.id); onDismiss()
            }
          }
        }
      }
    }
  }
}
