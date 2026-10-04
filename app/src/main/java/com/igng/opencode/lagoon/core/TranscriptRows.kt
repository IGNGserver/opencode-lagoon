package com.igng.opencode.lagoon.core

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One expandable body line of a tool/change row. */
data class ToolSection(val label: String, val text: String, val code: Boolean = true, val copyText: String? = null)

/**
 * UI-independent transcript projection. Ports the official OpenCode session client
 * (`packages/session-ui`): turn grouping, part visibility, context-tool grouping and the
 * default-collapsed policy for tool bodies. Row kinds:
 * time | user | text | reasoning | meta | divider | thinking | error | note |
 * tool | tool-body | context-group | context-item | tool-summary | diff-summary | diff-file
 */
data class TranscriptRow(
  val key: String,
  val kind: String,
  val title: String = "",
  val subtitle: String = "",
  val args: List<String> = emptyList(),
  val status: String = "",
  val text: String = "",
  val attachments: List<Attachment> = emptyList(),
  val meta: String = "",
  val copyText: String? = null
)

object TranscriptRows {
  /** Tool runs of at least this many calls fold behind one summary row. */
  const val SUMMARY_THRESHOLD = 8
  private const val TIME_GAP_MS = 30 * 60 * 1000L
  private const val MAX_DIFF_FILES = 10

  private val CONTEXT_TOOLS = setOf("read", "glob", "grep", "list")
  private val HIDDEN_TOOLS = setOf("todowrite")
  private val EDIT_TOOLS = setOf("edit", "write", "patch", "apply_patch")
  private val FINISHED_TOOLS = setOf("completed", "error")
  private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault())

  fun build(messages: List<Message>, expanded: Set<String> = emptySet(), working: Boolean = false): List<TranscriptRow> {
    val turns = mutableListOf<MutableList<Message>>()
    messages.filter { it.isDisplayable }.forEach { message ->
      if (message.role == "user" || turns.isEmpty()) turns += mutableListOf(message) else turns.last() += message
    }
    val rows = mutableListOf<TranscriptRow>()
    var previousEnd = 0L
    turns.forEachIndexed { index, turn ->
      val created = turn.first().created
      if (index == 0 || created - previousEnd >= TIME_GAP_MS) rows += TranscriptRow("time:${turn.first().id}", "time", text = formatTime(created))
      previousEnd = turn.maxOf { it.completedAt ?: it.created }
      rows += turnRows(turn, "turn:${turn.first().id}", expanded, working)
    }
    return rows.withUniqueKeys()
  }

  /**
   * A part's identity within the transcript. Part ids are not unique on their own: V2 text and
   * reasoning parts carry no id at all, and providers may reuse a tool call id across messages of one
   * turn. Scoping by message and falling back to the position keeps keys unique and stable.
   */
  private fun partRef(message: Message, index: Int, part: MessagePart): String = "${message.id}/${part.id.ifBlank { "#$index" }}"

  /** LazyColumn rejects duplicate keys with a crash; never let server data reach it un-deduplicated. */
  private fun List<TranscriptRow>.withUniqueKeys(): List<TranscriptRow> {
    val seen = HashMap<String, Int>(size * 2)
    return map { row ->
      val count = seen.merge(row.key, 1, Int::plus)!!
      if (count == 1) row else row.copy(key = "${row.key}~$count")
    }
  }

  private fun turnRows(turn: List<Message>, turnKey: String, expanded: Set<String>, working: Boolean): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    val users = turn.filter { it.role == "user" }
    val assistants = turn.filter { it.role != "user" }
    users.forEach { user ->
      val text = user.parts.filter { it.type == "text" || it.type == "user" }.joinToString("\n") { it.text }.trim()
      val attachments = (user.parts.filter { it.type == "file" }.map { Attachment(it.path, it.mime, it.title) } + user.parts.flatMap { it.attachments }).distinctBy { it.url }
      rows += TranscriptRow("user:${user.id}", "user", text = text, attachments = attachments,
        meta = listOfNotNull(user.agent, user.model?.label, formatTime(user.created).substringAfter(' ')).filter { it.isNotBlank() }.joinToString(" · "),
        copyText = text.takeIf { it.isNotBlank() })
    }

    val body = mutableListOf<TranscriptRow>()
    val run = mutableListOf<Pair<String, MessagePart>>()
    fun flush() {
      if (run.isEmpty()) return
      val folded = mutableListOf<TranscriptRow>()
      var index = 0
      while (index < run.size) {
        val (ref, part) = run[index]
        if (part.tool in CONTEXT_TOOLS) {
          val group = mutableListOf<Pair<String, MessagePart>>()
          while (index < run.size && run[index].second.tool in CONTEXT_TOOLS) group += run[index++]
          folded += contextGroupRows(turnKey, group, expanded)
        } else {
          folded += toolRows(turnKey, ref, part, expanded)
          index++
        }
      }
      if (run.size >= SUMMARY_THRESHOLD) {
        val key = "$turnKey:summary:${run.first().first}"
        body += TranscriptRow(key, "tool-summary", title = "已处理 ${run.size} 个操作")
        if (key in expanded) body += folded
      } else body += folded
      run.clear()
    }
    assistants.forEach { message ->
      message.parts.forEachIndexed { index, part ->
        when {
          part.type == "tool" && (part.tool in HIDDEN_TOOLS || (part.tool == "question" && part.status !in FINISHED_TOOLS)) -> Unit
          part.type == "tool" && part.isDisplayable -> run += partRef(message, index, part) to part
          else -> {
            flush()
            body += partRows(turnKey, partRef(message, index, part), part)
          }
        }
      }
    }
    flush()
    rows += body

    if (assistants.any { it.finish == "aborted" || it.error?.contains("abort", true) == true }) {
      rows += TranscriptRow("$turnKey:interrupted", "divider", text = "本轮已中断")
    }
    assistants.mapNotNull { it.error }.filterNot { it.contains("abort", true) }.joinToString("\n").takeIf { it.isNotBlank() }?.let {
      rows += TranscriptRow("$turnKey:error", "error", text = unwrapError(it))
    }
    if (assistants.isNotEmpty()) {
      val finished = assistants.all { it.completedAt != null }
      val duration = if (finished) turn.maxOf { it.completedAt ?: 0L } - turn.minOf { it.created } else 0L
      val meta = listOfNotNull(
        assistants.lastOrNull()?.agent,
        assistants.lastOrNull()?.model?.label,
        durationText(duration),
        assistants.lastOrNull()?.let { formatTime(it.completedAt ?: it.created).substringAfter(' ') }
      ).filter { it.isNotBlank() }.joinToString(" · ")
      val copy = body.filter { it.kind == "text" }.joinToString("\n\n") { it.text }
      rows += TranscriptRow("$turnKey:meta", "meta", meta = meta, copyText = copy.takeIf { it.isNotBlank() })
    }
    if (!working) {
      val edits = assistants.flatMap { message -> message.parts.mapIndexed { index, part -> partRef(message, index, part) to part } }
        .filter { (_, it) -> it.tool in EDIT_TOOLS && (it.patch.isNotBlank() || it.files.isNotEmpty() || inputString(it, "filePath").isNotBlank()) }
      if (edits.isNotEmpty()) {
        val key = "$turnKey:diff"
        val files = edits.flatMap { (_, edit) -> edit.files.ifEmpty { listOf(inputString(edit, "filePath")) } }.filter { it.isNotBlank() }.distinct()
        rows += TranscriptRow(key, "diff-summary", title = "本轮改动 ${files.size} 个文件", subtitle = files.take(3).joinToString("、"))
        if (key in expanded) edits.take(MAX_DIFF_FILES).forEach { (ref, edit) ->
          val path = inputString(edit, "filePath").ifBlank { edit.files.firstOrNull().orEmpty() }.ifBlank { "改动" }
          val patch = edit.patch.ifBlank { edit.output }
          rows += TranscriptRow("$key:$ref", "diff-file", title = path)
          MarkdownBlocks.chunks(patch).forEachIndexed { index, chunk ->
            rows += TranscriptRow("$key:$ref:$index", "tool-body", title = if (index == 0) "改动" else "", text = chunk,
              copyText = patch.takeIf { index == 0 })
          }
        }
      }
    }
    if (working && body.none { it.kind != "meta" }) rows += TranscriptRow("$turnKey:thinking", "thinking", text = "运行中…")
    return rows
  }

  private fun partRows(turnKey: String, ref: String, part: MessagePart): List<TranscriptRow> {
    val key = "$turnKey:$ref"
    return when {
      part.type == "text" && part.text.isNotBlank() -> listOf(TranscriptRow(key, "text", text = part.text.trim()))
      part.type == "reasoning" && part.text.isNotBlank() -> listOf(TranscriptRow(key, "reasoning", text = part.text.trim()))
      part.type == "patch" || part.type == "diff" -> {
        val patch = part.patch.ifBlank { part.text }.ifBlank { part.output }
        listOf(TranscriptRow(key, "tool", title = "代码改动", subtitle = part.files.joinToString("、").take(80), status = part.status,
          attachments = part.files.map { Attachment(it, "", it.substringAfterLast('/')) })) +
          MarkdownBlocks.chunks(patch).mapIndexed { index, chunk ->
            TranscriptRow("$key:$index", "tool-body", title = if (index == 0) "改动" else "", text = chunk, copyText = patch.takeIf { index == 0 })
          }
      }
      part.type == "file" -> listOf(TranscriptRow(key, "attachment", attachments = listOf(Attachment(part.path, part.mime, part.title))))
      part.type == "compaction" -> listOf(TranscriptRow(key, "divider", text = "上下文已整理"))
      part.type == "subtask" || part.type == "agent" -> listOf(TranscriptRow(key, "note", title = "子任务", text = part.text.ifBlank { part.title }.trim()))
      part.text.isNotBlank() -> listOf(TranscriptRow(key, "text", text = part.text.trim()))
      else -> emptyList()
    }
  }

  private fun contextGroupRows(turnKey: String, refs: List<Pair<String, MessagePart>>, expanded: Set<String>): List<TranscriptRow> {
    val key = "$turnKey:ctx:${refs.first().first}"
    val parts = refs.map { it.second }
    val read = parts.count { it.tool == "read" }
    val search = parts.count { it.tool == "glob" || it.tool == "grep" }
    val list = parts.count { it.tool == "list" }
    val summary = listOfNotNull(
      "$read 个读取".takeIf { read > 0 }, "$search 个搜索".takeIf { search > 0 }, "$list 个列表".takeIf { list > 0 }
    ).joinToString("、").ifBlank { "无" }
    return listOf(TranscriptRow(key, "context-group", title = "已收集上下文", subtitle = summary, status = parts.last().status)) +
      if (key !in expanded) emptyList() else refs.map { (ref, part) ->
        val (title, subtitle) = toolInfo(part)
        TranscriptRow("$key:$ref", "context-item", title = title, subtitle = subtitle, args = toolArgs(part), status = part.status)
      }
  }

  private fun toolRows(turnKey: String, ref: String, part: MessagePart, expanded: Set<String>): List<TranscriptRow> {
    val key = "$turnKey:tool:$ref"
    val (title, subtitle) = toolInfo(part)
    val row = TranscriptRow(key, "tool", title = title, subtitle = subtitle, args = toolArgs(part), status = part.status,
      attachments = part.files.map { Attachment(it, "", it.substringAfterLast('/')) } + part.attachments)
    return listOf(row) + if (key !in expanded) emptyList() else toolSections(part).flatMap { section ->
      MarkdownBlocks.chunks(section.text).mapIndexed { index, chunk ->
        TranscriptRow("$key:${section.label}:$index", "tool-body", title = if (index == 0) section.label else "", text = chunk,
          copyText = section.copyText?.takeIf { index == 0 })
      }
    }
  }

  internal fun toolSections(part: MessagePart): List<ToolSection> {
    val input = inputObject(part)
    val command = input?.str("command").orEmpty().ifBlank { part.input.takeIf { !it.trimStart().startsWith("{") }.orEmpty() }
    val sections = mutableListOf<ToolSection>()
    when (part.tool) {
      "bash", "shell" -> {
        val output = part.output.ifBlank { input?.str("output").orEmpty() }
        sections += ToolSection("输出", "$ ${command.ifBlank { part.text }}\n\n${output}".trim(), copyText = output.takeIf { it.isNotBlank() })
      }
      "edit", "write", "patch", "apply_patch" -> {
        val patch = part.patch.ifBlank { input?.str("patch").orEmpty() }.ifBlank { part.output }
        if (patch.isNotBlank()) sections += ToolSection("改动", patch, copyText = patch)
        if (part.error.isNotBlank()) sections += ToolSection("错误", part.error, copyText = part.error)
      }
      else -> {
        if (part.output.isNotBlank()) sections += ToolSection("输出", part.output, copyText = part.output)
        if (part.error.isNotBlank()) sections += ToolSection("错误", part.error, copyText = part.error)
        if (sections.isEmpty() && part.input.isNotBlank() && part.tool.isNotBlank()) sections += ToolSection("输入", part.input, copyText = part.input)
      }
    }
    return sections.filter { it.text.isNotBlank() }
  }

  internal fun toolInfo(part: MessagePart): Pair<String, String> {
    val input = inputObject(part)
    fun value(key: String) = input?.str(key).orEmpty()
    return when (part.tool) {
      "read" -> "读取文件" to value("filePath").ifBlank { part.title }.ifBlank { part.path }.substringAfterLast('/')
      "list" -> "列出目录" to value("path").ifBlank { part.path }
      "glob" -> "搜索文件" to value("pattern")
      "grep" -> "搜索内容" to value("pattern").ifBlank { part.title }
      "webfetch" -> "抓取网页" to value("url").ifBlank { part.path }
      "websearch" -> "网络搜索" to value("query").ifBlank { part.title }
      "task", "subagent" -> "子任务" to value("description").ifBlank { part.title }
      "bash", "shell" -> "命令行" to command(part).lineSequence().firstOrNull().orEmpty()
      "edit" -> "编辑文件" to value("filePath").ifBlank { part.path }.substringAfterLast('/')
      "write" -> "写入文件" to value("filePath").ifBlank { part.path }.substringAfterLast('/')
      "patch", "apply_patch" -> "应用补丁" to value("files").ifBlank { part.files.size.takeIf { it > 0 }?.let { "$it 个文件" }.orEmpty() }
      "question" -> "提问" to part.title
      "skill" -> "技能" to value("name").ifBlank { part.title }
      else -> part.tool.ifBlank { "工具" } to part.title.ifBlank { part.path }
    }
  }

  internal fun toolArgs(part: MessagePart): List<String> {
    val input = inputObject(part) ?: return emptyList()
    val args = mutableListOf<String>()
    if (part.tool == "read") {
      if (input.has("offset")) args += "offset=${input.str("offset")}"
      if (input.has("limit")) args += "limit=${input.str("limit")}"
    }
    if (part.tool == "grep" && input.str("include").isNotBlank()) args += "include=${input.str("include")}"
    return args
  }

  /** Strips a JSON error envelope down to its human-readable message (official `unwrap`). */
  internal fun unwrapError(message: String): String {
    val text = message.removePrefix("Error: ").trim()
    val json = runCatching { JSONObject(text) }.getOrNull() ?: runCatching {
      val start = text.indexOf('{'); val end = text.lastIndexOf('}')
      if (start in 0 until end) JSONObject(text.substring(start, end + 1)) else null
    }.getOrNull() ?: return text
    val error = json.optJSONObject("error")
    val errorText = error?.str("message")?.ifBlank { error.str("type") }.orEmpty().ifBlank { error?.toString().orEmpty() }
    return errorText.ifBlank { json.str("message") }.ifBlank { json.str("error") }.ifBlank { text }
  }

  private fun command(part: MessagePart): String = inputObject(part)?.str("command").orEmpty()
    .ifBlank { part.input.takeIf { !it.trimStart().startsWith("{") }.orEmpty() }.ifBlank { part.text }

  private fun inputObject(part: MessagePart): JSONObject? = part.input.trim().takeIf { it.startsWith("{") }
    ?.let { runCatching { JSONObject(it) }.getOrNull() }

  private fun inputString(part: MessagePart, key: String): String = inputObject(part)?.str(key).orEmpty().ifBlank {
    if (key == "filePath") part.path else ""
  }

  private fun formatTime(millis: Long): String = clock.format(Instant.ofEpochMilli(if (millis > 0) millis else System.currentTimeMillis()))

  private fun durationText(ms: Long): String = when {
    ms <= 0 -> ""
    ms < 60_000 -> "用时 ${ms / 1000} 秒"
    else -> "用时 ${ms / 60_000} 分 ${(ms % 60_000) / 1000} 秒"
  }
}
