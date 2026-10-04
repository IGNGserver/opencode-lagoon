package com.igng.opencode.lagoon.core

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class ServerCredentials(
  val username: String = "opencode",
  val password: String = "",
  val cookie: String = ""
)

data class ApiPage<T>(val items: List<T>, val next: String? = null)

data class ServerEvent(val id: String = "", val directory: String, val type: String, val properties: JSONObject)
class ApiException(val status: Int, message: String) : IOException(message)
enum class ServerProtocol {
  UNKNOWN, V1, V2;

  /** Rename/delete/fork/share session operations exist only on V1. */
  val supportsSessionActions: Boolean get() = this == V1
  /** V1 accepts a title when creating a session; V2 derives it. */
  val supportsTitleOnCreate: Boolean get() = this == V1
  /** Todo and diff endpoints exist only on V1. */
  val supportsTodosAndDiff: Boolean get() = this == V1
}

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
  @Volatile private var protocol = ServerProtocol.UNKNOWN
  @Volatile private var currentV2 = false
  private var discoveredCapabilities: ApiCapabilities? = null
  fun capabilities(): ApiCapabilities = discoveredCapabilities ?: ApiCapabilities.fallback(protocol, currentV2)
  fun supportsSavedPermissions() = capabilities().savedPermissions
  suspend fun discoverCapabilities(): ApiCapabilities {
    ensureProtocol()
    discoveredCapabilities?.let { return it }
    val document = try { dataObject(obj("doc")) } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
      catch (_: Exception) { JSONObject() }
    return ApiCapabilities.fromDocument(protocol, document, currentV2).also { discoveredCapabilities = it }
  }
  private fun actionEndpoint(action: SessionAction, session: Session): ActionEndpoint {
    val endpoint = capabilities().actions[action] ?: unsupported("服务器未提供此操作")
    return endpoint.copy(path = endpoint.path.replace(Regex("\\{[^}]+\\}"), segment(session.id)))
  }

  init {
    require(base.scheme == "https" || base.scheme == "http" && profile.allowCleartext) { "HTTP 明文连接未获授权，请在服务器资料中明确开启" }
    require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "请使用不含凭据或参数的服务器地址" }
  }

  private fun url(path: String, directory: String? = null, query: Map<String, String> = emptyMap()): HttpUrl {
    // The path is already percent-encoded segment-by-segment (see [segment]/[encodedPath]); using the
    // encoded variant avoids OkHttp re-encoding '%' and producing e.g. '%2520' for ids containing spaces.
    val builder = base.newBuilder().addEncodedPathSegments(path.trimStart('/'))
    if (!directory.isNullOrBlank()) builder.addQueryParameter("directory", directory)
    query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
    return builder.build()
  }

  private fun requestBuilder(path: String, directory: String?, query: Map<String, String>): Request.Builder {
    val builder = Request.Builder().url(url(path, directory, query)).header("Accept", "application/json")
    if (credentials.password.isNotEmpty()) {
      builder.header("Authorization", Credentials.basic(credentials.username.ifBlank { profile.username.ifBlank { "opencode" } }, credentials.password))
    }
    if (credentials.cookie.isNotBlank()) builder.header("Cookie", credentials.cookie)
    return builder
  }

  /** Every request this client builds targets [origin]; the redirect policy forbids OkHttp from
   *  moving an authenticated request elsewhere, so the credentials can only ever reach [origin]. */
  private fun requireOrigin(request: Request) {
    val url = request.url
    require(HttpOrigin.of(url) == origin) { "拒绝向非授权地址发送凭据" }
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
        else -> "服务器返回 HTTP $status"
      }
    }
  }

  private suspend fun request(method: String, path: String, directory: String? = null, query: Map<String, String> = emptyMap(), body: JSONObject? = null): String {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    return runCall(requestBuilder(path, directory, query).method(method, if (method == "GET" || method == "DELETE") null else (body?.toString() ?: "{}").toRequestBody(mediaType)).build()) { response ->
      val responseBody = response.body?.readText(MAX_RESPONSE_CHARS).orEmpty()
      if (!response.isSuccessful) throw ApiException(response.code, httpErrorMessage(response.code, responseBody))
      responseBody
    }
  }

  private suspend fun requestBytes(method: String, path: String, directory: String? = null, query: Map<String, String> = emptyMap()): Pair<ByteArray, String> =
    runCall(requestBuilder(path, directory, query).method(method, null).build()) { response ->
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
  private suspend fun obj(path: String, directory: String? = null, query: Map<String, String> = emptyMap()): JSONObject =
    withContext(Dispatchers.IO) { jsonObject(request("GET", path, directory, query)) }
  private suspend fun arr(path: String, directory: String? = null, query: Map<String, String> = emptyMap()): JSONArray =
    withContext(Dispatchers.IO) { jsonArray(request("GET", path, directory, query)) }
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
      val raw = request("GET", path, query = pageQuery)
      totalChars += raw.length
      if (totalChars > MAX_RESPONSE_CHARS * 2L) throw IOException("列表数据过大，未使用不完整结果")
      val page = jsonObject(raw)
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
  private fun segment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
  /** Percent-encodes each `/`-separated segment so the result can be passed to [url] unchanged. */
  private fun encodedPath(value: String): String = value.trim('/').split('/').joinToString("/") { segment(it) }
  private fun locationQuery(directory: String): Map<String, String> = if (directory.isBlank()) emptyMap() else mapOf("location[directory]" to directory)
  fun detectedProtocol(): ServerProtocol = protocol

  private suspend fun ensureProtocol(): ServerProtocol {
    if (protocol == ServerProtocol.UNKNOWN) health()
    return protocol
  }

  suspend fun health(): String {
    if (protocol == ServerProtocol.V1) {
      val response = obj("global/health")
      if (!response.optBoolean("healthy")) throw IOException("OpenCode 服务未就绪")
      return response.str("version")
    }
    if (protocol == ServerProtocol.V2) return v2Health()
    try {
      val legacy = obj("global/health")
      if (!legacy.optBoolean("healthy")) throw IOException("OpenCode 服务未就绪")
      protocol = ServerProtocol.V1
      return legacy.str("version")
    } catch (error: Exception) {
      // If legacy /global/health returned 404 OR returned non-JSON/SPA HTML with HTTP 200,
      // it means the server is likely an OpenCode V2 instance (where /global/health does not exist,
      // or unknown routes are served with SPA HTML fallback by the web server).
      val isHtmlOrNotJson = error is IOException && error !is ApiException &&
        (error.message?.contains("网页") == true || error.message?.contains("JSON") == true)
      val is404 = error is ApiException && error.status == 404
      if (!is404 && !isHtmlOrNotJson) throw error
    }
    val version = v2Health()
    protocol = ServerProtocol.V2
    return version
  }

  /**
   * V2 health probe. Older V2 revisions exposed `api/health`; the current published V2 API exposes
   * `api/info`. Trying the older path first and falling back on 404 keeps both revisions connectable
   * (previously a 404 here aborted the whole connection).
   */
  private suspend fun v2Health(): String {
    try {
      val response = obj("api/health")
      if (!response.optBoolean("healthy") && !response.has("pid")) throw IOException("OpenCode 服务未就绪")
      return "OpenCode V2"
    } catch (error: ApiException) {
      if (error.status != 404) throw error
    }
    // A responding info endpoint means the server is up; the body shape is version-specific.
    obj("api/info")
    currentV2 = true
    return "OpenCode V2"
  }
  suspend fun projects(): List<Project> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("project").objects().map { it.toProject() }.filter { it.directory.isNotBlank() }
    ServerProtocol.V2 -> {
      val projectPath = capabilities().projectPath
      // The published V2 contract returns `Project[]` as a bare array; accept a `{data: […]}` envelope
      // too. A server without the endpoint (404) falls back to its single current location.
      val listed = projectPath?.let { path ->
        try {
          val raw = withContext(Dispatchers.IO) { request("GET", path) }
          (if (raw.trimStart().startsWith("[")) jsonArray(raw) else dataArray(jsonObject(raw))).objects().map { it.toProject() }.filter { it.directory.isNotBlank() }
        } catch (error: ApiException) { if (error.status == 404) null else throw error }
      }
      if (listed != null) listed
      else {
        val location = dataObject(obj("api/location"))
        val directory = location.str("directory")
        if (directory.isBlank()) emptyList()
        else listOf(Project(location.obj("project").str("id").ifBlank { directory }, directory, directory.substringAfterLast('/')))
      }
    }
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun sessions(directory: String): List<Session> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session", directory).objects().map { it.toSession() }
    ServerProtocol.V2 -> dataObjects("api/session", query = mapOf("directory" to directory, "order" to "desc")).map { it.toSession() }
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun session(id: String, directory: String): Session = when (ensureProtocol()) {
    ServerProtocol.V1 -> obj("session/${segment(id)}", directory).toSession()
    ServerProtocol.V2 -> dataObject(obj("api/session/${segment(id)}")).toSession()
    else -> error("OpenCode 协议未检测")
  }
  suspend fun sessionsPage(directory: String, cursor: String? = null, size: Int = 100): ApiPage<Session> = withContext(Dispatchers.IO) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val limit = (cursor?.toIntOrNull() ?: size).coerceAtMost(MAX_PAGE_ITEMS)
        val items = arr("session", directory, mapOf("limit" to limit.toString())).objects().map { it.toSession() }.sortedByDescending { it.updated }
        ApiPage(items.take(limit), (limit + size).toString().takeIf { items.size >= limit && limit < MAX_PAGE_ITEMS })
      }
      ServerProtocol.V2 -> {
        val query = mutableMapOf("directory" to directory, "limit" to size.toString())
        if (cursor == null) query["order"] = "desc" else query["cursor"] = cursor
        val page = obj("api/session", query = query)
        ApiPage(dataArray(page).objects().map { it.toSession() }, page.obj("cursor").str("next").takeIf(String::isNotBlank))
      }
      else -> ApiPage(emptyList())
    }
  }
  /** Home catalog: V2 is global (directory filters are exact); V1 queries each project/worktree. */
  suspend fun rootSessionsPage(directory: String? = null, cursor: String? = null, size: Int = 100): ApiPage<Session> = withContext(Dispatchers.IO) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val limit = (cursor?.toIntOrNull() ?: size).coerceAtMost(MAX_PAGE_ITEMS)
        val items = arr("session", directory, mapOf("limit" to limit.toString(), "roots" to "true")).objects().map { it.toSession() }.sortedWith(sessionActivityOrder)
        ApiPage(items.take(limit), (limit + size).toString().takeIf { items.size >= limit && limit < MAX_PAGE_ITEMS })
      }
      ServerProtocol.V2 -> {
        val query = mutableMapOf("limit" to size.toString(), "parentID" to "null")
        if (directory != null) query["directory"] = directory
        if (cursor == null) query["order"] = "desc" else query["cursor"] = cursor
        val page = obj("api/session", query = query)
        ApiPage(dataArray(page).objects().map { it.toSession() }, page.obj("cursor").str("next").takeIf(String::isNotBlank))
      }
      else -> ApiPage(emptyList())
    }
  }
  /** Bind the acknowledgement to the exact completed execution, never to wall-clock time. */
  suspend fun viewSession(session: Session, idle: Long) {
    if (idle <= 0) return
    val endpoint = capabilities().sessionView ?: return
    val path = endpoint.path.replace(Regex("\\{[^}]+\\}"), segment(session.id))
    request(endpoint.method, path, body = JSONObject().put("idle", idle))
  }
  suspend fun messagesPage(id: String, directory: String, cursor: String? = null, size: Int = 100): ApiPage<Message> = withContext(Dispatchers.IO) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val limit = (cursor?.toIntOrNull() ?: size).coerceAtMost(MAX_PAGE_ITEMS)
        val items = arr("session/${segment(id)}/message", directory, mapOf("limit" to limit.toString())).objects().map { it.toMessage() }
        ApiPage(items.takeLast(limit), (limit + size).toString().takeIf { items.size >= limit && limit < MAX_PAGE_ITEMS })
      }
      ServerProtocol.V2 -> {
        val query = mutableMapOf("limit" to size.toString())
        if (cursor == null) query["order"] = "desc" else query["cursor"] = cursor
        val page = obj("api/session/${segment(id)}/message", query = query)
        ApiPage(dataArray(page).objects().map { it.toMessage() }.asReversed(), page.obj("cursor").str("next").takeIf(String::isNotBlank))
      }
      else -> ApiPage(emptyList())
    }
  }
  suspend fun status(directory: String): Map<String, String> {
    return when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val json = obj("session/status", directory)
        json.keys().asSequence().associateWith { json.optJSONObject(it)?.str("type") ?: "idle" }
      }
      ServerProtocol.V2 -> dataObject(obj("api/session/active")).keys().asSequence().associateWith { "running" }
      ServerProtocol.UNKNOWN -> emptyMap()
    }
  }
  suspend fun messages(sessionId: String, directory: String): List<Message> = withContext(Dispatchers.IO) { when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session/${segment(sessionId)}/message", directory).objects().map { it.toMessage() }
    ServerProtocol.V2 -> dataObjects("api/session/${segment(sessionId)}/message", query = mapOf("order" to "asc"), dropAfterFirst = setOf("order")).map { it.toMessage() }
    ServerProtocol.UNKNOWN -> emptyList()
  }
  }
  suspend fun createSession(directory: String, title: String): Session = when (ensureProtocol()) {
    ServerProtocol.V1 -> jsonObject(request("POST", "session", directory, body = JSONObject().apply { if (title.isNotBlank()) put("title", title.trim()) })).toSession()
    ServerProtocol.V2 -> dataObject(requestObject("POST", "api/session", body = JSONObject().put("location", JSONObject().put("directory", directory)).apply {
      if (capabilities().titleOnCreate && title.isNotBlank()) put("title", title.trim())
    })).toSession()
    ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
  }
  suspend fun renameSession(session: Session, title: String) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("PATCH", "session/${segment(session.id)}", session.directory, body = JSONObject().put("title", title))
      ServerProtocol.V2 -> actionEndpoint(SessionAction.RENAME, session).let { request(it.method, it.path, body = JSONObject().put("title", title)) }
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun deleteSession(session: Session) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("DELETE", "session/${segment(session.id)}", session.directory)
      ServerProtocol.V2 -> actionEndpoint(SessionAction.DELETE, session).let { request(it.method, it.path) }
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun forkSession(session: Session): Session = when (ensureProtocol()) {
    ServerProtocol.V1 -> jsonObject(request("POST", "session/${segment(session.id)}/fork", session.directory)).toSession()
    ServerProtocol.V2 -> actionEndpoint(SessionAction.FORK, session).let { dataObject(requestObject(it.method, it.path, JSONObject())).toSession() }
    ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
  }
  suspend fun abort(session: Session) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "session/${segment(session.id)}/abort", session.directory)
      ServerProtocol.V2 -> request("POST", "api/session/${segment(session.id)}/interrupt")
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun share(session: Session): String {
    ensureProtocol()
    val endpoint = actionEndpoint(SessionAction.SHARE, session)
    val response = dataObject(jsonObject(request(endpoint.method, endpoint.path, session.directory)))
    return response.obj("share").str("url").ifBlank { response.str("share") }.ifBlank { response.str("url") }
  }
  suspend fun unshare(session: Session) {
    ensureProtocol()
    val endpoint = actionEndpoint(SessionAction.UNSHARE, session)
    request(endpoint.method, endpoint.path, session.directory)
  }
  suspend fun summarize(session: Session, model: ModelChoice?) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val selected = model ?: error("请先选择模型")
        request("POST", "session/${segment(session.id)}/summarize", session.directory,
          body = JSONObject().put("providerID", selected.providerId).put("modelID", selected.modelId))
      }
      ServerProtocol.V2 -> request("POST", "api/session/${segment(session.id)}/compact")
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun revert(session: Session, messageId: String) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "session/${segment(session.id)}/revert", session.directory, body = JSONObject().put("messageID", messageId))
      ServerProtocol.V2 -> request("POST", "api/session/${segment(session.id)}/revert/stage", body = JSONObject().put("messageID", messageId))
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun unrevert(session: Session) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "session/${segment(session.id)}/unrevert", session.directory)
      ServerProtocol.V2 -> actionEndpoint(SessionAction.UNREVERT, session).let { request(it.method, it.path) }
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun send(session: Session, text: String, agent: String?, model: ModelChoice?, references: List<FileReference> = emptyList()) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        references.forEach { ref -> parts.put(JSONObject().put("type", "file").put("mime", ref.mime)
          .put("filename", ref.path.substringAfterLast('/')).put("url", referenceUri(session, ref))) }
        val body = JSONObject().put("parts", parts)
        if (!agent.isNullOrBlank()) body.put("agent", agent)
        if (model != null) body.put("model", JSONObject().put("providerID", model.providerId).put("modelID", model.modelId))
        request("POST", "session/${segment(session.id)}/prompt_async", session.directory, body = body)
      }
      ServerProtocol.V2 -> {
        val contract = capabilities()
        check(references.isEmpty() || contract.fileReferences) { "此实例尚未确认文件引用协议，请移除附件或刷新连接" }
        if (!agent.isNullOrBlank()) request("POST", "api/session/${segment(session.id)}/agent", body = JSONObject().put("agent", agent))
        if (model != null) request("POST", "api/session/${segment(session.id)}/model", body = JSONObject().put("model", JSONObject().put("providerID", model.providerId).put("id", model.modelId)))
        val prompt = JSONObject().put("text", text)
        if (references.isNotEmpty()) prompt.put("files", JSONArray().apply { references.forEach { ref ->
          put(JSONObject().put(contract.fileUriField, referenceUri(session, ref)).put("mime", ref.mime).put("name", ref.path.substringAfterLast('/')))
        } })
        request("POST", "api/session/${segment(session.id)}/prompt", body = if (contract.promptEnvelope) JSONObject().put("prompt", prompt) else prompt)
      }
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  private fun referenceUri(session: Session, reference: FileReference): String {
    val path = if (reference.path.startsWith('/')) reference.path else session.directory.trimEnd('/') + "/" + reference.path
    return java.net.URI("file", "", path, null).toASCIIString()
  }
  suspend fun command(session: Session, name: String, arguments: String, agent: String?, model: ModelChoice?) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val body = JSONObject().put("command", name).put("arguments", arguments)
        if (!agent.isNullOrBlank()) body.put("agent", agent)
        if (model != null) body.put("model", "${model.providerId}/${model.modelId}")
        request("POST", "session/${segment(session.id)}/command", session.directory, body = body)
      }
      ServerProtocol.V2 -> send(session, "/$name ${arguments.trim()}".trim(), agent, model)
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun agents(directory: String): List<AgentChoice> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("agent", directory).objects().map { AgentChoice(it.str("name"), it.str("description")) }
    ServerProtocol.V2 -> dataArray(obj("api/agent", query = locationQuery(directory))).objects().filterNot { it.optBoolean("hidden") }.map { AgentChoice(it.str("id"), it.str("description")) }
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun commands(directory: String): List<CommandChoice> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("command", directory).objects().map { CommandChoice(it.str("name"), it.str("description")) }
    ServerProtocol.V2 -> dataArray(obj("api/command", query = locationQuery(directory))).objects().map { CommandChoice(it.str("name"), it.str("description")) }
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun models(directory: String): List<ModelChoice> {
    return when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val providers = obj("config/providers", directory).arr("providers").objects()
        providers.flatMap { provider ->
          val providerId = provider.str("id")
          val models = provider.obj("models")
          models.keys().asSequence().map { key ->
            val model = models.optJSONObject(key) ?: JSONObject()
            ModelChoice(providerId, key, model.str("name").ifBlank { key })
          }.toList()
        }
      }
      ServerProtocol.V2 -> dataArray(obj("api/model", query = locationQuery(directory))).objects().map {
        ModelChoice(it.str("providerID"), it.str("id"), it.str("name").ifBlank { it.str("id") })
      }
      ServerProtocol.UNKNOWN -> emptyList()
    }
  }
  suspend fun permissions(directory: String): List<PermissionRequest> = try {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> arr("permission", directory).objects().map { it.toPermission(directory) }
      ServerProtocol.V2 -> dataArray(obj("api/permission/request", query = locationQuery(directory))).objects().map { it.toPermission(directory) }
      ServerProtocol.UNKNOWN -> emptyList()
    }
  } catch (e: ApiException) { throw e }
  suspend fun questions(directory: String): List<QuestionRequest> = try {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> arr("question", directory).objects().map { it.toQuestion(directory) }
      ServerProtocol.V2 -> if (currentV2) dataArray(obj("api/form", query = locationQuery(directory))).objects().map { it.toForm(directory) }
        else dataArray(obj("api/question/request", query = locationQuery(directory))).objects().map { it.toQuestion(directory) }
      ServerProtocol.UNKNOWN -> emptyList()
    }
  } catch (e: ApiException) { throw e }
  suspend fun replyPermission(request: PermissionRequest, reply: String) {
    ensureProtocol()
    require(reply in setOf("once", "always", "reject")) { "未知权限决定" }
    require(reply != "always" || currentV2 && request.always.isNotEmpty()) { "无法确认保存规则范围，请仅允许一次" }
    when (ensureProtocol()) {
      ServerProtocol.V1 -> try {
        request("POST", "permission/${segment(request.id)}/reply", request.directory, body = JSONObject().put("reply", reply))
      } catch (e: ApiException) {
        if (e.status != 404) throw e
        request("POST", "session/${segment(request.sessionId)}/permissions/${segment(request.id)}", request.directory,
          body = JSONObject().put("response", reply).put("remember", reply == "always"))
      }
      ServerProtocol.V2 -> request("POST", "api/session/${segment(request.sessionId)}/permission/${segment(request.id)}/reply",
        body = JSONObject().put(if (currentV2) "decision" else "reply", reply))
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
    if (request.form) {
      this.request("POST", "api/session/${segment(request.sessionId)}/form/${segment(request.id)}/reply",
        body = JSONObject().put("answer", formAnswer(request, answers)))
      return
    }
    val array = JSONArray()
    answers.forEach { row -> array.put(JSONArray(row)) }
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "question/${segment(request.id)}/reply", request.directory, body = JSONObject().put("answers", array))
      ServerProtocol.V2 -> request("POST", "api/session/${segment(request.sessionId)}/question/${segment(request.id)}/reply",
        body = JSONObject().put("answers", array))
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun rejectQuestion(request: QuestionRequest) {
    if (request.form) { this.request("DELETE", "api/session/${segment(request.sessionId)}/form/${segment(request.id)}"); return }
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "question/${segment(request.id)}/reject", request.directory)
      ServerProtocol.V2 -> request("POST", "api/session/${segment(request.sessionId)}/question/${segment(request.id)}/reject")
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun savedPermissions(projectId: String): List<SavedPermission> {
    ensureProtocol(); check(capabilities().savedPermissions) { "此版本不支持已保存权限管理" }
    return dataArray(obj("api/permission/saved", query = mapOf("projectID" to projectId))).objects().map {
      SavedPermission(it.str("id"), it.str("projectID"), it.str("action"), it.str("resource"))
    }
  }
  suspend fun revokePermission(id: String) { ensureProtocol(); check(capabilities().savedPermissions); request("DELETE", "api/permission/saved/${segment(id)}") }
  suspend fun todos(session: Session): List<TodoItem> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session/${segment(session.id)}/todo", session.directory).objects().map { it.toTodo() }
    ServerProtocol.V2 -> if (capabilities().todos) dataArray(obj("api/session/${segment(session.id)}/todo")).objects().map { it.toTodo() } else unsupported("服务器不提供待办")
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun children(session: Session): List<Session> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session/${segment(session.id)}/children", session.directory).objects().map { it.toSession() }
    ServerProtocol.V2 -> dataObjects("api/session", query = mapOf("parentID" to session.id)).map { it.toSession() }
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun diff(session: Session): List<FileChange> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session/${segment(session.id)}/diff", session.directory).objects().map { it.toChange() }
    ServerProtocol.V2 -> if (capabilities().diff) dataArray(obj("api/session/${segment(session.id)}/diff")).objects().map { it.toChange() } else unsupported("服务器不提供改动记录")
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun files(directory: String, path: String): List<FileNode> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("file", directory, mapOf("path" to path)).objects().map { it.toNode() }
    ServerProtocol.V2 -> dataArray(obj("api/fs/list", query = locationQuery(directory) + (if (path.isNotBlank()) mapOf("path" to path) else emptyMap())))
      .objects().map { item -> FileNode(item.str("path"), item.str("type")) }
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun fileContent(directory: String, path: String): FileContent = when (ensureProtocol()) {
    ServerProtocol.V1 -> obj("file/content", directory, mapOf("path" to path)).toFileContent()
    ServerProtocol.V2 -> {
      val (bytes, contentType) = requestBytes("GET", "api/fs/read/${encodedPath(path)}", query = locationQuery(directory))
      val binary = !isTextFile(path, contentType)
      if (binary) FileContent("binary", Base64.encodeToString(bytes, Base64.NO_WRAP))
      else FileContent("text", bytes.toString(Charsets.UTF_8))
    }
    ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
  }
  suspend fun searchFiles(directory: String, query: String): List<String> {
    return when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val values = arr("find/file", directory, mapOf("query" to query))
        (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
      }
      ServerProtocol.V2 -> dataArray(obj("api/fs/find", query = locationQuery(directory) + mapOf("query" to query))).objects()
        .mapNotNull { it.str("path").takeIf(String::isNotBlank) }
      ServerProtocol.UNKNOWN -> emptyList()
    }
  }

  fun events(lastEventId: String? = null, onOpen: () -> Unit = {}): Flow<ServerEvent> = callbackFlow {
    val path = if (protocol == ServerProtocol.V1) "global/event" else "api/event"
    val builder = requestBuilder(path, null, emptyMap()).header("Accept", "text/event-stream")
    if (!lastEventId.isNullOrBlank()) builder.header("Last-Event-ID", lastEventId)
    val request = builder.build()
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

  private suspend fun requestObject(method: String, path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) { jsonObject(request(method, path, body = body)) }

  private fun unsupported(message: String): Nothing = throw ApiException(501, message)

  private fun isTextFile(path: String, contentType: String): Boolean {
    val mime = contentType.substringBefore(';').trim().lowercase()
    if (mime.startsWith("text/") || mime in TEXT_MIME_TYPES) return true
    val name = path.substringAfterLast('/').lowercase()
    if (name in TEXT_FILE_NAMES) return true
    val extension = name.substringAfterLast('.', "")
    return extension in TEXT_EXTENSIONS
  }

  private companion object {
    const val MAX_RESPONSE_CHARS = 8_000_000
    const val MAX_FILE_BYTES = 8_000_000L
    const val MAX_PAGES = 40
    const val MAX_PAGE_ITEMS = 5_000
    val TEXT_MIME_TYPES = setOf("application/json", "application/xml", "application/javascript", "application/x-javascript")
    val TEXT_FILE_NAMES = setOf("dockerfile", "makefile", "license", "readme", "changelog", ".gitignore", ".gitattributes", ".env")
    val TEXT_EXTENSIONS = setOf(
      "txt", "md", "markdown", "rst", "json", "jsonc", "yaml", "yml", "toml", "xml", "html", "htm", "css", "scss", "less",
      "js", "jsx", "ts", "tsx", "vue", "svelte", "kt", "kts", "java", "groovy", "gradle", "py", "rb", "go", "rs", "c", "cc",
      "cpp", "h", "hh", "hpp", "sh", "bash", "zsh", "sql", "swift", "php", "pl", "ini", "cfg", "conf", "properties",
      "env", "gitignore", "gitattributes", "bat", "cmd", "ps1", "tf", "hcl", "proto", "graphql", "lock", "svg", "tex", "diff", "patch"
    )
  }
}

internal fun String.toServerEvent(sseId: String): ServerEvent {
  val json = JSONObject(this)
  val payload = json.optJSONObject("payload")
  if (payload != null) {
    val event = payload.toString().toServerEvent(sseId)
    return event.copy(directory = json.str("directory").ifBlank { event.directory })
  }
  val type = json.str("type")
  val data = json.optJSONObject("data") ?: json.obj("properties")
  val properties = when (type) {
    "permission.v2.asked" -> JSONObject().apply {
      put("id", data.str("id")); put("sessionID", data.str("sessionID")); put("permission", data.str("action"))
      put("patterns", data.arr("resources")); put("always", data.arr("save")); put("metadata", data.obj("metadata"))
      val source = data.obj("source")
      if (source.str("type") == "tool") put("tool", JSONObject().put("messageID", source.str("messageID")).put("callID", source.str("id").ifBlank { source.str("callID") }))
    }
    else -> data
  }
  val normalizedType = when (type) {
    "permission.v2.asked" -> "permission.asked"
    "permission.v2.replied" -> "permission.replied"
    "question.v2.asked" -> "question.asked"
    "question.v2.replied" -> "question.replied"
    "question.v2.rejected" -> "question.rejected"
    else -> type
  }
  if (!properties.has("timestamp") && json.optLong("created") > 0) properties.put("timestamp", json.optLong("created"))
  val directory = json.obj("location").str("directory").ifBlank { json.str("directory") }
  return ServerEvent(json.str("id").ifBlank { sseId }, directory, normalizedType, properties)
}
