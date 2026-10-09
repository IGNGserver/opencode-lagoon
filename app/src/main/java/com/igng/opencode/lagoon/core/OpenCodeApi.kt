package com.igng.opencode.lagoon.core

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class ServerCredentials(
  val username: String = "opencode",
  val password: String = "",
  val cookie: String = ""
)

data class ApiPage<T>(val items: List<T>, val next: String? = null)

/** One `/api/event` frame: `type`, its `data` as [properties], the event `location` and `created` time. */
data class ServerEvent(val id: String = "", val directory: String, val type: String, val properties: JSONObject, val created: Long = 0)
class ApiException(val status: Int, message: String) : IOException(message)

/** How a new prompt is delivered while the session is busy (official `Session.Inbox.Delivery`). */
enum class Delivery(val wire: String) { STEER("steer"), QUEUE("queue") }

/**
 * Client for the OpenCode 2.x server (`anomalyco/opencode` `packages/protocol`). Routes, payloads and
 * response envelopes follow the published contract; operations whose route differs between 2.0.x
 * releases are resolved from the server's own `/doc` OpenAPI document (see [ApiCapabilities]).
 */
class OpenCodeApi(
  private val profile: ServerProfile,
  private val credentials: ServerCredentials,
  /** Whole-call deadline. Background callers (e.g. notification actions) pass a small value to stay
   *  inside the short lifetime Android grants a BroadcastReceiver; foreground callers keep the default. */
  callTimeoutSeconds: Long = 60
) {
  constructor(profile: ServerProfile, password: String) : this(profile, ServerCredentials(profile.username, password))

  private val base: HttpUrl = requireNotNull(profile.url.trimEnd('/').toHttpUrlOrNull()) { "服务器地址无效" }
  private val origin = HttpOrigin.of(base)
  private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
    .connectionPool(SharedHttp.connectionPool)
    .dispatcher(SharedHttp.dispatcher)
    // Never let OkHttp's default redirect policy replay an authenticated request to a different
    // scheme/host/port: a trusted server (or its proxy) could otherwise reflect the Authorization
    // header or the raw Cookie onto a downgraded or unrelated origin before we ever check the final
    // URL.
    .followRedirects(false)
    .followSslRedirects(false)
    .addInterceptor { chain ->
      // Defence in depth: even if the redirect policy is ever changed, no authenticated request can
      // leave the configured origin.
      val request = chain.request()
      if (HttpOrigin.of(request.url) != origin) throw IOException("拒绝向非授权地址发送凭据")
      chain.proceed(request)
    }
    .build()
  private val streamClient = client.newBuilder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()
  /** Dedicated scope for blocking HTTP work so a cancelled caller never waits on the socket. */
  private val ioScope = SharedHttp.ioScope
  @Volatile private var version: String? = null
  @Volatile private var discoveredCapabilities: ApiCapabilities? = null
  fun capabilities(): ApiCapabilities = discoveredCapabilities ?: ApiCapabilities.BASELINE

  /** Reads the instance's own OpenAPI document once; V2 and compatibility routes are taken from it. */
  suspend fun discoverCapabilities(): ApiCapabilities {
    discoveredCapabilities?.let { return it }
    val document = try {
      var found: JSONObject? = null
      for (path in listOf("openapi.json", "doc", "api/openapi.json")) {
        try {
          val candidate = obj(path)
          if (candidate.optJSONObject("paths") != null) {
            found = candidate
            break
          }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
          throw cancel
        } catch (error: Exception) {
          // If the endpoint is 404, or returns SPA HTML (IOException with "网页"), try next candidate.
          Diagnostics.warn("OpenCodeApi", "无法读取 /$path，尝试下一个能力文档", error)
        }
      }
      found
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
      catch (error: Exception) { Diagnostics.warn("OpenCodeApi", "无法读取能力文档，按 V2 协议处理", error); null }
    return (if (document == null) ApiCapabilities.BASELINE else ApiCapabilities.fromDocument(document)).also { discoveredCapabilities = it }
  }

  init {
    require(base.scheme == "https" || base.scheme == "http" && profile.allowCleartext) { "HTTP 明文连接未获授权，请在服务器资料中明确开启" }
    require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "请使用不含凭据或参数的服务器地址" }
  }

  private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
    // The path is already percent-encoded segment-by-segment (see [segment]/[encodedPath]); using the
    // encoded variant avoids OkHttp re-encoding '%' and producing e.g. '%2520' for ids containing spaces.
    val builder = base.newBuilder().addEncodedPathSegments(path.trimStart('/'))
    query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
    return builder.build()
  }

  private fun requestBuilder(path: String, query: Map<String, String>): Request.Builder {
    val builder = Request.Builder().url(url(path, query)).header("Accept", "application/json")
    if (credentials.password.isNotEmpty()) {
      builder.header("Authorization", Credentials.basic(credentials.username.ifBlank { profile.username.ifBlank { "opencode" } }, credentials.password))
    }
    if (credentials.cookie.isNotBlank()) builder.header("Cookie", credentials.cookie)
    return builder
  }

  /** Every request this client builds targets [origin]; the redirect policy forbids OkHttp from
   *  moving an authenticated request elsewhere, so the credentials can only ever reach [origin]. */
  private fun requireOrigin(request: Request) {
    require(HttpOrigin.of(request.url) == origin) { "拒绝向非授权地址发送凭据" }
  }

  private fun httpErrorMessage(status: Int, body: String): String {
    val serverMessage = runCatching {
      val json = JSONObject(body)
      json.errorMessage().ifBlank { json.obj("data").errorMessage() }
    }.getOrDefault("")
    return serverMessage.ifBlank {
      when (status) {
        401 -> "用户名或密码错误"
        403 -> "服务器拒绝访问"
        404 -> "OpenCode 未找到请求的资源或接口"
        503 -> "OpenCode 服务正在启动或停止，请稍后重试"
        else -> "服务器返回 HTTP $status"
      }
    }
  }

  private suspend fun request(method: String, path: String, query: Map<String, String> = emptyMap(), body: JSONObject? = null): String {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    return runCall(requestBuilder(path, query).method(method, if (method == "GET" || method == "DELETE" && body == null) null else (body?.toString() ?: "{}").toRequestBody(mediaType)).build()) { response ->
      val responseBody = response.body?.readText(MAX_RESPONSE_CHARS).orEmpty()
      if (!response.isSuccessful) throw ApiException(response.code, httpErrorMessage(response.code, responseBody))
      responseBody
    }
  }

  private suspend fun requestBytes(path: String, query: Map<String, String> = emptyMap()): Pair<ByteArray, String> =
    runCall(requestBuilder(path, query).get().build()) { response ->
      val responseBody = response.body ?: error("服务器返回空响应")
      if (!response.isSuccessful) {
        val body = responseBody.readText(MAX_RESPONSE_CHARS)
        throw ApiException(response.code, httpErrorMessage(response.code, body))
      }
      responseBody.bytes(MAX_FILE_BYTES) to (response.header("Content-Type") ?: "application/octet-stream")
    }

  /**
   * Runs the blocking call and its body read on a background worker, but cancels the underlying
   * socket the moment the caller's coroutine is cancelled. Cancellation must not wait for the server
   * to answer a request the user already navigated away from (A15): the coroutine returns
   * immediately while [call] is aborted and the abandoned worker unwinds on its own.
   */
  private suspend fun <T> runCall(request: Request, read: (Response) -> T): T {
    requireOrigin(request)
    return suspendCancellableCoroutine { continuation ->
      val call = client.newCall(request)
      val worker = ioScope.launch {
        try {
          continuation.resume(call.execute().use(read))
        } catch (error: Throwable) {
          continuation.resumeWith(Result.failure(error))
        }
      }
      continuation.invokeOnCancellation {
        call.cancel()
        worker.cancel()
      }
    }
  }

  private fun ResponseBody.readText(limit: Int): String {
    val source = source()
    return if (source.request(limit.toLong())) {
      throw IOException("服务器响应过大（超过 $limit 字节），已拒绝读取")
    } else source.readString(contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8)
  }

  private fun ResponseBody.bytes(limit: Long): ByteArray {
    val source = source()
    if (source.request(limit + 1)) throw IOException("文件过大（超过 $limit 字节），已拒绝下载")
    return bytes()
  }

  // Parsing runs on the IO dispatcher: JSONObject/JSONArray construction of a large transcript is
  // CPU-bound and the controller calls these from its Main-immediate scope (A15).
  private suspend fun obj(path: String, query: Map<String, String> = emptyMap()): JSONObject =
    withContext(Dispatchers.IO) { jsonObject(request("GET", path, query)) }
  private fun dataObject(value: JSONObject): JSONObject = value.optJSONObject("data") ?: value
  private fun dataArray(value: JSONObject): JSONArray = value.optJSONArray("data") ?: JSONArray()

  /**
   * HTTP 200 bodies are parsed here. When the configured address is not an OpenCode API — a web UI
   * returning SPA HTML, a login/proxy page, or an empty body — org.json raises raw errors such as
   * "Value ... of type java.lang.String cannot be converted to JSONObject", which used to leak
   * verbatim into the add-server form. Translate them into a specific, actionable message instead.
   */
  private fun jsonObject(raw: String): JSONObject = try { JSONObject(raw) } catch (error: JSONException) { throw invalidJsonResponse(raw, error) }
  private fun jsonArray(raw: String): JSONArray = try { JSONArray(raw) } catch (error: JSONException) { throw invalidJsonResponse(raw, error) }
  private fun invalidJsonResponse(raw: String, cause: JSONException): IOException {
    val trimmed = raw.trim()
    val reason = when {
      trimmed.isEmpty() -> "服务器返回了空响应，请确认地址指向 OpenCode 服务端口"
      trimmed.startsWith("<") -> "服务器返回的是网页而非 OpenCode 接口数据，请确认填写的是 API 地址（不要使用网页界面地址）"
      else -> "服务器返回的数据不是有效 JSON，可能被代理拦截或该地址不是 OpenCode 服务"
    }
    // cause.message is deliberately not repeated: Android's org.json embeds the whole offending
    // body in the message, which would flood the dialog. The cause stays chained for diagnostics.
    return IOException(reason, cause)
  }

  /** Follows `cursor.next` of a `{data, cursor}` listing; [dropAfterFirst] keys are first-page-only (e.g. `order`). */
  private suspend fun dataObjects(
    path: String,
    query: Map<String, String> = emptyMap(),
    dropAfterFirst: Set<String> = emptySet()
  ): List<JSONObject> = withContext(Dispatchers.IO) {
    val result = mutableListOf<JSONObject>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    // A misbehaving server could hand out a fresh cursor forever; bound both the page count and the
    // total items so one listing cannot exhaust memory or spin indefinitely (A15).
    var pages = 0
    var totalChars = 0L
    do {
      if (pages++ >= MAX_PAGES) throw IOException("列表超过分页上限，请在服务器缩小查询范围")
      val pageQuery = query.toMutableMap().apply {
        if (cursor != null) {
          put("cursor", cursor!!)
          dropAfterFirst.forEach(::remove)
        }
      }
      val raw = request("GET", path, pageQuery)
      totalChars += raw.length
      if (totalChars > MAX_RESPONSE_CHARS * 2L) throw IOException("列表数据过大，未使用不完整结果")
      val page = runCatching { jsonObject(raw) }.getOrNull()
      if (page == null) {
        // The legacy InstanceHttpApi returned a bare array and did not paginate.
        result += jsonArray(raw).objects()
        if (result.size > MAX_PAGE_ITEMS) throw IOException("列表超过 $MAX_PAGE_ITEMS 条，未使用不完整结果")
        cursor = null
        continue
      }
      result += page.optJSONArray("data")?.objects().orEmpty()
      if (result.size > MAX_PAGE_ITEMS) {
        throw IOException("列表超过 $MAX_PAGE_ITEMS 条，未使用不完整结果")
      }
      val next = page.obj("cursor").str("next").takeIf { it.isNotBlank() }
      if (next != null && !seen.add(next)) throw IOException("服务器返回重复分页游标，未使用不完整结果")
      cursor = next
    } while (cursor != null)
    result
  }
  private suspend fun page(path: String, query: Map<String, String>): ApiPage<JSONObject> = withContext(Dispatchers.IO) {
    val raw = request("GET", path, query)
    if (raw.trimStart().startsWith("[")) return@withContext ApiPage(jsonArray(raw).objects())
    val value = jsonObject(raw)
    ApiPage(dataArray(value).objects(), value.obj("cursor").str("next").takeIf(String::isNotBlank))
  }
  /** Legacy message pages are bare arrays; their documented continuation cursor is an HTTP header. */
  private suspend fun legacyMessagePage(path: String, query: Map<String, String>): ApiPage<JSONObject> = withContext(Dispatchers.IO) {
    val (raw, headerCursor) = runCall(requestBuilder(path, query).get().build()) { response ->
      val responseBody = response.body?.readText(MAX_RESPONSE_CHARS).orEmpty()
      if (!response.isSuccessful) throw ApiException(response.code, httpErrorMessage(response.code, responseBody))
      responseBody to response.header("X-Next-Cursor")?.takeIf(String::isNotBlank)
    }
    if (raw.trimStart().startsWith("[")) ApiPage(jsonArray(raw).objects(), headerCursor)
    else {
      val value = jsonObject(raw)
      ApiPage(dataArray(value).objects(), headerCursor ?: value.obj("cursor").str("next").takeIf(String::isNotBlank))
    }
  }
  private suspend fun stringValues(path: String, query: Map<String, String>): List<String> = withContext(Dispatchers.IO) {
    val raw = request("GET", path, query)
    if (raw.trimStart().startsWith("[")) {
      val values = jsonArray(raw)
      (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
    } else {
      dataArray(jsonObject(raw)).strings()
    }
  }
  private fun segment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
  /** Percent-encodes each `/`-separated segment so the result can be passed to [url] unchanged. */
  private fun encodedPath(value: String): String = value.trim('/').split('/').joinToString("/") { segment(it) }
  /** The official `LocationQuery` (deepObject): `location[directory]=…`. Absent means the server's own cwd. */
  private fun locationQuery(directory: String): Map<String, String> = if (directory.isBlank()) emptyMap() else mapOf("location[directory]" to directory)
  private fun legacyLocationQuery(directory: String): Map<String, String> = if (directory.isBlank()) emptyMap() else mapOf("directory" to directory)
  private fun sessionPath(id: String, suffix: String = ""): String =
    "${capabilities().sessionRoot}/${segment(id)}$suffix"
  private fun endpointQuery(caps: ApiCapabilities, endpoint: ActionEndpoint, directory: String): Map<String, String> =
    if (caps.nativeV2 && endpoint.path.startsWith("api/")) emptyMap() else legacyLocationQuery(directory)
  private fun ActionEndpoint.path(session: Session): String = path
    .replace("{sessionID}", segment(session.id))
    .replace(Regex("\\{[^}]+\\}"), segment(session.id))
  private fun ActionEndpoint.path(sessionId: String, requestId: String? = null): String {
    val resolved = path.replace("{sessionID}", segment(sessionId))
    return resolved.replace(Regex("\\{[^}]+\\}"), segment(requestId ?: sessionId))
  }

  /**
   * V2 readiness probe:
   * 1. Try `api/info` (the standard OpenCode 2.x info endpoint returning version, pid, etc.)
   * 2. Try `api/health`
   * 3. Fall back to legacy `global/health`
   *
   * If an endpoint returns HTML (such as SPA index.html returned with 200 OK by web UI),
   * it is caught and skipped to the next candidate instead of failing immediately.
   */
  suspend fun health(): String {
    version?.let { return it }
    val candidates = listOf("api/info", "api/health", "global/health")
    var firstNonJsonError: IOException? = null
    for (path in candidates) {
      val info = try {
        obj(path)
      } catch (error: ApiException) {
        if (error.status == 404) continue
        throw error
      } catch (error: IOException) {
        // If web UI SPA intercepts unknown path and returns HTML, jsonObject throws IOException.
        // Save the first non-JSON error in case all candidates fail because the user provided a web URL.
        if (error.message?.contains("网页") == true || error.message?.contains("JSON") == true || error.message?.contains("空响应") == true) {
          if (firstNonJsonError == null) firstNonJsonError = error
          continue
        }
        throw error
      }
      if (path == "api/info" && (info.has("version") || info.has("pid"))) {
        return info.str("version").ifBlank { "2.x" }.also { version = it }
      }
      if (!info.optBoolean("healthy") && !info.has("pid")) continue
      val result = if (path == "api/health") "2.x" else info.str("version").ifBlank { "2.x" }
      return result.also { version = it }
    }
    throw firstNonJsonError ?: IOException("该地址不是 OpenCode 服务器（未找到可用的健康接口）")
  }

  suspend fun projects(): List<Project> {
    val caps = discoverCapabilities()
    if (caps.nativeV2) {
      val location = dataObject(obj("api/location"))
      val directory = location.str("directory")
      return if (directory.isBlank()) emptyList()
      else listOf(Project(location.obj("project").str("id").ifBlank { directory }, directory, directory.substringAfterLast('/')))
    }
    val listed = try {
      val endpoint = caps.legacyProjectList ?: unsupported("旧服务器未提供项目列表接口")
      val raw = withContext(Dispatchers.IO) { request(endpoint.method, endpoint.path, legacyLocationQuery("")) }
      // The published contract returns `Project[]` as a bare array; accept a `{data: […]}` envelope too.
      (if (raw.trimStart().startsWith("[")) jsonArray(raw) else dataArray(jsonObject(raw))).objects().map { it.toProject() }.filter { it.directory.isNotBlank() }
    } catch (error: ApiException) { if (error.status == 404) null else throw error }
    if (listed != null) return listed
    return emptyList()
  }
  suspend fun session(id: String): Session {
    val caps = capabilities()
    val path = if (caps.nativeV2) sessionPath(id) else {
      val endpoint = caps.legacySessionGet ?: unsupported("旧服务器未提供会话读取接口")
      endpoint.path(id)
    }
    return dataObject(obj(path, if (caps.nativeV2) emptyMap() else legacyLocationQuery(""))).toSession()
  }

  /**
   * Resolve [directory] to the server's canonical project, like the desktop client's `project.current()`.
   * Passing the directory through `location[directory]` lets the server do the path normalization, so
   * the phone stores the canonical directory and never spawns a duplicate project for the same folder.
   */
  suspend fun projectAt(directory: String): Project? {
    val caps = capabilities()
    if (!caps.nativeV2) {
      val endpoint = caps.legacyProjectCurrent
        ?: unsupported("旧服务器未提供当前项目接口")
      return dataObject(obj(endpoint.path, legacyLocationQuery(directory))).toProject().takeIf { it.directory.isNotBlank() }
    }
    val location = dataObject(obj("api/location", locationQuery(directory)))
    val project = location.obj("project")
    val canonical = project.str("canonical").ifBlank { project.str("worktree") }.ifBlank { location.str("directory") }.ifBlank { directory }
    if (canonical.isBlank()) return null
    val id = project.str("id").ifBlank { canonical }
    return Project(id, canonical, project.str("name").ifBlank { canonical.trimEnd('/').substringAfterLast('/').ifBlank { canonical } }, project.arr("sandboxes").strings())
  }

  /** Home catalog: V2 has no parentID query; root rows are filtered from the ordered global page. */
  suspend fun rootSessionsPage(directory: String? = null, cursor: String? = null, size: Int = 100): ApiPage<Session> = withContext(Dispatchers.IO) {
    val caps = capabilities()
    val query = mutableMapOf("limit" to size.toString())
    if (caps.nativeV2) {
      if (directory != null) query["directory"] = directory
      if (cursor == null) query["order"] = "desc" else query["cursor"] = cursor
    } else {
      query["roots"] = "true"
      if (directory != null) query["directory"] = directory
      if (cursor != null) query["start"] = cursor
    }
    val path = if (caps.nativeV2) caps.sessionRoot else (caps.legacySessionList ?: unsupported("旧服务器未提供会话列表接口")).path
    val page = page(path, query)
    ApiPage(page.items.map { it.toSession() }.filter { !caps.nativeV2 || it.parentId == null }, page.next)
  }
  /** Bind the acknowledgement to the exact completed execution, never to wall-clock time. */
  suspend fun viewSession(session: Session, idle: Long) {
    if (idle <= 0) return
    val caps = capabilities()
    val endpoint = caps.sessionView ?: return
    request(endpoint.method, endpoint.path(session), endpointQuery(caps, endpoint, session.directory), JSONObject().put("idle", idle))
  }
  /**
   * One page of the projected timeline, oldest first. The first page is the newest [size] messages
   * (official page size 20); [cursor] walks further back.
   */
  suspend fun messagesPage(id: String, cursor: String? = null, size: Int = MESSAGE_PAGE): ApiPage<Message> = withContext(Dispatchers.IO) {
    val caps = capabilities()
    val query = mutableMapOf("limit" to size.toString())
    if (caps.nativeV2) {
      if (cursor == null) query["order"] = "desc" else query["cursor"] = cursor
    } else if (cursor != null) {
      query["before"] = cursor
    }
    val page = if (caps.nativeV2) page(sessionPath(id, "/message"), query)
      else {
        val endpoint = caps.legacyMessageList ?: unsupported("旧服务器未提供消息列表接口")
        legacyMessagePage(endpoint.path(id), query)
      }
    ApiPage(page.items.map { it.toMessage() }.asReversed(), page.next)
  }
  /** V2 reports admitted input through durable events; it has no inbox REST endpoint. */
  suspend fun inbox(id: String): List<Message> = emptyList()
  /** Sessions whose foreground drain this server currently owns; every other session is idle. */
  suspend fun activeSessions(): Set<String> {
    val caps = capabilities()
    if (caps.nativeV2) return dataObject(obj("api/session/active")).keys().asSequence().toSet()
    val endpoint = caps.legacyStatus ?: unsupported("旧服务器未提供会话状态接口")
    val statuses = dataObject(obj(endpoint.path, legacyLocationQuery("")))
    return statuses.keys().asSequence().filter { statuses.obj(it).str("type") != "idle" }.toSet()
  }

  /**
   * Running shell jobs for [directory] (`GET /api/shell`). Shells are not sessions: a background
   * command keeps running while its session execution is idle, so this is the only authoritative
   * read for "background work still pending" after a cold start (official `shell.list`).
   */
  suspend fun shells(directory: String): List<ShellJob> =
    if (capabilities().nativeV2) {
      dataArray(obj("api/shell", locationQuery(directory))).objects().mapNotNull { it.toShellJob(directory) }
    } else emptyList()

  suspend fun createSession(directory: String, title: String): Session =
    run {
      val caps = capabilities()
      val body = if (caps.nativeV2) JSONObject().put("location", JSONObject().put("directory", directory))
      else JSONObject().apply { if (title.isNotBlank()) put("title", title.trim()) }
      val path = if (caps.nativeV2) caps.sessionRoot else (caps.legacySessionCreate ?: unsupported("旧服务器未提供会话创建接口")).path
      dataObject(requestObject("POST", path, body = body, query = if (caps.nativeV2) emptyMap() else legacyLocationQuery(directory))).toSession()
    }
  suspend fun renameSession(session: Session, title: String) {
    val caps = capabilities()
    val endpoint = caps.actions[SessionAction.RENAME] ?: unsupported("服务器未提供重命名")
    request(endpoint.method, endpoint.path(session), endpointQuery(caps, endpoint, session.directory), JSONObject().put("title", title))
  }
  /** Archive (or, when the instance allows a null `time.archived`, restore) through the documented update body. */
  suspend fun setArchived(session: Session, archived: Boolean, now: Long = System.currentTimeMillis()) {
    val caps = capabilities()
    val archive = caps.archive ?: unsupported("服务器未提供归档")
    if (!archived && !archive.restorable) unsupported("服务器不支持取消归档")
    request(archive.endpoint.method, archive.endpoint.path(session), endpointQuery(caps, archive.endpoint, session.directory),
      JSONObject().put("time", JSONObject().put("archived", if (archived) now else JSONObject.NULL)))
  }
  suspend fun deleteSession(session: Session) {
    val caps = capabilities()
    val endpoint = caps.removeEndpoint ?: unsupported("服务器未提供删除会话接口")
    request(endpoint.method, endpoint.path(session), endpointQuery(caps, endpoint, session.directory))
  }
  suspend fun forkSession(session: Session): Session {
    val caps = capabilities()
    val endpoint = caps.forkEndpoint ?: unsupported("服务器未提供分支会话接口")
    return dataObject(requestObject(endpoint.method, endpoint.path(session), JSONObject(), endpointQuery(caps, endpoint, session.directory))).toSession()
  }
  suspend fun abort(session: Session) {
    val caps = capabilities()
    if (caps.nativeV2) request("POST", sessionPath(session.id, "/interrupt"))
    else {
      val endpoint = caps.legacyAbort ?: unsupported("服务器未提供停止会话接口")
      request(endpoint.method, endpoint.path(session), legacyLocationQuery(session.directory))
    }
  }
  suspend fun compact(session: Session) {
    val caps = capabilities()
    if (caps.nativeV2) request("POST", sessionPath(session.id, "/compact"))
    else {
      val model = session.model ?: unsupported("旧服务器整理上下文需要会话模型")
      val endpoint = caps.legacyCompact ?: unsupported("服务器未提供整理上下文接口")
      request(endpoint.method, endpoint.path(session), legacyLocationQuery(session.directory),
        body = JSONObject().put("providerID", model.providerId).put("modelID", model.modelId))
    }
  }
  suspend fun revert(session: Session, messageId: String) {
    val caps = capabilities()
    if (caps.nativeV2) request("POST", sessionPath(session.id, "/revert/stage"), body = JSONObject().put("messageID", messageId))
    else {
      val endpoint = caps.legacyRevert ?: unsupported("服务器未提供撤销接口")
      request(endpoint.method, endpoint.path(session), legacyLocationQuery(session.directory), JSONObject().put("messageID", messageId))
    }
  }
  suspend fun unrevert(session: Session) {
    val caps = capabilities()
    val endpoint = caps.actions[SessionAction.UNREVERT] ?: caps.legacyUnrevert
    val selected = endpoint ?: unsupported("服务器未提供恢复撤销")
    request(selected.method, selected.path(session), endpointQuery(caps, selected, session.directory))
  }

  /**
   * Official composer order: a steering prompt first applies the composer's agent/model selection to
   * the session, then admits the prompt under a client-minted id so one retry of a lost response is
   * idempotent. Phone files travel inline as `data:` URIs in `files[].uri`.
   */
  suspend fun send(session: Session, text: String, agent: String?, model: ModelChoice?, references: List<FileReference> = emptyList(),
    inline: List<InlineFile> = emptyList(), id: String = MessageIds.ascending(), delivery: Delivery = Delivery.STEER) {
    val caps = capabilities()
    if (!caps.nativeV2) {
      val endpoint = caps.legacyPrompt ?: unsupported("旧服务器未提供消息发送接口")
      val body = JSONObject().put("messageID", id).put("parts", legacyParts(session, text, references, inline))
      agent?.takeIf(String::isNotBlank)?.let { body.put("agent", it) }
      model?.let {
        body.put("model", JSONObject().put("providerID", it.providerId).put("modelID", it.modelId))
        it.variant?.let { variant -> body.put("variant", variant) }
      }
      retryOnce { request(endpoint.method, endpoint.path(session), legacyLocationQuery(session.directory), body) }
      return
    }
    if (delivery == Delivery.STEER) applySelection(session, agent, model)
    val prompt = JSONObject().put("text", text)
    files(session, references, inline)?.let { prompt.put("files", it) }
    val body = JSONObject().put("id", id).put("prompt", prompt).put("delivery", delivery.wire)
    retryOnce { request("POST", sessionPath(session.id, "/prompt"), body = body) }
  }
  /** Legacy command execution. Native V2 exposes command discovery but deliberately no command POST route. */
  suspend fun command(session: Session, name: String, arguments: String, agent: String?, model: ModelChoice?,
    references: List<FileReference> = emptyList(), inline: List<InlineFile> = emptyList(), delivery: Delivery = Delivery.STEER,
    id: String = MessageIds.ascending()) {
    val caps = capabilities()
    check(!caps.nativeV2 && caps.legacyCommand) { "原生 V2 协议不提供命令执行接口" }
    val endpoint = caps.legacyCommandEndpoint ?: unsupported("旧服务器未提供命令执行接口")
    val body = JSONObject().put("messageID", id).put("command", name).put("arguments", arguments)
    agent?.takeIf(String::isNotBlank)?.let { body.put("agent", it) }
    model?.let { body.put("model", "${it.providerId}/${it.modelId}"); it.variant?.let { variant -> body.put("variant", variant) } }
    val parts = (references.map { ref -> JSONObject().put("type", "file").put("mime", ref.mime).put("filename", ref.path.substringAfterLast('/')).put("url", referenceUri(session, ref)) } +
      inline.map { file -> JSONObject().put("type", "file").put("mime", file.mime).put("filename", file.name).put("url", file.uri) })
    if (parts.isNotEmpty()) body.put("parts", JSONArray(parts))
    request(endpoint.method, endpoint.path(session), legacyLocationQuery(session.directory), body)
  }
  private suspend fun applySelection(session: Session, agent: String?, model: ModelChoice?) {
    if (!agent.isNullOrBlank()) request("POST", sessionPath(session.id, "/agent"), body = JSONObject().put("agent", agent))
    if (model != null) request("POST", sessionPath(session.id, "/model"), body = JSONObject().put("model", modelRef(model)))
  }
  private fun modelRef(model: ModelChoice) = JSONObject().put("providerID", model.providerId).put("id", model.modelId)
    .apply { model.variant?.let { put("variant", it) } }
  private fun files(session: Session, references: List<FileReference>, inline: List<InlineFile>): JSONArray? {
    if (references.isEmpty() && inline.isEmpty()) return null
    return JSONArray().apply {
      references.forEach { ref -> put(JSONObject().put("uri", referenceUri(session, ref)).put("mime", ref.mime).put("name", ref.path.substringAfterLast('/'))) }
      inline.forEach { file -> put(JSONObject().put("uri", file.uri).put("mime", file.mime).put("name", file.name)) }
    }
  }
  private fun legacyParts(session: Session, text: String, references: List<FileReference>, inline: List<InlineFile>): JSONArray = JSONArray().apply {
    put(JSONObject().put("type", "text").put("text", text))
    references.forEach { reference ->
      put(JSONObject().put("type", "file").put("mime", reference.mime).put("url", referenceUri(session, reference))
        .put("filename", reference.path.substringAfterLast('/')))
    }
    inline.forEach { file ->
      put(JSONObject().put("type", "file").put("mime", file.mime).put("url", file.uri).put("filename", file.name))
    }
  }
  /**
   * The official client retries an admission once with the same id. Only a transport failure is
   * retried; if that retry reports a conflict, the first attempt already admitted this id.
   */
  private suspend fun retryOnce(block: suspend () -> Unit) {
    try { block() } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
    catch (error: IOException) {
      if (error is ApiException) throw error
      try { block() } catch (conflict: ApiException) { if (conflict.status != 409) throw conflict }
    }
  }
  private fun referenceUri(session: Session, reference: FileReference): String {
    val path = if (reference.path.startsWith('/')) reference.path else session.directory.trimEnd('/') + "/" + reference.path
    return java.net.URI("file", "", path, null).toASCIIString()
  }
  suspend fun agents(directory: String): List<AgentChoice> {
    val caps = capabilities()
    val endpoint = if (caps.nativeV2) ActionEndpoint("GET", "api/agent") else caps.legacyAgentList
      ?: unsupported("服务器未提供代理列表接口")
    val query = if (caps.nativeV2) locationQuery(directory) else legacyLocationQuery(directory)
    return dataObjects(endpoint.path, query).filterNot { it.optBoolean("hidden") }
      .map { AgentChoice(it.str("id").ifBlank { it.str("name") }, it.str("description")) }
  }
  suspend fun commands(directory: String): List<CommandChoice> {
    val caps = capabilities()
    val endpoint = if (caps.nativeV2) ActionEndpoint("GET", "api/command") else caps.legacyCommandList
      ?: unsupported("服务器未提供命令列表接口")
    val query = if (caps.nativeV2) locationQuery(directory) else legacyLocationQuery(directory)
    return dataObjects(endpoint.path, query).map { CommandChoice(it.str("name"), it.str("description")) }
  }

  /** Models the server can run in [directory], with the metadata [ModelVisibility] needs. */
  suspend fun modelCatalog(directory: String): List<ModelInfo> {
    val caps = capabilities()
    val modelEndpoint = if (caps.nativeV2) ActionEndpoint("GET", "api/model") else caps.legacyModelList
    val providerEndpoint = if (caps.nativeV2) ActionEndpoint("GET", "api/provider") else caps.legacyProviderList
    val query = if (caps.nativeV2) locationQuery(directory) else legacyLocationQuery(directory)
    val models = modelEndpoint?.let { dataObjects(it.path, query) }.orEmpty()
    val providerItems = try {
      providerEndpoint?.let { providerObjects(it.path, query) }.orEmpty()
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) { emptyList() }
    val names = providerItems.associate { it.str("id") to it.str("name") }
    val expandedModels = models.ifEmpty {
      // InstanceHttpApi exposes the model catalog nested in `/provider` (`all[].models`); it
      // deliberately has no fabricated `/model` fallback. A separately documented `/model` route
      // is still accepted above for compatible older instances.
      providerItems.flatMap { provider ->
        val providerId = provider.str("id")
        val array = provider.optJSONArray("models")
        if (array != null) (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { model ->
          JSONObject(model.toString()).apply { if (!has("providerID")) put("providerID", providerId) }
        } else {
          val objectModels = provider.optJSONObject("models") ?: JSONObject()
          objectModels.keys().asSequence().mapNotNull { id ->
            val model = objectModels.optJSONObject(id) ?: return@mapNotNull null
            JSONObject(model.toString()).apply {
              if (!has("id")) put("id", id)
              if (!has("providerID")) put("providerID", providerId)
            }
          }.toList()
        }
      }
    }
    return expandedModels.mapNotNull { it.toV2ModelInfo(names) }
  }
  /** Reads either a V2 `{data:[…]}` provider response or the legacy `{all:[…]}` response. */
  private suspend fun providerObjects(path: String, query: Map<String, String>): List<JSONObject> = withContext(Dispatchers.IO) {
    val raw = request("GET", path, query)
    if (raw.trimStart().startsWith("[")) return@withContext jsonArray(raw).objects()
    val value = jsonObject(raw)
    (value.optJSONArray("data") ?: value.optJSONArray("all") ?: JSONArray()).objects()
  }
  /** Pending permission requests owned by one session (official `session.permission.list`). */
  suspend fun sessionPermissions(session: Session): List<PermissionRequest> {
    val caps = capabilities()
    return if (caps.nativeV2) dataArray(obj(sessionPath(session.id, "/permission"))).objects().map { it.toPermission(session.directory) }
      else permissions(session.directory).filter { it.sessionId == session.id }
  }
  /** Legacy global permission list, retained only for InstanceHttpApi compatibility. */
  private suspend fun permissions(directory: String): List<PermissionRequest> {
    val endpoint = capabilities().legacyPermissionList ?: unsupported("旧服务器未提供权限列表接口")
    return dataObjects(endpoint.path, legacyLocationQuery(directory)).map { it.toPermission(directory) }
  }

  /** Pending questions at [directory]. V2 uses `question.request`; the old form route is isolated here. */
  suspend fun forms(directory: String): List<QuestionRequest> {
    val caps = capabilities()
    if (caps.nativeV2) return dataArray(obj("api/question/request", locationQuery(directory))).objects().map { it.toQuestion(directory) }
    val endpoint = caps.legacyQuestionList ?: unsupported("旧服务器未提供问题接口")
    return dataObjects(endpoint.path, legacyLocationQuery(directory)).map { it.toQuestion(directory) }
  }
  suspend fun replyPermission(request: PermissionRequest, reply: String) {
    require(reply in setOf("once", "always", "reject")) { "未知权限决定" }
    require(reply != "always" || request.always.isNotEmpty()) { "无法确认保存规则范围，请仅允许一次" }
    val caps = capabilities()
    if (caps.nativeV2) request("POST", sessionPath(request.sessionId, "/permission/${segment(request.id)}/reply"), body = JSONObject().put("reply", reply))
    else {
      val endpoint = caps.legacyPermissionReply ?: unsupported("旧服务器未提供权限回复接口")
      request(endpoint.method, endpoint.path(request.sessionId, request.id), legacyLocationQuery(request.directory), JSONObject().put("reply", reply))
    }
  }
  suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
    val caps = capabilities()
    if (caps.nativeV2) {
      request("POST", sessionPath(request.sessionId, "/question/${segment(request.id)}/reply"), body = JSONObject().put("answers", JSONArray(answers.map { JSONArray(it) })))
    } else {
      val endpoint = caps.legacyQuestionReply ?: unsupported("旧服务器未提供问题回复接口")
      request(endpoint.method, endpoint.path(request.sessionId, request.id), legacyLocationQuery(request.directory),
        JSONObject().put("answers", JSONArray(answers.map { JSONArray(it) })))
    }
  }
  suspend fun rejectQuestion(request: QuestionRequest) {
    val caps = capabilities()
    if (caps.nativeV2) request("POST", sessionPath(request.sessionId, "/question/${segment(request.id)}/reject"))
    else {
      val endpoint = caps.legacyQuestionReject ?: unsupported("旧服务器未提供问题拒绝接口")
      request(endpoint.method, endpoint.path(request.sessionId, request.id), legacyLocationQuery(request.directory))
    }
  }
  suspend fun children(session: Session): List<Session> {
    val caps = capabilities()
    return if (caps.nativeV2) dataObjects(caps.sessionRoot, mapOf("limit" to "100", "order" to "desc"), setOf("order"))
      .map { it.toSession() }.filter { it.parentId == session.id }
      else caps.legacyChildrenEndpoint?.let { endpoint -> dataObjects(endpoint.path(session.id), legacyLocationQuery(session.directory)).map { it.toSession() } }.orEmpty()
  }
  suspend fun diff(session: Session): List<FileChange> {
    val caps = capabilities()
    val endpoint = caps.diffEndpoint ?: unsupported("服务器不提供改动记录")
    return dataObjects(endpoint.path(session), endpointQuery(caps, endpoint, session.directory)).map { it.toChange() }
  }
  suspend fun files(directory: String, path: String): List<FileNode> {
    val caps = capabilities()
    if (!caps.nativeV2) {
      val endpoint = caps.legacyFiles ?: unsupported("旧服务器未提供文件列表接口")
      return dataObjects(endpoint.path, legacyLocationQuery(directory) + mapOf("path" to path)).map { FileNode(it.str("path"), it.str("type")) }
    }
    return dataArray(obj("api/fs/list", locationQuery(directory) + (if (path.isNotBlank() && path != ".") mapOf("path" to path) else emptyMap())))
      .objects().map { item -> FileNode(item.str("path"), item.str("type")) }
  }
  suspend fun fileContent(directory: String, path: String): FileContent {
    val caps = capabilities()
    if (!caps.nativeV2) {
      val endpoint = caps.legacyFileContent ?: unsupported("旧服务器未提供文件读取接口")
      return dataObject(obj(endpoint.path, legacyLocationQuery(directory) + mapOf("path" to path))).let { value ->
        FileContent(value.str("type"), value.str("content"))
      }
    }
    val (bytes, contentType) = requestBytes("api/fs/read/${encodedPath(path)}", locationQuery(directory))
    return if (!isTextFile(path, contentType)) FileContent("binary", Base64.encodeToString(bytes, Base64.NO_WRAP))
    else FileContent("text", bytes.toString(Charsets.UTF_8))
  }
  suspend fun searchFiles(directory: String, query: String): List<String> {
    val caps = capabilities()
    if (!caps.nativeV2) {
      val endpoint = caps.legacySearchFiles ?: unsupported("旧服务器未提供文件搜索接口")
      return stringValues(endpoint.path, legacyLocationQuery(directory) + mapOf("query" to query))
    }
    return dataArray(obj("api/fs/find", locationQuery(directory) + mapOf("query" to query))).objects().mapNotNull { it.str("path").takeIf(String::isNotBlank) }
  }

  /** `GET /api/event`: volatile by contract, so a dropped stream is recovered by reconnect + reconciliation. */
  fun events(onOpen: () -> Unit = {}): Flow<ServerEvent> = callbackFlow {
    val request = requestBuilder(capabilities().eventPath, emptyMap()).header("Accept", "text/event-stream").build()
    requireOrigin(request)
    val source: EventSource = EventSources.createFactory(streamClient).newEventSource(request, object : EventSourceListener() {
      override fun onOpen(eventSource: EventSource, response: Response) { onOpen() }
      override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        try {
          val event = data.toServerEvent(id.orEmpty())
          // A full/closed buffer must not silently drop an event: the dropped event could be the very
          // permission or terminal state the UI is waiting for. Surface it as a stream failure so the
          // controller reconnects and runs a full reconciliation instead of trusting a partial view.
          val delivered = trySend(event)
          if (delivered.isFailure) {
            Diagnostics.warn("SSE", "事件积压，触发重新同步", delivered.exceptionOrNull())
            close(IOException("SSE 事件积压，已触发全量对账"))
          }
        } catch (error: Exception) {
          // Ignore malformed event; next event or refresh repairs state. Logged for diagnosis.
          Diagnostics.warn("SSE", "忽略无法解析的事件", error)
        }
      }
      override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        close(t ?: IOException("SSE 已断开：HTTP ${response?.code ?: 0}"))
      }
      override fun onClosed(eventSource: EventSource) { close() }
    })
    awaitClose { source.cancel() }
  }

  private suspend fun requestObject(method: String, path: String, body: JSONObject, query: Map<String, String> = emptyMap()): JSONObject =
    withContext(Dispatchers.IO) { jsonObject(request(method, path, query, body)) }

  private fun unsupported(message: String): Nothing = throw ApiException(501, message)

  private fun isTextFile(path: String, contentType: String): Boolean {
    val mime = contentType.substringBefore(';').trim().lowercase()
    if (mime.startsWith("text/") || mime in TEXT_MIME_TYPES) return true
    val name = path.substringAfterLast('/').lowercase()
    if (name in TEXT_FILE_NAMES) return true
    val extension = name.substringAfterLast('.', "")
    return extension in TEXT_EXTENSIONS
  }

  companion object {
    /** Official initial page size (`messagePageLimit`). */
    const val MESSAGE_PAGE = 20
    /** Older-history page size: the official client and the server both cap a page at 200. */
    const val HISTORY_PAGE = 200
    private const val MAX_RESPONSE_CHARS = 8_000_000
    private const val MAX_FILE_BYTES = 8_000_000L
    private const val MAX_PAGES = 40
    private const val MAX_PAGE_ITEMS = 5_000
    private val TEXT_MIME_TYPES = setOf("application/json", "application/xml", "application/javascript", "application/x-javascript")
    private val TEXT_FILE_NAMES = setOf("dockerfile", "makefile", "license", "readme", "changelog", ".gitignore", ".gitattributes", ".env")
    private val TEXT_EXTENSIONS = setOf(
      "txt", "md", "markdown", "rst", "json", "jsonc", "yaml", "yml", "toml", "xml", "html", "htm", "css", "scss", "less",
      "js", "jsx", "ts", "tsx", "vue", "svelte", "kt", "kts", "java", "groovy", "gradle", "py", "rb", "go", "rs", "c", "cc",
      "cpp", "h", "hh", "hpp", "sh", "bash", "zsh", "sql", "swift", "php", "pl", "ini", "cfg", "conf", "properties",
      "env", "gitignore", "gitattributes", "bat", "cmd", "ps1", "tf", "hcl", "proto", "graphql", "lock", "svg", "tex", "diff", "patch"
    )
  }
}

/**
 * Client-minted message ids in the server's own format (`Identifier.ascending`): 6 bytes of
 * `timestamp * 0x1000 + counter` in hex, then 14 random base-62 characters. They sort with server
 * ids, which matters for revert boundaries and timeline order.
 */
object MessageIds {
  private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
  private val random = SecureRandom()
  private var lastTimestamp = 0L
  private var counter = 0L
  @Synchronized fun ascending(timestamp: Long = System.currentTimeMillis()): String {
    if (timestamp != lastTimestamp) { lastTimestamp = timestamp; counter = 0 }
    counter++
    val value = timestamp * 0x1000L + counter
    val time = (0 until 6).joinToString("") { index -> "%02x".format((value shr (40 - 8 * index)) and 0xff) }
    val tail = (0 until 14).joinToString("") { CHARS[random.nextInt(62)].toString() }
    return "msg_$time$tail"
  }
}

/** Parses native V2, legacy global, and the older unwrapped event envelopes. */
internal fun String.toServerEvent(sseId: String): ServerEvent {
  val json = JSONObject(this)
  val payload = json.obj("payload")
  val legacy = payload.length() > 0
  val properties = when {
    legacy -> payload.obj("properties")
    json.optJSONObject("data") != null -> json.obj("data")
    else -> json.obj("properties")
  }
  val created = properties.optLong("timestamp").takeIf { it > 0 }
    ?: properties.longPath("time", "created").takeIf { it > 0 }
    ?: properties.optLong("created").takeIf { it > 0 }
    ?: json.optLong("created")
  val id = if (legacy) payload.str("id") else json.str("id")
  val type = if (legacy) payload.str("type") else json.str("type")
  val directory = if (legacy) json.str("directory").ifBlank { payload.obj("location").str("directory") }
    else json.obj("location").str("directory").ifBlank { json.str("directory") }
  val resolvedDirectory = directory.ifBlank { properties.obj("location").str("directory") }.ifBlank { properties.str("directory") }
  return ServerEvent(id.ifBlank { sseId }, resolvedDirectory, type, properties, created)
}
