package com.igng.opencode.lagoon.core

import android.content.Context
import com.igng.opencode.lagoon.system.BackgroundSyncWorker
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

/** Pending phone attachments belong to one composer: a session, or the not-yet-created draft. */
internal fun attachmentKey(session: String?): String = session ?: "draft"

data class LagoonState(
  val profiles: List<ServerProfile> = emptyList(), val serverId: String? = null, val version: String = "",
  val connected: Boolean = false, val cached: Boolean = false, val loading: Boolean = false, val error: String? = null,
  /** True when the last catalog refresh could not read every project; some values are last-known. */
  val degraded: Boolean = false,
  val staleDirectories: Set<String> = emptySet(), val streamConnected: Boolean = false,
  /** Non-error feedback (e.g. a share link); kept separate so it is not rendered as a failure. */
  val message: String? = null,
  val projects: List<Project> = emptyList(), val projectId: String? = null,
  /** Home list filter: null = 全部会话 (default), otherwise one project id. Not the execution target. */
  val scopeProjectId: String? = null,
  val sessions: List<Session> = emptyList(), val sessionId: String? = null,
  val knownParents: Map<String, String> = emptyMap(),
  val messages: List<Message> = emptyList(), val tasks: Map<String, TaskState> = emptyMap(),
  val permissions: List<PermissionRequest> = emptyList(), val questions: List<QuestionRequest> = emptyList(),
  val children: List<Session> = emptyList(), val changes: List<FileChange> = emptyList(),
  val agents: List<AgentChoice> = emptyList(), val models: List<ModelChoice> = emptyList(), val commands: List<CommandChoice> = emptyList(),
  /** [models] with catalog metadata; visibility follows [ModelVisibility] plus this phone's switches. */
  val modelCatalog: List<ModelInfo> = emptyList(), val modelOverrides: Map<String, Boolean> = emptyMap(), val recentModels: List<String> = emptyList(),
  /** Files picked on this phone, per session (`draft` before a session exists), until they are sent. */
  val pendingAttachments: Map<String, List<LocalAttachment>> = emptyMap(),
  val agent: String? = null, val model: ModelChoice? = null,
  val files: List<FileNode> = emptyList(), val filePath: String = ".", val fileText: String? = null, val fileBinary: Boolean = false,
  val searchResults: List<String> = emptyList(),
  val supportsSavedPermissions: Boolean = false, val savedPermissions: List<SavedPermission>? = null,
  val capabilities: ApiCapabilities = ApiCapabilities(),
  val previews: Map<String, SessionPreview> = emptyMap(), val resources: Map<String, ResourceStatus> = emptyMap(),
  val pendingOperations: Set<String> = emptySet(),
  val agentChanged: Boolean = false, val modelChanged: Boolean = false,
  val draftSessions: Set<String> = emptySet(), val draft: String = "", val references: List<FileReference> = emptyList(),
  val cacheComplete: Boolean = true, val sessionCursors: Map<String, String> = emptyMap(), val messagesCursor: String? = null,
  val catalogComplete: Boolean = true, val loadedOlderMessages: Boolean = false,
  /**
   * 未读结果账本（对齐官方客户端的通知账本）：只有本机见证“运行中 → 结束”的主会话才会产生一条，
   * 打开会话即标记已读。列表的“已完成 / 失败”、灵动岛计数都只读这里，从不由历史数据推断。
   */
  val notices: List<SessionNotice> = emptyList(), val collapsedSections: Set<String> = emptySet(), val pinned: Set<String> = emptySet(),
  /** 全服务器范围的任务计数，由实时 [tasks] 与未读 [notices] 派生，供灵动岛与系统通知复用。 */
  val summary: TaskSummary = TaskSummary.EMPTY,
  /** 存在未读已完成/失败时，灵动岛点击应跳转的会话 id。 */
  val summaryTargetId: String? = null
) {
  val server: ServerProfile? get() = profiles.firstOrNull { it.id == serverId }
  val project: Project? get() = projects.firstOrNull { it.id == projectId }
  val session: Session? get() = sessions.firstOrNull { it.id == sessionId }
  val executionDirectory: String? get() = session?.directory ?: project?.directory
  val attachments: List<LocalAttachment> get() = pendingAttachments[attachmentKey(sessionId)].orEmpty()
  val visibleModels: List<ModelInfo> get() = ModelVisibility.visible(modelCatalog, modelOverrides)
  fun pending(action: String): Boolean = pendingOperations.contains(operationKey(serverId, sessionId, projectId, action))
  fun resource(name: String): ResourceStatus = resources[name] ?: ResourceStatus()
  fun title(session: Session): String = session.displayTitle()
  val parents: Map<String, String> get() = knownParents + sessions.mapNotNull { it.parentId?.let { parent -> it.id to parent } }.toMap()
  /** Live (running / waiting) state of each session family, rolled up to its root. */
  val activeRootTasks: Map<String, TaskState> get() = TaskSummary.aggregate(tasks.filterValues { it.active }, parents)
  fun sessionStatus(session: Session, active: Map<String, TaskState> = activeRootTasks): SessionStatus =
    sessionStatus(active[session.id], notices.unseenFor(session.id))
}

class LagoonController private constructor(private val appContext: Context) {
  companion object {
    private val TERMINAL_PHASES = setOf(TaskPhase.COMPLETED, TaskPhase.FAILED)
    /** Returning to the foreground re-reads the server unless a full refresh just happened. */
    private const val FOREGROUND_REFRESH_MILLIS = 15_000L
    /** Keep the live stream briefly after leaving the app so quick app switches do not reconnect. */
    private const val BACKGROUND_GRACE_MILLIS = 30_000L
    @Volatile private var instance: LagoonController? = null
    fun get(context: Context): LagoonController = instance ?: synchronized(this) {
      instance ?: LagoonController(context.applicationContext).also { instance = it }
    }
    /** Control-plane reconciliation cadence while the SSE stream is up. */
    private const val RECONCILE_INTERVAL_MILLIS = 45_000L
    /** Upper bound of running sessions whose pending permissions are read in one reconciliation. */
    private const val MAX_REQUEST_SESSIONS = 24
  }
  /**
   * Recomputes the server-wide island summary and persists live task state. Persisted running states are
   * what lets a later refresh — even after the process was frozen or killed — notice that a run this device
   * saw has ended and record it as an unread result.
   */
  private fun withSummary(state: LagoonState): LagoonState {
    state.serverId?.let { server -> state.tasks.values.forEach { store.rememberTask(server, it, state.parents[it.sessionId]) } }
    val titles = state.sessions.associate { it.id to state.title(it) }
    val active = state.activeRootTasks
    val summary = TaskSummary.of(state.tasks, state.notices, state.parents, titles)
    return state.copy(
    summary = summary,
    // Prefer the session that needs a reply, then the newest unread result; otherwise the summary's
    // headline task, so tapping the island always lands somewhere, including when tasks are only running.
    summaryTargetId = active.values.firstOrNull { it.phase in TaskState.WAITING_PHASES }?.sessionId
      ?: state.notices.filter { !it.viewed && it.sessionId !in active }.maxByOrNull { it.time }?.sessionId
      ?: summary.items.firstOrNull()?.sessionId
  )
  }
  private val store = ServerStore(appContext)
  private val cache = OfflineCache(appContext)
  private val notifications = TaskNotifications(appContext)
  private val attachmentImporter = AttachmentImporter(appContext)
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
  private val sendMutex = Mutex()
  private val referenceMutex = Mutex()
  private var searchSequence = 0L
  private var fileSequence = 0L
  private var selectionRevision = 0L
  private var catalogSequence = 0L
  private var messageSequence = 0L
  private var reconcile: Job? = null
  private var lastSummary: Triple<ServerProfile?, TaskSummary, String?>? = null
  private var visibleConversation: Pair<String, String>? = null
  private val seenEvents = linkedSetOf<String>()
  private val draftWrites = mutableMapOf<String, Job>()
  /** An activity is started (ON_START…ON_STOP). The live stream runs only while foreground or monitoring. */
  @Volatile private var foreground = false
  /** [TaskMonitorService] keeps the process alive for running tasks; the stream may then run in background. */
  @Volatile private var monitoring = false
  private var backgroundStop: Job? = null
  private var connecting: Job? = null
  @Volatile private var lastFullRefreshAt = 0L
  /** Root sessions already handed to [TaskMonitorService] during this run. */
  private val monitorRequested = mutableSetOf<String>()

  init {
    // Single publisher for the server-wide island summary, so the system notification never drifts
    // from the in-app island: both read the same derived `summary` on every state emission.
    scope.launch { state.collect { state ->
      val signature = Triple(state.server, state.summary, state.summaryTargetId)
      if (signature != lastSummary) { lastSummary = signature; publishSummary(state); ensureMonitoring(state) }
    } }
    if (mutable.value.profiles.any { it.id == mutable.value.serverId && it.autoConnect }) connect(mutable.value.serverId!!)
    scheduleBackgroundSync()
  }
  private fun scheduleBackgroundSync() {
    attempt { BackgroundSyncWorker.schedule(appContext, store.profiles().isNotEmpty()) }
      .onFailure { Diagnostics.warn("BackgroundSync", "无法安排后台定时刷新", it) }
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
    scheduleBackgroundSync()
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
    }
    store.delete(id)
    cache.delete(id)
    scheduleBackgroundSync()

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
    api = OpenCodeApi(profile, store.credentials(id))
    val rememberedProject = store.selectedProject(id)
    val rememberedSession = store.selectedSession(id)
    seenEvents.clear(); visibleConversation = null
    val configuration = rememberedSession?.let { store.configuration(id, it) } ?: SessionConfiguration()
    store.select(id, rememberedProject, rememberedSession)
    // Drop the pre-ledger “永久已完成” states and read markers; keep runs this device saw as still running.
    store.migrateTaskLedger(id)
    mutable.update { LagoonState(profiles = store.profiles(), serverId = id, loading = true, projectId = rememberedProject, sessionId = rememberedSession,
      agent = configuration.agent, model = configuration.model, agentChanged = configuration.agentChanged, modelChanged = configuration.modelChanged,
      draft = rememberedSession?.let { store.draft(id, it) }.orEmpty(), references = rememberedSession?.let { store.references(id, it) }.orEmpty(),
      tasks = store.taskStates(id), knownParents = store.taskParents(id), notices = store.sessionNotices(id), collapsedSections = store.collapsedSections(id), pinned = store.pinnedSessions(id),
      modelOverrides = store.modelOverrides(id), recentModels = store.recentModels(id), scopeProjectId = store.scope(id),
      message = if (leaving != null) "已结束上一服务器的本地监控；远端任务继续运行。" else null) }
    connecting = scope.launch {
      try {
        loadAll(token)
        if (token == generation && streamAllowed()) {
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
      if (streamAllowed()) startStream(token)
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
  private suspend fun loadAll(token: Int, controlOnly: Boolean = false, followUp: Boolean = true) {
    val client = api ?: return
    val requestSequence = ++catalogSequence
    val version = client.health()
    val capabilities = client.discoverCapabilities()
    val serverId = state.value.serverId ?: return
    // Projects are authoritative from the server; this client never invents one from a folder.
    val projects = if (controlOnly && state.value.projects.isNotEmpty()) state.value.projects else client.projects()
    // Official home index: one global, newest-first listing of root sessions (directory filters are
    // exact, so per-project queries would miss worktrees). Reconciliation re-reads its first page too,
    // so a session created elsewhere while an event was missed still appears.
    val page = client.rootSessionsPage()
    val sessions = page.items
    // Activity is authoritative from `/api/session/active`; a failed read keeps last-known tasks.
    val activeResult = attempt { client.activeSessions() }
    val active = activeResult.getOrNull()
    if (token != generation || requestSequence != catalogSequence) return
    // Active sessions (often subagent children) may be older than the first page. Keep their identity and parent chain.
    val missingIds = (active.orEmpty() + listOfNotNull(state.value.sessionId, store.selectedSession(serverId)))
      .filter { id -> sessions.none { it.id == id } && state.value.sessions.none { it.id == id } }.distinct()
    val extraSessions = mutableListOf<Session>()
    missingIds.take(100).chunked(4).forEach { batch ->
      extraSessions += coroutineScope { batch.map { id -> async { attempt { findSession(client, id) }.getOrNull() } }.awaitAll().filterNotNull() }
      if (token != generation || requestSequence != catalogSequence) return
    }
    // An active child may refer to a root outside the catalog page. Fetch the ancestry so both
    // pending requests and running indicators roll up to that root instead of leaking child rows.
    val identities = (state.value.sessions + sessions + extraSessions).associateBy { it.id }.toMutableMap()
    val attempted = mutableSetOf<String>()
    repeat(32) {
      val parents = identities.values.mapNotNull { it.parentId }.filter { it !in identities && attempted.add(it) }.distinct().take(100)
      if (parents.isEmpty()) return@repeat
      parents.chunked(4).forEach { batch ->
        val fetched = coroutineScope { batch.map { id -> async { attempt { findSession(client, id) }.getOrNull() } }.awaitAll().filterNotNull() }
        fetched.forEach { identities[it.id] = it }; extraSessions += fetched
        if (token != generation || requestSequence != catalogSequence) return
      }
    }
    // Pending requests, read like the official client: permissions per session, forms per location.
    // Only a running session can be waiting on the user.
    val waitingCandidates = active.orEmpty().mapNotNull { identities[it] }.take(MAX_REQUEST_SESSIONS)
    val permissionResults = coroutineScope { waitingCandidates.map { session -> async { session.id to attempt { client.sessionPermissions(session) } } }.awaitAll() }
    val formDirectories = waitingCandidates.map { it.directory }.filter(String::isNotBlank).distinctBy(::normalizedDirectory)
    val formResults = coroutineScope { formDirectories.map { directory -> async { directory to attempt { client.forms(directory) } } }.awaitAll() }
    if (token != generation || requestSequence != catalogSequence) return
    val failedPermissionSessions = permissionResults.filter { it.second.isFailure }.map { it.first }.toSet()
    val failedFormDirs = formResults.filter { it.second.isFailure }.map { normalizedDirectory(it.first) }.toSet()
    val permissions = permissionResults.mapNotNull { it.second.getOrNull() }.flatten().distinctBy { it.id }
    val questions = formResults.mapNotNull { it.second.getOrNull() }.flatten().distinctBy { it.id }
    store.rememberParents(serverId, sessions + extraSessions)
    val degraded = activeResult.isFailure || failedPermissionSessions.isNotEmpty() || failedFormDirs.isNotEmpty()
    if (degraded) Diagnostics.warn("LagoonController", "部分状态读取失败，保留上次可信状态", activeResult.exceptionOrNull())

    val storedTasks = store.taskStates(serverId)
    var finishedRuns = emptyList<Pair<Session, Long>>()
    mutable.update { previous ->
      val previousTasks = previous.tasks + storedTasks.filter { (id, task) -> previous.tasks[id]?.let { old -> task.since > old.since || (task.finishedAt ?: 0) > (old.finishedAt ?: 0) } ?: true }
      val nextSessions = (sessions + extraSessions + previous.sessions.filter {
        // A session this device saw running, or one with an unread result, never silently drops out;
        // neither do rows loaded through “加载更多” while the catalog has more pages.
        it.id in active.orEmpty() || it.id == previous.sessionId || previousTasks[it.id]?.active == true ||
          previous.notices.unseenFor(it.id).isNotEmpty() || page.next != null
      }).distinctBy { it.id }.sortedWith(sessionActivityOrder)
      // Only live state is carried; finished runs live in the unread ledger, never as a permanent phase.
      val states = mutableMapOf<String, TaskState>()
      nextSessions.forEach { session ->
        val next = if (active == null) previousTasks[session.id]
        else TaskReducer.status(session.id, session.id in active, previousTasks[session.id]?.takeIf { it.active })
        if (next != null && next.phase != TaskPhase.IDLE) states[session.id] = next
      }
      // A successful read is authoritative; a failed one keeps the previous requests so a pending
      // approval is never silently dropped.
      val keptPermissions = previous.permissions.filter { active == null || it.sessionId in failedPermissionSessions }
      val nextPermissions = (permissions + keptPermissions).distinctBy { it.id }
      val keptQuestions = previous.questions.filter { active == null || normalizedDirectory(it.directory) in failedFormDirs }
      val nextQuestions = (questions + keptQuestions).distinctBy { it.id }
      nextPermissions.forEach { states[it.sessionId] = TaskState(it.sessionId, TaskPhase.WAITING_PERMISSION, "等待权限确认", previousTasks[it.sessionId]?.since ?: System.currentTimeMillis()) }
      nextQuestions.forEach { states[it.sessionId] = TaskState(it.sessionId, TaskPhase.WAITING_QUESTION, "等待你的回答", previousTasks[it.sessionId]?.since ?: System.currentTimeMillis()) }
      // Roots whose family this device saw running and that an authoritative read now shows idle.
      val parentMap = store.taskParents(serverId) + nextSessions.mapNotNull { s -> s.parentId?.let { s.id to it } }
      val wasActive = TaskSummary.aggregate(previousTasks.filterValues { it.active }, parentMap)
      val isActive = TaskSummary.aggregate(states.filterValues { it.active }, parentMap)
      finishedRuns = if (active == null) emptyList() else nextSessions.filter { it.parentId == null && it.id in wasActive && it.id !in isActive }
        .map { it to wasActive.getValue(it.id).since }
      // Reconciliation keeps “加载更多” progress; a full refresh restarts paging from the first page.
      val cursors = if (controlOnly && previous.sessionCursors.isNotEmpty()) previous.sessionCursors.takeIf { page.next != null }.orEmpty()
        else listOfNotNull(page.next).associateBy { "" }
      withSummary(previous.copy(version = version, capabilities = capabilities, supportsSavedPermissions = capabilities.savedPermissions,
        draftSessions = store.draftSessionIds(serverId), previews = nextSessions.associate { session -> session.id to (previous.previews[session.id] ?: store.sessionPreview(serverId, session.id)) }, connected = true, cached = previous.cached && previous.sessionId != null, loading = false,
        error = null, degraded = degraded, staleDirectories = emptySet(),
        projects = projects, sessions = nextSessions, knownParents = store.taskParents(serverId), tasks = states, notices = store.sessionNotices(serverId), collapsedSections = store.collapsedSections(serverId), sessionCursors = cursors, catalogComplete = cursors.isEmpty(),
        permissions = nextPermissions, questions = nextQuestions,
        projectId = (previous.projectId ?: store.selectedProject(serverId))?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id,
        scopeProjectId = previous.scopeProjectId?.takeIf { id -> projects.any { it.id == id } },
        sessionId = (previous.sessionId ?: store.selectedSession(serverId))?.takeIf { id -> nextSessions.any { it.id == id } }))
    }
    finishedRuns.forEach { (session, runSince) ->
      if (!state.value.notices.coversRun(session.id, runSince)) session.transitionNotice(runSince)?.let { recordNotice(serverId, session, it, runSince) }
    }
    if (!controlOnly) lastFullRefreshAt = System.currentTimeMillis()
    val current = mutable.value
    current.serverId?.let { cache.saveCatalog(it, current.projects, current.sessions, complete = current.catalogComplete) }
    if (!followUp) return
    if (controlOnly) {
      current.session?.let { loadSession(it, token = token, ancillary = false) }
      return
    }
    current.project?.let { project -> loadChoices(project.directory, token) }
    current.session?.let { loadSession(it, token = token) }
  }
  fun loadMoreSessions() = act("sessions-more") { op ->
    val cursor = op.snapshot.sessionCursors[""] ?: return@act
    val page = op.client.rootSessionsPage(cursor = cursor)
    check(page.next != cursor) { "服务器返回了重复分页游标" }
    store.rememberParents(op.serverId, page.items)
    op.commitConnection { it.copy(knownParents = store.taskParents(op.serverId), sessions = (page.items + it.sessions).distinctBy { s -> s.id }.sortedWith(sessionActivityOrder),
      sessionCursors = listOfNotNull(page.next).associateBy { "" }, catalogComplete = page.next == null) }
    if (op.connectionCurrent(this)) cache.saveCatalog(op.serverId, state.value.projects, state.value.sessions, complete = state.value.catalogComplete)
  }
  fun loadOlderMessages() = withSession("messages-more") { op, client, session ->
    val cursor = op.snapshot.messagesCursor ?: return@withSession
    val page = client.messagesPage(session.id, cursor)
    check(page.next != cursor) { "服务器返回了重复分页游标" }
    op.commit { it.copy(messages = (page.items + it.messages).distinctBy { m -> m.id }, messagesCursor = page.next, loadedOlderMessages = true) }
  }
  fun reload(): Job {
    val token = generation
    refresh?.cancel()
    return scope.launch {
      try {
        loadAll(token)
        // A refresh after an offline start must also (re)establish the live event stream, otherwise
        // the UI shows connected=true with no realtime updates (A10).
        if (token == generation && stream?.isActive != true && streamAllowed()) startStream(token)
      } catch (error: Exception) {
        if (token == generation) mutable.update { state -> state.copy(error = error.message) }
      }
    }.also { refresh = it }
  }

  /** Live events need a visible app or the monitoring service; otherwise the socket would only go stale. */
  private fun streamAllowed() = foreground || monitoring

  /** The app came to the foreground: re-read the server unless that just happened, and resume live events. */
  fun onForeground() {
    foreground = true
    backgroundStop?.cancel()
    val server = state.value.serverId ?: return
    if (api == null || connecting?.isActive == true || refresh?.isActive == true) return
    when {
      !state.value.connected -> reload()
      System.currentTimeMillis() - lastFullRefreshAt > FOREGROUND_REFRESH_MILLIS -> reload()
      stream?.isActive != true && state.value.serverId == server -> startStream(generation)
    }
    ensureMonitoring(state.value)
  }

  /** The app left the foreground: keep live events only while the monitoring service runs. */
  fun onBackground() {
    foreground = false
    backgroundStop?.cancel()
    backgroundStop = scope.launch {
      delay(BACKGROUND_GRACE_MILLIS)
      if (!foreground && !monitoring) stopStream()
    }
  }

  /** Called by [TaskMonitorService]: while it runs, the process (and the live stream) may stay up in background. */
  fun setMonitoring(active: Boolean) {
    monitoring = active
    if (active && stream?.isActive != true && state.value.connected) startStream(generation)
    if (!active && !foreground) stopStream()
  }

  private fun stopStream() {
    stream?.cancel(); reconcile?.cancel()
    mutable.update { it.copy(streamConnected = false) }
  }

  /**
   * Periodic background check (WorkManager): one authoritative read without opening the live stream.
   * A run this device saw that has since ended becomes an unread result and a completion notification.
   */
  suspend fun backgroundSync() {
    if (foreground || monitoring && stream?.isActive == true) return
    connecting?.takeIf { it.isActive }?.join()
    if (state.value.serverId == null || api == null) return
    if (System.currentTimeMillis() - lastFullRefreshAt < FOREGROUND_REFRESH_MILLIS) return
    val token = generation
    attempt { loadAll(token, followUp = false) }.onFailure { Diagnostics.warn("BackgroundSync", "后台同步失败", it) }
  }

  /**
   * Running tasks — from this phone or any other client — are monitored while they run, so their
   * results arrive in real time even after the app is backgrounded. A foreground service can only be
   * started from the foreground, so this runs on state changes while the app is visible.
   */
  private fun ensureMonitoring(state: LagoonState) {
    val server = state.server ?: return
    if (!foreground || !server.notifications) return
    val running = state.activeRootTasks.keys
    monitorRequested.retainAll(running)
    (running - monitorRequested).forEach { session ->
      monitorRequested += session
      attempt { TaskMonitorService.start(appContext, server.id, session) }.onFailure { Diagnostics.warn("Monitor", "无法启动任务监控", it) }
    }
  }
  private fun startStream(token: Int) {
    stream?.cancel()
    stream = scope.launch {
      var retry = 1_000L
      while (isActive && token == generation) {
        api?.events(onOpen = { scope.launch {
          if (token == generation) mutable.update { it.copy(streamConnected = true) }
        } })?.catch { cause ->
          // The stream is volatile by contract; a drop is a connection state, not a user-facing error.
          Diagnostics.warn("SSE", "实时连接断开，正在重连", cause)
          if (token == generation) mutable.update { it.copy(streamConnected = false) }
        }?.collect { event ->
          if (token == generation) {
            if (event.id.isNotBlank()) {
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
          mutable.update { it.copy(streamConnected = false) }
          delay(retry)
          retry = (retry * 2).coerceAtMost(30_000L)
          // Events during the gap are lost (volatile stream): re-read the control plane and the open session.
          attempt { loadAll(token, controlOnly = true) }.onFailure { error ->
            Diagnostics.warn("SSE", "重新同步失败", error)
            if (token == generation) mutable.update { it.copy(connected = false, degraded = true) }
          }
        }
      }
    }
    // Standing reconciliation: while the stream is nominally up, periodically re-read the control
    // plane (catalog/activity/permissions/forms). This converges state even if the server drops an
    // event without the client seeing a stream failure, so a missed permission or terminal state is
    // repaired rather than waiting for the user to notice (A04/A10).
    reconcile?.cancel()
    reconcile = scope.launch {
      while (isActive && token == generation) {
        delay(RECONCILE_INTERVAL_MILLIS)
        // Skip while a manual refresh is running or while the stream is already down: the stream
        // loop re-reads on every reconnect, so reconciling there would only fight the disconnected state.
        if (token != generation || refresh?.isActive == true || !state.value.connected) continue
        attempt { loadAll(token, controlOnly = true, followUp = false) }.onFailure { error ->
          Diagnostics.warn("LagoonController", "状态同步失败", error)
          if (token == generation) mutable.update { it.copy(degraded = true) }
        }
      }
    }
  }

  /**
   * Applies one `/api/event` frame the way the official client does: targeted state updates and a
   * re-read of only the affected session, never a full catalog reload per event.
   */
  private fun handleEvent(event: ServerEvent) {
    val props = event.properties
    val sessionId = props.str("sessionID").ifBlank { props.obj("form").str("sessionID") }
    val known = mutable.value.sessions.firstOrNull { it.id == sessionId }
    val directory = known?.directory ?: event.directory
    if (sessionId.isNotBlank()) {
      val before = mutable.value.tasks[sessionId]
      var after = TaskReducer.event(sessionId, event.type, props, before, event.created)
      if (after != null) {
        val resolved = props.str("requestID").ifBlank { props.str("id") }
        val pendingPermission = mutable.value.permissions.any { it.sessionId == sessionId && !(event.type == "permission.replied" && it.id == resolved) } || event.type == "permission.asked"
        val pendingQuestion = mutable.value.questions.any { it.sessionId == sessionId && !(event.type in setOf("form.replied", "form.cancelled") && it.id == resolved) } || event.type == "form.created"
        val pending = pendingPermission || pendingQuestion
        if (pending && after.phase !in setOf(TaskPhase.FAILED, TaskPhase.ABORTED, TaskPhase.COMPLETED)) after = TaskState(sessionId,
          if (pendingPermission) TaskPhase.WAITING_PERMISSION else TaskPhase.WAITING_QUESTION,
          if (pendingPermission) "等待权限确认" else "等待你的回答", before?.since ?: System.currentTimeMillis())
        val next = after
        val enteringTerminal = next.phase in TERMINAL_PHASES && before?.phase !in TERMINAL_PHASES
        mutable.update { current -> withSummary(current.copy(tasks = current.tasks + (sessionId to next))) }
        if (enteringTerminal && sessionId == state.value.sessionId && messageRefresh?.isActive != true) {
          state.value.session?.let { selected -> val token = generation; messageRefresh = scope.launch { delay(250); loadSession(selected, ancillary = false, token = token) } }
        }
        // Live states notify here; results notify once, from the unread ledger (recordNotice).
        val profile = mutable.value.server
        if (known != null && profile?.notifications == true && next.active && next.phase != before?.phase) notifications.show(profile, known, next,
          mutable.value.permissions.firstOrNull { it.sessionId == sessionId })
      }
    }
    when (event.type) {
      "permission.asked" -> {
        val request = props.toPermission(directory)
        mutable.update { it.copy(permissions = (it.permissions.filterNot { old -> old.id == request.id } + request)) }
        notifyAttention(request.sessionId)
      }
      "form.created" -> {
        val request = props.obj("form").toForm(directory)
        mutable.update { it.copy(questions = (it.questions.filterNot { old -> old.id == request.id } + request)) }
        notifyAttention(request.sessionId)
      }
      "permission.replied" -> {
        val requestId = props.str("requestID")
        mutable.update { it.copy(permissions = it.permissions.filterNot { old -> old.id == requestId }) }
      }
      "form.replied", "form.cancelled" -> {
        val requestId = props.str("id")
        mutable.update { it.copy(questions = it.questions.filterNot { old -> old.id == requestId }) }
      }
      "session.created" -> {
        val created = JSONObject(props.toString()).put("id", sessionId)
          .put("time", JSONObject().put("created", event.created).put("updated", event.created)).toSession()
        if (created.id.isNotBlank()) {
          created.parentId?.let { parent -> state.value.serverId?.let { store.rememberParents(it, listOf(created)) }; mutable.update { it.copy(knownParents = it.knownParents + (created.id to parent)) } }
          mutable.update { it.copy(sessions = (listOf(created) + it.sessions).distinctBy { item -> item.id }.sortedWith(sessionActivityOrder)) }
        }
      }
      "session.renamed" -> updateSession(sessionId) { it.copy(title = props.str("title")) }
      "session.moved" -> updateSession(sessionId) { session ->
        val location = props.obj("location").str("directory")
        val subpath = props.str("subpath")
        session.copy(directory = if (subpath.isBlank()) location else "${location.trimEnd('/')}/${subpath.trim('/')}", projectId = props.str("projectID").ifBlank { session.projectId })
      }
      "session.viewed" -> updateSession(sessionId) { it.copy(viewed = maxOf(it.viewed, props.optLong("idle"))) }
      "session.revert.staged" -> updateSession(sessionId) { it.copy(revertMessageId = props.obj("revert").str("messageID").ifBlank { null }) }
      "session.revert.cleared", "session.revert.committed" -> updateSession(sessionId) { it.copy(revertMessageId = null) }
      "session.agent.selected", "session.model.selected" -> {
        updateSession(sessionId) { session ->
          if (event.type == "session.agent.selected") session.copy(agent = props.str("agent").ifBlank { session.agent })
          else session.copy(model = props.obj("model").toModelChoice() ?: session.model)
        }
        mutable.update { current ->
          if (sessionId != current.sessionId) current else current.copy(
            agent = if (event.type == "session.agent.selected" && !current.agentChanged) props.str("agent").ifBlank { current.agent } else current.agent,
            model = if (event.type == "session.model.selected" && !current.modelChanged) props.obj("model").toModelChoice() ?: current.model else current.model)
        }
      }
      "session.deleted" -> {
        state.value.serverId?.let { store.forgetSession(it, sessionId) }
        mutable.update { it.copy(sessions = it.sessions.filterNot { session -> session.id == sessionId }, notices = it.notices.filterNot { notice -> notice.sessionId == sessionId }) }
      }
      "project.updated" -> {
        val project = props.toProject()
        if (project.id.isNotBlank()) mutable.update { current -> current.copy(projects = current.projects.map { if (it.id == project.id) project.copy(sandboxes = project.sandboxes.ifEmpty { it.sandboxes }) else it }) }
      }
      // A run started or ended: official `session.sync` of that one session (outcome, idle, updated).
      "session.execution.started", "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted" -> refreshSession(sessionId)
    }
    if (event.type in setOf("session.execution.succeeded", "session.execution.failed")) recordResultNotice(event, sessionId)
    if (sessionId.isNotBlank() && sessionId == mutable.value.sessionId) {
      val projected = TranscriptProjection.apply(mutable.value.messages, event) ?: return
      if (projected.messages !== mutable.value.messages) mutable.update { it.copy(messages = projected.messages, resources = it.resources + ("messages" to ResourceStatus(ResourceState.READY))) }
      if (projected.reconcile && messageRefresh?.isActive != true) {
        val session = mutable.value.session ?: return
        val token = generation
        messageRefresh = scope.launch { delay(350); loadSession(session, ancillary = false, token = token) }
      }
    }
  }

  private fun updateSession(id: String, change: (Session) -> Session) {
    if (id.isBlank()) return
    mutable.update { current -> if (current.sessions.none { it.id == id }) current else current.copy(sessions = current.sessions.map { if (it.id == id) change(it) else it }.sortedWith(sessionActivityOrder)) }
  }

  /** Re-reads one session's info; an unknown session becomes known (e.g. a run started from another client). */
  private fun refreshSession(id: String) {
    if (id.isBlank()) return
    val client = api ?: return
    val token = generation
    scope.launch {
      val fresh = attempt { client.session(id) }.getOrNull() ?: return@launch
      if (token != generation) return@launch
      state.value.serverId?.let { store.rememberParents(it, listOf(fresh)) }
      mutable.update { current -> withSummary(current.copy(sessions = (listOf(fresh) + current.sessions).distinctBy { it.id }.sortedWith(sessionActivityOrder),
        knownParents = current.knownParents + listOfNotNull(fresh.parentId?.let { fresh.id to it }))) }
      // A child whose root is outside the catalog page: resolve the ancestry so its state rolls up.
      fresh.parentId?.takeIf { parent -> state.value.sessions.none { it.id == parent } }?.let(::refreshSession)
    }
  }

  private fun recordResultNotice(event: ServerEvent, sessionId: String) {
    if (sessionId.isBlank()) return
    val current = state.value
    val server = current.serverId ?: return
    val token = generation
    val known = current.sessions.firstOrNull { it.id == sessionId }
    val runSince = current.tasks[sessionId]?.since ?: System.currentTimeMillis()
    fun record(session: Session) {
      if (generation != token || state.value.serverId != server) return
      event.resultNotice(session)?.let { recordNotice(server, session, it, runSince) }
    }
    if (known != null) record(known)
    else scope.launch {
      val client = api ?: return@launch
      attempt { findSession(client, sessionId) }.getOrNull()?.let(::record)
    }
  }

  /**
   * Adds one unread result to the ledger. As in the official client it is born viewed when the user is
   * looking at that conversation; otherwise it raises the completion/failure notification. A second
   * report of the same run (`session.idle` plus a V2 execution event, or event plus reconciliation)
   * only upgrades a success to a failure.
   */
  private fun recordNotice(server: String, session: Session, notice: SessionNotice, runSince: Long) {
    val existing = state.value.notices.filter { it.sessionId == session.id && it.time >= runSince }
    if (existing.any { it.error || !notice.error }) return
    val viewing = foreground && visibleConversation == (server to session.id)
    val notices = store.rememberNotice(server, notice.copy(viewed = viewing))
    mutable.update { if (it.serverId == server) withSummary(it.copy(notices = notices)) else it }
    if (viewing) return
    val profile = state.value.server?.takeIf { it.id == server && it.notifications } ?: return
    val result = TaskState(session.id, if (notice.error) TaskPhase.FAILED else TaskPhase.COMPLETED,
      if (notice.error) "执行失败" else "任务已完成", runSince, notice.time)
    attempt { notifications.show(profile, session, result) }
  }

  fun toggleSection(key: String) {
    val current = state.value; val server = current.serverId ?: return
    val collapsed = if (key in current.collapsedSections) current.collapsedSections - key else current.collapsedSections + key
    store.rememberCollapsedSections(server, collapsed)
    mutable.update { it.copy(collapsedSections = collapsed) }
  }
  fun togglePin(sessionId: String) {
    val current = state.value; val server = current.serverId ?: return
    val pinned = if (sessionId in current.pinned) current.pinned - sessionId else current.pinned + sessionId
    store.rememberPinnedSessions(server, pinned)
    mutable.update { it.copy(pinned = pinned) }
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
      agentChanged = false, modelChanged = false, draft = "", references = emptyList(), savedPermissions = null, resources = emptyMap(),
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
      val modelsTask = async { attempt { client.modelCatalog(directory) } }
      val commandsTask = async { attempt { client.commands(directory) } }
      Triple(agentsTask.await(), modelsTask.await(), commandsTask.await())
    }
    if (token == generation && revision == selectionRevision && mutable.value.executionDirectory == directory) {
      mutable.update { current ->
        val catalog = models.getOrDefault(current.modelCatalog)
        current.copy(agents = agents.getOrDefault(current.agents), models = catalog.map { it.choice }, modelCatalog = catalog, commands = commands.getOrDefault(current.commands),
          resources = current.resources + mapOf("agents" to agents.resourceStatus(), "models" to models.resourceStatus(), "commands" to commands.resourceStatus()))
      }
    }
  }
  /** Resolve a notification/child target independently of the current catalog page. */
  fun resolveSession(id: String, onResolved: (List<String>) -> Unit): Job = act("open:$id") { op ->
    val known = op.snapshot.sessions.associateBy { it.id }.toMutableMap()
    val seen = linkedSetOf<String>(); val lineage = mutableListOf<String>()
    var next: String? = id
    while (next != null && seen.add(next) && lineage.size < 32) {
      val target = next
      val session = known[target] ?: findSession(op.client, target)
      if (session == null) {
        check(lineage.isNotEmpty()) { "未找到这个会话，请刷新后重试" }
        break
      }
      known[target] = session; lineage += target
      next = session.parentId
    }
    if (!op.isCurrent(this)) return@act
    store.rememberParents(op.serverId, known.values.toList())
    op.commit { withSummary(it.copy(sessions = known.values.sortedWith(sessionActivityOrder), knownParents = store.taskParents(op.serverId))) }
    onResolved(lineage.asReversed())
  }
  /** Null when the server no longer has the session. */
  private suspend fun findSession(client: OpenCodeApi, id: String): Session? = try { client.session(id).takeIf { it.id == id } }
    catch (error: ApiException) { if (error.status == 404) null else throw error }
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
    mutable.update { withSummary(it.copy(projectId = project?.id, sessionId = id, messages = messages,
      agent = configuration.agent ?: session.agent, model = configuration.model ?: session.model,
      agentChanged = configuration.agentChanged, modelChanged = configuration.modelChanged,
      draft = draft, references = store.references(serverId, id), savedPermissions = null, loadedOlderMessages = false, messagesCursor = null, resources = mapOf("messages" to ResourceStatus(ResourceState.LOADING)),
      children = emptyList(), changes = emptyList(), files = emptyList(),
      searchResults = emptyList(), fileText = null, fileBinary = false)) }
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
    var nextCursor: String? = null
    // The newest page of the projected timeline plus admitted-but-undelivered input, like the official
    // client's message and pending syncs.
    val (result, pending) = coroutineScope {
      val page = async { attempt { client.messagesPage(session.id).let { nextCursor = it.next; it.items } } }
      val inbox = async { attempt { client.inbox(session.id) } }
      page.await() to inbox.await().getOrDefault(emptyList())
    }
    if (token != generation) return
    val previousMessages = state.value.messages
    val messages = result.map { fetched ->
      val older = if (current() && state.value.loadedOlderMessages && fetched.isNotEmpty()) previousMessages.filter { it.created < fetched.first().created } else emptyList()
      (older + fetched + pending.filter { item -> fetched.none { it.id == item.id } }).distinctBy { it.id }
    }.getOrElse { error ->
      // Shown in place (“保留上次数据 · …”); a transcript read failure is not a global error toast.
      Diagnostics.warn("LagoonController", "会话消息读取失败", error)
      val fallback = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.messages(serverId, session.id) }
      if (current()) mutable.update { it.copy(cached = fallback.isNotEmpty(), cacheComplete = false,
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
    // Opening a session never infers a result from its history: “已完成” exists only in the ledger.
    if (ancillary) {
      // Children and diff are independent; fetch in parallel (one round trip when connected).
      mutable.update { it.copy(resources = it.resources + listOf("children", "changes").associateWith { ResourceStatus(ResourceState.LOADING) }) }
      val (children, changes) = coroutineScope {
        val childrenTask = async { attempt { client.children(session) } }
        val changesTask = async { attempt { client.diff(session) } }
        childrenTask.await() to changesTask.await()
      }
      if (current()) mutable.update { it.copy(children = children.getOrDefault(it.children), changes = changes.getOrDefault(it.changes),
        resources = it.resources + mapOf("children" to children.resourceStatus(), "changes" to changes.resourceStatus()),
        sessions = (it.sessions + children.getOrDefault(emptyList())).distinctBy { item -> item.id }.sortedWith(sessionActivityOrder)) }
    }
  }
  fun chooseAgent(name: String?) {
    mutable.update { it.copy(agent = name ?: it.messages.asReversed().firstNotNullOfOrNull { message -> message.agent } ?: it.session?.agent, agentChanged = name != null) }; rememberConfiguration()
  }
  fun chooseModel(model: ModelChoice?) {
    mutable.update { it.copy(model = model ?: it.messages.asReversed().firstNotNullOfOrNull { message -> message.model } ?: it.session?.model, modelChanged = model != null) }; rememberConfiguration()
    val server = state.value.serverId ?: return
    if (model != null) {
      store.rememberRecentModel(server, modelKey(model))
      mutable.update { it.copy(recentModels = store.recentModels(server)) }
    }
  }
  /** A switch from the phone's "管理模型" list; it overrides the official default rule for this server. */
  fun setModelVisible(model: ModelInfo, visible: Boolean) {
    val server = state.value.serverId ?: return
    store.rememberModelOverride(server, model.key, visible)
    mutable.update { it.copy(modelOverrides = it.modelOverrides + (model.key to visible)) }
  }
  /** Copies picked phone files into the cache off the main thread; each rejection is reported once. */
  fun addLocalAttachments(uris: List<android.net.Uri>) {
    if (uris.isEmpty() || state.value.pending("send")) return
    val server = state.value.serverId ?: return
    val key = attachmentKey(state.value.sessionId)
    operationScope.launch {
      val errors = mutableListOf<String>()
      for (uri in uris) {
        val pending = state.value.pendingAttachments[key].orEmpty().sumOf { it.size }
        val result = kotlinx.coroutines.withContext(Dispatchers.IO) { attempt { attachmentImporter.import(uri, pending) } }
        result.onSuccess { item ->
          if (state.value.serverId == server) mutable.update { it.copy(pendingAttachments = it.pendingAttachments + (key to (it.pendingAttachments[key].orEmpty() + item))) }
          else attachmentImporter.discard(listOf(item))
        }.onFailure { errors += it.message ?: "无法添加附件" }
      }
      if (errors.isNotEmpty() && state.value.serverId == server) mutable.update { it.copy(error = errors.distinct().joinToString("\n")) }
    }
  }
  fun removeLocalAttachment(id: String) {
    if (state.value.pending("send")) return
    val key = attachmentKey(state.value.sessionId)
    val removed = state.value.pendingAttachments[key].orEmpty().filter { it.id == id }
    mutable.update { it.copy(pendingAttachments = it.pendingAttachments + (key to it.pendingAttachments[key].orEmpty().filterNot { item -> item.id == id })) }
    operationScope.launch(Dispatchers.IO) { attachmentImporter.discard(removed) }
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
  /** Home list filter only; choosing a project also makes it the target for new sessions. */
  fun setScope(projectId: String?) {
    val server = state.value.serverId ?: return
    val scope = projectId?.takeIf { id -> state.value.projects.any { it.id == id } }
    store.rememberScope(server, scope)
    mutable.update { it.copy(scopeProjectId = scope) }
    if (scope != null && scope != state.value.projectId) selectProject(scope)
  }
  /** Leaves any open session for a blank draft that starts in the scope's (or latest) project. */
  fun beginDraft() {
    val current = state.value
    val target = HomeScope.draftTarget(current.sessions, current.projects, current.scopeProjectId, current.projectId) ?: return
    if (target != current.projectId || current.sessionId != null) selectProject(target)
  }
  fun conversationVisible(server: String, session: String, visible: Boolean) {
    if (visible) visibleConversation = server to session
    else if (visibleConversation == (server to session)) visibleConversation = null
    acknowledgeVisible()
  }
  /**
   * Official `MarkSessionNotificationsViewed`: once the conversation is on screen, every unread result of
   * that session is viewed — immediately, not after its transcript loads — and its result notification is
   * withdrawn. On V2 the server's own read marker for that idle transition is synced best-effort.
   */
  private fun acknowledgeVisible() {
    val current = state.value
    val server = current.serverId ?: return
    val session = current.sessionId ?: return
    if (!foreground || visibleConversation != (server to session)) return
    val unseen = current.notices.unseenFor(session)
    if (unseen.isNotEmpty()) {
      val notices = store.viewNotices(server, session, unseen.map { it.id }.toSet())
      mutable.update { if (it.serverId == server) withSummary(it.copy(notices = notices)) else it }
      attempt { notifications.cancel(server, session) }
    }
    val target = current.session ?: return
    val idle = target.idle
    if (idle > 0 && target.viewed < idle && current.capabilities.sessionView != null) {
      val client = api
      mutable.update { it.copy(sessions = it.sessions.map { item -> if (item.id == session && item.idle == idle) item.copy(viewed = idle) else item }) }
      operationScope.launch {
        attempt { client?.viewSession(target, idle) }.onFailure { Diagnostics.warn("SessionView", "服务端已读同步失败，保留本地已读记录") }
      }
    }
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
    // Files picked on the blank draft page move to the new session so a failed first send keeps them.
    val draftFiles = op.snapshot.pendingAttachments[attachmentKey(null)].orEmpty()
    check(prompt.isNotBlank() || draftFiles.isNotEmpty()) { "请输入任务" }
    val project = op.snapshot.project ?: error("先选择项目")
    val session = op.client.createSession(project.directory, title)
    op.commitConnection { it.copy(sessions = (listOf(session) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) {
      store.rememberDraft(op.serverId, session.id, prompt)
      store.rememberConfiguration(op.serverId, session.id, SessionConfiguration(agent, model, agent != null, model != null))
      if (draftFiles.isNotEmpty()) mutable.update { it.copy(pendingAttachments = it.pendingAttachments - attachmentKey(null) + (session.id to draftFiles)) }
      selectSession(session.id)
      mutable.update { it.copy(agentChanged = agent != null, modelChanged = model != null, draft = prompt, previews = it.previews + (session.id to SessionPreview(SessionContent.EMPTY))) }
      onStarted?.invoke(session)
      send(prompt)
    }
  }
  /**
   * Official composer submission: the prompt gets a client-minted id and is shown at once; it steers a
   * running turn instead of being refused, and a failed admission removes the optimistic row again.
   * A slash command known to the server runs through `/command`.
   */
  fun send(text: String, expectedServer: String? = state.value.serverId, expectedSession: String? = state.value.sessionId, accepted: (() -> Unit)? = null): Job {
    if (state.value.serverId != expectedServer || state.value.sessionId != expectedSession) return operationScope.launch { }
    return act("send") { op ->
    val attachments = op.snapshot.attachments
    check(text.isNotBlank() || op.snapshot.references.isNotEmpty() || attachments.isNotEmpty()) { "请输入任务" }
    val session = op.snapshot.session ?: error("先打开会话")
    sendMutex.withLock {
      if (!op.isCurrent(this)) return@act
      val command = if (text.startsWith('/')) op.snapshot.commands.firstOrNull { text.substringAfter('/').substringBefore(' ') == it.name } else null
      val agent = op.snapshot.agent.takeIf { op.snapshot.agentChanged }
      val model = op.snapshot.model.takeIf { op.snapshot.modelChanged }
      val inline = if (attachments.isEmpty()) emptyList() else kotlinx.coroutines.withContext(Dispatchers.IO) { attachments.map(attachmentImporter::inline) }
      val id = MessageIds.ascending()
      if (command == null && text.isNotBlank()) op.commit { it.copy(messages = it.messages + Message(id, "user", System.currentTimeMillis(),
        listOf(MessagePart("$id:text", "text", text = text)), agent = agent, model = model, type = "user")) }
      try {
        if (command != null) op.client.command(session, command.name, text.substringAfter(' ', ""), agent, model, op.snapshot.references, inline)
        else op.client.send(session, text, agent, model, op.snapshot.references, inline, id)
      } catch (error: Exception) {
        op.commit { it.copy(messages = it.messages.filterNot { message -> message.id == id }) }
        throw error
      }
      if (attachments.isNotEmpty()) {
        mutable.update { it.copy(pendingAttachments = it.pendingAttachments + (attachmentKey(session.id) to it.pendingAttachments[attachmentKey(session.id)].orEmpty().filterNot { item -> item in attachments })) }
        kotlinx.coroutines.withContext(Dispatchers.IO) { attachmentImporter.discard(attachments) }
      }
      store.rememberConfiguration(op.serverId, session.id, SessionConfiguration(op.snapshot.agent, op.snapshot.model))
      referenceMutex.withLock { kotlinx.coroutines.withContext(Dispatchers.IO) { store.rememberReferences(op.serverId, session.id, emptyList()) } }
      store.rememberPreview(op.serverId, session.id, SessionPreview(SessionContent.CONTENT, text.take(300)))
      if (op.isCurrent(this)) {
        if (state.value.draft == text) updateDraft("")
        accepted?.invoke()
      }
      op.commitConnection { current -> withSummary(current.copy(
        tasks = if (current.tasks[session.id]?.active == true) current.tasks else current.tasks + (session.id to TaskState(session.id, TaskPhase.THINKING, TaskState.RUNNING_DETAIL)),
        previews = current.previews + (session.id to SessionPreview(SessionContent.CONTENT, text.take(300))))) }
      op.commit { it.copy(agentChanged = false, modelChanged = false, references = emptyList()) }
    }
    if (op.connectionCurrent(this) && op.snapshot.server?.notifications == true) TaskMonitorService.start(appContext, op.serverId, session.id)
    // With the live stream up, the inbox/step events project the turn; otherwise read it back.
    if (op.isCurrent(this) && !state.value.streamConnected) loadSession(session, token = op.token, client = op.client, ancillary = false)
  }
  }
  fun abort() = withSession("abort") { op, client, session ->
    client.abort(session)
    op.commitConnection { withSummary(it.copy(tasks = it.tasks + (session.id to TaskState(session.id, TaskPhase.ABORTED, "任务已停止", it.tasks[session.id]?.since ?: System.currentTimeMillis(), System.currentTimeMillis())))) }
  }
  /** Renames [target] (a session id from the home list) or, by default, the open session. */
  fun rename(title: String, target: String? = null, onRenamed: (() -> Unit)? = null) = withSession("rename", target) { op, client, session ->
    check(title.isNotBlank()) { "标题不能为空" }
    client.renameSession(session, title.trim())
    op.commitConnection { it.copy(sessions = it.sessions.map { item -> if (item.id == session.id) item.copy(title = title.trim()) else item }) }
    if (op.isCurrent(this)) onRenamed?.invoke()
  }
  fun deleteSession(target: String? = null, onDeleted: (() -> Unit)? = null) = withSession("delete", target) { op, client, session ->
    client.deleteSession(session)
    cache.deleteMessages(op.serverId, session.id)
    store.forgetSession(op.serverId, session.id)
    if (op.isCurrent(this)) onDeleted?.invoke()
    if (session.id == op.snapshot.sessionId) op.commit { it.copy(sessionId = null, messages = emptyList()) }
    if (session.id in op.snapshot.pinned) store.rememberPinnedSessions(op.serverId, op.snapshot.pinned - session.id)
    op.commitConnection { it.copy(sessions = it.sessions.filterNot { s -> s.id == session.id }, tasks = it.tasks - session.id, pinned = it.pinned - session.id) }
    if (op.connectionCurrent(this)) reload()
  }
  /** Archives (or restores) [target]; the home list hides archived sessions, 已归档 lists them. */
  fun setArchived(target: String, archived: Boolean, onDone: (() -> Unit)? = null) = withSession("archive", target) { op, client, session ->
    client.setArchived(session, archived)
    op.commitConnection { it.copy(sessions = it.sessions.map { item -> if (item.id == session.id) item.copy(archived = archived) else item }) }
    if (op.connectionCurrent(this)) onDone?.invoke()
  }
  fun fork(onForked: ((Session) -> Unit)? = null) = withSession("fork") { op, client, session ->
    val fork = client.forkSession(session)
    op.commitConnection { it.copy(sessions = (listOf(fork) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) { selectSession(fork.id); onForked?.invoke(fork) }
  }
  fun revert(messageId: String, onReverted: (() -> Unit)? = null) = withSession("revert") { op, client, session ->
    client.revert(session, messageId)
    op.commitConnection { current -> current.copy(sessions = current.sessions.map { if (it.id == session.id) it.copy(revertMessageId = messageId) else it }) }
    if (op.isCurrent(this)) { op.commit { it.copy(message = "已撤销，可通过恢复撤销还原") }; onReverted?.invoke() }
  }
  fun unrevert() = withSession("unrevert") { op, client, session ->
    client.unrevert(session)
    op.commitConnection { current -> current.copy(sessions = current.sessions.map { if (it.id == session.id) it.copy(revertMessageId = null) else it }) }
    if (op.isCurrent(this)) op.commit { it.copy(message = "已恢复撤销") }
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
  private fun withSession(action: String, target: String? = null, block: suspend (OperationContext, OpenCodeApi, Session) -> Unit) = act(action) { op ->
    val session = (if (target == null) op.snapshot.session else op.snapshot.sessions.firstOrNull { it.id == target }) ?: error("先打开会话")
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
