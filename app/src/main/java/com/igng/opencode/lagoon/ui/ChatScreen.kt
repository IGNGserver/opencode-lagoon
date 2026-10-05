package com.igng.opencode.lagoon.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperListPopup
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.miuixCapsuleShape
import top.yukonga.miuix.kmp.theme.miuixShape

private enum class ChatSheet { CHANGES, FILES, CHILDREN, AGENTS }

@Composable
fun ChatScreen(state: LagoonState, controller: LagoonController, onBack: () -> Unit, onOpenChild: (String) -> Unit, onModal: (Boolean) -> Unit, interactive: Boolean = true) {
  val session = state.session
  var sheet by remember { mutableStateOf<ChatSheet?>(null) }
  var menu by remember { mutableStateOf(false) }
  var rename by rememberSaveable { mutableStateOf(false) }
  var delete by rememberSaveable { mutableStateOf(false) }
  var composerModal by remember { mutableStateOf(false) }
  var requestModal by remember { mutableStateOf(false) }
  val modal = sheet != null || menu || rename || delete || composerModal || requestModal
  DisposableEffect(modal) { onModal(modal); onDispose { onModal(false) } }
  fun openFile(path: String) { sheet = ChatSheet.FILES; controller.readFile(path) }
  if (session == null) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { Text("会话尚未加载或已关闭"); TextButton(text = "返回列表", onClick = onBack) }
    return
  }
  // ⋯ 分两段：查看会话的资源，以及对会话本身的操作。整理上下文 / 撤销 / 分享 / 分支不在手机上提供，已保存权限在设置页。
  val menuSections = listOf(
    MenuSection(buildList {
      add(MenuAction("子任务（${state.children.size}）", MiuixIcons.Layers) { sheet = ChatSheet.CHILDREN })
      if (state.capabilities.diff) add(MenuAction("改动（${state.changes.size}）", MiuixIcons.Merge) { sheet = ChatSheet.CHANGES })
      add(MenuAction("文件", MiuixIcons.Folder) { sheet = ChatSheet.FILES; controller.listFiles() })
      add(MenuAction("Agent：${state.agent ?: "默认"}", MiuixIcons.ContactsCircle) { sheet = ChatSheet.AGENTS })
    }),
    MenuSection(buildList {
      add(MenuAction(if (session.id in state.pinned) "取消置顶" else "置顶", if (session.id in state.pinned) MiuixIcons.Unpin else MiuixIcons.Pin) { controller.togglePin(session.id) })
      if (state.capabilities.supports(SessionAction.RENAME)) add(MenuAction("重命名", MiuixIcons.Rename) { rename = true })
      add(MenuAction("删除", MiuixIcons.Delete, danger = true) { delete = true })
    })
  )
  Column(Modifier.fillMaxSize()) {
    PageTopBar(state.title(session), onBack, subtitle = { SessionSubtitle(state, session) }) {
      Box {
        CircleIconButton(MiuixIcons.More, "会话操作", { menu = true })
        MenuPopup(menu, { menu = false }, menuSections)
      }
    }
    Conversation(state, controller, Modifier.weight(1f).fillMaxWidth(), ::openFile, { requestModal = it }, onOpenChild)
    ChatComposer(state, controller, interactive, onModal = { composerModal = it })
  }
  sheet?.let { which ->
    val resource = when (which) { ChatSheet.CHANGES -> "changes"; ChatSheet.FILES -> "files"; ChatSheet.CHILDREN -> "children"; ChatSheet.AGENTS -> "agents" }
    SuperBottomSheet(title = when (which) { ChatSheet.CHANGES -> "代码改动"; ChatSheet.FILES -> "文件"; ChatSheet.CHILDREN -> "子任务"; ChatSheet.AGENTS -> "选择 Agent" }, show = true, onDismissRequest = { sheet = null }) {
      Column(Modifier.fillMaxWidth().fillMaxHeight(if (which == ChatSheet.AGENTS) 0.6f else 0.72f).padding(vertical = 8.dp)) {
        if (which != ChatSheet.FILES) ResourceHint(state.resource(resource), "暂无记录", retry = controller::reload)
        when (which) {
          // Server lists may repeat an entry; LazyColumn crashes on a duplicate key, so keys never come from data alone.
          ChatSheet.CHANGES -> LazyColumn { itemsIndexed(state.changes, key = { index, change -> "$index:${change.path}" }) { _, change ->
            var expanded by rememberSaveable(change.path) { mutableStateOf(false) }
            BasicComponent(title = change.path, summary = "+${change.additions} −${change.deletions}", onClick = { expanded = !expanded })
            if (expanded) VirtualText(change.patch.ifBlank { change.after })
          } }
          ChatSheet.FILES -> FilesPanel(state, controller) { path -> controller.addReference(path); sheet = null }
          ChatSheet.CHILDREN -> LazyColumn { items(state.children.distinctBy { it.id }, key = { it.id }) { child ->
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp), insideMargin = PaddingValues(14.dp), onClick = { sheet = null; onOpenChild(child.id) }) {
              Text(state.title(child)); state.tasks[child.id]?.takeIf { it.active }?.let { MiuixStatePill(it.phase) }
            }
          } }
          ChatSheet.AGENTS -> LazyColumn {
            item { Card(Modifier.fillMaxWidth()) { ChoiceRow("跟随会话", "使用会话当前的 Agent", !state.agentChanged) { controller.chooseAgent(null); sheet = null } } }
            item { Spacer(Modifier.height(12.dp)) }
            item { Card(Modifier.fillMaxWidth()) { state.agents.forEach { agent ->
              ChoiceRow(agent.name, agent.description.takeIf(String::isNotBlank), state.agentChanged && state.agent == agent.name) { controller.chooseAgent(agent.name); sheet = null }
            } } }
          }
        }
      }
    }
  }
  if (rename) RenameSessionDialog(state, controller, session) { rename = false }
  if (delete) SuperDialog(title = "删除此会话？", show = true, onDismissRequest = { if (!state.pending("delete")) delete = false }) {
    Column { Text("会话消息将从服务器永久删除。\n${state.title(session)}"); ActionError(state, "delete")
      DialogActions("取消", { delete = false }, if (state.pending("delete")) "删除中…" else "永久删除", !state.pending("delete"), danger = true) { controller.deleteSession { delete = false; onBack() } }
    }
  }
}

/**
 * 新建会话：先进入空白页，发出第一条消息时才在服务器上创建会话（失败时正文和附件都保留）。
 * 目标项目默认取首页范围里的项目，「全部」时取最近活跃的项目，可在输入框下方切换。
 */
@Composable
fun DraftScreen(state: LagoonState, controller: LagoonController, onBack: () -> Unit, onModal: (Boolean) -> Unit, interactive: Boolean = true) {
  var targetPicker by remember { mutableStateOf(false) }
  var addProject by remember { mutableStateOf(false) }
  var composerModal by remember { mutableStateOf(false) }
  val modal = targetPicker || addProject || composerModal
  DisposableEffect(modal) { onModal(modal); onDispose { onModal(false) } }
  Column(Modifier.fillMaxSize()) {
    PageTopBar("新会话", onBack, subtitle = {
      // The subtitle is where the session will be created; tap it to pick another project.
      Row(Modifier.clip(miuixCapsuleShape()).clickable(enabled = state.connected) { targetPicker = true }.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(MiuixIcons.Folder, null, Modifier.size(14.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.width(4.dp))
        Text(listOfNotNull(state.server?.name, state.project?.name ?: "选择项目").joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        DropdownChevron(14.dp, description = "选择项目")
      }
    })
    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("要做点什么？", style = MiuixTheme.textStyles.title3)
        Spacer(Modifier.height(8.dp))
        Text(state.project?.let { "会在「${it.name}」中新建，发出第一条消息后才会创建会话" } ?: "先点标题下方选择一个项目",
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
          textAlign = androidx.compose.ui.text.style.TextAlign.Center)
      }
    }
    ChatComposer(state, controller, interactive, { composerModal = it }, busy = state.pending("create"), actionKey = "create", ready = state.project != null,
      // The created session inherits the text as its draft until the first send succeeds; each draft page
      // gets a fresh saveable key from MainActivity, so nothing needs clearing here.
      onSend = { text, _ -> controller.startSession("", text, state.agent.takeIf { state.agentChanged }, state.model.takeIf { state.modelChanged }) })
  }
  if (targetPicker) DraftTargetSheet(state, controller, { targetPicker = false }) { targetPicker = false; addProject = true }
  if (addProject) DirectoryBrowserSheet(state, controller, { addProject = false }) { addProject = false }
}

/** “服务器 · 项目” under a chat title, led by the session's live state (running spinner / waiting / failed). */
@Composable
private fun SessionSubtitle(state: LagoonState, session: Session) {
  val status = state.sessionStatus(session)
  val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
  when (status) {
    SessionStatus.RUNNING -> { InfiniteProgressIndicator(color = MiuixTheme.colorScheme.primary, size = 12.dp, strokeWidth = 1.5.dp, orbitingDotSize = 1.5.dp); Spacer(Modifier.width(6.dp)) }
    SessionStatus.WAITING_PERMISSION, SessionStatus.WAITING_QUESTION, SessionStatus.FAILED ->
      Text("${status.label} · ", style = MiuixTheme.textStyles.footnote1.copy(color = if (status == SessionStatus.FAILED) MiuixColorTokens.Error else MiuixColorTokens.Warning))
    else -> {}
  }
  Text(listOfNotNull(state.server?.name, resolveSessionProject(session, state.projects)?.name).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
    style = MiuixTheme.textStyles.footnote1.copy(color = muted))
}

/** MIUIX dialog footer: two equal-width buttons, the confirming one in the primary (or error) color. */
@Composable
internal fun DialogActions(cancel: String, onCancel: () -> Unit, confirm: String, enabled: Boolean = true, danger: Boolean = false, onConfirm: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    TextButton(text = cancel, onClick = onCancel, modifier = Modifier.weight(1f))
    TextButton(text = confirm, onClick = onConfirm, enabled = enabled, modifier = Modifier.weight(1f),
      colors = if (danger) ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error) else ButtonDefaults.textButtonColorsPrimary())
  }
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
          "规则将保存在服务器项目中，适用于后续任务，可在「设置 › 已保存的项目权限」中撤销。\n" + request.always.joinToString("\n"),
          style = MiuixTheme.textStyles.body2
        )
        DialogActions("取消", { confirmSave = false }, if (pending) "正在提交…" else "保存并允许", !pending) {
          confirmSave = false
          controller.replyPermission(request, "always")
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
      TextButton(text = "拒绝", onClick = { controller.replyPermission(request, "reject") }, enabled = !pending, modifier = Modifier.weight(1f))
      TextButton(text = "允许一次", onClick = { controller.replyPermission(request, "once") }, enabled = !pending, modifier = Modifier.weight(1f),
        colors = ButtonDefaults.textButtonColorsPrimary())
    }
    if (canSave && request.always.isNotEmpty()) {
      TextButton(text = "始终允许", enabled = !pending, onClick = { confirmSave = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
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
    DialogActions("取消", { if (!pending) controller.rejectQuestion(request) }, if (pending) "正在提交…" else "提交回答",
      enabled = !pending && (if (request.form) runCatching { formAnswer(request, answerRows) }.isSuccess else answers.indices.all { answers[it].isNotEmpty() || (request.questions[it].custom && custom[it].isNotBlank()) })) {
      controller.replyQuestion(request, answers.mapIndexed { index, list ->
        val value = custom[index].trim()
        if (value.isBlank()) list else if (request.questions[index].multiple) list + value else listOf(value)
      })
    }
  }
}



/**
 * One rounded input box like the desktop composer: attachment tray, text, then ＋ / model / send.
 * Commands, Agent and server-file references no longer sit here; Agent lives in the ⋯ menu.
 */
@Composable
private fun ChatComposer(
  state: LagoonState, controller: LagoonController, interactive: Boolean, onModal: (Boolean) -> Unit,
  busy: Boolean = state.pending("send"), actionKey: String = "send", ready: Boolean = true,
  onSend: (String, () -> Unit) -> Unit = { text, clear -> controller.send(text, state.serverId, state.sessionId, clear) },
  footer: (@Composable RowScope.() -> Unit)? = null
) {
  var value by rememberSaveable(state.serverId, state.sessionId, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(state.draft)) }
  var picker by remember { mutableStateOf<String?>(null) }
  var attachMenu by remember { mutableStateOf(false) }
  val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { controller.addLocalAttachments(it) }
  val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { controller.addLocalAttachments(it) }
  LaunchedEffect(state.draft) { if (value.text != state.draft) value = TextFieldValue(state.draft, androidx.compose.ui.text.TextRange(state.draft.length)) }
  DisposableEffect(picker, attachMenu) { onModal(picker != null || attachMenu); onDispose { onModal(false) } }
  val task = state.tasks[state.sessionId]
  val sending = busy
  val hasContent = value.text.isNotBlank() || state.references.isNotEmpty() || state.attachments.isNotEmpty()
  // Like the official composer, a prompt typed while the agent works steers the running turn.
  val canSend = ready && state.connected && !state.cached && !sending && hasContent
  Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)) {
    val hint = when {
      !state.connected -> "离线 · 草稿保留，恢复连接后可发送"
      state.cached -> "消息未同步，刷新后可发送"
      sending -> "正在发送…"
      state.attachments.any { it.image } && state.modelCatalog.firstOrNull { it.choice.modelId == state.model?.modelId && it.choice.providerId == state.model?.providerId }?.imageInput == false -> "当前模型不支持图片，发送前请换一个模型"
      else -> null
    }
    hint?.let { Text(it, Modifier.padding(start = 8.dp, bottom = 4.dp), color = if (sending) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixColorTokens.Warning, style = MiuixTheme.textStyles.footnote2) }
    Column(Modifier.fillMaxWidth().clip(miuixShape(20.dp)).background(MiuixTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 8.dp)) {
      if (state.attachments.isNotEmpty() || state.references.isNotEmpty()) AttachmentTray(state, controller)
      Box(Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 160.dp).padding(horizontal = 4.dp, vertical = 8.dp)) {
        if (value.text.isEmpty()) Text("输入任务…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        BasicTextField(value, { next -> value = next; controller.updateDraft(next.text, state.serverId, state.sessionId) }, Modifier.fillMaxWidth(),
          textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface), cursorBrush = SolidColor(MiuixTheme.colorScheme.primary), maxLines = 6, readOnly = !interactive)
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
          RoundAction(MiuixIcons.Add, "添加图片或文件", { attachMenu = true }, enabled = !sending && interactive,
            container = MiuixTheme.colorScheme.secondaryContainer, content = MiuixTheme.colorScheme.onSecondaryContainer)
          SuperListPopup(show = attachMenu, onDismissRequest = { attachMenu = false }) {
            ListPopupColumn {
              val options = listOf("图片", "文件")
              options.forEachIndexed { index, label ->
                DropdownImpl(text = label, optionSize = options.size, isSelected = false, index = index, onSelectedIndexChange = {
                  attachMenu = false
                  if (index == 0) images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                  else documents.launch(arrayOf("*/*"))
                })
              }
            }
          }
        }
        Spacer(Modifier.width(8.dp))
        CapsuleSelector(state.model?.label ?: "默认模型", { picker = "models" }, Modifier.weight(1f, fill = false), enabled = !sending && interactive)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        if (task?.phase in TaskState.RUNNING_PHASES && !hasContent) {
          RoundAction(null, "停止", { controller.abort() }, enabled = !state.pending("abort"), container = MiuixTheme.colorScheme.error) {
            Box(Modifier.size(12.dp).background(MiuixTheme.colorScheme.onError, miuixShape(3.dp)))
          }
        } else RoundAction(MiuixIcons.Send, "发送", {
          val draft = value.text
          onSend(draft) { if (value.text == draft) { controller.updateDraft(""); value = TextFieldValue("") } }
        }, enabled = canSend)
      }
    }
    ActionError(state, actionKey)
    footer?.let { Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically, content = it) }
  }
  when (picker) {
    "models" -> ModelPickerSheet(state, controller, { picker = null }) { picker = "manage" }
    "manage" -> ManageModelsSheet(state, controller) { picker = null }
  }
}

/** Pending phone attachments (thumbnails / file chips) and server file references, each removable. */
@Composable
private fun AttachmentTray(state: LagoonState, controller: LagoonController) {
  Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    state.attachments.forEach { item ->
      if (item.image) ImageThumb(item) { controller.removeLocalAttachment(item.id) }
      else FileChip(item.name, "${(item.size + 1023) / 1024} KB") { controller.removeLocalAttachment(item.id) }
    }
    state.references.forEach { ref -> FileChip(ref.path.substringAfterLast('/'), "服务器文件") { controller.removeReference(ref.path) } }
  }
}

@Composable
private fun ImageThumb(item: LocalAttachment, onRemove: () -> Unit) {
  val bitmap by produceState<android.graphics.Bitmap?>(null, item.path) {
    value = withContext(Dispatchers.IO) { runCatching {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(item.path, bounds)
      BitmapFactory.decodeFile(item.path, BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 256).coerceAtLeast(1) })
    }.getOrNull() }
  }
  Box(Modifier.size(56.dp).clip(miuixShape(12.dp)).background(MiuixTheme.colorScheme.secondaryContainer)) {
    bitmap?.let { Image(it.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    RemoveBadge(Modifier.align(Alignment.TopEnd), onRemove)
  }
}

@Composable
private fun FileChip(name: String, detail: String, onRemove: () -> Unit) {
  Row(Modifier.height(56.dp).clip(miuixShape(12.dp)).background(MiuixTheme.colorScheme.secondaryContainer).padding(start = 12.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically) {
    Icon(MiuixIcons.File, null, Modifier.size(18.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Spacer(Modifier.width(8.dp))
    Column(Modifier.widthIn(max = 160.dp)) {
      Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium))
      Text(detail, maxLines = 1, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
    RemoveBadge(Modifier, onRemove)
  }
}

@Composable
private fun RemoveBadge(modifier: Modifier, onRemove: () -> Unit) {
  Box(modifier.size(28.dp).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
    Box(Modifier.size(18.dp).clip(miuixCapsuleShape()).background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
      Icon(MiuixIcons.Close, "移除", Modifier.size(12.dp), tint = MiuixTheme.colorScheme.surface)
    }
  }
}

@Composable
private fun FilesPanel(state: LagoonState, controller: LagoonController, onReference: (String) -> Unit) {
  var query by rememberSaveable { mutableStateOf("") }
  Column(Modifier.fillMaxSize()) {
    TextField(query, { query = it; controller.searchFiles(it) }, label = "搜索工程文件", useLabelAsPlaceholder = true, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) { TextButton(text = "上一级", onClick = { controller.listFiles(state.filePath.substringBeforeLast('/', ".")) }); Spacer(Modifier.width(8.dp)); Text(state.filePath, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.footnote2) }
    ResourceHint(state.resource(if (query.length >= 2) "search" else "files"), "此目录没有文件", retry = { if (query.length >= 2) controller.searchFiles(query) else controller.listFiles() })
    if (state.fileBinary) { Text("当前无法预览此二进制文件"); TextButton(text = "引用到消息", enabled = !state.pending("send"), onClick = { onReference(state.filePath) }) }
    state.fileText?.let { text -> TextButton(text = "引用到消息", onClick = { onReference(state.filePath) }); VirtualText(text, Modifier.weight(1f)) }
      ?: LazyColumn { items((if (query.length >= 2) state.searchResults.map { FileNode(it, "file") } else state.files).distinctBy { it.path }, key = { it.path }) { node ->
        BasicComponent(title = node.path.substringAfterLast('/').ifBlank { node.path }, summary = if (node.type == "directory") "文件夹" else node.path,
          onClick = { if (node.type == "directory") controller.listFiles(node.path) else controller.readFile(node.path) })
      } }
  }
}

internal fun editDraft(drafts: MutableMap<String, String>, key: String, value: String) {
  drafts[key] = value
  drafts["revision:$key"] = ((drafts["revision:$key"]?.toLongOrNull() ?: 0L) + 1).toString()
}
