package com.igng.opencode.lagoon.core

/** Server identities and execution directories are separate: a worktree is still the same project. */
fun resolveSessionProject(session: Session, projects: List<Project>): Project? {
  session.projectId?.let { id -> projects.firstOrNull { it.id == id }?.let { return it } }
  val directory = normalizedDirectory(session.directory)
  projects.firstOrNull { project -> project.directories.any { normalizedDirectory(it) == directory } }?.let { return it }
  return projects.flatMap { project -> project.directories.map { project to normalizedDirectory(it) } }
    .filter { (_, root) -> root.isNotEmpty() && (directory == root || directory.startsWith(if (root == "/") root else "$root/")) }
    .maxByOrNull { (_, root) -> root.length }?.first
}

fun normalizedDirectory(directory: String): String {
  val path = directory.trim().replace('\\', '/').trimEnd('/').ifEmpty { if (directory.startsWith('/')) "/" else "" }
  return if (Regex("^[A-Za-z]:/").containsMatchIn(path)) path.lowercase() else path
}

enum class SessionContent { UNKNOWN, EMPTY, CONTENT }
data class SessionPreview(val content: SessionContent = SessionContent.UNKNOWN, val text: String = "")

/**
 * 官方客户端的标题口径：只认 `session.title`。
 * 自动生成的占位标题（`New session - <ISO时间>` / `Child session - <ISO时间>`）归一为“新会话 / 子会话”，
 * 空标题按是否有父会话回退；不使用首条用户消息兜底。
 */
private val placeholderTitle = Regex("^(New session|Child session) - \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
fun Session.displayTitle(): String {
  val value = title
  if (value.isNotBlank()) {
    val match = placeholderTitle.matchEntire(value) ?: return value
    return if (match.groupValues[1] == "Child session") "子会话" else "新会话"
  }
  return if (parentId != null) "子会话" else "新会话"
}

/** Attention and running states include descendants; result/unread states belong to the root. */
enum class SessionStatus(val label: String) {
  WAITING_PERMISSION("等待授权"), WAITING_QUESTION("等待回答"), RUNNING("运行中"),
  NEEDS_REVIEW("待查看"), FAILED("失败"), INTERRUPTED("已中断"), COMPLETED("已完成"), NONE("")
}

fun Session.status(activeFamily: TaskState?, rootTask: TaskState?, unseen: Boolean): SessionStatus = when {
  activeFamily?.phase == TaskPhase.WAITING_PERMISSION -> SessionStatus.WAITING_PERMISSION
  activeFamily?.phase == TaskPhase.WAITING_QUESTION -> SessionStatus.WAITING_QUESTION
  activeFamily?.phase in TaskState.RUNNING_PHASES -> SessionStatus.RUNNING
  unseen -> SessionStatus.NEEDS_REVIEW
  outcome == "failed" || rootTask?.phase == TaskPhase.FAILED -> SessionStatus.FAILED
  outcome == "interrupted" || rootTask?.phase == TaskPhase.ABORTED -> SessionStatus.INTERRUPTED
  outcome == "succeeded" || rootTask?.phase == TaskPhase.COMPLETED -> SessionStatus.COMPLETED
  else -> SessionStatus.NONE
}

val Session.visibleOnHome: Boolean get() = parentId == null && !archived
val sessionActivityOrder: Comparator<Session> = compareByDescending<Session> { it.activityAt }.thenBy { it.id }

data class SessionGroup(val key: String, val name: String, val sessions: List<Session>) {
  val latest: Long get() = sessions.firstOrNull()?.activityAt ?: 0
}

/** Keep official root/archive eligibility, regardless of title, content, draft, or execution origin. */
fun groupSessions(sessions: List<Session>, projects: List<Project>): List<SessionGroup> =
  sessions.filter { it.visibleOnHome }.sortedWith(sessionActivityOrder).groupBy { session ->
    val project = resolveSessionProject(session, projects)
    (project?.id ?: "dir:${normalizedDirectory(session.directory)}") to
      (project?.name ?: session.directory.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { session.directory })
  }.map { (key, list) -> SessionGroup(key.first, key.second, list) }
    .sortedWith(compareByDescending<SessionGroup> { it.latest }.thenBy { it.key })

fun List<Message>.sessionPreview(): SessionPreview {
  val visible = filter { it.isDisplayable }
  val firstPrompt = visible.firstOrNull { it.role == "user" }?.parts
    ?.firstOrNull { it.type == "text" || it.type == "user" }?.text.orEmpty()
  return SessionPreview(if (visible.isEmpty()) SessionContent.EMPTY else SessionContent.CONTENT, firstPrompt.take(300))
}

val MessagePart.isDisplayable: Boolean get() = when (type) {
  "text", "user", "system", "synthetic", "reasoning" -> text.isNotBlank()
  "tool" -> tool.isNotBlank() || input.isNotBlank() || output.isNotBlank() || error.isNotBlank()
  "file" -> path.isNotBlank()
  "patch", "diff" -> patch.isNotBlank() || files.isNotEmpty()
  "subtask" -> text.isNotBlank() || title.isNotBlank()
  else -> text.isNotBlank() || attachments.isNotEmpty()
}
val Message.isDisplayable: Boolean get() = error?.isNotBlank() == true || parts.any { it.isDisplayable }

enum class ResourceState { NOT_LOADED, LOADING, READY, EMPTY, ERROR, UNSUPPORTED, STALE }
data class ResourceStatus(val state: ResourceState = ResourceState.NOT_LOADED, val error: String? = null)
data class SessionConfiguration(val agent: String? = null, val model: ModelChoice? = null, val agentChanged: Boolean = false, val modelChanged: Boolean = false)
data class FileReference(val path: String, val mime: String = "text/plain")

/** Preserve binary/image reference semantics instead of labelling every file as plain text. */
fun referenceMime(path: String): String = when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
  "png" -> "image/png"
  "jpg", "jpeg" -> "image/jpeg"
  "gif" -> "image/gif"
  "webp" -> "image/webp"
  "svg" -> "image/svg+xml"
  "pdf" -> "application/pdf"
  "mp3" -> "audio/mpeg"
  "wav" -> "audio/wav"
  "mp4" -> "video/mp4"
  "zip" -> "application/zip"
  "bin", "exe", "so", "dll" -> "application/octet-stream"
  else -> "text/plain"
}
