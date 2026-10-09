package com.igng.opencode.lagoon.core

import org.json.JSONObject

/** Session operations whose route is not present in the native V2 protocol. */
enum class SessionAction { RENAME, UNREVERT }
data class ActionEndpoint(val method: String, val path: String)
/** Session update that accepts `{time: {archived}}`; [restorable] when null is accepted. */
data class ArchiveEndpoint(val endpoint: ActionEndpoint, val restorable: Boolean)

/**
 * Per-instance routes read from the server's own `/doc` OpenAPI document. Native V2 routes are the
 * default; old InstanceHttpApi routes are enabled only when the document explicitly contains them.
 */
data class ApiCapabilities(
  /** True when the document contains the native API V2 protocol. */
  val nativeV2: Boolean = true,
  /** The documented session collection (`api/session` for V2, `session` for InstanceHttpApi). */
  val sessionRoot: String = "api/session",
  val legacySessionList: ActionEndpoint? = null,
  val legacySessionCreate: ActionEndpoint? = null,
  val legacySessionGet: ActionEndpoint? = null,
  val legacyMessageList: ActionEndpoint? = null,
  val legacyStatus: ActionEndpoint? = null,
  val actions: Map<SessionAction, ActionEndpoint> = emptyMap(),
  /** Legacy read-marker route; native V2 has no viewed/idle write endpoint. */
  val sessionView: ActionEndpoint? = null,
  val remove: Boolean = false,
  val removeEndpoint: ActionEndpoint? = null,
  val fork: Boolean = false,
  val forkEndpoint: ActionEndpoint? = null,
  val legacyChildren: Boolean = false,
  val legacyChildrenEndpoint: ActionEndpoint? = null,
  val legacyCommand: Boolean = false,
  val legacyCommandEndpoint: ActionEndpoint? = null,
  val legacyPrompt: ActionEndpoint? = null,
  val legacyAbort: ActionEndpoint? = null,
  val legacyCompact: ActionEndpoint? = null,
  val legacyRevert: ActionEndpoint? = null,
  val legacyUnrevert: ActionEndpoint? = null,
  val legacyPermissionList: ActionEndpoint? = null,
  val legacyPermissionReply: ActionEndpoint? = null,
  val legacyQuestionList: ActionEndpoint? = null,
  val legacyQuestionReply: ActionEndpoint? = null,
  val legacyQuestionReject: ActionEndpoint? = null,
  val legacyFiles: ActionEndpoint? = null,
  val legacyFileContent: ActionEndpoint? = null,
  val legacySearchFiles: ActionEndpoint? = null,
  val legacyProjectList: ActionEndpoint? = null,
  val legacyProjectCurrent: ActionEndpoint? = null,
  val legacyAgentList: ActionEndpoint? = null,
  val legacyCommandList: ActionEndpoint? = null,
  val legacyModelList: ActionEndpoint? = null,
  val legacyProviderList: ActionEndpoint? = null,
  /** SSE route: native V2 `/api/event`, legacy `/global/event` or `/event`. */
  val eventPath: String = "api/event",
  val diff: Boolean = false,
  val diffEndpoint: ActionEndpoint? = null,
  val archive: ArchiveEndpoint? = null,
  /** True when [fromDocument] successfully read the instance document. */
  val documented: Boolean = false
) {
  fun supports(action: SessionAction) = action in actions

  companion object {
    /** Native V2 routes that are safe to use when `/doc` is unavailable. */
    val BASELINE = ApiCapabilities(
      nativeV2 = true,
      actions = mapOf(SessionAction.UNREVERT to ActionEndpoint("POST", "api/session/{sessionID}/revert/clear"))
    )

    fun fromDocument(document: JSONObject): ApiCapabilities {
      val paths = document.optJSONObject("paths") ?: return BASELINE
      if (paths.length() == 0) return BASELINE

      fun schema(value: JSONObject, depth: Int = 0): JSONObject {
        if (depth >= 15) return JSONObject()
        val ref = value.str("$" + "ref")
        if (ref.startsWith("#/")) {
          var resolved: Any = document
          for (key in ref.removePrefix("#/").split('/')) {
            resolved = (resolved as? JSONObject)?.opt(key.replace("~1", "/").replace("~0", "~")) ?: return JSONObject()
          }
          return schema(resolved as? JSONObject ?: JSONObject(), depth + 1)
        }
        val branch = value.arr("anyOf").objects().firstOrNull { it.optString("type") != "null" }
          ?: value.arr("oneOf").objects().firstOrNull { it.optString("type") != "null" }
        return if (branch != null) schema(branch, depth + 1) else value
      }

      fun body(op: JSONObject): JSONObject = schema(op.obj("requestBody").obj("content").obj("application/json").obj("schema"))
      fun operation(path: String, method: String): JSONObject? = paths.obj(path).optJSONObject(method.lowercase())
      fun has(path: String, method: String): Boolean = operation(path, method) != null
      val nativeV2 = has("/api/health", "GET") || has("/api/session/active", "GET") ||
        has("/api/question/request", "GET") || has("/api/permission/request", "GET") ||
        has("/api/session/{sessionID}/history", "GET")
      fun sessionPath(suffix: String): Regex = Regex("^/api/session/\\{[^/}]+\\}" + Regex.escape(suffix) + "$")
      fun sessionPathFor(prefix: String, suffix: String): Regex =
        Regex("^/" + Regex.escape(prefix) + "/\\{[^/}]+\\}" + Regex.escape(suffix) + "$")
      fun route(suffix: String, method: String): ActionEndpoint? {
        val candidates = paths.keys().asSequence().filter {
          (sessionPath(suffix).matches(it) || sessionPathFor("session", suffix).matches(it)) && operation(it, method) != null
        }.toList()
        val path = candidates.firstOrNull { nativeV2 == it.startsWith("/api/") } ?: candidates.firstOrNull()
          ?: return null
        return ActionEndpoint(method, path.removePrefix("/"))
      }
      fun baseRoute(method: String): Pair<String, JSONObject>? {
        val candidates = paths.keys().asSequence().filter {
          (Regex("^/api/session/\\{[^/}]+\\}$").matches(it) || Regex("^/session/\\{[^/}]+\\}$").matches(it)) && operation(it, method) != null
        }.toList()
        val path = candidates.firstOrNull { nativeV2 == it.startsWith("/api/") } ?: candidates.firstOrNull()
          ?: return null
        return path to requireNotNull(operation(path, method))
      }
      fun endpoint(suffix: String, method: String, fields: Set<String> = emptySet()): ActionEndpoint? {
        val route = route(suffix, method) ?: return null
        val input = body(requireNotNull(operation("/${route.path}", method)))
        val required = input.arr("required")
        if ((0 until required.length()).any { required.optString(it) !in fields }) return null
        if (fields.any { !input.obj("properties").has(it) }) return null
        return route
      }
      fun direct(path: String, method: String): ActionEndpoint? =
        if (has(path, method)) ActionEndpoint(method, path.removePrefix("/")) else null

      val actions = mutableMapOf<SessionAction, ActionEndpoint>()
      (endpoint("", "PATCH", setOf("title")) ?: endpoint("/rename", "POST", setOf("title")))?.let {
        actions[SessionAction.RENAME] = it
      }
      (route("/revert/clear", "POST") ?: route("/revert", "DELETE") ?: route("/unrevert", "POST"))?.let {
        actions[SessionAction.UNREVERT] = it
      }

      val archive = baseRoute("PATCH")?.let { (path, operation) ->
        val input = body(operation)
        val required = input.arr("required")
        val time = schema(input.obj("properties").optJSONObject("time") ?: return@let null)
        val archived = time.obj("properties").optJSONObject("archived") ?: return@let null
        if ((0 until required.length()).any { required.optString(it) != "time" }) return@let null
        val nullable = archived.optBoolean("nullable") || listOf("anyOf", "oneOf").any { key ->
          archived.arr(key).objects().any { it.optString("type") == "null" }
        } || archived.optJSONArray("type")?.let { types ->
          (0 until types.length()).any { types.optString(it) == "null" }
        } == true
        ArchiveEndpoint(ActionEndpoint("PATCH", path.removePrefix("/")), nullable)
      }
      val legacyPrompt = route("/message", "POST") ?: route("/prompt_async", "POST") ?: route("/prompt", "POST")
      val legacySessionList = direct("/session", "GET") ?: direct("/api/session", "GET")
      val legacySessionCreate = direct("/session", "POST") ?: direct("/api/session", "POST")
      val legacySessionGet = direct("/session/{sessionID}", "GET") ?: direct("/api/session/{sessionID}", "GET")
      val legacyMessageList = route("/message", "GET")
      val legacyStatus = direct("/session/status", "GET") ?: direct("/api/session/status", "GET")
      val legacyCommandEndpoint = route("/command", "POST")
      val sessionRoot = when {
        nativeV2 && has("/api/session", "GET") -> "api/session"
        !nativeV2 && has("/session", "GET") -> "session"
        has("/api/session", "GET") -> "api/session"
        has("/session", "GET") -> "session"
        else -> "api/session"
      }
      val sessionView = endpoint("/view", "POST", setOf("idle"))
      val legacyPermissionList = direct("/permission", "GET") ?: direct("/api/permission", "GET")
      val legacyPermissionReply = direct("/permission/{requestID}/reply", "POST")
        ?: direct("/api/permission/{requestID}/reply", "POST")
      val legacyQuestionList = direct("/question", "GET") ?: direct("/api/question", "GET")
      val legacyQuestionReply = direct("/question/{requestID}/reply", "POST")
        ?: direct("/api/question/{requestID}/reply", "POST")
      val legacyQuestionReject = direct("/question/{requestID}/reject", "POST")
        ?: direct("/api/question/{requestID}/reject", "POST")
      val eventPath = when {
        has("/api/event", "GET") -> "api/event"
        has("/global/event", "GET") -> "global/event"
        has("/event", "GET") -> "event"
        else -> "api/event"
      }

      val diffEndpoint = route("/diff", "GET")
      return ApiCapabilities(
        nativeV2 = nativeV2,
        sessionRoot = sessionRoot,
        legacySessionList = legacySessionList,
        legacySessionCreate = legacySessionCreate,
        legacySessionGet = legacySessionGet,
        legacyMessageList = legacyMessageList,
        legacyStatus = legacyStatus,
        actions = actions,
        sessionView = sessionView,
        remove = baseRoute("DELETE") != null,
        removeEndpoint = baseRoute("DELETE")?.let { ActionEndpoint("DELETE", it.first.removePrefix("/")) },
        fork = route("/fork", "POST") != null,
        forkEndpoint = route("/fork", "POST"),
        legacyChildren = route("/children", "GET") != null,
        legacyChildrenEndpoint = route("/children", "GET"),
        legacyCommand = legacyCommandEndpoint != null,
        legacyCommandEndpoint = legacyCommandEndpoint,
        legacyPrompt = legacyPrompt,
        legacyAbort = route("/abort", "POST"),
        legacyCompact = route("/summarize", "POST"),
        legacyRevert = route("/revert", "POST"),
        legacyUnrevert = route("/unrevert", "POST"),
        legacyPermissionList = legacyPermissionList,
        legacyPermissionReply = legacyPermissionReply,
        legacyQuestionList = legacyQuestionList,
        legacyQuestionReply = legacyQuestionReply,
        legacyQuestionReject = legacyQuestionReject,
        legacyFiles = direct("/file", "GET") ?: direct("/api/file", "GET"),
        legacyFileContent = direct("/file/content", "GET") ?: direct("/api/file/content", "GET"),
        legacySearchFiles = direct("/find/file", "GET"),
        legacyProjectList = direct("/project", "GET") ?: direct("/api/project", "GET"),
        legacyProjectCurrent = direct("/project/current", "GET") ?: direct("/api/project/current", "GET"),
        legacyAgentList = direct("/agent", "GET") ?: direct("/api/agent", "GET"),
        legacyCommandList = direct("/command", "GET") ?: direct("/api/command", "GET"),
        legacyModelList = direct("/model", "GET") ?: direct("/api/model", "GET"),
        legacyProviderList = direct("/provider", "GET") ?: direct("/api/provider", "GET"),
        eventPath = eventPath,
        diff = diffEndpoint != null,
        diffEndpoint = diffEndpoint,
        archive = archive,
        documented = true
      )
    }
  }
}
