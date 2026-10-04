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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val markdownCache = object : LinkedHashMap<String, Pair<String, List<MarkdownBlock>>>(32, 0.75f, true) {
  override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, List<MarkdownBlock>>>?) = size > 32
}
private fun parsed(key: String, text: String): List<MarkdownBlock> = synchronized(markdownCache) {
  markdownCache[key]?.takeIf { it.first == text }?.second ?: MarkdownBlocks.parse(text).also { markdownCache[key] = text to it }
}

@Composable
internal fun Conversation(state: LagoonState, controller: LagoonController, modifier: Modifier = Modifier, onFile: (String) -> Unit, onModal: (Boolean) -> Unit) {
  val list = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val expanded = rememberSaveable(saver = mapSaver(save = { it.toMap() }, restore = { map -> mutableStateMapOf<String, Boolean>().apply { map.forEach { (k, v) -> put(k, v as Boolean) } } })) { mutableStateMapOf<String, Boolean>() }
  val expansions = expanded.filterValues { it }.keys
  val working = state.tasks[state.sessionId]?.active == true
  val rows by produceState(emptyList<TranscriptRow>(), state.messages, expansions, working) {
    delay(32)
    value = withContext(Dispatchers.Default) { TranscriptRows.build(state.messages, expansions, working) }
  }
  var follow by rememberSaveable { mutableStateOf(list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0) }
  var newContent by remember { mutableStateOf(false) }
  var autoScroll by remember { mutableStateOf(false) }
  val dragged by list.interactionSource.collectIsDraggedAsState()
  val nearBottom by remember { derivedStateOf {
    val info = list.layoutInfo; val last = info.visibleItemsInfo.lastOrNull()
    last != null && last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 40
  } }
  LaunchedEffect(dragged, nearBottom) { if (dragged && !autoScroll) follow = nearBottom }
  val contentKey = state.messages.lastOrNull()?.let { it.id to it.parts.sumOf { part -> part.text.length + part.output.length } }
  suspend fun scrollBottom() {
    val count = list.layoutInfo.totalItemsCount
    if (count > 0) { autoScroll = true; list.scrollToItem(count - 1); list.scrollBy(list.layoutInfo.viewportEndOffset.toFloat()); autoScroll = false }
  }
  LaunchedEffect(contentKey, rows.size, state.pending("send")) {
    if (state.pending("send")) follow = true
    if (follow) { scrollBottom(); newContent = false } else if (rows.isNotEmpty()) newContent = true
  }
  val permissions = state.permissions.filter { it.sessionId == state.sessionId }
  val questions = state.questions.filter { it.sessionId == state.sessionId }
  Box(modifier) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (state.cached) item { Text(if (state.cacheComplete) if (state.connected) "会话缓存 · 消息待同步" else "离线缓存 · 恢复连接后更新" else "离线缓存已截断，部分历史与长输出未保留", color = MiuixColorTokens.Warning, style = MiuixTheme.textStyles.footnote1) }
      if (state.messagesCursor != null) item { TextButton(text = if (state.pending("messages-more")) "正在加载…" else "加载更早消息", enabled = !state.pending("messages-more"), onClick = { follow = false; controller.loadOlderMessages() }) }
      if (rows.isEmpty()) item { ResourceHint(state.resource("messages"), "会话尚无消息", "在下方输入第一项任务。", controller::reload) }
      items(rows, key = { it.key }, contentType = { it.kind }) { row -> TranscriptRowView(row, expanded, onFile) }
      items(permissions, key = { "permission:${it.id}" }) { MiuixPermissionCard(it, controller, state.supportsSavedPermissions, onModal) }
      items(questions, key = { "question:${it.id}" }) { MiuixQuestionCard(it, controller) }
    }
    AnimatedVisibility(!nearBottom && rows.isNotEmpty(), modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
      Button(onClick = { follow = true; newContent = false; scope.launch { scrollBottom() } }, colors = ButtonDefaults.buttonColors()) { Text(if (newContent) "新消息 ↓" else "回到底部 ↓") }
    }
  }
}

@Composable
private fun TranscriptRowView(row: TranscriptRow, expanded: SnapshotStateMap<String, Boolean>, onFile: (String) -> Unit) {
  when (row.kind) {
    "time" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
      Text(row.text, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
    "user" -> UserMessageRow(row, onFile)
    "text" -> parsed(row.key, row.text).forEach { block -> MarkdownBlockView(block) }
    "reasoning" -> Column(Modifier.padding(start = 10.dp)) {
      parsed(row.key, row.text).forEach { block -> MarkdownBlockView(block, subdued = true) }
    }
    "meta" -> Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      if (row.meta.isNotBlank()) Text(row.meta, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      if (!row.copyText.isNullOrBlank()) CopyButton(row.copyText)
    }
    "divider" -> DividerRow(row.text)
    "thinking" -> ThinkingRow(row.text)
    "error" -> Column(Modifier.fillMaxWidth().background(MiuixColorTokens.ErrorSubtle, miuixSquircleShape(10.dp)).padding(12.dp)) {
      Text(row.text, color = MiuixColorTokens.Error, style = MiuixTheme.textStyles.body2)
    }
    "note" -> Text("${row.title} · ${row.text}", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    "tool", "context-group", "tool-summary", "diff-summary" -> ExpandableRow(row, expanded)
    "context-item" -> Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(row.title, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium))
      if (row.subtitle.isNotBlank()) {
        Spacer(Modifier.width(8.dp))
        Text(row.subtitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
      } else Spacer(Modifier.weight(1f))
      row.args.forEach { arg -> ArgChip(arg) }
    }
    "tool-body" -> Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 4.dp)) {
      if (row.title.isNotBlank() || row.copyText != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(row.title.ifBlank { "详细" }, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        row.copyText?.let { CopyButton(it) }
      }
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
      if (!row.copyText.isNullOrBlank()) CopyButton(row.copyText)
    }
  }
}

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

@Composable
private fun CodePanel(text: String) {
  Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.secondaryContainer, miuixSquircleShape(8.dp)).padding(8.dp)) {
    SelectionContainer {
      BasicText(text, Modifier.horizontalScroll(rememberScrollState()),
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
private fun CopyButton(text: String) {
  val clipboard = LocalClipboardManager.current
  TextButton(text = "复制", onClick = { clipboard.setText(AnnotatedString(text)) })
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
internal fun MarkdownBlockView(block: MarkdownBlock, subdued: Boolean = false) {
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
        if (block.copyText != null || block.prefix.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(block.prefix.ifBlank { "代码" }, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2)
          block.copyText?.let { CopyButton(it) }
        }
        SelectionContainer { BasicText(block.text, Modifier.horizontalScroll(rememberScrollState()), style = base.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)) }
      }
      "table-head", "table-row" -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MiuixTheme.colorScheme.secondaryContainer).padding(8.dp)) {
        block.cells.forEach { cell -> SelectionContainer { BasicText(annotated(cell), Modifier.width(180.dp).padding(end = 12.dp), style = base.copy(fontWeight = if (block.kind == "table-head") FontWeight.SemiBold else FontWeight.Normal)) } }
      }
      else -> {
        val task = taskMarker(block)
        SelectionContainer { BasicText(buildAnnotatedString { append(displayPrefix); append(task.first); append(annotated(task.second)) },
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
internal fun VirtualText(text: String, modifier: Modifier = Modifier) {
  val chunks by produceState(emptyList<String>(), text) { value = withContext(Dispatchers.Default) { MarkdownBlocks.chunks(text) } }
  LazyColumn(modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    items(chunks.size) { i -> CodePanel(chunks[i]) }
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
