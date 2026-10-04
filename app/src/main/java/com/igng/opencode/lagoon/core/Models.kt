package com.igng.opencode.lagoon.core

import org.json.JSONArray
import org.json.JSONObject

data class ServerProfile(
  val id: String,
  val name: String,
  val url: String,
  val username: String = "opencode",
  val autoConnect: Boolean = true,
  val notifications: Boolean = true,
  val allowCleartext: Boolean = false,
  /** 启用荣耀灵动胶囊通道（需荣耀白名单，默认关闭）。 */
  val islandHonor: Boolean = false,
  /** 启用 OPPO ColorOS 15 流体云通道（需 serviceId，默认关闭）。 */
  val islandOppoFluidCloud: Boolean = false
)

data class Project(val id: String, val directory: String, val name: String)
data class Session(val id: String, val directory: String, val title: String, val updated: Long, val parentId: String? = null,
  val projectId: String? = null, val created: Long = 0, val archived: Boolean = false,
  val agent: String? = null, val model: ModelChoice? = null)
data class Message(val id: String, val role: String, val created: Long, val parts: List<MessagePart>, val error: String? = null,
  val agent: String? = null, val model: ModelChoice? = null, val completedAt: Long? = null, val finish: String? = null)
data class Attachment(val url: String, val mime: String = "", val name: String = "")
data class MessagePart(
  val id: String,
  val type: String,
  val text: String = "",
  val tool: String = "",
  val title: String = "",
  val status: String = "",
  val input: String = "",
  val output: String = "",
  val path: String = "",
  val error: String = "",
  val patch: String = "",
  val files: List<String> = emptyList(),
  val mime: String = "", val attachments: List<Attachment> = emptyList()
)
data class PermissionRequest(
  val id: String,
  val sessionId: String,
  val directory: String,
  val action: String,
  val detail: String,
  val always: List<String> = emptyList(),
  val toolMessageId: String = "",
  val toolCallId: String = ""
)
data class SavedPermission(val id: String, val projectId: String, val action: String, val resource: String)
data class QuestionOption(val label: String, val description: String, val value: String = label)
data class QuestionPrompt(
  val title: String,
  val options: List<QuestionOption>,
  val multiple: Boolean,
  val custom: Boolean = false,
  val field: String = ""
)
data class QuestionRequest(val id: String, val sessionId: String, val directory: String, val questions: List<QuestionPrompt>, val form: Boolean = false)
data class TodoItem(val content: String, val status: String, val priority: String)
data class FileChange(
  val path: String,
  val after: String,
  val additions: Int,
  val deletions: Int,
  val patch: String = ""
)
data class FileNode(val path: String, val type: String)
data class FileContent(val type: String, val content: String)
data class ModelChoice(val providerId: String, val modelId: String, val label: String)
data class AgentChoice(val name: String, val description: String)
data class CommandChoice(val name: String, val description: String)

enum class TaskPhase { IDLE, THINKING, TOOL, SUBAGENT, TESTING, WAITING_PERMISSION, WAITING_QUESTION, COMPLETED, FAILED, ABORTED, DISCONNECTED }
data class TaskState(val sessionId: String, val phase: TaskPhase, val detail: String = "", val since: Long = System.currentTimeMillis(), val finishedAt: Long? = null) {
  val active: Boolean get() = when (phase) {
    TaskPhase.THINKING, TaskPhase.TOOL, TaskPhase.SUBAGENT, TaskPhase.TESTING, TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION -> true
    else -> false
  }

  companion object {
    /** The single user-facing label for every running phase (thinking/tool/subagent/testing alike). */
    const val RUNNING_DETAIL = "运行中"
    /** Phases where the agent is actively working, without an outstanding user prompt. */
    val RUNNING_PHASES: Set<TaskPhase> = setOf(TaskPhase.THINKING, TaskPhase.TOOL, TaskPhase.SUBAGENT, TaskPhase.TESTING)
    /** Phases waiting on a user decision. */
    val WAITING_PHASES: Set<TaskPhase> = setOf(TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION)
    /** Union of [RUNNING_PHASES] and [WAITING_PHASES], matching [active]. */
    val ACTIVE_PHASES: Set<TaskPhase> = RUNNING_PHASES + WAITING_PHASES
  }
}

internal fun JSONObject.str(key: String): String = optString(key).takeUnless { it == "null" } ?: ""
internal fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
internal fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.longPath(parent: String, child: String): Long = obj(parent).optLong(child, 0)
internal fun JSONObject.errorMessage(key: String = "error"): String {
  val direct = str("message")
  if (direct.isNotBlank()) return direct
  val value = opt(key)
  if (value is JSONObject) {
    return value.str("message").ifBlank { value.obj("data").str("message") }
  }
  return value?.toString().orEmpty().takeUnless { it == "null" }.orEmpty()
}

internal fun JSONObject.valueText(key: String): String {
  val value = opt(key) ?: return ""
  return when (value) {
    is JSONObject -> value.toString(2)
    is JSONArray -> value.toString(2)
    JSONObject.NULL -> ""
    else -> value.toString()
  }
}

internal fun JSONObject.toProject(): Project {
  val directory = str("worktree").ifBlank { str("directory") }
  return Project(str("id"), directory, str("name").ifBlank { directory.substringAfterLast('/') })
}
internal fun JSONObject.toSession(): Session = Session(
  str("id"), str("directory").ifBlank { obj("location").str("directory").let { root -> str("subpath").takeIf { it.isNotBlank() }?.let { "$root/${it.trim('/')}" } ?: root } },
  str("title"), longPath("time", "updated"), str("parentID").ifBlank { null },
  str("projectID").ifBlank { obj("location").obj("project").str("id") }.ifBlank { null },
  longPath("time", "created"), longPath("time", "archived") > 0,
  str("agent").ifBlank { null }, obj("model").toModelChoice()
)
internal fun JSONObject.toModelChoice(): ModelChoice? {
  val provider = str("providerID")
  val model = str("modelID").ifBlank { str("id") }
  return if (provider.isBlank() || model.isBlank()) null else ModelChoice(provider, model, str("name").ifBlank { model })
}
internal fun JSONArray.toAttachments(): List<Attachment> = objects().mapNotNull {
  val url = it.str("uri").ifBlank { it.str("url") }.ifBlank { it.str("path") }
  if (url.isBlank()) null else Attachment(url, it.str("mime").ifBlank { it.str("mediaType") }, it.str("filename").ifBlank { it.str("name") })
}
internal fun JSONArray.contentText(): String = objects().mapNotNull { item ->
  item.str("text").takeIf { it.isNotBlank() }
}.joinToString("\n")
internal fun JSONObject.toMessage(): Message {
  val info = obj("info")
  if (info.length() == 0 && str("type").isNotBlank()) return toV2Message()
  val parts = arr("parts").objects().map { part -> part.toMessagePart() }
  return Message(info.str("id"), info.str("role"), info.longPath("time", "created"), parts,
    info.errorMessage().ifBlank { null }, info.str("agent").ifBlank { null }, (info.obj("model").toModelChoice() ?: info.toModelChoice()),
    info.longPath("time", "completed").takeIf { it > 0 }, info.str("finish").ifBlank { null })
}

/** Projects one legacy/V1 `part` object (as delivered in `message.part.updated` and `parts[]`). */
internal fun JSONObject.toMessagePart(): MessagePart {
  val state = obj("state")
  val type = str("type")
  val files = (0 until arr("files").length()).mapNotNull { index -> arr("files").optString(index).takeIf(String::isNotBlank) }
  return MessagePart(
    id = str("id"), type = type,
    text = str("text").ifBlank { str("description").ifBlank { str("prompt") } }, tool = str("tool"),
    title = state.str("title").ifBlank { str("filename") }, status = state.str("status"), input = state.valueText("input"),
    output = state.valueText("output").ifBlank { state.valueText("result") }, path = str("url").ifBlank { str("uri").ifBlank { str("path").ifBlank { str("filename") } } },
    error = state.errorMessage().ifBlank { errorMessage() }, patch = str("patch"), files = files,
    mime = str("mime"), attachments = state.arr("attachments").toAttachments()
  )
}

private fun JSONObject.toV2Message(): Message {
  val type = str("type")
  val role = when (type) {
    "user" -> "user"
    "assistant" -> "assistant"
    else -> "system"
  }
  val parts = when (type) {
    // V2 text/reasoning content has no id (only tool calls do); give each part a stable, unique one.
    "assistant" -> arr("content").objects().mapIndexed { index, part ->
      part.toV2MessagePart().let { if (it.id.isBlank()) it.copy(id = "${str("id")}#$index") else it }
    }
    "shell" -> listOf(MessagePart(str("id"), "tool", text = str("command"), tool = "shell", output = str("output")))
    else -> listOfNotNull(str("text").takeIf(String::isNotBlank)?.let { MessagePart(str("id"), type, text = it) }) +
      arr("files").toAttachments().mapIndexed { index, attachment ->
        MessagePart("${str("id")}:file:$index", "file", path = attachment.url, title = attachment.name, mime = attachment.mime)
      }
  }
  return Message(
    id = str("id"), role = role, created = longPath("time", "created"), parts = parts,
    error = errorMessage().ifBlank { null },
    agent = str("agent").ifBlank { null }, model = obj("model").toModelChoice(),
    completedAt = longPath("time", "completed").takeIf { it > 0 }, finish = str("finish").ifBlank { null }
  )
}

/** Projects one V2 `message.part.updated` part object. */
internal fun JSONObject.toV2MessagePart(): MessagePart {
  val state = obj("state")
  return MessagePart(
    id = str("id"), type = str("type"), text = str("text"), tool = str("name"),
    status = state.str("status"), input = state.valueText("input"),
    output = state.arr("content").contentText().ifBlank { state.valueText("result") },
    path = str("uri").ifBlank { str("url").ifBlank { str("path") } }, title = str("filename"), mime = str("mime"),
    files = (0 until state.arr("outputPaths").length()).mapNotNull { state.arr("outputPaths").optString(it).takeIf(String::isNotBlank) },
    attachments = state.arr("attachments").toAttachments() + state.arr("content").toAttachments(),
    error = state.errorMessage().ifBlank { errorMessage() }
  )
}
internal fun JSONObject.toPermission(directory: String): PermissionRequest {
  val detail = when {
    arr("patterns").length() > 0 -> arr("patterns").toString()
    arr("resources").length() > 0 -> arr("resources").toString()
    else -> obj("metadata").toString()
  }
  val tool = optJSONObject("tool") ?: obj("source")
  return PermissionRequest(
    str("id").ifBlank { str("requestID") }, str("sessionID"), directory,
    str("permission").ifBlank { str("action") }, detail,
    (optJSONArray("always") ?: arr("save")).let { values -> (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) } },
    tool.str("messageID"), tool.str("callID").ifBlank { tool.str("id") }
  )
}
internal fun JSONObject.toQuestion(directory: String): QuestionRequest = QuestionRequest(
  str("id").ifBlank { str("requestID") }, str("sessionID"), directory,
  arr("questions").objects().map { q -> QuestionPrompt(
    q.str("question"), q.arr("options").objects().map { QuestionOption(it.str("label"), it.str("description")) },
    q.optBoolean("multiple"), q.optBoolean("custom")
  ) }
)
internal fun JSONObject.toTodo(): TodoItem = TodoItem(str("content"), str("status"), str("priority"))
internal fun JSONObject.toChange(): FileChange = FileChange(
  path = str("file").ifBlank { str("path") }, after = str("after"), additions = optInt("additions"), deletions = optInt("deletions"),
  patch = str("patch")
)
internal fun JSONObject.toNode(): FileNode = FileNode(str("path"), str("type"))
internal fun JSONObject.toFileContent(): FileContent = FileContent(
  str("type").ifBlank { if (str("encoding") == "base64") "binary" else "text" },
  str("content")
)

object TaskReducer {
  private val TEST_COMMAND = Regex("(?i)(test|gradle|pytest|vitest|jest)")
  private val SUBAGENT_TOOLS = setOf("task", "subagent")
  private val SHELL_TOOLS = setOf("bash", "shell")

  fun status(sessionId: String, status: String, previous: TaskState? = null): TaskState = when (status) {
    "busy", "running" -> {
      val continuing = previous?.active == true
      TaskState(sessionId, if (continuing && previous!!.phase !in TaskState.WAITING_PHASES) previous.phase else TaskPhase.THINKING,
        TaskState.RUNNING_DETAIL, if (continuing) previous!!.since else System.currentTimeMillis())
    }
    "retry" -> TaskState(sessionId, TaskPhase.THINKING, TaskState.RUNNING_DETAIL,
      if (previous?.active == true) previous.since else System.currentTimeMillis())
    "idle" -> when {
      previous?.active == true -> TaskState(sessionId, TaskPhase.COMPLETED, "任务已完成", previous.since, System.currentTimeMillis())
      previous?.phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED, TaskPhase.ABORTED) -> previous!!
      else -> TaskState(sessionId, TaskPhase.IDLE)
    }
    else -> previous ?: TaskState(sessionId, TaskPhase.IDLE)
  }
  fun event(sessionId: String, type: String, properties: JSONObject, previous: TaskState?): TaskState? {
    val since = previous?.since ?: System.currentTimeMillis()
    return when (type) {
      "session.status" -> status(sessionId, properties.obj("status").str("type"), previous)
      "session.idle" -> status(sessionId, "idle", previous)
      "session.error" -> TaskState(sessionId, TaskPhase.FAILED, properties.errorMessage().ifBlank { properties.obj("error").str("message").ifBlank { "执行失败" } }, since, System.currentTimeMillis())
      "session.aborted" -> TaskState(sessionId, TaskPhase.ABORTED, "任务已停止", since, System.currentTimeMillis())
      "permission.asked" -> TaskState(sessionId, TaskPhase.WAITING_PERMISSION, "等待权限确认", since)
      "question.asked" -> TaskState(sessionId, TaskPhase.WAITING_QUESTION, "等待你的回答", since)
      "permission.replied", "permission.rejected", "question.replied", "question.rejected" ->
        TaskState(sessionId, TaskPhase.THINKING, TaskState.RUNNING_DETAIL, since)
      "message.part.updated" -> {
        val part = properties.obj("part")
        val toolStatus = part.obj("state").str("status")
        if (previous?.phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED, TaskPhase.ABORTED) ||
          part.str("type") == "tool" && toolStatus in setOf("completed", "error")) return previous
        when {
          part.str("type") == "reasoning" -> TaskState(sessionId, TaskPhase.THINKING, TaskState.RUNNING_DETAIL, since)
          part.str("type") == "tool" -> {
            // V1 parts carry `tool`; V2 parts carry `name`. Read both so the same V2 event produces the
            // same phase through the reducer as through the plugin (A13).
            val tool = part.str("tool").ifBlank { part.str("name") }
            val phase = when {
              tool in SUBAGENT_TOOLS -> TaskPhase.SUBAGENT
              tool in SHELL_TOOLS && TEST_COMMAND.containsMatchIn(part.obj("state").obj("input").str("command")) -> TaskPhase.TESTING
              else -> TaskPhase.TOOL
            }
            TaskState(sessionId, phase, TaskState.RUNNING_DETAIL, since)
          }
          else -> previous
        }
      }
      "message.part.delta", "session.next.text.delta", "session.next.reasoning.delta" ->
        if (previous?.phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED, TaskPhase.ABORTED)) previous
        else TaskState(sessionId, TaskPhase.THINKING, TaskState.RUNNING_DETAIL, since)
      "session.next.prompted", "session.next.prompt.admitted", "session.next.step.started", "session.next.retried" ->
        status(sessionId, "running", previous)
      "session.next.tool.called", "session.next.shell.started" -> {
        val tool = properties.str("tool").ifBlank { if (type.endsWith("shell.started")) "shell" else properties.str("name") }
        val phase = when {
          tool in SUBAGENT_TOOLS -> TaskPhase.SUBAGENT
          tool in SHELL_TOOLS && TEST_COMMAND.containsMatchIn(properties.obj("input").str("command").ifBlank { properties.str("command") }) -> TaskPhase.TESTING
          else -> TaskPhase.TOOL
        }
        if (previous?.phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED, TaskPhase.ABORTED)) previous
        else TaskState(sessionId, phase, TaskState.RUNNING_DETAIL, since)
      }
      "session.next.step.failed" -> TaskState(sessionId, TaskPhase.FAILED,
        properties.obj("error").str("message").ifBlank { "执行失败" }, since, System.currentTimeMillis())
      else -> previous
    }
  }
}
