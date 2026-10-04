package com.igng.opencode.lagoon.core

import org.json.JSONObject

enum class SessionAction { RENAME, DELETE, FORK, SHARE, UNSHARE, COMPACT, REVERT, UNREVERT }
data class ActionEndpoint(val method: String, val path: String)

/** Per-instance contract. Missing documentation retains only the protocol's established operations. */
data class ApiCapabilities(
  val actions: Map<SessionAction, ActionEndpoint> = emptyMap(),
  val titleOnCreate: Boolean = false, val todos: Boolean = false, val diff: Boolean = false,
  val savedPermissions: Boolean = false, val projectPath: String? = null,
  val promptEnvelope: Boolean = false, val fileReferences: Boolean = false,
  val fileUriField: String = "uri", val documented: Boolean = false,
  val sessionView: ActionEndpoint? = null
) {
  fun supports(action: SessionAction) = action in actions
  companion object {
    fun fallback(protocol: ServerProtocol, currentV2: Boolean = false): ApiCapabilities = when (protocol) {
      ServerProtocol.V1 -> ApiCapabilities(actions = mapOf(
        SessionAction.RENAME to ActionEndpoint("PATCH", "session/{id}"),
        SessionAction.DELETE to ActionEndpoint("DELETE", "session/{id}"),
        SessionAction.FORK to ActionEndpoint("POST", "session/{id}/fork"),
        SessionAction.SHARE to ActionEndpoint("POST", "session/{id}/share"),
        SessionAction.UNSHARE to ActionEndpoint("DELETE", "session/{id}/share"),
        SessionAction.COMPACT to ActionEndpoint("POST", "session/{id}/summarize"),
        SessionAction.REVERT to ActionEndpoint("POST", "session/{id}/revert"),
        SessionAction.UNREVERT to ActionEndpoint("POST", "session/{id}/unrevert")
      ), titleOnCreate = true, todos = true, diff = true, projectPath = "project", fileReferences = true, fileUriField = "url")
      ServerProtocol.V2 -> ApiCapabilities(actions = mapOf(
        SessionAction.COMPACT to ActionEndpoint("POST", "api/session/{id}/compact"),
        SessionAction.REVERT to ActionEndpoint("POST", "api/session/{id}/revert/stage"),
        SessionAction.UNREVERT to ActionEndpoint(if (currentV2) "DELETE" else "POST", if (currentV2) "api/session/{id}/revert" else "api/session/{id}/revert/clear")
      ), savedPermissions = currentV2, projectPath = if (currentV2) "api/project" else null, promptEnvelope = !currentV2)
      ServerProtocol.UNKNOWN -> ApiCapabilities()
    }

    fun fromDocument(protocol: ServerProtocol, document: JSONObject, currentV2: Boolean = false): ApiCapabilities {
      val paths = document.optJSONObject("paths") ?: return fallback(protocol, currentV2)
      val base = fallback(protocol, currentV2)
      if (protocol != ServerProtocol.V2) return base.copy(documented = true)
      fun schema(value: JSONObject, depth: Int = 0): JSONObject {
        if (depth >= 15) return JSONObject()
        val ref = value.str("$" + "ref")
        if (ref.startsWith("#/")) {
          var resolved: Any = document
          for (key in ref.removePrefix("#/").split('/')) resolved = (resolved as? JSONObject)?.opt(key.replace("~1", "/").replace("~0", "~")) ?: return JSONObject()
          return schema(resolved as? JSONObject ?: JSONObject(), depth + 1)
        }
        val branch = value.arr("anyOf").objects().firstOrNull { it.str("type") != "null" }
          ?: value.arr("oneOf").objects().firstOrNull { it.str("type") != "null" }
        return if (branch != null) schema(branch, depth + 1) else value
      }
      fun body(op: JSONObject) = schema(op.obj("requestBody").obj("content").obj("application/json").obj("schema"))
      fun endpoint(suffix: String, method: String, fields: Set<String> = emptySet()): ActionEndpoint? {
        val regex = Regex("^/api/session/\\{[^/}]+\\}" + Regex.escape(suffix) + "$")
        val path = paths.keys().asSequence().firstOrNull { regex.matches(it) && paths.obj(it).has(method.lowercase()) } ?: return null
        val op = paths.obj(path).obj(method.lowercase())
        val input = body(op)
        val required = input.arr("required")
        if ((0 until required.length()).any { required.optString(it) !in fields }) return null
        if (fields.any { !input.obj("properties").has(it) }) return null
        return ActionEndpoint(method, path.removePrefix("/"))
      }
      val actions = mutableMapOf<SessionAction, ActionEndpoint>()
      listOf(
        Triple(SessionAction.RENAME, "", "PATCH"), Triple(SessionAction.DELETE, "", "DELETE"),
        Triple(SessionAction.FORK, "/fork", "POST"), Triple(SessionAction.SHARE, "/share", "POST"),
        Triple(SessionAction.UNSHARE, "/share", "DELETE"), Triple(SessionAction.COMPACT, "/compact", "POST"),
        Triple(SessionAction.REVERT, "/revert/stage", "POST"), Triple(SessionAction.UNREVERT, "/revert", "DELETE")
      ).forEach { (action, suffix, method) ->
        val fields = when (action) { SessionAction.RENAME -> setOf("title"); SessionAction.REVERT -> setOf("messageID"); else -> emptySet() }
        endpoint(suffix, method, fields)?.let { actions[action] = it }
      }
      endpoint("/revert/clear", "POST")?.let { actions.putIfAbsent(SessionAction.UNREVERT, it) }
      val prompt = body(paths.obj("/api/session/{sessionID}/prompt").obj("post").takeIf { it.length() > 0 }
        ?: paths.keys().asSequence().firstOrNull { it.endsWith("/prompt") && it.startsWith("/api/session/{") }?.let { paths.obj(it).obj("post") } ?: JSONObject())
      val envelope = prompt.obj("properties").has("prompt")
      val content = if (envelope) schema(prompt.obj("properties").obj("prompt")) else prompt
      val file = schema(content.obj("properties").obj("files"))
      val item = schema(file.obj("items"))
      val uriField = when { item.obj("properties").has("uri") -> "uri"; item.obj("properties").has("url") -> "url"; else -> "uri" }
      return ApiCapabilities(actions, body(paths.obj("/api/session").obj("post")).obj("properties").has("title"),
        endpoint("/todo", "GET") != null, endpoint("/diff", "GET") != null,
        paths.obj("/api/permission/saved").has("get"),
        "api/project".takeIf { paths.obj("/api/project").has("get") },
        if (prompt.length() > 0) envelope else base.promptEnvelope,
        item.obj("properties").has(uriField), uriField, true, endpoint("/view", "POST", setOf("idle")))
    }
  }
}
