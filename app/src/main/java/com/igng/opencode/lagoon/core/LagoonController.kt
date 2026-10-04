package com.igng.opencode.lagoon.core

import android.content.Context
import com.igng.opencode.lagoon.system.TaskNotifications
import com.igng.opencode.lagoon.system.TaskMonitorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal fun operationKey(server: String?, session: String?, project: String?, action: String): String {
  val target = when {
    action.startsWith("permission:") || action.startsWith("question:") || action.startsWith("revoke:") -> action.substringAfter(':')
    action == "create" || action == "sessions-more" -> project
    else -> session
  }
  return "$server:$target:$action"
}

data class LagoonState(
  val profiles: List<ServerProfile> = emptyList(), val serverId: String? = null, val version: String = "", val protocol: ServerProtocol = ServerProtocol.UNKNOWN,
  val connected: Boolean = false, val cached: Boolean = false, val loading: Boolean = false, val error: String? = null,
  /** True when the last catalog refresh could not read every project; some values are last-known. */
  val degraded: Boolean = false,
  val staleDirectories: Set<String> = emptySet(), val streamConnected: Boolean = false,
  /** Non-error feedback (e.g. a share link); kept separate so it is not rendered as a failure. */
  val message: String? = null,
  val projects: List<Project> = emptyList(), val projectId: String? = null,
  val sessions: List<Session> = emptyList(), val sessionId: String? = null,
  val knownParents: Map<String, String> = emptyMap(),
  val messages: List<Message> = emptyList(), val tasks: Map<String, TaskState> = emptyMap(),
  val permissions: List<PermissionRequest> = emptyList(), val questions: List<QuestionRequest> = emptyList(),
  val todos: List<TodoItem> = emptyList(), val children: List<Session> = emptyList(), val changes: List<FileChange> = emptyList(),
  val agents: List<AgentChoice> = emptyList(), val models: List<ModelChoice> = emptyList(), val commands: List<CommandChoice> = emptyList(),
  val agent: String? = null, val model: ModelChoice? = null,
  val files: List<FileNode> = emptyList(), val filePath: String = ".", val fileText: String? = null, val fileBinary: Boolean = false,
  val searchResults: List<String> = emptyList(),
  val supportsSavedPermissions: Boolean = false, val savedPermissions: List<SavedPermission>? = null,
  val capabilities: ApiCapabilities = ApiCapabilities(),
  val previews: Map<String, SessionPreview> = emptyMap(), val resources: Map<String, ResourceStatus> = emptyMap(),
  val pendingOperations: Set<String> = emptySet(), val sharedUrl: String? = null,
  val agentChanged: Boolean = false, val modelChanged: Boolean = false,
  val draftSessions: Set<String> = emptySet(), val draft: String = "", val references: List<FileReference> = emptyList(),
  val cacheComplete: Boolean = true, val sessionCursors: Map<String, String> = emptyMap(), val messagesCursor: String? = null,
  val catalogComplete: Boolean = true, val loadedOlderMessages: Boolean = false,
  /** 已被用户查看过、不再计入“未读已完成/失败”的会话 id（本会话内有效）。 */
  val acknowledged: Set<String> = emptySet(),
  /** 全服务器范围的任务计数，由 [tasks] 与 [acknowledged] 派生，供灵动岛与系统通知复用。 */
  val summary: TaskSummary = TaskSummary.EMPTY,
  /** 存在未读已完成/失败时，灵动岛点击应跳转的会话 id。 */
  val summaryTargetId: String? = null
) {
  val server: ServerProfile? get() = profiles.firstOrNull { it.id == serverId }
  val project: Project? get() = projects.firstOrNull { it.id == projectId }
  val session: Session? get() = sessions.firstOrNull { it.id == sessionId }
  val executionDirectory: String? get() = session?.directory ?: project?.directory
  fun pending(action: String): Boolean = pendingOperations.contains(operationKey(serverId, sessionId, projectId, action))
  fun resource(name: String): ResourceStatus = resources[name] ?: ResourceStatus()
  fun title(session: Session): String = session.displayTitle(previews[session.id]?.text.orEmpty())
  val parents: Map<String, String> get() = knownParents + sessions.mapNotNull { it.parentId?.let { parent -> it.id to parent } }.toMap()
  val rootTasks: Map<String, TaskState> get() = TaskSummary.aggregate(tasks, parents)
}

class LagoonController private constructor(private val appContext: Context) {
  companion object {
    private val CATALOG_REFRESH_EVENTS = setOf(
      "session.created", "session.updated", "session.deleted", "session.idle", "session.error",
      "permission.asked", "question.asked", "permission.replied", "permission.rejected",
      "question.replied", "question.rejected"
    )
    private val TERMINAL_PHASES = setOf(TaskPhase.COMPLETED, TaskPhase.FAILED)
    @Volatile private var instance: LagoonController? = null
    fun get(context: Context): LagoonController = instance ?: synchronized(this) {
      instance ?: LagoonController(context.applicationContext).also { instance = it }
    }
    /** Control-plane reconciliation cadence while the SSE stream is up. */
    private const val RECONCILE_INTERVAL_MILLIS = 45_000L
  }
  /**
   * Recomputes the server-wide island summary. A session's unread terminal state keeps counting until
   * its terminal transcript is successfully fetched while visible, so “已完成/失败” means “未读”。
   */
  private fun withSummary(state: LagoonState): LagoonState {
    state.serverId?.let { server -> state.tasks.values.forEach { store.rememberTask(server, it, state.parents[it.sessionId]) } }
    val titles = state.sessions.associate { it.id to state.title(it) }
    return state.copy(
    summary = TaskSummary.of(state.tasks, state.acknowledged, state.parents, titles),
    // Prefer a session that needs a reply; otherwise jump to the first unread terminal result.
    summaryTargetId = state.tasks.values.firstOrNull { it.phase in TaskState.WAITING_PHASES }?.sessionId
      ?: state.tasks.values.firstOrNull { it.phase in TERMINAL_PHASES && it.sessionId !in state.acknowledged }?.sessionId
  )
  }
  private val store = ServerStore(appContext)
  private val cache = OfflineCache(appContext)
  private val notifications = TaskNotifications(appContext)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  // Writing operations that must survive the UI leaving the foreground (permission replies, aborts)
  // run here rather than in a composer-scoped coroutine.
  private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val mutable = MutableStateFlow(LagoonState(profiles = store.profiles(), serverId = store.selectedId()))
  val state: StateFlow<LagoonState> = mutable
  private var api: OpenCodeApi? = null
  private var stream: Job? = null
  private var generation = 0
  private var refresh: Job? = null
  private var eventRefresh: Job? = null
  private var messageRefresh: Job? = null
  private var lastEventId = ""
  private val sendMutex = Mutex()
  private val referenceMutex = Mutex()
  private var previewHydration: Job? = null
  private var searchSequence = 0L
  private var fileSequence = 0L
  private var selectionRevision = 0L
  private var catalogSequence = 0L
  private var messageSequence = 0L
  private var reconcile: Job? = null
  private var lastSummary: Triple<ServerProfile?, TaskSummary, String?>? = null
  private var visibleConversation: Pair<String, String>? = null
  private val seenEvents = linkedSetOf<String>()
  private val eventTimestamps = mutableMapOf<String, Long>()
  private val observedTerminal = mutableMapOf<String, Long>()
  private val draftWrites = mutableMapOf<String, Job>()

  init {
    // Single publisher for the server-wide island summary, so the system notification never drifts
    // from the in-app island: both read the same derived `summary` on every state emission.
    scope.launch { state.collect { state ->
      val signature = Triple(state.server, state.summary, state.summaryTargetId)
      if (signature != lastSummary) { lastSummary = signature; publishSummary(state) }
    } }
    if (mutable.value.profiles.any { it.id == mutable.value.serverId && it.autoConnect }) connect(mutable.value.serverId!!)
  }
  private fun publishSummary(state: LagoonState) {
    val profile = state.server ?: return
    // showSummary cancels the notification itself when the summary is empty.
    notifications.showSummary(profile, state.summary, state.summaryTargetId)
  }
  fun credentials(serverId: String): ServerCredentials = store.credentials(serverId)
  fun deviceId(): String = store.deviceId()
  suspend fun testServer(profile: ServerProfile, credentials: ServerCredentials): String {
    val client = OpenCodeApi(profile, credentials)
    val version = client.health()
    client.projects()
    return version
  }
  fun clearError() = mutable.update { it.copy(error = null) }
  fun clearMessage() = mutable.update { it.copy(message = null) }

  fun saveServer(profile: ServerProfile, password: String?, cookie: String? = null, credentialUsername: String? = null, connect: Boolean = true) {
    store.save(profile, password, cookie, credentialUsername)
    mutable.update { it.copy(profiles = store.profiles()) }
    if (connect) connect(profile.id)
  }
  /** 持久化待合作灵动岛通道开关（荣耀 / OPPO 流体云），不触发重连。 */
  fun setIslandVendor(profile: ServerProfile) {
    store.updateIslandVendor(profile)
    mutable.update { it.copy(profiles = store.profiles()) }
  }
  fun deleteServer(id: String) {
    if (mutable.value.serverId == id) {
      stream?.cancel()
      refresh?.cancel()
      eventRefresh?.cancel()
      messageRefresh?.cancel()
      reconcile?.cancel()
      generation += 1
      api = null
      lastEventId = ""
    }
    store.delete(id)
    cache.delete(id)

    if (mutable.value.serverId == id) {
      mutable.update { LagoonState(profiles = store.profiles(), serverId = store.profiles().firstOrNull()?.id) }
      mutable.value.serverId?.let(::connect)
    } else mutable.update { it.copy(profiles = store.profiles()) }
  }
  fun connect(id: String) {
    val profile = store.profiles().firstOrNull { it.id == id } ?: return
    val leaving = state.value.serverId?.takeIf { it != id && state.value.tasks.values.any { task -> task.active } }
    stream?.cancel()
    refresh?.cancel()
    eventRefresh?.cancel()
    messageRefresh?.cancel()
    reconcile?.cancel()
    generation += 1
    val token = generation
    lastEventId = ""
    api = OpenCodeApi(profile, store.credentials(id))
    val rememberedProject = store.selectedProject(id)
    val rememberedSession = store.selectedSession(id)
    seenEvents.clear(); eventTimestamps.clear(); observedTerminal.clear(); visibleConversation = null
    val configuration = rememberedSession?.let { store.configuration(id, it) } ?: SessionConfiguration()
    store.select(id, rememberedProject, rememberedSession)
    mutable.update { LagoonState(profiles = store.profiles(), serverId = id, loading = true, projectId = rememberedProject, sessionId = rememberedSession,
      agent = configuration.agent, model = configuration.model, agentChanged = configuration.agentChanged, modelChanged = configuration.modelChanged,
      draft = rememberedSession?.let { store.draft(id, it) }.orEmpty(), references = rememberedSession?.let { store.references(id, it) }.orEmpty(),
      tasks = store.taskStates(id), knownParents = store.taskParents(id), acknowledged = store.acknowledgedTasks(id),
      message = if (leaving != null) "已结束上一服务器的本地监控；远端任务继续运行。" else null) }
    scope.launch {
      try {
        loadAll(token)
        if (token == generation) {
          startStream(token)
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (token != generation) return@launch
        showOffline(id, token, error.message ?: "连接失败")
        // A refresh while offline must be able to recover: retry the connection with backoff instead
        // of leaving the user on a stale snapshot with no path back to a live stream (A10).
        scheduleReconnect(id, token)
      }
    }
  }
  private fun scheduleReconnect(id: String, token: Int, attempt: Int = 0) {
    if (token != generation || state.value.serverId != id) return
    val delayMillis = (2_000L shl attempt.coerceAtMost(4)).coerceAtMost(60_000L)
    scope.launch {
      delay(delayMillis)
      if (token == generation && state.value.serverId == id && !state.value.connected) reconnect(id, token, attempt)
    }
  }
  private suspend fun reconnect(id: String, token: Int, attempt: Int) {
    val profile = store.profiles().firstOrNull { it.id == id } ?: return
    api = OpenCodeApi(profile, store.credentials(id))
    try {
      loadAll(token)
      if (token != generation) return
      startStream(token)
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      if (token == generation) showOffline(id, token, "连接失败，正在重试")
      scheduleReconnect(id, token, attempt + 1)
    }
  }
  private suspend fun showOffline(id: String, token: Int, reason: String) {
    val snapshot = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.catalog(id) }
    if (token != generation || state.value.serverId != id) return
    if (snapshot == null) mutable.update { it.copy(loading = false, connected = false, error = reason) }
    else {
      val (projects, sessions) = snapshot
      val sessionId = store.selectedSession()?.takeIf { selected -> sessions.any { it.id == selected } }
      mutable.update { it.copy(loading = false, connected = false, cached = true, error = "离线缓存：$reason",
        projects = projects, sessions = sessions, projectId = store.selectedProject() ?: projects.firstOrNull()?.id,
        sessionId = sessionId, messages = emptyList(), catalogComplete = cache.catalogComplete(id)) }
      if (sessionId != null) {
        val messages = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.messages(id, sessionId) }
        if (token == generation && state.value.serverId == id && state.value.sessionId == sessionId) mutable.update { it.copy(messages = messages, cacheComplete = cache.messagesComplete(id, sessionId), resources = mapOf("messages" to ResourceStatus(ResourceState.STALE, "离线缓存"))) }
      }
    }
  }
  private suspend fun loadAll(token: Int, controlOnly: Boolean = false) {
    val client = api ?: return
    val requestSequence = ++catalogSequence
    val version = client.health()
    val capabilities = client.discoverCapabilities()
    val serverId = state.value.serverId ?: return
    val discovered = if (controlOnly && state.value.projects.isNotEmpty()) state.value.projects else client.projects()
    val known = store.knownDirectories(serverId).map { directory ->
      state.value.projects.firstOrNull { normalizedDirectory(it.directory) == normalizedDirectory(directory) }
        ?: Project("directory:$directory", directory, directory.substringAfterLast('/').ifBlank { directory })
    }
    val projects = (discovered + known).distinctBy { normalizedDirectory(it.directory) }
    // Sessions, statuses, permissions and questions are independent per project; fetch them in
    // parallel so a multi-project server resolves in roughly one round trip instead of 4*N.
    //
    // Failures are tracked per resource rather than converted to an empty result: a failed status,
    // permission or question read must not be mistaken for an authoritative "nothing to see", which
    // used to turn a running task into COMPLETED and silently clear pending approvals (A05).
    val sessionResults = coroutineScope {
      projects.map { project -> async { project to attempt {
        if (controlOnly) state.value.sessions.filter { resolveSessionProject(it, projects)?.id == project.id }
        else client.sessionsPage(project.directory).let { page ->
          if (token == generation) mutable.update { it.copy(sessionCursors = if (page.next == null) it.sessionCursors - project.directory else it.sessionCursors + (project.directory to page.next)) }
          page.items
        }
      } } }.awaitAll()
    }
    if (sessionResults.isNotEmpty() && sessionResults.all { it.second.isFailure }) throw sessionResults.first().second.exceptionOrNull()!!
    val failedSessionDirs = sessionResults.filter { it.second.isFailure }.map { it.first.directory }.toSet()
    val sessions = sessionResults.flatMap { it.second.getOrDefault(emptyList()) }.distinctBy { it.id }.sortedByDescending { it.updated }
    val statusResults = coroutineScope {
      projects.map { project -> async { project.directory to attempt { client.status(project.directory) } } }.awaitAll()
    }
    val failedStatusDirs = statusResults.filter { it.second.isFailure }.map { it.first }.toSet()
    val statuses = statusResults.mapNotNull { it.second.getOrNull() }.flatMap { it.entries }.associate { it.key to it.value }
    val permissionResults = coroutineScope {
      projects.map { project -> async { project.directory to attempt { client.permissions(project.directory) } } }.awaitAll()
    }
    val failedPermissionDirs = permissionResults.filter { it.second.isFailure }.map { it.first }.toSet()
    val permissions = permissionResults.mapNotNull { it.second.getOrNull() }.flatten().distinctBy { it.id }
    val questionResults = coroutineScope {
      projects.map { project -> async { project.directory to attempt { client.questions(project.directory) } } }.awaitAll()
    }
    val failedQuestionDirs = questionResults.filter { it.second.isFailure }.map { it.first }.toSet()
    val questions = questionResults.mapNotNull { it.second.getOrNull() }.flatten().distinctBy { it.id }
    if (token != generation || requestSequence != catalogSequence) return
    // Active/approval sessions may be older than the first page. Keep their identity and parent chain.
    val missingIds = (statuses.keys + permissions.map { it.sessionId } + questions.map { it.sessionId } + listOfNotNull(state.value.sessionId, store.selectedSession(serverId))).filter { id -> sessions.none { it.id == id } && state.value.sessions.none { it.id == id } }
    val extraSessions = mutableListOf<Session>()
    missingIds.take(100).chunked(4).forEach { batch ->
      extraSessions += coroutineScope { batch.map { id -> async {
        val directory = permissions.firstOrNull { it.sessionId == id }?.directory ?: questions.firstOrNull { it.sessionId == id }?.directory
        attempt { findSession(client, id, listOfNotNull(directory) + projects.map { it.directory }) }.getOrNull()
      } }.awaitAll().filterNotNull() }
      if (token != generation || requestSequence != catalogSequence) return
    }
    store.rememberParents(serverId, sessions + extraSessions)
    val degraded = failedSessionDirs + failedStatusDirs + failedPermissionDirs + failedQuestionDirs
    if (degraded.isNotEmpty()) Diagnostics.warn("LagoonController", "部分数据读取失败，保留上次可信状态：$degraded")

    val storedTasks = store.taskStates(serverId)
    mutable.update { previous ->
      // A session whose project failed to report status keeps its previous phase; only an actual
      // authoritative status (or its absence from a successful response) may reduce it.
      val nextSessions = (sessions + extraSessions + previous.sessions.filter { it.directory in failedSessionDirs || it.id in statuses || it.id == previous.sessionId }).distinctBy { it.id }.sortedByDescending { it.updated }
      val degradedDirs = failedStatusDirs + failedSessionDirs
      val previousTasks = previous.tasks + storedTasks.filter { (id, task) -> previous.tasks[id]?.let { old -> task.since > old.since || (task.finishedAt ?: 0) > (old.finishedAt ?: 0) } ?: true }
      val states = previousTasks.filterValues { it.phase in TERMINAL_PHASES || it.phase == TaskPhase.ABORTED }.toMutableMap()
      nextSessions.forEach { session ->
        val authoritative = session.directory !in degradedDirs
        val next = if (!authoritative) previousTasks[session.id]
        else TaskReducer.status(session.id, statuses[session.id] ?: "idle", previousTasks[session.id])
        if (next != null) states[session.id] = next
      }
      // A fully successful read is authoritative; otherwise merge fetched entries with the previous
      // ones for the failed directories so a pending approval is never silently dropped.
      val keptPermissions = previous.permissions.filter { it.directory in failedPermissionDirs }
      val nextPermissions = (permissions + keptPermissions).distinctBy { it.id }
      val keptQuestions = previous.questions.filter { it.directory in failedQuestionDirs }
      val nextQuestions = (questions + keptQuestions).distinctBy { it.id }
      nextPermissions.forEach { states[it.sessionId] = TaskState(it.sessionId, TaskPhase.WAITING_PERMISSION, "等待权限确认", previousTasks[it.sessionId]?.since ?: System.currentTimeMillis()) }
      nextQuestions.forEach { states[it.sessionId] = TaskState(it.sessionId, TaskPhase.WAITING_QUESTION, "等待你的回答", previousTasks[it.sessionId]?.since ?: System.currentTimeMillis()) }
      withSummary(previous.copy(version = version, protocol = client.detectedProtocol(), capabilities = capabilities, supportsSavedPermissions = capabilities.savedPermissions,
        draftSessions = store.draftSessionIds(serverId), previews = nextSessions.associate { session -> session.id to (previous.previews[session.id] ?: store.sessionPreview(serverId, session.id)) }, connected = true, cached = previous.cached && previous.sessionId != null, loading = false,
        error = null, degraded = degraded.isNotEmpty(), staleDirectories = degraded,
        projects = projects, sessions = nextSessions, knownParents = store.taskParents(serverId), tasks = states, acknowledged = store.acknowledgedTasks(serverId), catalogComplete = previous.sessionCursors.isEmpty(),
        permissions = nextPermissions, questions = nextQuestions,
        projectId = (previous.projectId ?: store.selectedProject(serverId))?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id,
        sessionId = (previous.sessionId ?: store.selectedSession(serverId))?.takeIf { id -> nextSessions.any { it.id == id } }))
    }
    val current = mutable.value
    current.serverId?.let { cache.saveCatalog(it, current.projects, current.sessions, complete = current.catalogComplete) }
    if (controlOnly) {
      current.session?.let { loadSession(it, token = token, ancillary = false) }
      return
    }
    current.project?.let { project -> loadChoices(project.directory, token) }
    current.session?.let { loadSession(it, token = token) }
    hydratePreviews(serverId, current.sessions, client, token)
  }
  private fun hydratePreviews(server: String, sessions: List<Session>, client: OpenCodeApi, token: Int) {
    previewHydration?.cancel()
    previewHydration = scope.launch {
      sessions.filter { it.parentId == null && state.value.previews[it.id]?.content != SessionContent.CONTENT && state.value.previews[it.id]?.content != SessionContent.EMPTY }.chunked(4).forEach { batch ->
        if (token != generation || state.value.serverId != server) return@launch
        coroutineScope { batch.map { session -> async {
          val page = attempt { client.messagesPage(session.id, session.directory, size = 20) }.getOrNull() ?: return@async
          val preview = page.items.sessionPreview().let { if (page.next != null && it.content == SessionContent.EMPTY) SessionPreview() else it }
          if (token == generation && state.value.serverId == server) {
            kotlinx.coroutines.withContext(Dispatchers.IO) { store.rememberPreview(server, session.id, preview) }
            mutable.update { it.copy(previews = it.previews + (session.id to preview)) }
          }
        } }.awaitAll() }
      }
    }
  }
  fun loadMoreSessions() = act("sessions-more") { op ->
    op.snapshot.sessionCursors.forEach { (directory, cursor) ->
      val page = op.client.sessionsPage(directory, cursor)
      check(page.next != cursor) { "服务器返回了重复分页游标" }
      store.rememberParents(op.serverId, page.items)
      op.commitConnection { it.copy(knownParents = store.taskParents(op.serverId), sessions = (it.sessions + page.items).distinctBy { s -> s.id }.sortedByDescending { s -> s.updated },
        sessionCursors = if (page.next == null) it.sessionCursors - directory else it.sessionCursors + (directory to page.next),
        catalogComplete = page.next == null && it.sessionCursors.size <= 1) }
      hydratePreviews(op.serverId, page.items, op.client, op.token)
    }
  }
  fun loadOlderMessages() = withSession("messages-more") { op, client, session ->
    val cursor = op.snapshot.messagesCursor ?: return@withSession
    val page = client.messagesPage(session.id, session.directory, cursor)
    check(page.next != cursor) { "服务器返回了重复分页游标" }
    op.commit { it.copy(messages = (page.items + it.messages).distinctBy { m -> m.id }, messagesCursor = page.next, loadedOlderMessages = true) }
  }
  fun reload() {
    val token = generation
    refresh?.cancel()
    refresh = scope.launch {
      try {
        loadAll(token)
        // A refresh after an offline start must also (re)establish the live event stream, otherwise
        // the UI shows connected=true with no realtime updates (A10).
        if (token == generation && stream?.isActive != true) startStream(token)
      } catch (error: Exception) {
        if (token == generation) mutable.update { state -> state.copy(error = error.message) }
      }
    }
  }
  private fun startStream(token: Int) {
    stream?.cancel()
    stream = scope.launch {
      var retry = 1_000L
      while (isActive && token == generation) {
          api?.events(lastEventId, onOpen = { scope.launch {
            if (token == generation) mutable.update { it.copy(streamConnected = true) }
          } })?.catch { cause ->
            if (token == generation) mutable.update { it.copy(streamConnected = false,
              error = "实时连接断开，正在重连：${cause.message}") }
          }?.collect { event ->
            if (token == generation) {
              if (event.id.isNotBlank()) {
                lastEventId = event.id
                if (!seenEvents.add(event.id)) return@collect
                while (seenEvents.size > 512) seenEvents.remove(seenEvents.first())
              }
              handleEvent(event)
            }
            // An event arrived: the stream is healthy, so restore the short reconnect delay. A stream
            // that fails without ever delivering keeps doubling its backoff.
            retry = 1_000L
        }
        if (isActive && token == generation) {
          mutable.update { it.copy(streamConnected = false, message = "实时连接恢复中") }
          delay(retry)
          retry = (retry * 2).coerceAtMost(30_000L)
          attempt { loadAll(token) }.onFailure { error -> if (token == generation) mutable.update { it.copy(connected = false, degraded = true, error = "重新同步失败：${error.message.orEmpty()}") } }
        }
      }
    }
    // Standing reconciliation: while the stream is nominally up, periodically re-read the control
    // plane (catalog/status/permissions/questions). This converges state even if the server drops an
    // event without the client seeing a stream failure, so a missed permission or terminal state is
    // repaired rather than waiting for the user to notice (A04/A10).
    reconcile?.cancel()
    reconcile = scope.launch {
      while (isActive && token == generation) {
        delay(RECONCILE_INTERVAL_MILLIS)
        // Skip while a manual refresh is running or while the stream is already down: the stream
        // loop performs a full loadAll on every reconnect, so reconciling there would only fight the
        // disconnected state.
        if (token != generation || refresh?.isActive == true || !state.value.connected) continue
        attempt { loadAll(token, controlOnly = true) }.onFailure { error -> if (token == generation) mutable.update { it.copy(degraded = true, error = "状态同步失败：${error.message}") } }
      }
    }
  }
  private fun handleEvent(event: ServerEvent) {
    val props = event.properties
    val sessionId = props.str("sessionID").ifBlank { props.obj("part").str("sessionID") }.ifBlank { props.obj("info").str("sessionID") }.ifBlank { props.obj("info").str("id").takeIf { event.type.startsWith("session.") }.orEmpty() }
    val directory = event.directory.ifBlank { mutable.value.sessions.firstOrNull { it.id == sessionId }?.directory.orEmpty() }
    if (sessionId.isNotBlank()) {
      val before = mutable.value.tasks[sessionId]
      val timestamp = props.optLong("timestamp")
      val late = timestamp > 0 && timestamp < (eventTimestamps[sessionId] ?: 0)
      if (late) {
        if (sessionId == state.value.sessionId && messageRefresh?.isActive != true) {
          val token = generation; messageRefresh = scope.launch { delay(350); loadAll(token, controlOnly = true) }
        }
        return
      }
      if (timestamp > 0) eventTimestamps[sessionId] = timestamp
      var after = if (late) before else TaskReducer.event(sessionId, event.type, props, before)
      if (after != null) {
        val resolved = props.str("requestID").ifBlank { props.str("id") }
        val pendingPermission = mutable.value.permissions.any { it.sessionId == sessionId && !(event.type in setOf("permission.replied", "permission.rejected") && it.id == resolved) }
        val pendingQuestion = mutable.value.questions.any { it.sessionId == sessionId && !(event.type in setOf("question.replied", "question.rejected") && it.id == resolved) }
        val pending = pendingPermission || pendingQuestion
        if (pending && after.phase !in setOf(TaskPhase.FAILED, TaskPhase.ABORTED)) after = TaskState(sessionId,
          if (pendingPermission) TaskPhase.WAITING_PERMISSION else TaskPhase.WAITING_QUESTION,
          if (pendingPermission) "等待权限确认" else "等待你的回答", before?.since ?: System.currentTimeMillis())
        val next = after
        if (!(next.phase == TaskPhase.COMPLETED && pending)) {
          val enteringTerminal = next.phase in TERMINAL_PHASES && before?.phase !in TERMINAL_PHASES
          mutable.update { current ->
            // A fresh completion/failure is unread again even if this session was viewed before.
            val acknowledged = if (enteringTerminal) current.acknowledged - sessionId else current.acknowledged
            if (enteringTerminal) current.serverId?.let { store.unacknowledgeTask(it, sessionId) }
            withSummary(current.copy(tasks = current.tasks + (sessionId to next), acknowledged = acknowledged))
          }
          if (enteringTerminal && sessionId == state.value.sessionId && messageRefresh?.isActive != true) {
            state.value.session?.let { selected -> val token = generation; messageRefresh = scope.launch { delay(250); loadSession(selected, ancillary = false, token = token) } }
          }
          // A READY transcript from the previous run cannot acknowledge this terminal event.
          val session = mutable.value.sessions.firstOrNull { it.id == sessionId }
          val profile = mutable.value.server
          if (session != null && profile?.notifications == true && next.phase != before?.phase && sessionId !in mutable.value.acknowledged) notifications.show(profile, session, next,
            mutable.value.permissions.firstOrNull { it.sessionId == sessionId })
        }
      }
    }
    when (event.type) {
      "permission.asked" -> {
        val request = props.toPermission(directory)
        mutable.update { it.copy(permissions = (it.permissions.filterNot { old -> old.id == request.id } + request)) }
        notifyAttention(request.sessionId)
      }
      "question.asked" -> {
        val request = props.toQuestion(directory)
        mutable.update { it.copy(questions = (it.questions.filterNot { old -> old.id == request.id } + request)) }
        notifyAttention(request.sessionId)
      }
      "permission.replied", "permission.rejected" -> {
        val requestId = props.str("requestID").ifBlank { props.str("id") }
        mutable.update { it.copy(permissions = it.permissions.filterNot { old -> old.id == requestId }) }
      }
      "question.replied", "question.rejected" -> {
        val requestId = props.str("requestID").ifBlank { props.str("id") }
        mutable.update { it.copy(questions = it.questions.filterNot { old -> old.id == requestId }) }
      }
    }
    if (event.type == "session.created" || event.type == "session.updated") {
      val info = props.obj("info")
      if (info.str("id").isNotBlank()) {
        val previous = state.value.sessions.firstOrNull { it.id == info.str("id") }
        val parsed = info.toSession()
        val session = parsed.copy(directory = parsed.directory.ifBlank { previous?.directory.orEmpty() },
          title = if (info.has("title")) parsed.title else previous?.title.orEmpty(),
          parentId = if (info.has("parentID")) parsed.parentId else previous?.parentId,
          projectId = parsed.projectId ?: previous?.projectId, updated = parsed.updated.takeIf { it > 0 } ?: previous?.updated ?: 0)
        mutable.update { it.copy(sessions = (listOf(session) + it.sessions).distinctBy { item -> item.id }.sortedByDescending { item -> item.updated }) }
      }
    }
    if (event.type in setOf("session.next.agent.switched", "session.next.model.switched")) {
      mutable.update { current ->
        if (sessionId != current.sessionId) current else current.copy(
          agent = if (event.type.endsWith("agent.switched") && !current.agentChanged) props.str("agent").ifBlank { current.agent } else current.agent,
          model = if (event.type.endsWith("model.switched") && !current.modelChanged) props.obj("model").toModelChoice() ?: current.model else current.model)
      }
    }
    if (event.type in CATALOG_REFRESH_EVENTS) {
      eventRefresh?.cancel()
      eventRefresh = scope.launch { delay(350); if (generation > 0) reload() }
    } else if (sessionId == mutable.value.sessionId) {
      val next = TranscriptProjection.apply(mutable.value.messages, event, mutable.value.protocol)
      if (next != null) mutable.update { it.copy(messages = next, resources = it.resources + ("messages" to ResourceStatus(ResourceState.READY))) }
      else if (messageRefresh?.isActive != true) {
        val session = mutable.value.session ?: return
        val token = generation
        messageRefresh = scope.launch { delay(350); if (event.type.startsWith("message.") || event.type.startsWith("session.next.")) loadSession(session, ancillary = false, token = token) else loadAll(token, controlOnly = true) }
      }
    }
  }

  private fun notifyAttention(sessionId: String) {
    val current = mutable.value
    val profile = current.server ?: return
    val session = current.sessions.firstOrNull { it.id == sessionId } ?: return
    if (!profile.notifications) return
    current.tasks[sessionId]?.let { notifications.show(profile, session, it, current.permissions.firstOrNull { p -> p.sessionId == sessionId }) }
  }
  fun selectProject(id: String) {
    val project = mutable.value.projects.firstOrNull { it.id == id } ?: return
    selectionRevision += 1
    visibleConversation = null
    mutable.update { it.copy(projectId = id, sessionId = null, messages = emptyList(), agent = null, model = null,
      agentChanged = false, modelChanged = false, draft = "", references = emptyList(), savedPermissions = null, sharedUrl = null, resources = emptyMap(),
      files = emptyList(), searchResults = emptyList(), fileText = null, fileBinary = false) }
    store.rememberLocation(id, null)
    val token = generation
    scope.launch { loadChoices(project.directory, token) }
  }
  private suspend fun loadChoices(directory: String, token: Int = generation) {
    val client = api ?: return
    // Agents, models and commands are independent; one parallel round instead of three.
    val revision = selectionRevision
    val (agents, models, commands) = coroutineScope {
      val agentsTask = async { attempt { client.agents(directory) } }
      val modelsTask = async { attempt { client.models(directory) } }
      val commandsTask = async { attempt { client.commands(directory) } }
      Triple(agentsTask.await(), modelsTask.await(), commandsTask.await())
    }
    if (token == generation && revision == selectionRevision && mutable.value.executionDirectory == directory) {
      mutable.update { current -> current.copy(agents = agents.getOrDefault(current.agents), models = models.getOrDefault(current.models), commands = commands.getOrDefault(current.commands),
        resources = current.resources + mapOf("agents" to agents.resourceStatus(), "models" to models.resourceStatus(), "commands" to commands.resourceStatus())) }
    }
  }
  /** Resolve a notification/child target independently of the current catalog page. */
  fun resolveSession(id: String, onResolved: (List<String>) -> Unit): Job = act("open:$id") { op ->
    val known = op.snapshot.sessions.associateBy { it.id }.toMutableMap()
    val seen = linkedSetOf<String>(); val lineage = mutableListOf<String>()
    var next: String? = id
    var directories = listOfNotNull(op.snapshot.executionDirectory) + op.snapshot.projects.map { it.directory }
    while (next != null && seen.add(next) && lineage.size < 32) {
      val target = next
      val session = known[target] ?: findSession(op.client, target, directories)
      if (session == null) {
        check(lineage.isNotEmpty()) { "未找到这个会话，请刷新后重试" }
        break
      }
      known[target] = session; lineage += target
      directories = listOf(session.directory) + directories
      next = session.parentId
    }
    if (!op.isCurrent(this)) return@act
    store.rememberParents(op.serverId, known.values.toList())
    op.commit { withSummary(it.copy(sessions = known.values.sortedByDescending { session -> session.updated }, knownParents = store.taskParents(op.serverId))) }
    onResolved(lineage.asReversed())
  }
  private suspend fun findSession(client: OpenCodeApi, id: String, directories: List<String>): Session? {
    val candidates = directories.filter(String::isNotBlank).distinct().take(16).ifEmpty { listOf("") }
    for (directory in candidates) {
      val session = try { client.session(id, directory) } catch (error: ApiException) {
        if (error.status == 404) continue else throw error
      }
      if (session.id == id) return session
    }
    return null
  }
  fun selectSession(id: String) {
    val session = mutable.value.sessions.firstOrNull { it.id == id } ?: return
    selectionRevision += 1
    val project = resolveSessionProject(session, mutable.value.projects)
    val serverId = state.value.serverId ?: return
    val configuration = store.configuration(serverId, id)
    val draft = store.draft(serverId, id)
    visibleConversation = null
    val offline = mutable.value.cached && !mutable.value.connected
    val messages = emptyList<Message>()
    // Keep the unread marker until the terminal transcript has been fetched and is visible.
    val acknowledged = mutable.value.acknowledged
    mutable.update { withSummary(it.copy(projectId = project?.id, sessionId = id, messages = messages,
      agent = configuration.agent ?: session.agent, model = configuration.model ?: session.model,
      agentChanged = configuration.agentChanged, modelChanged = configuration.modelChanged,
      draft = draft, references = store.references(serverId, id), savedPermissions = null, sharedUrl = null, loadedOlderMessages = false, messagesCursor = null, resources = mapOf("messages" to ResourceStatus(ResourceState.LOADING)),
      todos = emptyList(), children = emptyList(), changes = emptyList(), files = emptyList(),
      searchResults = emptyList(), fileText = null, fileBinary = false, acknowledged = acknowledged)) }
    store.rememberLocation(project?.id, id)
    if (offline) {
      val serverId = state.value.serverId ?: return
      val revision = selectionRevision
      scope.launch {
        val cachedMessages = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.messages(serverId, id) }
        if (state.value.serverId == serverId && selectionRevision == revision) mutable.update { it.copy(messages = cachedMessages, cacheComplete = false,
          resources = it.resources + ("messages" to ResourceStatus(if (cachedMessages.isEmpty()) ResourceState.ERROR else ResourceState.STALE, "离线缓存"))) }
      }
    } else {
      val token = generation
      // Choices and the session transcript are independent; run them concurrently.
      scope.launch { coroutineScope { launch { loadChoices(session.directory, token) }; loadSession(session, token = token) } }
    }
  }
  private suspend fun loadSession(session: Session, ancillary: Boolean = true, token: Int = generation, client: OpenCodeApi? = api) {
    val client = client ?: return
    val serverId = state.value.serverId ?: return
    val revision = selectionRevision
    val requestSequence = ++messageSequence
    fun current() = token == generation && revision == selectionRevision && state.value.sessionId == session.id && state.value.executionDirectory == session.directory && requestSequence == messageSequence
    if (current() && state.value.messages.isEmpty()) mutable.update { it.copy(resources = it.resources + ("messages" to ResourceStatus(ResourceState.LOADING))) }
    val terminalAtRequest = state.value.tasks[session.id]?.takeIf { it.phase in TERMINAL_PHASES }?.since
    var nextCursor: String? = null
    val result = attempt { client.messagesPage(session.id, session.directory).let { nextCursor = it.next; it.items } }
    if (token != generation) return
    val previousMessages = state.value.messages
    val messages = result.map { fetched ->
      if (current() && state.value.loadedOlderMessages && fetched.isNotEmpty()) {
        val oldest = fetched.first().created
        (previousMessages.filter { it.created < oldest } + fetched).distinctBy { it.id }
      } else fetched
    }.getOrElse { error ->
      val fallback = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.messages(serverId, session.id) }
      if (current()) mutable.update { it.copy(error = error.message, cached = true, cacheComplete = false,
        resources = it.resources + ("messages" to ResourceStatus(if (fallback.isEmpty()) ResourceState.ERROR else ResourceState.STALE, error.message))) }
      if (fallback.isEmpty()) return else fallback
    }
    if (result.isSuccess) cache.saveMessages(serverId, session.id, messages, complete = nextCursor == null)
    if (!current()) return
    val preview = messages.sessionPreview()
    if (result.isSuccess) store.rememberPreview(serverId, session.id, preview)
    val latestAgent = messages.asReversed().firstNotNullOfOrNull { it.agent } ?: session.agent
    val latestModel = messages.asReversed().firstNotNullOfOrNull { it.model } ?: session.model
    mutable.update { it.copy(messages = messages, cached = result.isFailure, messagesCursor = if (it.loadedOlderMessages) it.messagesCursor else nextCursor, cacheComplete = result.isSuccess || cache.messagesComplete(serverId, session.id),
      previews = it.previews + (session.id to preview),
      agent = if (it.agentChanged) it.agent else latestAgent,
      model = if (it.modelChanged) it.model else latestModel,
      resources = it.resources + ("messages" to ResourceStatus(if (result.isSuccess) { if (messages.none { message -> message.isDisplayable }) ResourceState.EMPTY else ResourceState.READY } else ResourceState.STALE))) }
    if (result.isSuccess) {
      val last = messages.lastOrNull { it.isDisplayable }?.takeIf { it.role == "assistant" }
      val task = state.value.tasks[session.id]
      if (last != null && (task == null || task.phase == TaskPhase.IDLE) && (last.error != null || last.completedAt != null && last.finish !in setOf("tool-calls", "tool_calls"))) {
        val phase = if (last.error != null) TaskPhase.FAILED else TaskPhase.COMPLETED
        mutable.update { withSummary(it.copy(tasks = it.tasks + (session.id to TaskState(session.id, phase,
          last.error ?: "任务已完成", task?.since ?: last.created, last.completedAt)))) }
      }
      val terminal = state.value.tasks[session.id]?.takeIf { it.phase in TERMINAL_PHASES }
      if (terminal != null && (terminalAtRequest == terminal.since || terminalAtRequest == null && task?.phase in setOf(null, TaskPhase.IDLE))) observedTerminal[session.id] = terminal.since
      acknowledgeVisible()
    }
    if (ancillary) {
      // Todos, children and diff are independent; fetch in parallel (one round trip when connected).
      mutable.update { it.copy(resources = it.resources + listOf("todos", "children", "changes").associateWith { ResourceStatus(ResourceState.LOADING) }) }
      val (todos, children, changes) = coroutineScope {
        val todosTask = async { attempt { client.todos(session) } }
        val childrenTask = async { attempt { client.children(session) } }
        val changesTask = async { attempt { client.diff(session) } }
        Triple(todosTask.await(), childrenTask.await(), changesTask.await())
      }
      if (current()) mutable.update { it.copy(todos = todos.getOrDefault(it.todos), children = children.getOrDefault(it.children), changes = changes.getOrDefault(it.changes),
        resources = it.resources + mapOf("todos" to todos.resourceStatus(), "children" to children.resourceStatus(), "changes" to changes.resourceStatus()),
        sessions = (it.sessions + children.getOrDefault(emptyList())).distinctBy { item -> item.id }.sortedByDescending { item -> item.updated }) }
    }
  }
  fun chooseAgent(name: String?) {
    mutable.update { it.copy(agent = name ?: it.messages.asReversed().firstNotNullOfOrNull { message -> message.agent } ?: it.session?.agent, agentChanged = name != null) }; rememberConfiguration()
  }
  fun chooseModel(model: ModelChoice?) {
    mutable.update { it.copy(model = model ?: it.messages.asReversed().firstNotNullOfOrNull { message -> message.model } ?: it.session?.model, modelChanged = model != null) }; rememberConfiguration()
  }
  private fun rememberConfiguration() {
    val current = state.value
    val server = current.serverId ?: return
    val session = current.sessionId ?: return
    store.rememberConfiguration(server, session, SessionConfiguration(current.agent, current.model, current.agentChanged, current.modelChanged))
  }
  fun updateDraft(text: String, expectedServer: String? = state.value.serverId, expectedSession: String? = state.value.sessionId) {
    val current = state.value
    if (current.serverId != expectedServer || current.sessionId != expectedSession) return
    val server = current.serverId ?: return
    val session = current.sessionId ?: return
    mutable.update { it.copy(draft = text, draftSessions = if (text.isBlank()) it.draftSessions - session else it.draftSessions + session) }
    val key = "$server:$session"
    draftWrites.remove(key)?.cancel()
    draftWrites[key] = operationScope.launch(Dispatchers.IO) {
      delay(if (text.isEmpty()) 0 else 350)
      attempt { store.rememberDraft(server, session, text) }.onFailure { Diagnostics.warn("Draft", "草稿暂未保存") }
    }
  }
  fun addReference(path: String, mime: String = referenceMime(path)) {
    if (state.value.pending("send")) return
    mutable.update { it.copy(references = (it.references + FileReference(path, mime)).distinctBy { ref -> ref.path }) }; rememberReferences()
  }
  fun removeReference(path: String) { if (state.value.pending("send")) return; mutable.update { it.copy(references = it.references.filterNot { ref -> ref.path == path }) }; rememberReferences() }
  private fun rememberReferences() {
    val current = state.value; val server = current.serverId ?: return; val session = current.sessionId ?: return
    operationScope.launch { referenceMutex.withLock { kotlinx.coroutines.withContext(Dispatchers.IO) { attempt { store.rememberReferences(server, session, current.references) } } } }
  }
  fun addProjectDirectory(directory: String) {
    val server = state.value.serverId ?: return
    require(directory.isNotBlank() && !directory.contains('\u0000')) { "目录不能为空" }
    require(directory.trim().startsWith("/") || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(directory.trim())) { "请输入服务器上的绝对目录" }
    store.rememberDirectory(server, directory.trim())
    val project = Project("directory:${directory.trim()}", directory.trim(), directory.trim().substringAfterLast('/'))
    mutable.update { it.copy(projects = (it.projects + project).distinctBy { item -> normalizedDirectory(item.directory) }) }
    selectProject(project.id); reload()
  }
  fun conversationVisible(server: String, session: String, visible: Boolean) {
    if (visible) visibleConversation = server to session
    else if (visibleConversation == (server to session)) visibleConversation = null
    acknowledgeVisible()
  }
  private fun acknowledgeVisible() {
    val current = state.value
    val server = current.serverId ?: return
    val session = current.sessionId ?: return
    if (visibleConversation != (server to session) || current.resource("messages").state !in setOf(ResourceState.READY, ResourceState.EMPTY)) return
    if (current.tasks[session]?.phase !in TERMINAL_PHASES || observedTerminal[session] != current.tasks[session]?.since || session in current.acknowledged) return
    store.acknowledgeTask(server, session)
    mutable.update { withSummary(it.copy(acknowledged = it.acknowledged + session)) }
    notifications.cancel(server, session)
  }
  private fun <T> Result<List<T>>.resourceStatus(): ResourceStatus = fold(
    { ResourceStatus(if (it.isEmpty()) ResourceState.EMPTY else ResourceState.READY) },
    { ResourceStatus(if (it is ApiException && it.status == 501) ResourceState.UNSUPPORTED else ResourceState.ERROR, it.message) })

  /**
   * Immutable identity of the connection an asynchronous operation started on. A response may only
   * be committed while [isCurrent] still holds, so a request that was in flight when the user
   * switched server/project cannot write its result into the new context (A02).
   */
  private data class OperationContext(val token: Int, val serverId: String, val client: OpenCodeApi,
    val revision: Long, val snapshot: LagoonState) {
    fun connectionCurrent(c: LagoonController) = token == c.generation && c.state.value.serverId == serverId && c.api === client
    fun isCurrent(c: LagoonController) = connectionCurrent(c) && revision == c.selectionRevision &&
      c.state.value.projectId == snapshot.projectId && c.state.value.sessionId == snapshot.sessionId && c.state.value.executionDirectory == snapshot.executionDirectory
  }
  private fun beginOperation(targetDirectory: String? = null): OperationContext {
    val current = state.value
    check(current.connected) { "数据尚未同步，请重新连接后操作" }
    val directory = targetDirectory ?: current.executionDirectory
    check(!current.degraded || directory !in current.staleDirectories) { "当前项目状态尚未同步，请刷新后操作" }
    return OperationContext(generation, current.serverId ?: error("请先连接服务器"),
      requireNotNull(api) { "请先连接服务器" }, selectionRevision, current)
  }
  private fun OperationContext.commit(update: (LagoonState) -> LagoonState) {
    if (isCurrent(this@LagoonController)) mutable.update(update)
  }
  private fun OperationContext.commitConnection(update: (LagoonState) -> LagoonState) {
    if (connectionCurrent(this@LagoonController)) mutable.update(update)
  }
  fun createSession(title: String, onCreated: ((Session) -> Unit)? = null) = act("create", state.value.project?.directory) { op ->
    val project = op.snapshot.project ?: error("先选择项目")
    val session = op.client.createSession(project.directory, title)
    op.commitConnection { it.copy(sessions = (listOf(session) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) { selectSession(session.id); onCreated?.invoke(session) }
  }
  /**
   * 新建会话一步流：建会话后直接发送第一条 Prompt，免去"空会话 → 再发一条消息"的往返。
   * 首条消息复用 [send]，命令解析、任务计数与前台服务逻辑不重复实现。
   */
  fun startSession(title: String, prompt: String, agent: String? = null, model: ModelChoice? = null, onStarted: ((Session) -> Unit)? = null) = act("create", state.value.project?.directory) { op ->
    check(prompt.isNotBlank()) { "请输入任务" }
    val project = op.snapshot.project ?: error("先选择项目")
    val session = op.client.createSession(project.directory, title)
    op.commitConnection { it.copy(sessions = (listOf(session) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) {
      store.rememberDraft(op.serverId, session.id, prompt)
      store.rememberConfiguration(op.serverId, session.id, SessionConfiguration(agent, model, agent != null, model != null))
      selectSession(session.id)
      mutable.update { it.copy(agentChanged = agent != null, modelChanged = model != null, draft = prompt, previews = it.previews + (session.id to SessionPreview(SessionContent.EMPTY))) }
      onStarted?.invoke(session)
      send(prompt)
    }
  }
  fun send(text: String, expectedServer: String? = state.value.serverId, expectedSession: String? = state.value.sessionId, accepted: (() -> Unit)? = null): Job {
    if (state.value.serverId != expectedServer || state.value.sessionId != expectedSession) return operationScope.launch { }
    return act("send") { op ->
    check(text.isNotBlank() || op.snapshot.references.isNotEmpty()) { "请输入任务" }
    val session = op.snapshot.session ?: error("先打开会话")
    sendMutex.withLock {
      if (!op.isCurrent(this)) return@act
      check(state.value.tasks[session.id]?.active != true) { "当前会话仍在处理上一项任务" }
      val command = if (text.startsWith('/')) op.snapshot.commands.firstOrNull { text.substringAfter('/').substringBefore(' ') == it.name } else null
      val agent = op.snapshot.agent.takeIf { op.snapshot.protocol == ServerProtocol.V1 || op.snapshot.agentChanged }
      val model = op.snapshot.model.takeIf { op.snapshot.protocol == ServerProtocol.V1 || op.snapshot.modelChanged }
      if (command != null) {
        check(op.snapshot.references.isEmpty()) { "该命令未确认附件接口，任务正文与附件已保留" }
        op.client.command(session, command.name, text.substringAfter(' ', ""), agent, model)
      } else op.client.send(session, text, agent, model, op.snapshot.references)
      store.rememberConfiguration(op.serverId, session.id, SessionConfiguration(op.snapshot.agent, op.snapshot.model))
      referenceMutex.withLock { kotlinx.coroutines.withContext(Dispatchers.IO) { store.rememberReferences(op.serverId, session.id, emptyList()) } }
      store.rememberPreview(op.serverId, session.id, SessionPreview(SessionContent.CONTENT, text.take(300)))
      if (op.isCurrent(this)) {
        if (state.value.draft == text) updateDraft("")
        accepted?.invoke()
      }
      op.commitConnection { withSummary(it.copy(tasks = it.tasks + (session.id to TaskState(session.id, TaskPhase.THINKING, "任务已发送")),
        previews = it.previews + (session.id to SessionPreview(SessionContent.CONTENT, text.take(300))))) }
      op.commit { it.copy(agentChanged = false, modelChanged = false, references = emptyList()) }
    }
    if (op.connectionCurrent(this) && op.snapshot.server?.notifications == true) TaskMonitorService.start(appContext, op.serverId, session.id)
    if (op.isCurrent(this)) loadSession(session, token = op.token, client = op.client, ancillary = false)
  }
  }
  fun abort() = withSession("abort") { op, client, session ->
    client.abort(session)
    op.commitConnection { withSummary(it.copy(tasks = it.tasks + (session.id to TaskState(session.id, TaskPhase.ABORTED, "任务已停止", it.tasks[session.id]?.since ?: System.currentTimeMillis(), System.currentTimeMillis())))) }
  }
  fun rename(title: String, onRenamed: (() -> Unit)? = null) = withSession("rename") { op, client, session ->
    check(title.isNotBlank()) { "标题不能为空" }
    client.renameSession(session, title.trim())
    op.commitConnection { it.copy(sessions = it.sessions.map { item -> if (item.id == session.id) item.copy(title = title.trim()) else item }) }
    if (op.isCurrent(this)) onRenamed?.invoke()
  }
  fun deleteSession(onDeleted: (() -> Unit)? = null) = withSession("delete") { op, client, session ->
    client.deleteSession(session)
    cache.deleteMessages(op.serverId, session.id)
    store.forgetSession(op.serverId, session.id)
    if (op.isCurrent(this)) onDeleted?.invoke()
    op.commit { it.copy(sessionId = null, messages = emptyList()) }
    op.commitConnection { it.copy(sessions = it.sessions.filterNot { s -> s.id == session.id }, tasks = it.tasks - session.id) }
    if (op.connectionCurrent(this)) reload()
  }
  fun fork(onForked: ((Session) -> Unit)? = null) = withSession("fork") { op, client, session ->
    val fork = client.forkSession(session)
    op.commitConnection { it.copy(sessions = (listOf(fork) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) { selectSession(fork.id); onForked?.invoke(fork) }
  }
  fun share() = withSession("share") { op, client, session ->
    val url = client.share(session)
    check(url.isNotBlank()) { "服务器没有返回分享链接" }
    op.commit { it.copy(sharedUrl = url) }
  }
  fun closeShare() = mutable.update { it.copy(sharedUrl = null) }
  fun unshare() = withSession("unshare") { op, client, session -> client.unshare(session); op.commit { it.copy(sharedUrl = null, message = "已取消分享") } }
  fun summarize() = withSession("compact") { op, client, session -> client.summarize(session, op.snapshot.model); op.commit { it.copy(message = "上下文整理请求已接受") } }
  fun revert(messageId: String, onReverted: (() -> Unit)? = null) = withSession("revert") { op, client, session ->
    client.revert(session, messageId); if (op.isCurrent(this)) { op.commit { it.copy(message = "已撤销，可通过恢复撤销还原") }; onReverted?.invoke(); loadSession(session, token = op.token, client = client) }
  }
  fun unrevert() = withSession("unrevert") { op, client, session ->
    client.unrevert(session); if (op.isCurrent(this)) { op.commit { it.copy(message = "已恢复撤销") }; loadSession(session, token = op.token, client = client) }
  }
  fun replyPermission(request: PermissionRequest, reply: String) = act("permission:${request.id}", request.directory) { op ->
    check(request in op.snapshot.permissions) { "权限请求已变化，请刷新" }
    op.client.replyPermission(request, reply)
    op.commitConnection { it.copy(permissions = it.permissions.filterNot { p -> p.id == request.id }) }
  }
  /** Notification execution uses its own immutable client. Only reconcile matching UI state. */
  fun notificationCompleted(serverId: String) {
    scope.launch { if (state.value.serverId == serverId) reload() }
  }
  fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) = act("question:${request.id}", request.directory) { op ->
    check(request in op.snapshot.questions) { "问题已变化，请刷新" }
    op.client.replyQuestion(request, answers)
    op.commitConnection { it.copy(questions = it.questions.filterNot { q -> q.id == request.id }) }
  }
  fun rejectQuestion(request: QuestionRequest) = act("question:${request.id}", request.directory) { op ->
    check(request in op.snapshot.questions) { "问题已变化，请刷新" }
    op.client.rejectQuestion(request)
    op.commitConnection { it.copy(questions = it.questions.filterNot { q -> q.id == request.id }) }
  }
  fun listFiles(path: String = "."): Job {
    val request = ++fileSequence
    mutable.update { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.LOADING)), searchResults = emptyList()) }
    ++searchSequence
    return act { op ->
      val directory = op.snapshot.executionDirectory ?: error("先选择项目")
      val files = try { op.client.files(directory, path) } catch (error: Exception) {
        if (request == fileSequence) op.commit { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.ERROR, error.message))) }
        throw error
      }
      if (request == fileSequence) op.commit { it.copy(files = files, filePath = path, fileText = null, fileBinary = false,
        resources = it.resources + ("files" to ResourceStatus(if (files.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
    }
  }
  fun readFile(path: String): Job {
    val request = ++fileSequence
    mutable.update { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.LOADING))) }
    return act { op ->
      val directory = op.snapshot.executionDirectory ?: error("先选择项目")
      val content = try { op.client.fileContent(directory, path) } catch (error: Exception) {
        if (request == fileSequence) op.commit { it.copy(resources = it.resources + ("files" to ResourceStatus(ResourceState.ERROR, error.message))) }
        throw error
      }
      if (request == fileSequence) op.commit { it.copy(fileText = content.content, fileBinary = content.type == "binary", filePath = path,
        resources = it.resources + ("files" to ResourceStatus(ResourceState.READY))) }
    }
  }
  fun searchFiles(query: String): Job {
    val request = ++searchSequence
    if (query.trim().length < 2) {
      mutable.update { it.copy(searchResults = emptyList(), resources = it.resources + ("search" to ResourceStatus(ResourceState.EMPTY))) }
      return operationScope.launch { }
    }
    mutable.update { it.copy(searchResults = emptyList(), resources = it.resources + ("search" to ResourceStatus(ResourceState.LOADING))) }
    return act { op ->
      delay(250)
      if (request != searchSequence) return@act
      val directory = op.snapshot.executionDirectory ?: error("先选择项目")
      val results = try { op.client.searchFiles(directory, query.trim()) } catch (error: Exception) {
        if (request == searchSequence) op.commit { it.copy(resources = it.resources + ("search" to ResourceStatus(ResourceState.ERROR, error.message))) }
        throw error
      }
      if (request == searchSequence) op.commit { it.copy(searchResults = results,
        resources = it.resources + ("search" to ResourceStatus(if (results.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
    }
  }
  fun loadSavedPermissions(): Job {
    mutable.update { it.copy(savedPermissions = emptyList(), resources = it.resources + ("saved" to ResourceStatus(ResourceState.LOADING))) }
    return act("saved") { op ->
      val projectId = op.snapshot.session?.projectId ?: op.snapshot.project?.id ?: error("尚未取得项目身份，请刷新")
      val saved = try { op.client.savedPermissions(projectId) } catch (error: Exception) {
        op.commit { it.copy(resources = it.resources + ("saved" to ResourceStatus(ResourceState.ERROR, error.message))) }
        throw error
      }
      op.commit { it.copy(savedPermissions = saved, resources = it.resources + ("saved" to ResourceStatus(if (saved.isEmpty()) ResourceState.EMPTY else ResourceState.READY))) }
    }
  }
  fun closeSavedPermissions() = mutable.update { it.copy(savedPermissions = null) }
  fun revokeSavedPermission(rule: SavedPermission) = act("revoke:${rule.id}") { op ->
    check(rule in op.snapshot.savedPermissions.orEmpty())
    op.client.revokePermission(rule.id)
    op.commit { it.copy(savedPermissions = it.savedPermissions?.filterNot { old -> old.id == rule.id }) }
  }
  private fun withSession(action: String, block: suspend (OperationContext, OpenCodeApi, Session) -> Unit) = act(action) { op ->
    val session = op.snapshot.session ?: error("先打开会话")
    block(op, op.client, session)
  }
  private fun act(action: String = "", directory: String? = null, block: suspend (OperationContext) -> Unit): Job {
    val captured = attempt { beginOperation(directory) }
    val key = action.takeIf { it.isNotBlank() }?.let { operationKey(state.value.serverId, state.value.sessionId, state.value.projectId, it) }
    if (key != null && key in state.value.pendingOperations) return operationScope.launch { }
    if (key != null) mutable.update { it.copy(pendingOperations = it.pendingOperations + key,
      resources = it.resources + ("action:$action" to ResourceStatus(ResourceState.LOADING))) }
    return operationScope.launch {
      val op = captured.getOrElse { error ->
        mutable.update { it.copy(error = error.message ?: "操作失败", resources = it.resources + ("action:$action" to ResourceStatus(ResourceState.ERROR, error.message)), pendingOperations = it.pendingOperations - setOfNotNull(key)) }
        return@launch
      }
      try {
        block(op)
        if (key != null && op.isCurrent(this@LagoonController)) mutable.update {
          it.copy(resources = it.resources + ("action:$action" to ResourceStatus(ResourceState.READY)))
        }
      } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
        if (op.connectionCurrent(this@LagoonController)) mutable.update { it.copy(error = error.message ?: "操作失败",
          resources = if (key == null || !op.isCurrent(this@LagoonController)) it.resources else it.resources + ("action:$action" to ResourceStatus(ResourceState.ERROR, error.message))) }
      } finally {
        if (key != null) mutable.update { it.copy(pendingOperations = it.pendingOperations - key) }
      }
    }
  }
  private inline fun <T> attempt(block: () -> T): Result<T> = try { Result.success(block()) }
    catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) { Result.failure(error) }
}
