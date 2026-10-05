package com.igng.opencode.lagoon.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixCapsuleShape

private val markdownCache = object : LinkedHashMap<String, Pair<String, List<MarkdownBlock>>>(32, 0.75f, true) {
  override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, List<MarkdownBlock>>>?) = size > 32
}
private fun parsed(key: String, text: String): List<MarkdownBlock> = synchronized(markdownCache) {
  markdownCache[key]?.takeIf { it.first == text }?.second ?: MarkdownBlocks.parse(text).also { markdownCache[key] = text to it }
}

@Composable
internal fun Conversation(state: LagoonState, controller: LagoonController, modifier: Modifier = Modifier, onFile: (String) -> Unit, onModal: (Boolean) -> Unit,
  onOpenChild: (String) -> Unit = {}) {
  val list = rememberLazyListState()
  val scope = rememberCoroutineScope()
  // 点击分组行后在半屏窗口查看明细；明细由同一套投影按分组 key 重建，主时间线保持折叠。
  var detail by remember(state.sessionId) { mutableStateOf<TranscriptRow?>(null) }
  DisposableEffect(detail) { onModal(detail != null); onDispose { if (detail != null) onModal(false) } }
  // 「思考过程」等仍按行内展开；工具组改为半屏窗口，不再写入这里。
  val expanded = rememberSaveable(saver = mapSaver(save = { it.toMap() }, restore = { map -> mutableStateMapOf<String, Boolean>().apply { map.forEach { (k, v) -> put(k, v as Boolean) } } })) { mutableStateMapOf<String, Boolean>() }
  val expansions = expanded.filterValues { it }.keys
  val task = state.tasks[state.sessionId]
  val working = task?.active == true
  val revert = state.session?.revertMessageId
  val rows by produceState(emptyList<TranscriptRow>(), state.messages, expansions, working, revert) {
    delay(32)
    value = withContext(Dispatchers.Default) { TranscriptRows.build(state.messages, expansions, working, revert) }
  }
  // A brand-new conversation opens at the newest message; only a deliberate drag up opts out of following.
  var follow by rememberSaveable { mutableStateOf(true) }
  var newContent by remember { mutableStateOf(false) }
  var autoScroll by remember { mutableStateOf(false) }
  val dragged by list.interactionSource.collectIsDraggedAsState()
  val nearBottom by remember { derivedStateOf {
    val info = list.layoutInfo; val last = info.visibleItemsInfo.lastOrNull()
    last != null && last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 40
  } }
  LaunchedEffect(dragged, nearBottom) { if (dragged && !autoScroll) follow = nearBottom }
  val contentKey = state.messages.lastOrNull()?.let { it.id to it.parts.sumOf { part -> part.text.length + part.output.length } }
  val permissions = state.permissions.filter { it.sessionId == state.sessionId }
  val questions = state.questions.filter { it.sessionId == state.sessionId }
  // The exact number of LazyColumn items the data implies; the scroll effect waits for the list to lay
  // out that many before landing, so the initial open does not scroll while `rows` are still empty.
  val mainRows = rows.filter { it.detailOf == null }
  val expectedItems = (if (state.cached) 1 else 0) +
    (if (state.sessionId in state.backgroundRunning) 1 else 0) +
    (if (state.messagesCursor != null) 1 else 0) +
    (if (rows.isEmpty()) 1 else 0) + mainRows.size + permissions.size + questions.size
  suspend fun scrollBottom() {
    autoScroll = true
    try {
      withTimeoutOrNull(1_000) { snapshotFlow { list.layoutInfo.totalItemsCount }.first { it >= expectedItems && it > 0 } }
      val count = list.layoutInfo.totalItemsCount
      if (count > 0) { list.scrollToItem(count - 1); list.scrollBy(list.layoutInfo.viewportEndOffset.toFloat()) }
    } finally { autoScroll = false }
  }
  // Only a new message, a fresh send or the first successful layout moves the viewport. Expanding or
  // collapsing a row never scrolls, so the row the reader is looking at keeps its place.
  LaunchedEffect(contentKey, expectedItems, state.pending("send")) {
    if (state.pending("send")) follow = true
    if (!follow) { if (state.messages.isNotEmpty()) newContent = true; return@LaunchedEffect }
    newContent = false
    scrollBottom()
  }
  // 键盘弹出时列表视口变矮，最后几条会落到键盘后面；这一刻自动回到底部（等布局收敛后再滚）。
  val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
  LaunchedEffect(imeBottom > 0) {
    if (imeBottom > 0) { follow = true; delay(120); scrollBottom() }
  }
  // Only a deliberate scroll to the top pulls the previous page; the initial layout must not trigger it,
  // otherwise it kept loading older history until the very first message.
  val loadingOlder = state.pending("messages-more")
  val atTop by remember { derivedStateOf {
    val info = list.layoutInfo
    info.totalItemsCount > 0 && (info.visibleItemsInfo.firstOrNull()?.index ?: Int.MAX_VALUE) <= 1
  } }
  LaunchedEffect(atTop, state.messagesCursor, loadingOlder, state.connected, state.cached, follow) {
    if (atTop && !follow && state.connected && !state.cached && !loadingOlder && state.messagesCursor != null) {
      controller.loadOlderMessages()
    }
  }
  Box(modifier) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (state.cached) item { Text(if (state.cacheComplete) if (state.connected) "会话缓存 · 消息待同步" else "离线缓存 · 恢复连接后更新" else "离线缓存已截断，部分历史与长输出未保留", color = MiuixColorTokens.Warning, style = MiuixTheme.textStyles.footnote1) }
      if (state.sessionId in state.backgroundRunning) item(key = "background-running") {
        Text("任务正在后台运行中", color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.footnote1)
      }
      if (state.messagesCursor != null) item(key = "older-loader") {
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
          if (loadingOlder) InfiniteProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
        }
      }
      if (rows.isEmpty()) item { ResourceHint(state.resource("messages"), "会话尚无消息", "在下方输入第一项任务。", controller::reload) }
      items(rows.filter { it.detailOf == null }, key = { it.key }, contentType = { it.kind }) { row -> TranscriptRowView(row, expanded, onFile, onOpenChild, onDetail = { detail = it }) }
      items(permissions, key = { "permission:${it.id}" }) { MiuixPermissionCard(it, controller, onModal) }
      items(questions, key = { "question:${it.id}" }) { MiuixQuestionCard(it, controller) }
    }
    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (expansions.isNotEmpty()) {
        Row(Modifier.clip(miuixCapsuleShape()).background(MiuixTheme.colorScheme.secondaryContainer)
          .clickable { expanded.clear() }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
          Icon(MiuixIcons.ChevronForward, "收起全部", Modifier.size(16.dp).rotate(-90f), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
          Spacer(Modifier.width(6.dp))
          Text("收起全部", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        }
      }
      AnimatedVisibility(!nearBottom && rows.isNotEmpty()) {
        Box(Modifier.size(36.dp).clip(miuixCapsuleShape()).background(MiuixTheme.colorScheme.secondaryContainer)
          .clickable { follow = true; newContent = false; scope.launch { scrollBottom() } }, contentAlignment = Alignment.Center) {
          DropdownChevron(18.dp, tint = if (newContent) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            description = if (newContent) "有新消息，回到最新" else "回到底部")
        }
      }
      if (task?.active == true) WorkingIndicator(task)
    }
  }
  detail?.let { row ->
    val details by produceState(emptyList<TranscriptRow>(), row.key, state.messages, working, revert) {
      value = withContext(Dispatchers.Default) { TranscriptRows.details(state.messages, row.key, working, revert) }
    }
    val sections = remember(details) { detailSections(details) }
    val expandedItems = remember(row.key) { mutableStateMapOf<String, Boolean>() }
    SuperBottomSheet(title = row.title.ifBlank { "详情" }, show = true, onDismissRequest = { detail = null }) {
      LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.6f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (sections.isEmpty()) item { Text("没有可显示的明细", style = MiuixTheme.textStyles.footnote1) }
        items(sections, key = { it.header.key }) { section -> DetailSectionView(section, expandedItems) }
      }
    }
  }
}

/**
 * 会话底部常驻的运行指示：只要该会话有任务在跑就一直显示，即使消息本身没有变化；已用时间每秒
 * 刷新，避免用户把静态的「用时」当成“已结束”。
 */
@Composable
private fun WorkingIndicator(task: TaskState) {
  val waiting = task.phase in TaskState.WAITING_PHASES
  var now by remember(task.sessionId, task.since) { mutableStateOf(System.currentTimeMillis()) }
  LaunchedEffect(task.sessionId, task.since) {
    while (true) { now = System.currentTimeMillis(); delay(1_000) }
  }
  val elapsed = ((now - task.since) / 1000).coerceAtLeast(0)
  Row(Modifier.clip(miuixCapsuleShape()).background(MiuixTheme.colorScheme.secondaryContainer)
    .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    if (!waiting) {
      InfiniteProgressIndicator(color = MiuixTheme.colorScheme.primary, size = 14.dp, strokeWidth = 1.5.dp)
      Spacer(Modifier.width(6.dp))
    }
    Text(if (waiting) "等待你的操作" else "运行中 · ${elapsedText(elapsed)}",
      style = MiuixTheme.textStyles.footnote2.copy(color = if (waiting) MiuixColorTokens.Warning else MiuixTheme.colorScheme.primary))
  }
}

private fun elapsedText(seconds: Long): String = when {
  seconds < 60 -> "${seconds} 秒"
  else -> "${seconds / 60} 分 ${seconds % 60} 秒"
}

@Composable
private fun TranscriptRowView(row: TranscriptRow, expanded: SnapshotStateMap<String, Boolean>, onFile: (String) -> Unit, onOpenChild: (String) -> Unit, onDetail: (TranscriptRow) -> Unit) {
  when (row.kind) {
    "time" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
      Text(row.text, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
    "user" -> UserMessageRow(row, onFile)
    // One selection container per reply so a long press can select across paragraphs; the turn's final
    // answer is also copyable as a whole from the meta row below it.
    "text" -> SelectionContainer { Column { parsed(row.key, row.text).forEach { block -> MarkdownBlockView(block, selectable = false) } } }
    "reasoning" -> ExpandableRow(row, expanded)
    "reasoning-body" -> SelectionContainer { Column(Modifier.padding(start = 10.dp)) {
      parsed(row.key, row.text).forEach { block -> MarkdownBlockView(block, subdued = true, selectable = false) }
    } }
    "meta" -> Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      row.copyText?.let { text ->
        val clipboard = LocalClipboardManager.current
        IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }, minWidth = 32.dp, minHeight = 32.dp) {
          Icon(MiuixIcons.Copy, "复制回复", Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Spacer(Modifier.width(6.dp))
      }
      if (row.meta.isNotBlank()) Text(row.meta, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
    "divider" -> DividerRow(row.text)
    "thinking" -> ThinkingRow(row.text)
    "error" -> Column(Modifier.fillMaxWidth().background(MiuixColorTokens.ErrorSubtle, miuixSquircleShape(10.dp)).padding(12.dp)) {
      Text(row.text, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.body2)
    }
    "note" -> NoticeRow(row, onOpenChild)
    "tool" -> Column {
      GroupRow(row) { onDetail(row) }
      row.target?.let { child -> TextButton(text = "打开子会话 ›", onClick = { onOpenChild(child) }, modifier = Modifier.padding(start = 16.dp)) }
    }
    "tool-group" -> Column {
      GroupRow(row) { onDetail(row) }
      row.target?.let { child -> TextButton(text = "打开子会话 ›", onClick = { onOpenChild(child) }, modifier = Modifier.padding(start = 16.dp)) }
    }
    "diff-summary" -> GroupRow(row) { onDetail(row) }
    "context-item" -> Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(row.title, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium))
      if (row.subtitle.isNotBlank()) {
        Spacer(Modifier.width(8.dp))
        Text(row.subtitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else Spacer(Modifier.weight(1f))
      row.args.forEach { arg -> ArgChip(arg) }
    }
    "tool-body" -> Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp)) {
      if (row.title.isNotBlank()) Text(row.title, modifier = Modifier.padding(bottom = 4.dp), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      CodePanel(row.text)
    }
    "diff-file" -> Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(row.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace))
    }
    "attachment" -> row.attachments.forEach { attachment -> AttachmentView(attachment, onFile) }
  }
}

@Composable
private fun UserMessageRow(row: TranscriptRow, onFile: (String) -> Unit) {
  val bubbleMax = LocalConfiguration.current.screenWidthDp.dp * 0.86f
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
    if (row.attachments.isNotEmpty()) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      row.attachments.forEach { attachment -> AttachmentView(attachment, onFile) }
    }
    if (row.text.isNotBlank()) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.widthIn(max = bubbleMax).background(MiuixTheme.colorScheme.secondaryContainer, miuixSquircleShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp)) {
          SelectionContainer {
            BasicText(row.text, style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface, lineHeight = 24.sp))
          }
        }
      }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Spacer(Modifier.weight(1f))
      if (row.meta.isNotBlank()) Text(row.meta, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
  }
}

/** 行内可展开行，用于「思考过程」等由 [expanded] 控制的内容（工具组改用 [GroupRow] + 半屏窗口）。 */
@Composable
private fun ExpandableRow(row: TranscriptRow, expanded: SnapshotStateMap<String, Boolean>) {
  val open = expanded[row.key] == true
  val running = row.status in setOf("running", "pending")
  Column(Modifier.fillMaxWidth().background(if (open) MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else Color.Transparent, miuixSquircleShape(10.dp))) {
    Row(Modifier.fillMaxWidth().clickable { expanded[row.key] = !open }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      if (row.kind == "tool") {
        ToolStatusDot(row.status)
        Spacer(Modifier.width(8.dp))
      }
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium, color = if (row.status == "error") MiuixColorTokens.Error else MiuixTheme.colorScheme.onSurface))
          row.args.forEach { arg -> Spacer(Modifier.width(6.dp)); ArgChip(arg) }
        }
        if (row.subtitle.isNotBlank()) Text(row.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
      if (running) {
        Spacer(Modifier.width(8.dp))
        Text("执行中", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Primary))
      }
      Spacer(Modifier.width(6.dp))
      Text(if (open) "⌄" else "›", style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
  }
}

/** 分组行：点击后打开半屏窗口查看明细（不再内联展开）。 */
@Composable
private fun GroupRow(row: TranscriptRow, onClick: () -> Unit) {
  val running = row.status in setOf("running", "pending")
  Row(Modifier.fillMaxWidth()
    .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f), miuixSquircleShape(10.dp))
    .clickable(onClick = onClick)
    .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    if (row.kind in setOf("tool", "tool-group")) {
      ToolStatusDot(row.status)
      Spacer(Modifier.width(8.dp))
    }
    Column(Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium, color = if (row.status == "error") MiuixColorTokens.Error else MiuixTheme.colorScheme.onSurface))
        row.args.forEach { arg -> Spacer(Modifier.width(6.dp)); ArgChip(arg) }
      }
      if (row.subtitle.isNotBlank()) Text(row.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
    if (running) {
      Spacer(Modifier.width(8.dp))
      Text("执行中", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Primary))
    }
    Spacer(Modifier.width(6.dp))
    Text("›", style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
  }
}

/** 半屏窗口里的一行明细：代码/输出走带高亮的等宽面板，其余是文件头、上下文项或分组小标题。 */
@Composable
private fun DetailRowView(row: TranscriptRow) {
  when (row.kind) {
    "tool-body" -> Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
      if (row.title.isNotBlank()) Text(row.title, Modifier.padding(bottom = 4.dp),
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      CodePanel(row.text)
    }
    "context-item" -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(row.title, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium))
      if (row.subtitle.isNotBlank()) {
        Spacer(Modifier.width(8.dp))
        Text(row.subtitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else Spacer(Modifier.weight(1f))
      row.args.forEach { arg -> ArgChip(arg) }
    }
    "diff-file" -> Text(row.title, Modifier.fillMaxWidth().padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
      style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace))
    "attachment" -> row.attachments.forEach { attachment -> AttachmentView(attachment) {} }
    "reasoning" -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(row.title.ifBlank { "思考" }, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium))
      if (row.subtitle.isNotBlank()) {
        Spacer(Modifier.width(8.dp))
        Text(row.subtitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else Spacer(Modifier.weight(1f))
    }
    "reasoning-body" -> SelectionContainer { Column(Modifier.fillMaxWidth().padding(start = 24.dp)) {
      parsed(row.key, row.text).forEach { block -> MarkdownBlockView(block, subdued = true, selectable = false) }
    } }
    "tool", "tool-group", "diff-summary" -> Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      ToolStatusDot(row.status)
      Spacer(Modifier.width(8.dp))
      Column(Modifier.weight(1f)) {
        Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium))
        if (row.subtitle.isNotBlank()) Text(row.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      }
    }
    else -> Unit
  }
}

/**
 * 半屏窗口里的一条调用记录：一行条目头（如“命令行 / ls -la · 已完成”）+ 可折叠的正文。点击条目头
 * 才展开命令 / 输出 / 思考，未展开时整个分组只显示一行行记录，和电脑端一致。
 */
private data class DetailSection(val header: TranscriptRow, val bodies: List<TranscriptRow>)

/** 按 [TranscriptRow.bodyOf] 把明细行折成「条目头 + 正文」的段落，保持原始顺序。 */
private fun detailSections(rows: List<TranscriptRow>): List<DetailSection> {
  val sections = mutableListOf<DetailSection>()
  var header: TranscriptRow? = null
  var bodies = mutableListOf<TranscriptRow>()
  rows.forEach { row ->
    if (row.bodyOf == null) {
      header?.let { sections += DetailSection(it, bodies) }
      header = row
      bodies = mutableListOf()
    } else bodies += row
  }
  header?.let { sections += DetailSection(it, bodies) }
  return sections
}

@Composable
private fun DetailSectionView(section: DetailSection, expandedItems: SnapshotStateMap<String, Boolean>) {
  val expandable = section.bodies.isNotEmpty()
  val open = expandedItems[section.header.key] == true
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().clickable(enabled = expandable) { if (expandable) expandedItems[section.header.key] = !open },
      verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.weight(1f)) { DetailRowView(section.header) }
      if (expandable) Text(if (open) "⌄" else "›", modifier = Modifier.padding(start = 6.dp),
        style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
    if (open) section.bodies.forEach { body -> DetailRowView(body) }
  }
}

/** Official notice row: a one-line label (agent change, instructions updated, subagent result…), never a reply bubble. */
@Composable
private fun NoticeRow(row: TranscriptRow, onOpenChild: (String) -> Unit) {
  val color = when (row.status) {
    "error" -> MiuixColorTokens.Error
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
  }
  val target = row.target
  Row(Modifier.fillMaxWidth().then(if (target != null) Modifier.clickable { onOpenChild(target) } else Modifier).padding(horizontal = 10.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Text(row.title, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium, color = color))
    if (row.text.isNotBlank()) {
      Spacer(Modifier.width(8.dp))
      Text(row.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    } else Spacer(Modifier.weight(1f))
    if (target != null) Text("›", style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
  }
}

@Composable
private fun ToolStatusDot(status: String) {
  val color = when (status) {
    "running", "pending" -> MiuixColorTokens.Primary
    "completed" -> MiuixColorTokens.Success
    "error" -> MiuixColorTokens.Error
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
  }
  Box(Modifier.size(7.dp).background(color, androidx.compose.foundation.shape.CircleShape))
}

@Composable
private fun ArgChip(text: String) {
  Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
    style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
    modifier = Modifier.background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.06f), miuixSquircleShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp))
}

private data class CodeColors(val plain: Color, val header: Color, val hunk: Color, val add: Color, val remove: Color, val command: Color)

@Composable
private fun rememberCodeColors(): CodeColors = CodeColors(
  plain = MiuixTheme.colorScheme.onSurface,
  header = MiuixTheme.colorScheme.onSurfaceVariantSummary,
  hunk = MiuixTheme.colorScheme.primary,
  add = MiuixColorTokens.Success,
  remove = MiuixColorTokens.Error,
  command = MiuixTheme.colorScheme.primary
)

@Composable
private fun CodePanel(text: String, highlight: Boolean = true) {
  val colors = rememberCodeColors()
  val annotated = remember(text, colors, highlight) {
    buildAnnotatedString {
      if (!highlight) { append(text); return@buildAnnotatedString }
      CodeHighlight.split(text).forEach { fragment ->
        val color = when (fragment.token) {
          CodeToken.PLAIN -> colors.plain
          CodeToken.HEADER -> colors.header
          CodeToken.HUNK -> colors.hunk
          CodeToken.ADD -> colors.add
          CodeToken.REMOVE -> colors.remove
          CodeToken.COMMAND -> colors.command
        }
        withStyle(SpanStyle(color = color)) { append(fragment.text) }
      }
    }
  }
  Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.secondaryContainer, miuixSquircleShape(8.dp)).padding(8.dp)) {
    SelectionContainer {
      BasicText(annotated, Modifier.horizontalScroll(rememberScrollState()),
        style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp))
    }
  }
}

@Composable
private fun DividerRow(label: String) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    HorizontalDivider(Modifier.weight(1f))
    Text(label, modifier = Modifier.padding(horizontal = 10.dp), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    HorizontalDivider(Modifier.weight(1f))
  }
}

@Composable
private fun ThinkingRow(text: String) {
  val transition = rememberInfiniteTransition(label = "thinking")
  val alpha by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
  Text(text, style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary), modifier = Modifier.alpha(alpha).padding(top = 4.dp))
}

@Composable
internal fun ResourceHint(status: ResourceStatus, empty: String, explanation: String = "", retry: () -> Unit) {
  when (status.state) {
    ResourceState.LOADING, ResourceState.NOT_LOADED -> Text("正在加载…", style = MiuixTheme.textStyles.footnote1)
    ResourceState.ERROR -> Column { Text(status.error ?: "加载失败", color = MiuixColorTokens.Error); TextButton(text = "重试", onClick = retry) }
    ResourceState.UNSUPPORTED -> Text("此服务器不提供该功能", style = MiuixTheme.textStyles.footnote1)
    ResourceState.STALE -> Column { Text("保留上次数据 · ${status.error.orEmpty()}", color = MiuixColorTokens.Warning); TextButton(text = "重新同步", onClick = retry) }
    ResourceState.EMPTY -> Column { Text(empty, style = MiuixTheme.textStyles.headline2); if (explanation.isNotBlank()) Text(explanation, style = MiuixTheme.textStyles.footnote1) }
    else -> Unit
  }
}

@Composable
private fun annotated(spans: List<MarkdownSpan>): AnnotatedString {
  val primary = MiuixTheme.colorScheme.primary
  val chip = MiuixTheme.colorScheme.secondaryContainer
  return buildAnnotatedString {
    spans.forEach { span ->
      val style = SpanStyle(
        fontWeight = if (span.bold) FontWeight.Bold else null,
        fontStyle = if (span.italic) FontStyle.Italic else null,
        fontFamily = if (span.code) FontFamily.Monospace else null,
        background = if (span.code) chip else Color.Transparent,
        textDecoration = if (span.strike) TextDecoration.LineThrough else null
      )
      if (span.href != null) withLink(LinkAnnotation.Url(span.href, TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline)))) { withStyle(style) { append(span.text) } }
      else withStyle(style) { append(span.text) }
    }
  }
}

@Composable
internal fun MarkdownBlockView(block: MarkdownBlock, subdued: Boolean = false, selectable: Boolean = true) {
  // Nested SelectionContainers would split selection per paragraph; callers wrapping a whole reply pass false.
  val select: @Composable (@Composable () -> Unit) -> Unit = { inner -> if (selectable) SelectionContainer { inner() } else inner() }
  val base = MiuixTheme.textStyles.body1.copy(
    color = if (subdued) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface,
    lineHeight = 26.sp, fontSize = if (subdued) 14.sp else 16.sp
  )
  val quote = block.prefix.trimStart().startsWith(">")
  val displayPrefix = if (quote) block.prefix.trimStart().trimStart('>').trimStart() else block.prefix
  val content: @Composable () -> Unit = {
    when (block.kind) {
      "rule" -> HorizontalDivider()
      "code" -> Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.secondaryContainer, miuixSquircleShape(10.dp)).padding(10.dp)) {
        if (block.prefix.isNotBlank()) Text(block.prefix, modifier = Modifier.padding(bottom = 4.dp), style = MiuixTheme.textStyles.footnote2)
        select { BasicText(block.text, Modifier.horizontalScroll(rememberScrollState()), style = base.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)) }
      }
      "table-head", "table-row" -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MiuixTheme.colorScheme.secondaryContainer).padding(8.dp)) {
        block.cells.forEach { cell -> select { BasicText(annotated(cell), Modifier.width(180.dp).padding(end = 12.dp), style = base.copy(fontWeight = if (block.kind == "table-head") FontWeight.SemiBold else FontWeight.Normal)) } }
      }
      else -> {
        val task = taskMarker(block)
        select { BasicText(buildAnnotatedString { append(displayPrefix); append(task.first); append(annotated(task.second)) },
          style = if (block.kind == "heading") base.copy(fontSize = when (block.level) { 1 -> 25.sp; 2 -> 21.sp; else -> 18.sp }, fontWeight = FontWeight.SemiBold) else base) }
      }
    }
  }
  if (quote) Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.05f), miuixSquircleShape(8.dp)).padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)) {
    content()
  } else content()
}

/** Renders `- [ ]` / `- [x]` task list markers as checkboxes. */
private fun taskMarker(block: MarkdownBlock): Pair<AnnotatedString, List<MarkdownSpan>> {
  val first = block.spans.firstOrNull()?.text ?: return AnnotatedString("") to block.spans
  val marker = when {
    first.startsWith("[ ] ") -> "☐ "
    first.startsWith("[x] ") || first.startsWith("[X] ") -> "☑ "
    else -> return AnnotatedString("") to block.spans
  }
  val head = block.spans.first()
  val rest = if (marker == "☑ ") listOf(head.copy(text = head.text.substring(4), strike = true)) + block.spans.drop(1) else listOf(head.copy(text = head.text.substring(4))) + block.spans.drop(1)
  return AnnotatedString(marker) to rest
}

@Composable
internal fun VirtualText(text: String, modifier: Modifier = Modifier, highlight: Boolean = true) {
  val chunks by produceState(emptyList<String>(), text) { value = withContext(Dispatchers.Default) { MarkdownBlocks.chunks(text) } }
  LazyColumn(modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    items(chunks.size) { i -> CodePanel(chunks[i], highlight) }
  }
}

@Composable
private fun AttachmentView(attachment: Attachment, onFile: (String) -> Unit) {
  val uriHandler = LocalUriHandler.current
  val dataImage = attachment.url.startsWith("data:image/") && attachment.url.length < 8_000_000
  val bitmap by produceState<android.graphics.Bitmap?>(null, attachment.url) {
    if (dataImage) value = withContext(Dispatchers.IO) { runCatching {
      val bytes = android.util.Base64.decode(attachment.url.substringAfter(','), android.util.Base64.DEFAULT)
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
      val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 1024).coerceAtLeast(1) }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull() }
  }
  Column(Modifier.fillMaxWidth()) {
    bitmap?.let { Image(it.asImageBitmap(), attachment.name.ifBlank { "图片附件" }, Modifier.fillMaxWidth().heightIn(max = 280.dp)) }
    TextButton(text = attachment.name.ifBlank { if (dataImage) "图片附件" else attachment.url.substringAfterLast('/').take(100).ifBlank { "附件" } }, onClick = {
      when { attachment.url.startsWith("http://") || attachment.url.startsWith("https://") -> runCatching { uriHandler.openUri(attachment.url) }
        !attachment.url.startsWith("data:") -> onFile(runCatching { java.net.URI(attachment.url).path }.getOrNull() ?: attachment.url) }
    })
  }
}
