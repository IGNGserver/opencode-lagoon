package com.igng.opencode.lagoon.ui

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class ChatSheet { TODO, CHANGES, FILES, CHILDREN }

@Composable
fun ChatScreen(state: LagoonState, controller: LagoonController, onBack: () -> Unit, onOpenChild: (String) -> Unit, onModal: (Boolean) -> Unit, interactive: Boolean = true) {
  val session = state.session
  var sheet by remember { mutableStateOf<ChatSheet?>(null) }
  var menu by remember { mutableStateOf(false) }
  var rename by rememberSaveable { mutableStateOf(false) }
  var title by rememberSaveable(session?.id) { mutableStateOf(session?.title.orEmpty()) }
  var delete by rememberSaveable { mutableStateOf(false) }
  var revertPicker by remember { mutableStateOf(false) }
  var revertId by remember { mutableStateOf<String?>(null) }
  var composerModal by remember { mutableStateOf(false) }
  var requestModal by remember { mutableStateOf(false) }
  val clipboard = LocalClipboardManager.current
  val context = LocalContext.current
  val uriHandler = LocalUriHandler.current
  val modal = sheet != null || menu || rename || delete || revertPicker || composerModal || requestModal || state.sharedUrl != null || state.savedPermissions != null
  DisposableEffect(modal) { onModal(modal); onDispose { onModal(false) } }
  fun openFile(path: String) { sheet = ChatSheet.FILES; controller.readFile(path) }
  if (session == null) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text("会话尚未加载或已关闭"); TextButton(text = "返回列表", onClick = onBack) }
    return
  }
  Column(Modifier.fillMaxSize()) {
    SmallTopAppBar(title = state.title(session), navigationIcon = { IconButton(onClick = onBack) { Icon(MiuixIcons.Back, "返回上一级") } }, actions = { IconButton(onClick = { menu = true }) { Icon(MiuixIcons.More, "会话操作") } })
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(listOfNotNull(resolveSessionProject(session, state.projects)?.name, state.server?.name).joinToString(" · "), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote2)
      state.tasks[session.id]?.takeIf { it.active }?.let { MiuixStatePill(it.phase) }
    }
    Text(session.directory, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary), modifier = Modifier.padding(horizontal = 20.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
      if (state.capabilities.todos) TextButton(text = "待办 ${state.todos.size}", onClick = { sheet = ChatSheet.TODO })
      if (state.capabilities.diff) TextButton(text = "改动 ${state.changes.size}", onClick = { sheet = ChatSheet.CHANGES })
      TextButton(text = "子任务 ${state.children.size}", onClick = { sheet = ChatSheet.CHILDREN })
      TextButton(text = "文件", onClick = { sheet = ChatSheet.FILES; controller.listFiles() })
    }
    Conversation(state, controller, Modifier.weight(1f).fillMaxWidth(), ::openFile, { requestModal = it })
    ChatComposer(state, controller, interactive) { composerModal = it }
  }
  sheet?.let { which ->
    val resource = when (which) { ChatSheet.TODO -> "todos"; ChatSheet.CHANGES -> "changes"; ChatSheet.FILES -> "files"; ChatSheet.CHILDREN -> "children" }
    SuperBottomSheet(title = when (which) { ChatSheet.TODO -> "待办"; ChatSheet.CHANGES -> "代码改动"; ChatSheet.FILES -> "文件"; ChatSheet.CHILDREN -> "子任务" }, show = true, onDismissRequest = { sheet = null }) {
      Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f).padding(16.dp)) {
        if (which != ChatSheet.FILES) ResourceHint(state.resource(resource), "暂无记录", retry = controller::reload)
        when (which) {
          ChatSheet.TODO -> LazyColumn { items(state.todos) { todo -> Text("${if (todo.status == "completed") "✓" else "○"} ${todo.content}", modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) } }
          // Server lists may repeat an entry; LazyColumn crashes on a duplicate key, so keys never come from data alone.
          ChatSheet.CHANGES -> LazyColumn { itemsIndexed(state.changes, key = { index, change -> "$index:${change.path}" }) { _, change ->
            var expanded by rememberSaveable(change.path) { mutableStateOf(false) }
            TextButton(text = "${change.path}  +${change.additions} −${change.deletions}", onClick = { expanded = !expanded })
            if (expanded) VirtualText(change.patch.ifBlank { change.after })
          } }
          ChatSheet.FILES -> FilesPanel(state, controller) { path -> controller.addReference(path); sheet = null }
          ChatSheet.CHILDREN -> LazyColumn { items(state.children.distinctBy { it.id }, key = { it.id }) { child ->
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), insideMargin = PaddingValues(14.dp), onClick = { sheet = null; onOpenChild(child.id) }) {
              Text(state.title(child)); state.tasks[child.id]?.takeIf { it.active }?.let { MiuixStatePill(it.phase) }
            }
          } }
        }
      }
    }
  }
  if (menu) SuperBottomSheet(title = "会话操作", show = true, onDismissRequest = { menu = false }) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
      fun close(action: () -> Unit) { menu = false; action() }
      if (state.capabilities.supports(SessionAction.RENAME)) TextButton(text = "重命名", onClick = { close { title = state.title(session); rename = true } })
      if (state.supportsSavedPermissions) TextButton(text = "已保存的项目权限", onClick = { close { controller.loadSavedPermissions() } })
      if (state.capabilities.supports(SessionAction.FORK)) TextButton(text = "从此会话创建分支", enabled = !state.pending("fork"), onClick = { close { controller.fork { onOpenChild(it.id) } } })
      if (state.capabilities.supports(SessionAction.SHARE)) TextButton(text = "分享会话", enabled = !state.pending("share"), onClick = { close { controller.share() } })
      if (state.capabilities.supports(SessionAction.COMPACT)) TextButton(text = "整理上下文", enabled = !state.pending("compact"), onClick = { close { controller.summarize() } })
      if (state.capabilities.supports(SessionAction.REVERT)) TextButton(text = "选择撤销位置", onClick = { close { revertPicker = true; revertId = null } })
      if (state.capabilities.supports(SessionAction.UNREVERT)) TextButton(text = "恢复撤销", enabled = !state.pending("unrevert"), onClick = { close { controller.unrevert() } })
      if (state.capabilities.supports(SessionAction.DELETE)) TextButton(text = "删除会话", colors = ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error), onClick = { close { delete = true } })
    }
  }
  if (rename) SuperDialog(title = "重命名会话", show = true, onDismissRequest = { if (!state.pending("rename")) rename = false }) {
    Column { TextField(title, { title = it }, label = "会话名称", modifier = Modifier.fillMaxWidth()); ActionError(state, "rename")
      Row { TextButton(text = "取消", onClick = { rename = false }); TextButton(text = if (state.pending("rename")) "保存中…" else "保存", enabled = !state.pending("rename") && title.isNotBlank(), onClick = { controller.rename(title) { rename = false } }) }
    }
  }
  if (delete) SuperDialog(title = "删除此会话？", show = true, onDismissRequest = { if (!state.pending("delete")) delete = false }) {
    Column { Text("会话消息将从服务器永久删除。\n${state.title(session)}"); ActionError(state, "delete")
      Row { TextButton(text = "取消", onClick = { delete = false }); TextButton(text = if (state.pending("delete")) "删除中…" else "永久删除", enabled = !state.pending("delete"), colors = ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error), onClick = { controller.deleteSession { delete = false; onBack() } }) }
    }
  }
  if (revertPicker) SuperDialog(title = "选择撤销边界", show = true, onDismissRequest = { revertPicker = false }) {
    Column { Text("将撤销所选用户消息及其后续修改，可通过“恢复撤销”恢复。", style = MiuixTheme.textStyles.footnote1)
      LazyColumn(Modifier.heightIn(max = 280.dp)) { items(state.messages.filter { it.role == "user" && it.isDisplayable }.distinctBy { it.id }, key = { it.id }) { message -> TextButton(text = (if (revertId == message.id) "✓ " else "") + message.parts.filter { it.type == "text" }.joinToString(" ") { it.text }.take(140), onClick = { revertId = message.id }) } }
      ActionError(state, "revert"); TextButton(text = "撤销所选消息及后续修改", enabled = revertId != null && !state.pending("revert"), onClick = { revertId?.let { controller.revert(it) { revertPicker = false } } })
    }
  }
  state.sharedUrl?.let { url -> SuperDialog(title = "分享链接", show = true, onDismissRequest = controller::closeShare) {
    Column { Text(url); Row(Modifier.horizontalScroll(rememberScrollState())) {
      TextButton(text = "复制", onClick = { clipboard.setText(AnnotatedString(url)) })
      TextButton(text = "打开", onClick = { runCatching { uriHandler.openUri(url) } })
      TextButton(text = "系统分享", onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "分享会话")) })
    }
      if (state.capabilities.supports(SessionAction.UNSHARE)) TextButton(text = "取消此链接的分享", enabled = !state.pending("unshare"), onClick = { controller.unshare() })
    }
  } }
  state.savedPermissions?.let { rules -> SuperDialog(title = "已保存的项目权限", show = true, onDismissRequest = controller::closeSavedPermissions) {
    Column { ResourceHint(state.resource("saved"), "没有已保存的权限规则", retry = { controller.loadSavedPermissions() })
      LazyColumn(Modifier.heightIn(max = 360.dp)) { items(rules.distinctBy { it.id }, key = { it.id }) { rule -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(rule.action); Text(rule.resource, style = MiuixTheme.textStyles.footnote2) }
        TextButton(text = "撤销", enabled = !state.pending("revoke:${rule.id}"), onClick = { controller.revokeSavedPermission(rule) })
      } } }
    }
  } }
}
@Composable
private fun ActionError(state: LagoonState, action: String) { state.resource("action:$action").takeIf { it.state == ResourceState.ERROR }?.error?.let { Text(it, color = MiuixColorTokens.Error) } }

@Composable
fun MiuixPermissionCard(request: PermissionRequest, controller: LagoonController, canSave: Boolean, onModal: (Boolean) -> Unit = {}) {
  val state by controller.state.collectAsState()
  val pending = state.pending("permission:${request.id}")
  var confirmSave by rememberSaveable(request.id) { mutableStateOf(false) }
  DisposableEffect(confirmSave) { if (confirmSave) onModal(true); onDispose { if (confirmSave) onModal(false) } }
  if (confirmSave) {
    SuperDialog(
      title = "保存项目权限规则",
      show = confirmSave,
      onDismissRequest = { confirmSave = false }
    ) {
      Column(Modifier.padding(top = 8.dp)) {
        Text(
          "规则将保存在服务器项目中，适用于后续任务，可在会话菜单“已保存权限”中撤销。\n" + request.always.joinToString("\n"),
          style = MiuixTheme.textStyles.body2
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
          TextButton(text = "取消", onClick = { confirmSave = false })
          Spacer(Modifier.width(8.dp))
          TextButton(
            text = if (pending) "正在提交…" else "保存并允许", enabled = !pending,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = {
              confirmSave = false
              controller.replyPermission(request, "always")
            }
          )
        }
      }
    }
  }

  val isDangerous = request.action.contains("shell", true) || request.action.contains("write", true) || request.action.contains("delete", true)

  Card(
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(16.dp),
    colors = CardDefaults.defaultColors(
      color = if (isDangerous) MiuixColorTokens.WarningSubtle else MiuixTheme.colorScheme.surfaceContainer
    )
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      MiuixStatePill(TaskPhase.WAITING_PERMISSION, if (isDangerous) "需确认关键操作" else "等待操作授权")
      Spacer(Modifier.weight(1f))
      Text(
        request.action.ifBlank { "操作" },
        style = MiuixTheme.textStyles.footnote2.copy(
          color = MiuixTheme.colorScheme.primary,
          fontWeight = FontWeight.Bold
        )
      )
    }
    Spacer(Modifier.height(10.dp))
    Text("OpenCode 正在请求执行动作：", style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.height(8.dp))
    Text(
      text = request.detail,
      fontFamily = FontFamily.Monospace,
      style = MiuixTheme.textStyles.body2,
      modifier = Modifier
        .fillMaxWidth()
        .background(MiuixTheme.colorScheme.secondaryContainer, miuixSquircleShape(8.dp))
        .padding(10.dp)
    )
    Spacer(Modifier.height(12.dp))
    if (request.always.isNotEmpty()) {
      Text(
        text = "选择“始终允许”将在服务器保存规则：" + request.always.joinToString(", "),
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
      )
      Spacer(Modifier.height(10.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = { controller.replyPermission(request, "reject") }, enabled = !pending,
        colors = ButtonDefaults.buttonColors()
      ) {
        Text("拒绝")
      }
      Button(
        onClick = { controller.replyPermission(request, "once") }, enabled = !pending,
        colors = ButtonDefaults.buttonColorsPrimary()
      ) {
        Text("允许一次")
      }
      if (canSave && request.always.isNotEmpty()) {
        TextButton(
          text = "始终允许", enabled = !pending,
          onClick = { confirmSave = true }
        )
      }
    }
  }
}

@Composable
fun MiuixQuestionCard(request: QuestionRequest, controller: LagoonController) {
  val state by controller.state.collectAsState()
  val pending = state.pending("question:${request.id}")
  val answerSaver = remember(request.id) { Saver<List<List<String>>, String>(save = { rows -> org.json.JSONArray().apply { rows.forEach { put(org.json.JSONArray(it)) } }.toString() }, restore = { json ->
    val rows = org.json.JSONArray(json); (0 until rows.length()).map { i -> val row = rows.getJSONArray(i); (0 until row.length()).map { row.optString(it) } }
  }) }
  var answers by rememberSaveable(request.id, request.questions.hashCode(), stateSaver = answerSaver) { mutableStateOf(request.questions.map { question -> question.defaultAnswers().filter { value -> question.options.any { it.value == value } } }) }
  var custom by rememberSaveable(request.id, request.questions.hashCode()) { mutableStateOf(request.questions.map { question ->
    question.defaultAnswers().firstOrNull()?.takeIf { value -> question.options.none { it.value == value } }.orEmpty()
  }) }
  val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
  val answerRows = answers.mapIndexed { index, list -> custom[index].trim().takeIf { it.isNotEmpty() }?.let { if (request.questions[index].multiple) list + it else listOf(it) } ?: list }
  val fieldValues = if (request.form) request.questions.mapIndexed { i, q -> org.json.JSONObject(q.field).str("key") to answerRows[i] }.toMap() else emptyMap()

  Card(
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(16.dp),
    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
  ) {
    MiuixStatePill(TaskPhase.WAITING_QUESTION, "需要你的回答")
    request.questions.forEachIndexed { index, question ->
      val field = question.field.takeIf { it.isNotBlank() }?.let { org.json.JSONObject(it) }
      if (field != null && !formVisible(field, fieldValues)) return@forEachIndexed
      if (field?.str("type") == "external") {
        Text(question.title, style = MiuixTheme.textStyles.headline2)
        val url = field.str("url")
        if (url.startsWith("https://")) {
          TextButton(text = "打开外部表单页面", onClick = { uriHandler.openUri(url) })
        } else {
          Text("外部页面仅支持 HTTPS，请在服务器确认此项", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Warning))
        }
        return@forEachIndexed
      }
      Spacer(Modifier.height(10.dp))
      Text(question.title, style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold))
      question.options.forEach { option ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable {
              answers = answers.toMutableList().also { list ->
                list[index] = if (question.multiple) {
                  if (option.value in list[index]) list[index] - option.value else list[index] + option.value
                } else listOf(option.value)
              }
              if (!question.multiple) custom = custom.toMutableList().also { it[index] = "" }
            }
            .padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (question.multiple) {
            Checkbox(checked = option.value in answers[index], onCheckedChange = { isChecked ->
              answers = answers.toMutableList().also { list ->
                list[index] = if (isChecked) list[index] + option.value else list[index] - option.value
              }
            })
          } else {
            RadioButton(selected = option.value in answers[index], onClick = {
              answers = answers.toMutableList().also { list ->
                list[index] = listOf(option.value)
              }
              custom = custom.toMutableList().also { it[index] = "" }
            })
          }
          Spacer(Modifier.width(10.dp))
          Column {
            Text(option.label, style = MiuixTheme.textStyles.body2)
            if (option.description.isNotBlank()) {
              Text(option.description, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
            }
          }
        }
      }
      if (question.custom) {
        Spacer(Modifier.height(6.dp))
        TextField(
          value = custom[index],
          onValueChange = { value: String -> custom = custom.toMutableList().also { it[index] = value } },
          useLabelAsPlaceholder = true,
          label = "输入自定义回答…",
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = { controller.rejectQuestion(request) }, enabled = !pending,
        colors = ButtonDefaults.buttonColors()
      ) {
        Text("取消")
      }
      Button(
        onClick = {
          controller.replyQuestion(request, answers.mapIndexed { index, list ->
            val value = custom[index].trim()
            if (value.isBlank()) list else if (request.questions[index].multiple) list + value else listOf(value)
          })
        },
        enabled = !pending && (if (request.form) runCatching { formAnswer(request, answerRows) }.isSuccess else answers.indices.all { answers[it].isNotEmpty() || (request.questions[it].custom && custom[it].isNotBlank()) }),
        colors = ButtonDefaults.buttonColorsPrimary()
      ) {
        Text(if (pending) "正在提交…" else "提交回答")
      }
    }
  }
}


@Composable
private fun ChatComposer(state: LagoonState, controller: LagoonController, interactive: Boolean, onModal: (Boolean) -> Unit) {
  var value by rememberSaveable(state.serverId, state.sessionId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.draft)) }
  var picker by remember { mutableStateOf<String?>(null) }
  var command by rememberSaveable(state.serverId, state.sessionId) { mutableStateOf<String?>(null) }
  var query by rememberSaveable { mutableStateOf("") }
  LaunchedEffect(state.draft) { if (value.text != state.draft) value = TextFieldValue(state.draft, androidx.compose.ui.text.TextRange(state.draft.length)) }
  DisposableEffect(picker) { onModal(picker != null); onDispose { onModal(false) } }
  val task = state.tasks[state.sessionId]
  Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surfaceContainer).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
    if (state.cached && state.connected) Text("消息未同步，刷新后可发送", color = MiuixColorTokens.Warning, style = MiuixTheme.textStyles.footnote2)
    if (!state.connected) Text("离线 · 草稿保留，恢复连接后可发送", color = MiuixColorTokens.Warning, style = MiuixTheme.textStyles.footnote2)
    if (state.pending("send")) Text("正在发送…", style = MiuixTheme.textStyles.footnote2)
    state.references.forEach { ref -> Row(verticalAlignment = Alignment.CenterVertically) { Text(ref.path, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote2); TextButton(text = "移除", onClick = { controller.removeReference(ref.path) }) } }
    command?.let { name -> Row(verticalAlignment = Alignment.CenterVertically) { Text("/$name", Modifier.weight(1f), color = MiuixTheme.colorScheme.primary); TextButton(text = "取消命令", onClick = { command = null }) } }
    Row(verticalAlignment = Alignment.Bottom) {
      Box(Modifier.weight(1f).heightIn(min = 48.dp, max = 140.dp).padding(vertical = 10.dp)) {
        if (value.text.isEmpty()) Text("输入任务…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        BasicTextField(value, { next -> value = next; controller.updateDraft(next.text, state.serverId, state.sessionId) }, Modifier.fillMaxWidth(), textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface), cursorBrush = SolidColor(MiuixTheme.colorScheme.primary), maxLines = 5, readOnly = !interactive)
      }
      Spacer(Modifier.width(8.dp))
      if (task?.phase in TaskState.RUNNING_PHASES) TextButton(text = "停止", enabled = !state.pending("abort"), onClick = { controller.abort() }, colors = ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error))
      else Button(enabled = state.connected && !state.cached && task?.active != true && !state.pending("send") && (value.text.isNotBlank() || state.references.isNotEmpty()), onClick = {
        val draft = value.text
        val submitted = command?.let { "/$it $draft" } ?: draft
        controller.send(submitted, state.serverId, state.sessionId) { if (value.text == draft) { controller.updateDraft(""); value = TextFieldValue(""); command = null } }
      }, colors = ButtonDefaults.buttonColorsPrimary()) { Text("发送") }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
      IconButton(enabled = !state.pending("send"), onClick = { query = ""; controller.listFiles(); picker = "files" }) { Icon(MiuixIcons.Add, "添加文件引用") }
      TextButton(text = "命令", enabled = state.commands.isNotEmpty() && !state.pending("send"), onClick = { picker = "commands" })
      TextButton(text = state.agent ?: "默认 Agent", enabled = !state.pending("send"), onClick = { picker = "agents" })
      TextButton(text = state.model?.label ?: "默认模型", enabled = !state.pending("send"), onClick = { picker = "models" })
    }
    ActionError(state, "send")
  }
  picker?.let { which -> SuperBottomSheet(title = when (which) { "files" -> "引用文件"; "commands" -> "选择命令"; "agents" -> "选择 Agent"; else -> "选择模型" }, show = true, onDismissRequest = { picker = null }) {
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(16.dp)) {
      if (which == "files") {
        TextField(query, { query = it; controller.searchFiles(it) }, label = "搜索文件（至少两个字符）", modifier = Modifier.fillMaxWidth())
        val resource = if (query.length >= 2) "search" else "files"
        ResourceHint(state.resource(resource), "没有匹配文件", retry = { if (query.length >= 2) controller.searchFiles(query) else controller.listFiles() })
      } else if (which != "commands") ResourceHint(state.resource(if (which == "agents") "agents" else "models"), "没有可选项", retry = controller::reload)
      LazyColumn {
        when (which) {
          "files" -> {
            val nodes = (if (query.length >= 2) state.searchResults.map { FileNode(it, "file") } else state.files).distinctBy { it.path }
            items(nodes, key = { it.path }) { node -> TextButton(text = (if (node.type == "directory") "目录 · " else "") + node.path, onClick = {
              if (node.type == "directory") controller.listFiles(node.path) else { controller.addReference(node.path); picker = null }
            }) }
          }
          "commands" -> items(state.commands.distinctBy { it.name }, key = { it.name }) { cmd -> TextButton(text = "/${cmd.name} · ${cmd.description}", onClick = { command = cmd.name; picker = null }) }
          "agents" -> { item { TextButton(text = "沿用会话当前配置", onClick = { controller.chooseAgent(null); picker = null }) }; items(state.agents.distinctBy { it.name }, key = { it.name }) { agent -> TextButton(text = agent.name + " · " + agent.description, onClick = { controller.chooseAgent(agent.name); picker = null }) } }
          else -> { item { TextButton(text = "沿用会话当前配置", onClick = { controller.chooseModel(null); picker = null }) }; items(state.models.distinctBy { "${it.providerId}:${it.modelId}" }, key = { "${it.providerId}:${it.modelId}" }) { model -> TextButton(text = "${model.providerId} · ${model.label}", onClick = { controller.chooseModel(model); picker = null }) } }
        }
      }
    }
  } }
}

@Composable
private fun FilesPanel(state: LagoonState, controller: LagoonController, onReference: (String) -> Unit) {
  var query by rememberSaveable { mutableStateOf("") }
  val clipboard = LocalClipboardManager.current
  Column(Modifier.fillMaxSize()) {
    TextField(query, { query = it; controller.searchFiles(it) }, label = "搜索工程文件", modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) { TextButton(text = "上一级", onClick = { controller.listFiles(state.filePath.substringBeforeLast('/', ".")) }); Text(state.filePath, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote2) }
    ResourceHint(state.resource(if (query.length >= 2) "search" else "files"), "此目录没有文件", retry = { if (query.length >= 2) controller.searchFiles(query) else controller.listFiles() })
    if (state.fileBinary) { Text("当前无法预览此二进制文件"); TextButton(text = "引用文件", enabled = !state.pending("send"), onClick = { onReference(state.filePath) }) }
    state.fileText?.let { text -> Row { TextButton(text = "引用文件", onClick = { onReference(state.filePath) }); TextButton(text = "复制全文", onClick = { clipboard.setText(AnnotatedString(text)) }) }; VirtualText(text, Modifier.weight(1f)) }
      ?: LazyColumn { items((if (query.length >= 2) state.searchResults.map { FileNode(it, "file") } else state.files).distinctBy { it.path }, key = { it.path }) { node -> TextButton(text = (if (node.type == "directory") "目录 · " else "") + node.path, onClick = { if (node.type == "directory") controller.listFiles(node.path) else controller.readFile(node.path) }) } }
  }
}

internal fun editDraft(drafts: MutableMap<String, String>, key: String, value: String) {
  drafts[key] = value
  drafts["revision:$key"] = ((drafts["revision:$key"]?.toLongOrNull() ?: 0L) + 1).toString()
}
